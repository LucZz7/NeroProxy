package com.necxa.proxy.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-256-GCM with a non-exportable Android Keystore key. */
object SecretBox {
    private const val ALIAS = "nero_proxy_secret_key"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return g.generateKey()
    }
    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }
    fun decrypt(enc: String): String? = try {
        val raw = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        String(c.doFinal(raw, 12, raw.size - 12))
    } catch (e: Exception) { null }
}

class ConfigStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("nero_cfg", Context.MODE_PRIVATE)
    fun save(f: FormState) {
        sp.edit()
            .putString("type", f.type.name).putString("host", f.host.trim()).putString("port", f.port.trim())
            .putString("user", f.username).putString("testUrl", f.testUrl.trim())
            .putString("pass", if (f.password.isEmpty()) "" else SecretBox.encrypt(f.password))
            .apply()
    }
    fun load(): FormState? {
        val host = sp.getString("host", null) ?: return null
        val enc = sp.getString("pass", "") ?: ""
        return FormState(
            type = runCatching { ProxyType.valueOf(sp.getString("type", "SOCKS5")!!) }.getOrDefault(ProxyType.SOCKS5),
            host = host, port = sp.getString("port", "") ?: "", username = sp.getString("user", "") ?: "",
            password = if (enc.isEmpty()) "" else SecretBox.decrypt(enc) ?: "",
            testUrl = sp.getString("testUrl", "https://example.com/") ?: "https://example.com/")
    }
}
