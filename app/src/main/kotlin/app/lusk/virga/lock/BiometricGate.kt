package app.lusk.virga.lock

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.lusk.virga.R

/**
 * Thin wrapper around [BiometricPrompt] for the app-lock gate.
 *
 * Allows BIOMETRIC_WEAK | DEVICE_CREDENTIAL so devices without enrolled
 * biometrics can still authenticate with their PIN/pattern/password.
 *
 * IMPORTANT: do NOT set a negative/cancel button when DEVICE_CREDENTIAL is
 * included in the allowed authenticators — the BiometricPrompt API throws
 * [IllegalArgumentException] if both are provided together.
 */
object BiometricGate {

    private val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** Returns true when the device can authenticate with the chosen methods. */
    fun canAuthenticate(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Shows the biometric/device-credential prompt.
     *
     * [onSuccess] is called on the main thread after successful authentication.
     * [onError] is called on the main thread for any failure or user cancellation.
     */
    fun authenticate(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onError: () -> Unit,
    ) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult,
                ) = onSuccess()

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) =
                    onError()

                override fun onAuthenticationFailed() {
                    // Individual biometric attempt failed but prompt stays visible;
                    // do not call onError here — the system handles the retry UI.
                }
            },
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.biometric_prompt_title))
            .setSubtitle(activity.getString(R.string.biometric_prompt_subtitle))
            // No negative button: DEVICE_CREDENTIAL provides its own cancel path.
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()

        prompt.authenticate(info)
    }
}
