package com.example.core

import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

enum class BiometricSupportStatus {
    READY,
    NONE_ENROLLED,
    UNAVAILABLE
}

data class BiometricCheckResult(
    val status: BiometricSupportStatus,
    val detail: String,
    val allowedAuthenticators: Int,
    val useKeyguardCredentialFallback: Boolean = false
)

/**
 * Tracks transient in-app external activity launches (SAF file pickers, Security Settings,
 * Keyguard confirm intent, runtime permission dialogs) so ON_STOP does not falsely lock
 * the vault while an approved system picker returns a URI.
 */
object VaultLifecycleGuard {
    @Volatile
    private var transientExternalFlowUntilMs: Long = 0L

    fun markExternalFlowLaunched(graceWindowMs: Long = 90_000L) {
        transientExternalFlowUntilMs = System.currentTimeMillis() + graceWindowMs
    }

    fun consumeOrIsWithinExternalFlow(): Boolean {
        val now = System.currentTimeMillis()
        return now <= transientExternalFlowUntilMs
    }

    fun clearExternalFlow() {
        transientExternalFlowUntilMs = 0L
    }
}

interface BiometricGateController {
    fun checkSupport(context: Context): BiometricCheckResult
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onFailureMessage: (String) -> Unit
    )
}

object DefaultBiometricGateController : BiometricGateController {

    internal fun supportedAuthenticatorsForSdk(sdkInt: Int = Build.VERSION.SDK_INT): Int =
        if (sdkInt >= Build.VERSION_CODES.R) {
            BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        } else {
            // AndroidX Biometric prohibits BIOMETRIC_STRONG | DEVICE_CREDENTIAL below API 30;
            // BIOMETRIC_WEAK | DEVICE_CREDENTIAL is the valid API 21-29 combination.
            BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        }

