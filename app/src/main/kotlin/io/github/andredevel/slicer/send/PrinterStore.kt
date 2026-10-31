package io.github.andredevel.slicer.send

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Impressoras salvas em `printers.json`; API key e código de acesso vão cifrados com uma chave
 * AES-GCM do Android Keystore, que não sai do aparelho.
 */
class PrinterStore(private val file: File) {
    fun load(): List<PrinterHost> {
        val text = try {
            AtomicFile(file).readFully().decodeToString()
        } catch (e: FileNotFoundException) {
            return emptyList()
        }
        return try {
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { i -> read(array.getJSONObject(i)) }
        } catch (e: JSONException) {
            Log.w(TAG, "unreadable $file", e)
            emptyList()
        }
    }

    fun save(printers: List<PrinterHost>) {
        val array = JSONArray()
        for (host in printers) {
            array.put(JSONObject().apply {
                put("id", host.id)
                put("name", host.name)
                put("kind", host.kind.name)
                put("address", host.address)
                host.preset?.let { put("preset", it) }
                host.secret?.takeIf { it.isNotEmpty() }?.let { put("secret", encrypt(it)) }
            })
        }
        // AtomicFile grava ao lado com fsync e troca: nem um erro no meio nem uma queda de energia
        // logo depois apagam a lista.
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(array.toString(2).toByteArray())
            atomic.finishWrite(out)
        } catch (e: IOException) {
            atomic.failWrite(out)
            throw e
        }
    }

    private fun read(json: JSONObject): PrinterHost? {
        val kind = Kind.entries.find { it.name == json.optString("kind") } ?: return null
        val secret = json.optString("secret").takeIf { it.isNotEmpty() }?.let { encrypted ->
            try {
                decrypt(encrypted)
            } catch (e: GeneralSecurityException) {
                // Chave perdida (app reinstalado com backup...): o usuário digita de novo.
                Log.w(TAG, "cannot decrypt secret", e)
                null
            }
        }
        return PrinterHost(
            id = json.getString("id"),
            name = json.getString("name"),
            kind = kind,
            address = json.getString("address"),
            secret = secret,
            preset = json.optString("preset").takeIf { it.isNotEmpty() },
        )
    }

    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(text.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(text: String): String {
        val bytes = try {
            Base64.decode(text, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            throw GeneralSecurityException(e)
        }
        if (bytes.size <= IV_SIZE) throw GeneralSecurityException("short secret")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_SIZE))
        return String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE))
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    private companion object {
        const val TAG = "PrinterStore"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "printer-secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
