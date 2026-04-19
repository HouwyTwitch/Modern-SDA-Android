package com.houwytwitch.modernsda.data.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BiometricAuthenticator @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    enum class Availability {
        AVAILABLE,
        NONE_ENROLLED,
        NO_HARDWARE,
        HARDWARE_UNAVAILABLE,
        UNSUPPORTED,
    }

    sealed interface Result {
        data object Success : Result
        data class Error(val code: Int, val message: String) : Result
        data object Failed : Result
        data object Cancelled : Result
    }

    private val allowedAuthenticators: Int =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK

    fun availability(): Availability {
        val manager = BiometricManager.from(context)
        return when (manager.canAuthenticate(allowedAuthenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Availability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Availability.NONE_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> Availability.NO_HARDWARE
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> Availability.HARDWARE_UNAVAILABLE
            else -> Availability.UNSUPPORTED
        }
    }

    fun isUsable(): Boolean = availability() == Availability.AVAILABLE

    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String?,
        negativeButtonText: String,
        onResult: (Result) -> Unit,
    ) {
        val executor = ContextCompat.getMainExecutor(context)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onResult(Result.Success)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    onResult(
                        if (cancelled) Result.Cancelled
                        else Result.Error(errorCode, errString.toString()),
                    )
                }

                override fun onAuthenticationFailed() {
                    onResult(Result.Failed)
                }
            },
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { if (subtitle != null) setSubtitle(subtitle) }
            .setAllowedAuthenticators(allowedAuthenticators)
            .setNegativeButtonText(negativeButtonText)
            .setConfirmationRequired(false)
            .build()

        prompt.authenticate(info)
    }
}
