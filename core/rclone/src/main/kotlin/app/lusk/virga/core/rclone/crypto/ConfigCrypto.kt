package app.lusk.virga.core.rclone.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase-based symmetric encryption for Virga config exports.
 *
 * ## Container layout (big-endian, all lengths in bytes)
 * ```
 * ┌──────────────────────────────────┐
 * │ MAGIC   8  "VIRGAEC1" (ASCII)    │
 * │ version 1  0x01                  │
 * │ salt   16  SecureRandom          │ ← KDF input
 * │ iv     12  SecureRandom          │ ← AES-GCM nonce
 * │ ciphertext variable (GCM output, │
 * │            includes 128-bit tag) │
 * └──────────────────────────────────┘
 * ```
 *
 * ## Key derivation
 * - Algorithm: PBKDF2WithHmacSHA256
 * - Iterations: 210 000 (OWASP 2023 recommendation — treated as a fixed assumption;
 *   review if the recommendation changes)
 * - Key length: 256 bits
 * - Salt length: 16 bytes (128 bits)
 *
 * ## Cipher
 * - Algorithm: AES/GCM/NoPadding
 * - IV length: 12 bytes (96 bits — GCM recommended)
 * - Authentication tag: 128 bits
 * - AAD: the 9-byte header (MAGIC + version) — binds the header to the ciphertext
 *   so a truncated or mis-versioned container cannot be decrypted successfully
 *
 * ## Passphrase handling
 * Callers MUST pass the passphrase as a [CharArray] rather than a [String] so it
 * can be zeroed from memory after use. This class zeros the corresponding
 * [PBEKeySpec] and derived key bytes in a `finally` block, and callers are
 * responsible for zeroing the [CharArray] they own.
 */
object ConfigCrypto {

    private val MAGIC = "VIRGAEC1".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val KEY_LEN_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val PBKDF2_ITERATIONS = 210_000
    private const val KDF_ALG = "PBKDF2WithHmacSHA256"
    private const val CIPHER_ALG = "AES/GCM/NoPadding"
    private const val KEY_ALG = "AES"

    /** Minimum header size: MAGIC(8) + version(1) + salt(16) + iv(12) = 37 bytes. */
    private const val HEADER_SIZE = 8 + 1 + SALT_LEN + IV_LEN

    // Exposed for tests.
    internal val MAGIC_BYTES: ByteArray get() = MAGIC.copyOf()

