package nic.drugrepo.db

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Thrown when the ledger key exists but cannot be used.
 *
 * This is deliberately a distinct type from the ordinary GeneralSecurityException path so no
 * caller can accidentally swallow it and "recover" by starting a new ledger: overwriting a
 * chain of custody is far worse than refusing to run.
 */
class DatabaseKeyUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * TODO.md Phase 1 task 2: the SQLCipher passphrase, protected by a Keystore-wrapped key
 * (PRD.md FR-009, architecture.md section 5).
 *
 * How it works: a 32-byte database key is generated once from SecureRandom and stored ONLY as
 * ciphertext, wrapped under a non-exportable AES-256-GCM key that lives in the Android
 * Keystore and never leaves the secure hardware. Opening the ledger unwraps the key in memory
 * and hands it to SQLCipher. Nothing in this app, and nothing in the APK, contains the key.
 *
 * Three rules are enforced here, in this order, because they are what keep a forensic ledger
 * trustworthy:
 *
 * 1. NO SOFTBACKED KEY, EVER. If the Keystore reports SECURITY_LEVEL_SOFTWARE the operation
 *    fails before any database is opened or created. architecture.md section 5 calls a
 *    software-only key "unsupported" and requires a fatal security error; the same reasoning
 *    applies to the wrapping key, because a software-backed one makes the ledger readable by
 *    anything that can reach this app's process.
 * 2. A MISSING WRAPPED KEY IS A FIRST RUN, NOT A RECOVERY. The blob's presence is the
 *    discriminator: absent => generate and persist; present => unwrap, and if that fails,
 *    fail closed.
 * 3. FAIL CLOSED ON AN EXISTING BUT UNUSABLE KEY. If the blob is present and cannot be
 *    unwrapped - tampered, invalidated, or restored from a backup without its Keystore key -
 *    nothing is regenerated, overwritten or deleted. The app reports that the ledger cannot be
 *    opened and stops. Re-encrypting an unreadable ledger under a fresh key would destroy the
 *    evidence and invent an empty chain that looks legitimate.
 *
 * Deliberately NOT done here, and why:
 *  - No user authentication on the wrapping key. architecture.md section 5 requires the device
 *    credential for *signing*; binding the storage key too would need the unlock flow that
 *    belongs to TODO Phase 2 and would make the test suite need a real unlock. Revisit when
 *    the Phase 6 signing key lands.
 *  - No key attestation. Reading OID 1.3.6.1.4.1.11129.2.1.17 is TODO Phase 6; here the
 *    achieved level is only detected from KeyInfo and refused if it is software.
 *  - No attempt to wipe the key bytes after use. SQLCipher's driver in this version has no
 *    passphrase-clearing flag and Room holds the array until first use, so the key stays in
 *    the heap longer than ideal. A limitation of the dependency, recorded rather than hidden.
 *
 * The alias, preferences file and blob layout carry a version marker so a future format
 * change can be migrated deliberately instead of silently orphaning an existing ledger.
 */
object DatabaseKeyManager {

    /** Fixed, versioned alias. Renaming it orphans every existing ledger on that device. */
    const val KEY_ALIAS = "drugrepo-db-key-v1"

    /** Private store for the wrapped database key. Private mode: no other app can read it. */
    const val PREFS_NAME = "drugrepo_key_store"

    private const val PREFS_WRAPPED_KEY = "wrapped_database_key_v1"

    /** Bump when the blob layout changes; [unwrappedKey] then refuses an unknown version. */
    private const val BLOB_VERSION = 1.toByte()

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12

    /** 256 bits. Not a tunable: this is the key SQLCipher itself hashes into its own key. */
    private const val DATABASE_KEY_BYTES = 32

