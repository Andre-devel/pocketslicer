package io.github.andredevel.slicer

import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Arquivo aberto recentemente; [uri] com permissão de leitura persistente. */
data class RecentFile(val uri: Uri, val name: String)

/** Arquivos recentes em `recent.json`, o mais novo primeiro. */
class RecentFilesStore(private val file: File) {
    fun load(): List<RecentFile> {
        if (!file.isFile) return emptyList()
        return try {
            val array = JSONArray(file.readText())
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
        // Grava ao lado e troca, para um erro no meio não apagar a lista.
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(array.toString(2))
        if (!temp.renameTo(file)) throw IOException("cannot replace $file")
    }

    companion object {
        private const val TAG = "RecentFilesStore"

        /** Quantos arquivos a lista guarda. */
        const val MAX = 5
    }
}
