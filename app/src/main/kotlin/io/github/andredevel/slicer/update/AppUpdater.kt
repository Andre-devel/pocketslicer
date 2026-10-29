package io.github.andredevel.slicer.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Release do GitHub com o APK do app: [version] sem o "v" do tag. */
data class Release(val version: String, val notes: String, val apkUrl: String, val apkSize: Long, val sha256: String?)

/**
 * Atualização pelo GitHub Releases (o app não está numa loja): procura uma versão mais nova que a
 * instalada, baixa o APK e o instala com o PackageInstaller. O Android só aceita o APK assinado
 * com a mesma chave (scripts/make-release-key.sh) e com versionCode igual ou maior.
 */
class AppUpdater(private val context: Context) {
    private val dir = File(context.cacheDir, "update")

    val installedVersion: String = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    /** A release mais nova (pré-releases também) com APK, se for mais nova que a instalada. */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val json = JSONArray(get(URL(RELEASES_URL)).bufferedReader().use { it.readText() })
        val release = (0 until json.length()).map(json::getJSONObject).firstOrNull { !it.optBoolean("draft") } ?: return@withContext null
        val assets = release.getJSONArray("assets")
        val apk = (0 until assets.length()).map(assets::getJSONObject).firstOrNull { it.getString("name").endsWith(".apk") } ?: return@withContext null
        val version = release.getString("tag_name").removePrefix("v")
        if (compareVersions(version, installedVersion) <= 0) return@withContext null
        Release(
            version,
            release.optString("body"),
            apk.getString("browser_download_url"),
            apk.getLong("size"),
            apk.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
        )
    }

    /** Baixa o APK de [release] (ou reaproveita o já baixado) e confere tamanho e SHA-256. */
    suspend fun download(release: Release, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val file = File(dir, "PocketSlicer-${release.version}.apk")
        if (file.length() == release.apkSize && verify(file, release)) return@withContext file
        // Só o APK desta versão: os de versões anteriores já não servem.
        dir.deleteRecursively()
        dir.mkdirs()
        val partial = File(dir, file.name + ".part")
        get(URL(release.apkUrl)).use { input ->
            partial.outputStream().use { output ->
                val buffer = ByteArray(BUFFER)
                var received = 0L
                while (true) {
                    ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    received += read
                    progress(if (release.apkSize > 0) received.toFloat() / release.apkSize else 0f)
                }
            }
        }
        if (partial.length() != release.apkSize || !verify(partial, release)) {
            partial.delete()
            throw IOException("APK ${release.version}: tamanho ou SHA-256 diferente do publicado")
        }
        partial.renameTo(file)
        file
    }

    /**
     * Instala [apk] por cima do app. O resultado chega a [UpdateReceiver]; o Android fecha o app
     * ao concluir. Sem confirmação a partir do Android 12 quando o próprio app instalou a versão
     * atual; senão, o sistema pede confirmação (e, na primeira vez, a permissão de instalar apps).
     */
    suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("app.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output, BUFFER) }
                    session.fsync(output)
                }
                val intent = Intent(context, UpdateReceiver::class.java)
                // Mutável: o PackageInstaller põe o status e o intent de confirmação nos extras.
                val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(id)
            throw e
        }
    }

    /** Apaga o APK baixado (depois de instalado, ou quando já não há versão nova). */
    fun clear() {
        dir.deleteRecursively()
    }

    private fun verify(file: File, release: Release): Boolean {
        val expected = release.sha256 ?: return true
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.equals(expected, ignoreCase = true)
    }

    private fun get(url: URL): InputStream {
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            throw IOException("HTTP $code: $url")
        }
        return connection.inputStream
    }

    private companion object {
        const val RELEASES_URL = "https://api.github.com/repos/Andre-devel/pocketslicer/releases?per_page=10"
        const val TIMEOUT_MS = 20_000
        const val BUFFER = 64 * 1024
    }
}

/** Compara "0.10.1" com "0.9" número a número; sufixos ("-beta") são ignorados. */
fun compareVersions(a: String, b: String): Int {
    fun parts(version: String) = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val x = parts(a)
    val y = parts(b)
    for (i in 0 until maxOf(x.size, y.size)) {
        val diff = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
        if (diff != 0) return diff
    }
    return 0
}
