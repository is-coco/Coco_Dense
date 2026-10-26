package com.coco.dense

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class BiometricStore(private val activity: FragmentActivity) {
    private val prefs = activity.getSharedPreferences("biometric-unlock", Context.MODE_PRIVATE)
    private val alias = "coco-dense-biometric"
    val available: Boolean get() = BiometricManager.from(activity)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    val enabled: Boolean get() = prefs.contains("credential")

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(true).build())
        return generator.generateKey()
    }

    private fun prompt(cipher: Cipher, done: (Cipher) -> Unit, error: (String) -> Unit) {
        val info = BiometricPrompt.PromptInfo.Builder().setTitle("解锁 Coco Dense")
            .setSubtitle("使用本机生物识别").setNegativeButtonText("取消")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                try { done(requireNotNull(result.cryptoObject?.cipher)) } catch (e: Exception) { error(e.message ?: "生物识别失败") }
            }
            override fun onAuthenticationError(code: Int, message: CharSequence) { error(message.toString()) }
        }).authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    fun enable(master: String, dataKey: String, done: () -> Unit, error: (String) -> Unit) {
        try {
            require(available) { "设备没有可用的强生物识别" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            prompt(cipher, { verified ->
                val data = JSONObject().put("master", master).put("dataKey", dataKey).toString().toByteArray()
                prefs.edit().putString("credential", Base64.encodeToString(verified.iv + verified.doFinal(data), Base64.NO_WRAP)).apply()
                done()
            }, error)
        } catch (e: Exception) { error(e.message ?: "无法启用生物识别") }
    }

    fun unlock(done: (String, String) -> Unit, error: (String) -> Unit) {
        try {
            val saved = prefs.getString("credential", null)
            if (saved == null) { error("尚未启用生物识别"); return }
            val stored = Base64.decode(saved, Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, stored.copyOfRange(0, 12)))
            prompt(cipher, { verified ->
                val credential = JSONObject(String(verified.doFinal(stored.copyOfRange(12, stored.size))))
                done(credential.getString("master"), credential.optString("dataKey"))
            }, error)
        } catch (e: Exception) { error(e.message ?: "生物识别解锁失败，请使用主密码") }
    }
    fun clear() { prefs.edit().clear().apply() }
}