    internal fun evaluateSupport(
        sdkInt: Int,
        biometricCanAuthCode: Int,
        keyguardDeviceSecure: Boolean,
        hasKeyguardConfirmIntent: Boolean
    ): BiometricCheckResult {
        val authenticators = supportedAuthenticatorsForSdk(sdkInt)

        return when (biometricCanAuthCode) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricCheckResult(
                status = BiometricSupportStatus.READY,
                detail = if (sdkInt >= Build.VERSION_CODES.R) {
                    "Biometric sensor & device credential protection active."
                } else {
                    "Biometric & device lock credential protection active."
                },
                allowedAuthenticators = authenticators,
                useKeyguardCredentialFallback = false
            )

            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                if (keyguardDeviceSecure && sdkInt >= Build.VERSION_CODES.R) {
                    BiometricCheckResult(
                        status = BiometricSupportStatus.READY,
                        detail = "Device lock screen credential active for vault unlock.",
                        allowedAuthenticators = authenticators,
                        useKeyguardCredentialFallback = false
                    )
                } else if (keyguardDeviceSecure && hasKeyguardConfirmIntent) {
                    BiometricCheckResult(
                        status = BiometricSupportStatus.READY,
                        detail = "Device PIN, pattern, or password lock active for vault unlock.",
                        allowedAuthenticators = DEVICE_CREDENTIAL,
                        useKeyguardCredentialFallback = true
                    )
                } else {
                    BiometricCheckResult(
                        status = BiometricSupportStatus.NONE_ENROLLED,
                        detail = "No fingerprint, face unlock, or screen lock credential is enrolled on this device.",
                        allowedAuthenticators = authenticators
                    )
                }
            }

            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                if (keyguardDeviceSecure && sdkInt >= Build.VERSION_CODES.R) {
                    BiometricCheckResult(
                        status = BiometricSupportStatus.READY,
                        detail = "Device lock screen credential active for vault unlock.",
                        allowedAuthenticators = DEVICE_CREDENTIAL,
                        useKeyguardCredentialFallback = false
                    )
                } else if (keyguardDeviceSecure && hasKeyguardConfirmIntent) {
                    BiometricCheckResult(
                        status = BiometricSupportStatus.READY,
                        detail = "Hardware biometric absent; device PIN/pattern/password lock active.",
                        allowedAuthenticators = DEVICE_CREDENTIAL,
                        useKeyguardCredentialFallback = true
                    )
                } else {
                    BiometricCheckResult(
                        status = BiometricSupportStatus.UNAVAILABLE,
                        detail = if (biometricCanAuthCode == BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE) {
                            "Biometric hardware is temporarily unavailable."
                        } else {
                            "Biometric authentication hardware is unavailable and no screen lock credential is set."
                        },
                        allowedAuthenticators = authenticators
                    )
                }
            }

            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> BiometricCheckResult(
                status = BiometricSupportStatus.UNAVAILABLE,
                detail = "A system security update is required before using biometrics.",
                allowedAuthenticators = authenticators
            )

            else -> {
                if (keyguardDeviceSecure && hasKeyguardConfirmIntent) {
                    BiometricCheckResult(
                        status = BiometricSupportStatus.READY,
                        detail = "Device screen lock credential active for vault unlock.",
                        allowedAuthenticators = DEVICE_CREDENTIAL,
                        useKeyguardCredentialFallback = true
                    )
                } else {
                    BiometricCheckResult(
                        status = BiometricSupportStatus.UNAVAILABLE,
                        detail = "Biometric authentication is not available on this hardware.",
                        allowedAuthenticators = authenticators
                    )
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun checkSupport(context: Context): BiometricCheckResult {
        val sdkInt = Build.VERSION.SDK_INT
        val authenticators = supportedAuthenticatorsForSdk(sdkInt)
        val biometricManager = BiometricManager.from(context)
        val resultCode = biometricManager.canAuthenticate(authenticators)
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val keyguardSecure = keyguardManager?.isDeviceSecure == true
        val hasConfirmIntent = keyguardSecure &&
            keyguardManager?.createConfirmDeviceCredentialIntent("FRIDAY", null) != null

        return evaluateSupport(
            sdkInt = sdkInt,
            biometricCanAuthCode = resultCode,
            keyguardDeviceSecure = keyguardSecure,
            hasKeyguardConfirmIntent = hasConfirmIntent
        )
    }

    @Suppress("DEPRECATION")
    fun buildConfirmCredentialIntent(context: Context, title: String, subtitle: String): Intent? {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return null
        if (!keyguardManager.isDeviceSecure) return null
        return keyguardManager.createConfirmDeviceCredentialIntent(title, subtitle)
    }

    override fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onFailureMessage: (String) -> Unit
    ) {
        val support = checkSupport(activity)
        if (support.status != BiometricSupportStatus.READY) {
            onFailureMessage(support.detail)
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                val friendly = when (errorCode) {
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_CANCELED,
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON ->
                        "Biometric unlock cancelled. Authenticate to access FRIDAY Vault."
                    BiometricPrompt.ERROR_LOCKOUT,
                    BiometricPrompt.ERROR_LOCKOUT_PERMANENT ->
                        "Biometric sensor locked out due to too many attempts: $errString"
                    else -> errString.toString().ifBlank { "Biometric authentication error ($errorCode)." }
                }
                onFailureMessage(friendly)
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                onFailureMessage("Biometric credential not recognized. Please try again.")
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(support.allowedAuthenticators)
            .setConfirmationRequired(true)

        // AndroidX Biometric requires either DEVICE_CREDENTIAL OR negative button text, never both.
        if (support.allowedAuthenticators and DEVICE_CREDENTIAL == 0) {
            builder.setNegativeButtonText("Cancel")
        }

        prompt.authenticate(builder.build())
    }
}

tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

fun buildBiometricEnrollIntent(context: Context): Intent {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val enrollIntent = Intent(Settings.ACTION_BIOMETRIC_ENROLL).apply {
            putExtra(
                Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                BIOMETRIC_STRONG or DEVICE_CREDENTIAL
            )
        }
        if (enrollIntent.resolveActivity(context.packageManager) != null) {
            return enrollIntent
        }
    }
    return Intent(Settings.ACTION_SECURITY_SETTINGS)
}