    /**
     * Returns `true` iff [bytes] is long enough to contain a header and its first
     * 8 bytes match [MAGIC]. Does NOT validate the version byte or attempt decryption.
     */
    fun isEncryptedContainer(bytes: ByteArray): Boolean {
        if (bytes.size < HEADER_SIZE) return false
        return bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)
    }

    /**
     * Encrypts [plaintext] (UTF-8) with [passphrase] using PBKDF2 + AES-GCM.
     *
     * The returned byte array is the complete versioned container described in the
     * class-level KDoc. The caller is responsible for zeroing [passphrase] after
     * this call returns.
     *
     * @param plaintext The UTF-8 config text to protect.
     * @param passphrase The user's passphrase. MUST NOT be empty.
     * @return The encrypted container bytes.
     */
    fun encrypt(plaintext: String, passphrase: CharArray): ByteArray {
        val rng = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { rng.nextBytes(it) }
        // IV is freshly generated from SecureRandom on every call — no IV reuse.
        // The IV is stored in the container header so decrypt can retrieve it.
        val iv = ByteArray(IV_LEN).also { rng.nextBytes(it) }
        val aad = buildHeader(salt, iv)
        val keyBytes = deriveKey(passphrase, salt)
        try {
            // IV is freshly generated per-call by SecureRandom above; no reuse possible.
            val cipher = Cipher.getInstance(CIPHER_ALG) // nosemgrep: kotlin.lang.security.gcm-detection.gcm-detection
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(keyBytes, KEY_ALG),
                GCMParameterSpec(GCM_TAG_BITS, iv), // nosemgrep: kotlin.lang.security.gcm-detection.gcm-detection
            )
            cipher.updateAAD(aad, 0, MAGIC.size + 1) // MAGIC + version only
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            return aad + ciphertext
        } finally {
            keyBytes.fill(0)
        }
    }

    /**
     * Decrypts a container produced by [encrypt].
     *
     * @param container The full container bytes (MAGIC + header + ciphertext).
     * @param passphrase The user's passphrase.
     * @return The original plaintext UTF-8 string.
     * @throws BadPassphraseException if the GCM authentication tag does not match
     *   (wrong passphrase or corrupted ciphertext).
     * @throws IllegalArgumentException if [container] is malformed, too short, or
     *   has an unrecognised magic/version.
     */
    fun decrypt(container: ByteArray, passphrase: CharArray): String {
        require(container.size > HEADER_SIZE) {
            "Container is too short to be a valid Virga encrypted config"
        }
        require(container.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            "Not a Virga encrypted config container (magic mismatch)"
        }
        val version = container[MAGIC.size]
        require(version == VERSION) { "Unsupported container version: $version" }

        // saltStart..ciphertextStart are fixed offsets within the HEADER_SIZE-byte
        // header already length-checked above, so no second size guard is needed.
        val saltStart = MAGIC.size + 1
        val ivStart = saltStart + SALT_LEN
        val ciphertextStart = ivStart + IV_LEN

        val salt = container.copyOfRange(saltStart, ivStart)
        val iv = container.copyOfRange(ivStart, ciphertextStart)
        val ciphertext = container.copyOfRange(ciphertextStart, container.size)
        val keyBytes = deriveKey(passphrase, salt)
        try {
            // IV is read from the container, unique per-container; no reuse possible.
            val cipher = Cipher.getInstance(CIPHER_ALG) // nosemgrep: kotlin.lang.security.gcm-detection.gcm-detection
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, KEY_ALG),
                GCMParameterSpec(GCM_TAG_BITS, iv), // nosemgrep: kotlin.lang.security.gcm-detection.gcm-detection
            )
            // AAD is the 9-byte header (MAGIC + version) only — same as encrypt.
            cipher.updateAAD(container, 0, MAGIC.size + 1)
            return try {
                cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
            } catch (e: javax.crypto.AEADBadTagException) {
                throw BadPassphraseException("GCM tag mismatch — wrong passphrase or corrupted data", e)
            } catch (e: javax.crypto.BadPaddingException) {
                // Some JVM impls wrap AEADBadTagException in BadPaddingException.
                throw BadPassphraseException("GCM tag mismatch — wrong passphrase or corrupted data", e)
            }
        } finally {
            keyBytes.fill(0)
        }
    }

    // -------------------------------------------------------------------------

    /**
     * Assembles the container header (MAGIC + version + salt + iv).
     * This doubles as the AAD source: only MAGIC + version (9 bytes) are
     * passed as AAD to the cipher — the salt and IV are not included in the AAD
     * because the cipher already binds them cryptographically via GCM.
     */
    private fun buildHeader(salt: ByteArray, iv: ByteArray): ByteArray =
        MAGIC + byteArrayOf(VERSION) + salt + iv

    /**
     * Derives a 256-bit AES key from [passphrase] and [salt] using
     * PBKDF2WithHmacSHA256 at [PBKDF2_ITERATIONS] iterations.
     *
     * The [PBEKeySpec] is cleared in a `finally` block. The returned [ByteArray]
     * is the caller's responsibility to zero when done.
     */
    private fun deriveKey(passphrase: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_LEN_BITS)
        return try {
            SecretKeyFactory.getInstance(KDF_ALG).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}

/**
 * Thrown by [ConfigCrypto.decrypt] when the GCM authentication tag does not match,
 * indicating either a wrong passphrase or a corrupted container.
 */
class BadPassphraseException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
