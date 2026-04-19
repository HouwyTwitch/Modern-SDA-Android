package com.houwytwitch.modernsda.data.security

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PinHasher @Inject constructor() {

    data class HashedPin(val hash: String, val salt: String)

    fun hashPin(pin: String, saltB64: String? = null): HashedPin {
        val salt = saltB64?.let { Base64.decode(it, Base64.NO_WRAP) } ?: generateSalt()
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hashBytes = factory.generateSecret(spec).encoded
        return HashedPin(
            hash = Base64.encodeToString(hashBytes, Base64.NO_WRAP),
            salt = Base64.encodeToString(salt, Base64.NO_WRAP),
        )
    }

    fun verifyPin(pin: String, expectedHash: String, saltB64: String): Boolean {
        val attempt = hashPin(pin, saltB64)
        return constantTimeEquals(attempt.hash, expectedHash)
    }

    private fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_BYTES)
        SecureRandom().nextBytes(salt)
        return salt
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }

    companion object {
        private const val ITERATIONS = 120_000
        private const val KEY_LENGTH_BITS = 256
        private const val SALT_BYTES = 16
    }
}
