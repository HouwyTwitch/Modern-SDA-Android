package com.houwytwitch.modernsda.domain.steam

import android.util.Base64
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Steam-specific TOTP (Time-based One-Time Password) implementation.
 *
 * Steam uses HMAC-SHA1 with a 30-second time step and a custom 26-character
 * alphabet: "23456789BCDFGHJKMNPQRTVWXY"
 */
object SteamTotp {

    private const val STEAM_ALPHABET = "23456789BCDFGHJKMNPQRTVWXY"
    private const val CODE_LENGTH = 5
    private const val TIME_STEP = 30L

    /**
     * Generate a 5-character Steam authenticator code.
     *
     * @param sharedSecret Base64-encoded shared secret from .mafile
     * @param timeOffsetSeconds Server time offset (usually 0)
     */
    fun generateCode(sharedSecret: String, timeOffsetSeconds: Long = 0L): String {
        return try {
            val key = Base64.decode(sharedSecret, Base64.DEFAULT)
            val timeStep = (System.currentTimeMillis() / 1000 + timeOffsetSeconds) / TIME_STEP

            val msg = ByteBuffer.allocate(8).putLong(timeStep).array()

            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(key, "HmacSHA1"))
            val hash = mac.doFinal(msg)

            val offset = hash[19].toInt() and 0x0F
            val fullCode = (
                ((hash[offset].toInt() and 0x7F) shl 24) or
                ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                (hash[offset + 3].toInt() and 0xFF)
            )

            buildString {
                var remaining = fullCode
                repeat(CODE_LENGTH) {
                    insert(0, STEAM_ALPHABET[remaining % STEAM_ALPHABET.length])
                    remaining /= STEAM_ALPHABET.length
                }
            }
        } catch (e: Exception) {
            "ERROR"
        }
    }

    /**
     * Seconds remaining until the current TOTP code expires (0-30).
     */
    fun getTimeRemaining(): Int {
        val seconds = (System.currentTimeMillis() / 1000) % TIME_STEP
        return (TIME_STEP - seconds).toInt()
    }

    /**
     * Progress fraction (0.0 to 1.0) for countdown display.
     * 1.0 = full time remaining, 0.0 = about to expire.
     */
    fun getProgressFraction(): Float {
        return getTimeRemaining().toFloat() / TIME_STEP.toFloat()
    }
}
