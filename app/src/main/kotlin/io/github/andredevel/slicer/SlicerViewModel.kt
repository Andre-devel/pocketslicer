package io.github.andredevel.slicer

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.andredevel.slicer.core.PresetKind
import io.github.andredevel.slicer.core.SliceStats
import io.github.andredevel.slicer.core.SlicerEngine
import io.github.andredevel.slicer.core.SlicerException
import io.github.andredevel.slicer.profiles.OrcaResources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class PresetChoice(val options: List<String> = emptyList(), val selected: String? = null)

sealed interface SliceState {
    data object Idle : SliceState
    data class Running(val percent: Int, val text: String) : SliceState
    data class Done(val stats: SliceStats) : SliceState
}

data class SlicerUiState(
    /** Operação em andamento que bloqueia os controles (carregar perfis, modelo...). */
    @StringRes val busy: Int? = R.string.busy_installing_profiles,
    val error: String? = null,
    val vendors: PresetChoice = PresetChoice(),
    val presets: Map<PresetKind, PresetChoice> = emptyMap(),
    val modelName: String? = null,
    val slice: SliceState = SliceState.Idle,
) {
    val canSlice get() = busy == null && modelName != null && slice !is SliceState.Running
}

class SlicerViewModel(application: Application) : AndroidViewModel(application) {
    private val app get() = getApplication<Application>()
    private val _state = MutableStateFlow(SlicerUiState())
    val state: StateFlow<SlicerUiState> = _state.asStateFlow()

    private val gcodeFile get() = File(app.cacheDir, "output.gcode")
    private var sliceJob: Job? = null

    private val engine = viewModelScope.async {
        val resources = withContext(Dispatchers.IO) { OrcaResources.install(app) }
        SlicerEngine.create(resources, File(app.filesDir, "orca-data")).also(::addCloseable)
    }

    init {
        launchEngine(R.string.busy_installing_profiles) { engine ->
            val vendors = engine.vendors()
            val vendor = if (DEFAULT_VENDOR in vendors) DEFAULT_VENDOR else vendors.first()
            _state.update { it.copy(vendors = PresetChoice(vendors)) }
            loadVendor(engine, vendor)
        }
    }

    fun selectVendor(vendor: String) = launchEngine(R.string.busy_loading_vendor) { loadVendor(it, vendor) }

    fun selectPreset(kind: PresetKind, name: String) = launchEngine(null) { engine ->
        engine.select(kind, name)
        refreshPresets(engine)
    }

    fun openModel(uri: Uri) = launchEngine(R.string.busy_loading_model) { engine ->
        val name = displayName(uri)
        val dir = File(app.cacheDir, "model").apply { deleteRecursively(); mkdirs() }
        // libslic3r detecta o formato pela extensão, então o nome original é mantido.
        val file = File(dir, name)
        withContext(Dispatchers.IO) {
            val input = app.contentResolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
            input.use { file.outputStream().use(it::copyTo) }
        }
        engine.loadModel(file)
        _state.update { it.copy(modelName = name, slice = SliceState.Idle) }
    }

    fun slice() {
        sliceJob = launchEngine(null) { engine ->
            _state.update { it.copy(slice = SliceState.Running(0, "")) }
            try {
                val stats = engine.slice(gcodeFile) { percent, text ->
                    _state.update { it.copy(slice = SliceState.Running(percent, text)) }
                }
                _state.update { it.copy(slice = SliceState.Done(stats)) }
            } finally {
                _state.update { if (it.slice is SliceState.Running) it.copy(slice = SliceState.Idle) else it }
            }
        }
    }

    fun cancelSlice() {
        sliceJob?.cancel()
    }

    fun saveGcode(uri: Uri) = launchEngine(R.string.busy_saving) {
        withContext(Dispatchers.IO) {
            val output = app.contentResolver.openOutputStream(uri) ?: throw IOException("cannot open $uri")
            output.use { gcodeFile.inputStream().use { input -> input.copyTo(it) } }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Nome sugerido para o G-code: o do modelo com a extensão trocada. */
    fun gcodeName(): String = (_state.value.modelName?.substringBeforeLast('.') ?: "model") + ".gcode"

    private suspend fun loadVendor(engine: SlicerEngine, vendor: String) {
        engine.loadVendor(vendor)
        _state.update { it.copy(vendors = it.vendors.copy(selected = vendor)) }
        refreshPresets(engine)
    }

    private suspend fun refreshPresets(engine: SlicerEngine) {
        val presets = PresetKind.entries.associateWith { PresetChoice(engine.presets(it), engine.selected(it)) }
        // Perfis mudaram: o G-code anterior não corresponde mais à seleção.
        _state.update { it.copy(presets = presets, slice = SliceState.Idle) }
    }

    private fun displayName(uri: Uri): String =
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "model.stl"

    private fun launchEngine(@StringRes busy: Int?, block: suspend (SlicerEngine) -> Unit): Job = viewModelScope.launch {
        _state.update { it.copy(busy = busy, error = null) }
        try {
            block(engine.await())
        } catch (e: SlicerException) {
            _state.update { it.copy(error = e.message) }
        } catch (e: IOException) {
            _state.update { it.copy(error = e.message ?: e.toString()) }
        } finally {
            _state.update { it.copy(busy = null) }
        }
    }

    private companion object {
        const val DEFAULT_VENDOR = "BBL"
    }
}