    /**
     * Returns the ledger key, creating and wrapping it on first run.
     *
     * [alias] and [prefsName] exist so instrumentation tests can use their own key material.
     * Instrumentation runs under this app's UID, so a test sharing the production alias could
     * delete a real officer's ledger key.
     */
    fun unwrappedKey(
        context: Context,
        alias: String = KEY_ALIAS,
        prefsName: String = PREFS_NAME,
    ): ByteArray {
        val secretKey = loadOrCreateWrappingKey(alias)
        requireHardwareBacked(secretKey, alias)

        val stored = prefs(context, prefsName).getString(PREFS_WRAPPED_KEY, null)
            ?.takeIf { it.isNotEmpty() }
        if (stored == null) {
            // Rule 2: nothing wrapped exists, so this is a genuine first run.
            return createAndPersist(context, prefsName, secretKey)
        }

        // Rule 3: a key exists. Anything wrong with it is fatal and nothing is regenerated.
        return try {
            decrypt(secretKey, decodeBlob(stored, alias))
        } catch (e: GeneralSecurityException) {
            throw DatabaseKeyUnavailableException("cannot decrypt the stored database key", e)
        }
    }

    /**
     * The security level actually achieved for [alias], never SOFTWARE: a software-backed key
     * throws instead of being reported. Callers use this to state plainly which hardware backing
     * the device gave us, which is also what TODO Phase 6 records in the auditable record.
     */
    fun achievedSecurityLevel(alias: String = KEY_ALIAS): KeySecurityLevel {
        val key = existingWrappingKey(alias)
            ?: throw DatabaseKeyUnavailableException("no Keystore key for alias $alias")
        return requireHardwareBacked(key, alias)
    }

    /** True when a wrapped ledger key is already stored, i.e. this is not a first run. */
    fun hasWrappedKey(context: Context, prefsName: String = PREFS_NAME): Boolean =
        prefs(context, prefsName).getString(PREFS_WRAPPED_KEY, null)?.isNotEmpty() == true

    /** Test seam: removes the wrapped key only. The Keystore alias is left alone. */
    fun forgetWrappedKey(context: Context, prefsName: String = PREFS_NAME) {
        prefs(context, prefsName).edit().remove(PREFS_WRAPPED_KEY).commit()
    }

    private fun createAndPersist(
        context: Context,
        prefsName: String,
        secretKey: SecretKey,
    ): ByteArray {
        val databaseKey = ByteArray(DATABASE_KEY_BYTES).also { SecureRandom().nextBytes(it) }
        val encoded = encodeBlob(encrypt(secretKey, databaseKey))
        if (!prefs(context, prefsName).edit().putString(PREFS_WRAPPED_KEY, encoded).commit()) {
            // Without a durable blob the key would exist only in memory and be lost on restart,
            // which would mean an unreadable database with no way back. Refuse instead.
            throw DatabaseKeyUnavailableException("could not persist the wrapped database key")
        }
        return databaseKey
    }

    /** The Keystore generates the IV; it is stored beside the ciphertext because GCM needs it back. */
    private fun encrypt(secretKey: SecretKey, plaintext: ByteArray): Blob =
        Cipher.getInstance(TRANSFORMATION)
            .apply { init(Cipher.ENCRYPT_MODE, secretKey) }
            .let { Blob(it.iv, it.doFinal(plaintext)) }

