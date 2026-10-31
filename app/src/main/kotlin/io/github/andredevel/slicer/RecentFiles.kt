package io.github.andredevel.slicer

import android.net.Uri
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** Arquivo aberto recentemente; [uri] com permissão de leitura persistente. */
data class RecentFile(val uri: Uri, val name: String)

/** Arquivos recentes em `recent.json`, o mais novo primeiro. */
class RecentFilesStore(private val file: File) {
    fun load(): List<RecentFile> {
        val text = try {
            AtomicFile(file).readFully().decodeToString()
        } catch (e: FileNotFoundException) {
            return emptyList()
        }
        return try {
            val array = JSONArray(text)
            (0 until array.length()).map { i ->
                val json = array.getJSONObject(i)
                RecentFile(Uri.parse(json.getString("uri")), json.getString("name"))
            }
        } catch (e: JSONException) {
            Log.w(TAG, "unreadable $file", e)
            emptyList()
        }
    }

    fun save(files: List<RecentFile>) {
        val array = JSONArray()
        for (recent in files) array.put(JSONObject().put("uri", recent.uri.toString()).put("name", recent.name))
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

    companion object {
        private const val TAG = "RecentFilesStore"

        /** Quantos arquivos a lista guarda. */
        const val MAX = 5
    }
}
