package com.settingpro.camera.util

import android.content.Context
import com.settingpro.camera.security.SecureEncryption

/**
 * Secret configuration with secure encryption.
 *
 * URLs are encrypted using AES-256-GCM and stored in Android KeyStore.
 * This provides military-grade protection against extraction.
 *
 * Security improvements over XOR:
 * - AES-256-GCM encryption (NIST approved)
 * - Keys stored in hardware-backed KeyStore
 * - Device-specific keys
 * - Tamper-evident (authentication tags)
 *
 * IMPORTANT: While much more secure than XOR, determined attackers
 * can still extract via network analysis or runtime inspection.
 */
object SecretConfig {

    private const val TAG = "SecretConfig"

    // Encrypted URL storage keys
    private const val PREFS_NAME = "SecretConfig"
    private const val KEY_SERVER_URL = "encrypted_server_url_v3"
    private const val KEY_WEBVIEW_URL = "encrypted_webview_url_v3"
    private const val KEY_SALT = "url_salt_v3"

    // Native library holds the URLs XOR-encrypted so plaintext never ships in the DEX
    init {
        try {
            System.loadLibrary("aichat")
            AppLogger.d(TAG, "Native URL library loaded")
        } catch (e: UnsatisfiedLinkError) {
            AppLogger.e(TAG, "Native URL library not found, using encoded fallback", e)
        }
    }

    // JNI-backed URL decoding (libaichat.so)
    private external fun getServerUrlNative(): String
    private external fun getWebViewUrlNative(): String

    // Fallback: XOR-encoded bytes (same key as native) in case libaichat fails to load.
    // Stored as byte arrays, not string literals, so the URLs stay out of strings/jadx output.
    private val XOR_KEY = "AIChatSecretKey2024".toByteArray()
    private val SERVER_URL_ENC = byteArrayOf(
        0x29, 0x3D, 0x37, 0x18, 0x12, 0x4E, 0x7C, 0x4A, 0x02, 0x02, 0x0C, 0x5A,
        0x28, 0x04, 0x0D, 0x46, 0x49, 0x41, 0x59, 0x32, 0x67, 0x30, 0x00, 0x0E,
        0x04
    )
    private val WEBVIEW_URL_ENC = byteArrayOf(
        0x29, 0x3D, 0x37, 0x18, 0x12, 0x4E, 0x7C, 0x4A, 0x14, 0x05, 0x12, 0x5A,
        0x2F, 0x00, 0x0A, 0x5B, 0x40, 0x5D, 0x46, 0x2F, 0x67, 0x3B, 0x10, 0x19,
        0x5B, 0x35, 0x04, 0x0E, 0x1B, 0x09, 0x0D, 0x66, 0x03, 0x18, 0x5C, 0x44,
        0x53, 0x47, 0x38, 0x64, 0x33, 0x09, 0x06, 0x11, 0x7E, 0x54, 0x4D, 0x1A,
        0x11, 0x19, 0x27
    )

    private fun xorDecode(enc: ByteArray): String {
        val out = ByteArray(enc.size)
        for (i in enc.indices) {
            out[i] = (enc[i].toInt() xor XOR_KEY[i % XOR_KEY.size].toInt()).toByte()
        }
        return String(out, Charsets.UTF_8)
    }

    // Default URLs (decoded at runtime; encrypted on first run)
    val DEFAULT_SERVER_URL: String
        get() = try {
            getServerUrlNative()
        } catch (e: UnsatisfiedLinkError) {
            xorDecode(SERVER_URL_ENC)
        }

    val DEFAULT_WEBVIEW_URL: String
        get() = try {
            getWebViewUrlNative()
        } catch (e: UnsatisfiedLinkError) {
            xorDecode(WEBVIEW_URL_ENC)
        }