    private fun decrypt(secretKey: SecretKey, blob: Blob): ByteArray =
        Cipher.getInstance(TRANSFORMATION)
            .apply { init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, blob.iv)) }
            .doFinal(blob.ciphertext)

    /** Blob layout: version byte, then the GCM IV, then ciphertext with its 128-bit tag. */
    private fun encodeBlob(blob: Blob): String {
        check(blob.iv.size == GCM_IV_BYTES) { "unexpected GCM IV size ${blob.iv.size}" }
        val bytes = ByteArray(1 + blob.iv.size + blob.ciphertext.size)
        bytes[0] = BLOB_VERSION
        blob.iv.copyInto(bytes, destinationOffset = 1)
        blob.ciphertext.copyInto(bytes, destinationOffset = 1 + blob.iv.size)
        return bytes.toHex()
    }

    private data class Blob(val iv: ByteArray, val ciphertext: ByteArray)

    private fun decodeBlob(encoded: String, alias: String): Blob {
        val bytes = try {
            encoded.hexToBytes()
        } catch (e: IllegalArgumentException) {
            throw DatabaseKeyUnavailableException("wrapped key for $alias is corrupt", e)
        }
        if (bytes.size <= 1 + GCM_IV_BYTES || bytes[0] != BLOB_VERSION) {
            throw DatabaseKeyUnavailableException(
                "wrapped key for $alias has an unrecognised layout or version",
            )
        }
        return Blob(
            iv = bytes.copyOfRange(1, 1 + GCM_IV_BYTES),
            ciphertext = bytes.copyOfRange(1 + GCM_IV_BYTES, bytes.size),
        )
    }

    private fun loadOrCreateWrappingKey(alias: String): SecretKey {
        existingWrappingKey(alias)?.let { return it }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // architecture.md section 5: StrongBox first. Some OEMs throw availability errors at
            // either KeyGenerator.init or KeyGenerator.generateKey, so we must catch both.
            val strongBoxSpec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setIsStrongBoxBacked(true)
                .build()
            try {
                val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                g.init(strongBoxSpec)
                return g.generateKey()
            } catch (t: Throwable) {
                // StrongBox unavailable - fall through to TEE. Remove any partial alias to avoid
                // reusing a half-created entry.
                try {
                    if (keyStore().containsAlias(alias)) {
                        keyStore().deleteEntry(alias)
                    }
                } catch (_: Exception) {
                }
            }
        }

        // TEE (or API < 28 path)
        val teeBuilder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
        val g2 = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        g2.init(teeBuilder.build())
        return try {
            g2.generateKey()
        } catch (e: GeneralSecurityException) {
            throw DatabaseKeyUnavailableException("could not create the ledger wrapping key", e)
        }
    }

    private fun existingWrappingKey(alias: String): SecretKey? =
        try {
            keyStore().takeIf { it.containsAlias(alias) }?.getKey(alias, null) as? SecretKey
        } catch (e: GeneralSecurityException) {
            throw DatabaseKeyUnavailableException("cannot read the ledger wrapping key", e)
        }

    /**
     * architecture.md section 5 forbids software-only keys. [KeySecurityLevel] deliberately has
     * no SOFTWARE member, so this can only ever return hardware backing or throw.
     */
    private fun requireHardwareBacked(secretKey: SecretKey, alias: String): KeySecurityLevel {
        val level = try {
            securityLevelOf(secretKey)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw DatabaseKeyUnavailableException(
                "the ledger key for $alias was invalidated by a device credential change; " +
                    "the sealed ledger can no longer be opened",
                e,
            )
        } catch (e: GeneralSecurityException) {
            throw DatabaseKeyUnavailableException("cannot read the ledger key's security level", e)
        }

        if (level == KeyProperties.SECURITY_LEVEL_SOFTWARE) {
            throw DatabaseKeyUnavailableException(
                "the ledger key for $alias is software-backed, which architecture.md section 5 " +
                    "does not allow; refusing to open or create the ledger",
            )
        }
        return if (level == KeyProperties.SECURITY_LEVEL_STRONGBOX) {
            KeySecurityLevel.STRONGBOX
        } else {
            KeySecurityLevel.TEE
        }
    }

    /**
     * STRONGBOX and TRUSTED_ENVIRONMENT are reported from API 31; below that only the
     * deprecated isInsideSecureHardware boolean exists, which is still the only signal
     * available on those releases.
     */
    private fun securityLevelOf(secretKey: SecretKey): Int {
        val factory = keyFactory()
        val info = factory.getKeySpec(secretKey, KeyInfo::class.java) as KeyInfo
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            info.securityLevel
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) {
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT
            } else {
                KeyProperties.SECURITY_LEVEL_SOFTWARE
            }
        }
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun keyFactory() =
        javax.crypto.SecretKeyFactory.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )

    private fun prefs(context: Context, prefsName: String) =
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}