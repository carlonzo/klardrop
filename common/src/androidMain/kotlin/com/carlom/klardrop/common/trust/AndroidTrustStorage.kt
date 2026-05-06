package com.carlom.klardrop.common.trust

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Android implementation of TrustStorage.
 *
 * Peer ECDH/ECDSA public keys and ECDH-derived shared secrets are stored in
 * SharedPreferences (not secret on their own). The device's own ECDSA private
 * key lives in the Android Keystore as a non-exportable key bound to alias
 * [DEVICE_KEY_ALIAS]; signing happens inside the Keystore via [signWithDeviceKey]
 * and the private bytes never leave the secure store. The matching public key
 * is cached in SharedPreferences for fast read; it can be rebuilt from the
 * Keystore certificate at any time.
 */
class AndroidTrustStorage(
    context: Context
) : TrustStorage {

    companion object {
        private const val TAG = "AndroidTrustStorage"
        private const val TRUST_PREFS = "trust_keys"
        private const val KEY_PREFIX = "trusted_device_"
        private const val ECDSA_KEY_PREFIX = "ecdsa_key_"
        private const val SHARED_SECRET_PREFIX = "shared_secret_"
        private const val DEVICE_PUBLIC_KEY = "device_public_key"
        private const val DEVICE_KEY_ALIAS = "klardrop_device_ecdsa_v1"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    }

    private val sharedPrefs = context.getSharedPreferences(TRUST_PREFS, Context.MODE_PRIVATE)

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    }

    override suspend fun storeTrustedDevice(deviceId: String, publicKey: ByteArray) {
        val encodedKey = Base64.encodeToString(publicKey, Base64.NO_WRAP)
        sharedPrefs.edit { putString(KEY_PREFIX + deviceId, encodedKey) }
    }

    override suspend fun getTrustedDeviceKey(deviceId: String): ByteArray? {
        val encodedKey = sharedPrefs.getString(KEY_PREFIX + deviceId, null) ?: return null
        return try {
            Base64.decode(encodedKey, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            sharedPrefs.edit { remove(KEY_PREFIX + deviceId) }
            null
        }
    }

    override suspend fun getAllTrustedDevices(): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        for ((key, value) in sharedPrefs.all) {
            if (key.startsWith(KEY_PREFIX) && value is String) {
                val deviceId = key.removePrefix(KEY_PREFIX)
                try {
                    result[deviceId] = Base64.decode(value, Base64.NO_WRAP)
                } catch (e: IllegalArgumentException) {
                    continue
                }
            }
        }
        return result
    }

    override suspend fun removeTrustedDevice(deviceId: String) {
        sharedPrefs.edit {
            remove(KEY_PREFIX + deviceId)
                .remove(ECDSA_KEY_PREFIX + deviceId)
                .remove(SHARED_SECRET_PREFIX + deviceId)
        }
    }

    override suspend fun clearAllTrustedDevices() {
        sharedPrefs.edit {
            for (key in sharedPrefs.all.keys) {
                if (key.startsWith(KEY_PREFIX) ||
                    key.startsWith(ECDSA_KEY_PREFIX) ||
                    key.startsWith(SHARED_SECRET_PREFIX)
                ) {
                    remove(key)
                }
            }
        }
    }

    override suspend fun storeECDSAKey(deviceId: String, ecdsaPublicKey: ByteArray) {
        val encodedKey = Base64.encodeToString(ecdsaPublicKey, Base64.NO_WRAP)
        sharedPrefs.edit { putString(ECDSA_KEY_PREFIX + deviceId, encodedKey) }
    }

    override suspend fun getECDSAKey(deviceId: String): ByteArray? {
        val encodedKey = sharedPrefs.getString(ECDSA_KEY_PREFIX + deviceId, null) ?: return null
        return try {
            Base64.decode(encodedKey, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            sharedPrefs.edit { remove(ECDSA_KEY_PREFIX + deviceId) }
            null
        }
    }

    override suspend fun storeSharedSecret(deviceId: String, sharedSecret: ByteArray) {
        val encoded = Base64.encodeToString(sharedSecret, Base64.NO_WRAP)
        sharedPrefs.edit { putString(SHARED_SECRET_PREFIX + deviceId, encoded) }
    }

    override suspend fun getSharedSecret(deviceId: String): ByteArray? {
        val encoded = sharedPrefs.getString(SHARED_SECRET_PREFIX + deviceId, null) ?: return null
        return try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            sharedPrefs.edit { remove(SHARED_SECRET_PREFIX + deviceId) }
            null
        }
    }

    // Device Identity — Keystore-backed

    override suspend fun storeDevicePrivateKey(privateKey: ByteArray) {
        // Android Keystore does not import raw EC private bytes; the Keystore
        // generates the key in place. The default ensureDeviceKey path is
        // bypassed via our override below, so this method is reachable only
        // from test paths. Generate the alias if it doesn't exist.
        if (!keyStore.containsAlias(DEVICE_KEY_ALIAS)) {
            generateDeviceKey()
        }
    }

    override suspend fun getDevicePrivateKey(): ByteArray? {
        // Private bytes never leave the Keystore.
        return null
    }

    override suspend fun hasDeviceKey(): Boolean = keyStore.containsAlias(DEVICE_KEY_ALIAS)

    override suspend fun storeDevicePublicKey(publicKey: ByteArray) {
        sharedPrefs.edit {
            putString(DEVICE_PUBLIC_KEY, Base64.encodeToString(publicKey, Base64.NO_WRAP))
        }
    }

    override suspend fun getDevicePublicKey(): ByteArray? {
        sharedPrefs.getString(DEVICE_PUBLIC_KEY, null)?.let { encoded ->
            try {
                return Base64.decode(encoded, Base64.NO_WRAP)
            } catch (_: IllegalArgumentException) {
                sharedPrefs.edit { remove(DEVICE_PUBLIC_KEY) }
            }
        }
        // Cache miss / corrupt cache: rebuild from the Keystore certificate.
        val cert = runCatching { keyStore.getCertificate(DEVICE_KEY_ALIAS) }.getOrNull() ?: return null
        val pub = cert.publicKey as? ECPublicKey ?: return null
        val raw = pub.toRawUncompressed()
        sharedPrefs.edit { putString(DEVICE_PUBLIC_KEY, Base64.encodeToString(raw, Base64.NO_WRAP)) }
        return raw
    }

    override suspend fun deleteDevicePrivateKey() {
        if (keyStore.containsAlias(DEVICE_KEY_ALIAS)) {
            keyStore.deleteEntry(DEVICE_KEY_ALIAS)
        }
        sharedPrefs.edit { remove(DEVICE_PUBLIC_KEY) }
    }

    override suspend fun signWithDeviceKey(data: ByteArray, crypto: TrustCrypto): ByteArray? {
        val privateKey = (keyStore.getKey(DEVICE_KEY_ALIAS, null) as? PrivateKey) ?: return null
        val derSignature = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(data)
            sign()
        }
        return EcdsaSignatureFormat.derToRaw(derSignature)
    }

    override suspend fun ensureDeviceKey(crypto: TrustCrypto): TrustCrypto.ECDSAPublicKey {
        if (!keyStore.containsAlias(DEVICE_KEY_ALIAS)) {
            generateDeviceKey()
        }
        val publicBytes = getDevicePublicKey()
            ?: error("Failed to read device public key from Android Keystore after generation")
        return TrustCrypto.ECDSAPublicKey(publicBytes)
    }

    private fun generateDeviceKey() {
        val baseSpec = KeyGenParameterSpec.Builder(
            DEVICE_KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)

        // Prefer StrongBox where available (API 28+), but fall back gracefully:
        // many devices don't have a StrongBox HSM and the generator throws
        // StrongBoxUnavailableException. The TEE-backed key is still much better
        // than plaintext SharedPreferences.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                runGenerator(baseSpec.setIsStrongBoxBacked(true).build())
                Log.i(TAG, "Generated device identity in StrongBox")
                return
            } catch (_: StrongBoxUnavailableException) {
                Log.i(TAG, "StrongBox unavailable, falling back to TEE-backed Keystore")
            }
        }
        runGenerator(baseSpec.setIsStrongBoxBacked(false).build())
        Log.i(TAG, "Generated device identity in Android Keystore (TEE-backed if supported)")
    }

    private fun runGenerator(spec: KeyGenParameterSpec) {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE)
        generator.initialize(spec)
        generator.generateKeyPair()
    }
}