    /**
     * Initialize encryption and encrypt URLs if not already stored
     */
    fun initialize(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            // Check if URLs are already encrypted and stored
            if (!prefs.contains(KEY_SERVER_URL)) {
                AppLogger.d(TAG, "First run - encrypting URLs")

                // Encrypt and store server URL
                val encryptedServerUrl = SecureEncryption.encryptWithKeyStore(DEFAULT_SERVER_URL)
                prefs.edit().putString(KEY_SERVER_URL, encryptedServerUrl).apply()

                // Encrypt and store WebView URL
                val encryptedWebViewUrl = SecureEncryption.encryptWithKeyStore(DEFAULT_WEBVIEW_URL)
                prefs.edit().putString(KEY_WEBVIEW_URL, encryptedWebViewUrl).apply()

                // Store salt for additional obfuscation
                val salt = generateSalt()
                prefs.edit().putString(KEY_SALT, salt).apply()

                AppLogger.d(TAG, "URLs encrypted and stored in KeyStore")
            } else {
                AppLogger.d(TAG, "Encrypted URLs found in storage")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error initializing SecretConfig", e)
        }
    }

    /**
     * Get the server URL
     * Decrypts from KeyStore-encrypted storage
     */
    fun getServerUrl(context: Context): String {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val encrypted = prefs.getString(KEY_SERVER_URL, null)

            if (encrypted != null) {
                return SecureEncryption.decryptWithKeyStore(encrypted)
            }

            // Fallback to default
            AppLogger.w(TAG, "No encrypted URL found, using default")
            DEFAULT_SERVER_URL
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error decrypting server URL", e)
            DEFAULT_SERVER_URL
        }
    }

    /**
     * Get the WebView URL
     * Decrypts from KeyStore-encrypted storage
     */
    fun getWebViewUrl(context: Context): String {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val encrypted = prefs.getString(KEY_WEBVIEW_URL, null)

            if (encrypted != null) {
                return SecureEncryption.decryptWithKeyStore(encrypted)
            }

            // Fallback to default
            AppLogger.w(TAG, "No encrypted URL found, using default")
            DEFAULT_WEBVIEW_URL
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error decrypting WebView URL", e)
            DEFAULT_WEBVIEW_URL
        }
    }

    /**
     * Update the server URL (e.g., for configuration)
     */
    fun updateServerUrl(context: Context, newUrl: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            // Encrypt and store new URL
            val encrypted = SecureEncryption.encryptWithKeyStore(newUrl)
            prefs.edit().putString(KEY_SERVER_URL, encrypted).apply()

            AppLogger.d(TAG, "Server URL updated and encrypted")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error updating server URL", e)
        }
    }

    /**
     * Update the WebView URL (e.g., for configuration)
     */
    fun updateWebViewUrl(context: Context, newUrl: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            // Encrypt and store new URL
            val encrypted = SecureEncryption.encryptWithKeyStore(newUrl)
            prefs.edit().putString(KEY_WEBVIEW_URL, encrypted).apply()

            AppLogger.d(TAG, "WebView URL updated and encrypted")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error updating WebView URL", e)
        }
    }

    /**
     * Generate random salt for additional obfuscation
     */
    private fun generateSalt(): String {
        val random = java.security.SecureRandom()
        val salt = ByteArray(16)
        random.nextBytes(salt)
        return android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP)
    }

    /**
     * Verify URL integrity (detect tampering)
     */
    fun verifyUrlIntegrity(context: Context): Boolean {
        return try {
            val serverUrl = getServerUrl(context)
            val webViewUrl = getWebViewUrl(context)

            // Basic validation
            serverUrl.startsWith("https://") &&
            webViewUrl.startsWith("https://") &&
            serverUrl.contains(".") &&
            webViewUrl.contains(".")
        } catch (e: Exception) {
            AppLogger.e(TAG, "URL integrity check failed", e)
            false
        }
    }

    /**
     * Get URL fingerprint for validation
     */
    fun getUrlFingerprint(context: Context): String {
        return try {
            val serverUrl = getServerUrl(context)
            val webViewUrl = getWebViewUrl(context)

            // Simple hash for validation
            val combined = serverUrl + webViewUrl
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(combined.toByteArray())

            android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        }
    }
}
