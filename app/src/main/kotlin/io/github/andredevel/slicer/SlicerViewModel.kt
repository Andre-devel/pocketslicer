package io.github.andredevel.slicer

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.andredevel.slicer.core.ObjectMesh
import io.github.andredevel.slicer.core.OptionDef
import io.github.andredevel.slicer.core.PresetValues
import io.github.andredevel.slicer.core.PresetKind
import io.github.andredevel.slicer.core.SliceStats
import io.github.andredevel.slicer.core.SlicerEngine
import io.github.andredevel.slicer.core.SlicerException
import io.github.andredevel.slicer.core.Thumbnail
import io.github.andredevel.slicer.profiles.OrcaResources
import io.github.andredevel.slicer.render.Preview
import io.github.andredevel.slicer.render.Scene
import io.github.andredevel.slicer.render.Tool
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

/**
 * Presets de um tipo: [options] para escolher, o [selected], quais são de usuário ([user]) e os
 * valores do preset em edição, com as alterações não salvas.
 */
data class PresetChoice(
    val options: List<String> = emptyList(),
    val selected: String? = null,
    val user: Set<String> = emptySet(),
    val values: PresetValues = PresetValues(emptyMap(), emptySet()),
) {
    val dirty get() = values.dirty.isNotEmpty()
    val selectedIsUser get() = selected in user
}

sealed interface SliceState {
    data object Idle : SliceState
    data class Running(val percent: Int, val text: String) : SliceState
    /** [preview]: caminhos do G-code gerado; deixa de valer com a próxima mudança de cena ou perfil. */
    data class Done(val stats: SliceStats, val preview: Preview) : SliceState
}

data class SlicerUiState(
    /** Operação em andamento que bloqueia os controles (carregar perfis, modelo...). */
    @StringRes val busy: Int? = R.string.busy_installing_profiles,
    val error: String? = null,
    val vendors: PresetChoice = PresetChoice(),
    val presets: Map<PresetKind, PresetChoice> = emptyMap(),
    /** Definições das opções editáveis (PrintConfigDef do Orca), fixas. */
    val optionDefs: Map<PresetKind, List<OptionDef>> = emptyMap(),
    /** Nome do primeiro modelo adicionado; dá nome ao G-code. */
    val modelName: String? = null,
    val scene: Scene? = null,
    val tool: Tool = Tool.MOVE,
    /** Viewport na pré-visualização do G-code (só com fatiamento concluído). */
    val showPreview: Boolean = false,
    val slice: SliceState = SliceState.Idle,
) {
    val objectCount get() = scene?.placements?.size ?: 0
    val anyOutside get() = scene?.placements?.any { it.outside } == true
    val canSlice get() = busy == null && objectCount > 0 && !anyOutside && slice !is SliceState.Running
    val preview get() = (slice as? SliceState.Done)?.preview
    val previewVisible get() = showPreview && preview != null
    val selectedPlacement get() = scene?.selected?.let { scene.placements.getOrNull(it) }
}

class SlicerViewModel(application: Application) : AndroidViewModel(application) {
    private val app get() = getApplication<Application>()
    private val _state = MutableStateFlow(SlicerUiState())
    val state: StateFlow<SlicerUiState> = _state.asStateFlow()

    private val gcodeFile get() = File(app.cacheDir, "output.gcode")

    /**
     * Renderiza a miniatura dos objetos (largura, altura) para o projeto e o G-code; null sem
     * objetos. O viewport registra enquanto existe (ViewportView.thumbnail).
     */
    var thumbnails: (suspend (width: Int, height: Int) -> Thumbnail?)? = null
    private var sliceJob: Job? = null

    private val engine = viewModelScope.async {
        val resources = withContext(Dispatchers.IO) { OrcaResources.install(app) }
        SlicerEngine.create(resources, File(app.filesDir, "orca-data")).also(::addCloseable)
    }

    // Perfis iniciais; arquivos abertos por outro app ("Abrir com") esperam por ela.
    private val setup = launchEngine(R.string.busy_installing_profiles) { engine ->
            val vendors = engine.vendors()
            val vendor = if (DEFAULT_VENDOR in vendors) DEFAULT_VENDOR else vendors.first()
            val defs = PresetKind.entries.associateWith { engine.options(it) }
            _state.update { it.copy(vendors = PresetChoice(vendors), optionDefs = defs) }
            loadVendor(engine, vendor)
        }

    fun selectVendor(vendor: String) = launchEngine(R.string.busy_loading_vendor) { loadVendor(it, vendor) }

    fun selectPreset(kind: PresetKind, name: String) = launchEngine(null) { engine ->
        engine.select(kind, name)
        refreshPresets(engine)
        // A mesa muda com a impressora, e o modelo é recentralizado nela.
        if (kind == PresetKind.PRINTER) refreshScene(engine)
    }

    /** [value] no formato do Orca; erros de valor aparecem como mensagem. */
    fun setOption(kind: PresetKind, key: String, value: String) = launchEngine(null) { engine ->
        try {
            engine.setOption(kind, key, value)
        } finally {
            // Também no erro: o campo volta a mostrar o valor em vigor.
            refreshPresets(engine)
        }
        // Mesa e volume vêm da impressora.
        if (kind == PresetKind.PRINTER) refreshScene(engine)
    }

    fun discardChanges(kind: PresetKind) = launchEngine(null) { engine ->
        engine.discardChanges(kind)
        refreshPresets(engine)
        if (kind == PresetKind.PRINTER) refreshScene(engine)
    }

    fun savePreset(kind: PresetKind, name: String) = launchEngine(null) { engine ->
        engine.savePreset(kind, name.trim())
        refreshPresets(engine)
    }

    fun deletePreset(kind: PresetKind, name: String) = launchEngine(null) { engine ->
        engine.deletePreset(kind, name)
        refreshPresets(engine)
        if (kind == PresetKind.PRINTER) refreshScene(engine)
    }

    fun importPreset(uri: Uri) = launchEngine(null) { engine ->
        val file = File(File(app.cacheDir, "import").apply { deleteRecursively(); mkdirs() }, displayName(uri))
        copy(uri, file)
        engine.importPreset(file)
        refreshPresets(engine)
    }

    fun exportPreset(kind: PresetKind, uri: Uri) = launchEngine(R.string.busy_saving) { engine ->
        val file = File(app.cacheDir, "export.json")
        engine.exportPreset(kind, file)
        withContext(Dispatchers.IO) {
            val output = app.contentResolver.openOutputStream(uri) ?: throw IOException("cannot open $uri")
            output.use { file.inputStream().use { input -> input.copyTo(it) } }
        }
    }

    /** Nome sugerido ao exportar: o do preset em edição. */
    fun presetFileName(kind: PresetKind): String = (_state.value.presets[kind]?.selected ?: "preset") + ".json"

    fun openModel(uri: Uri) = launchEngine(R.string.busy_loading_model) { engine -> addModel(engine, uri, displayName(uri)) }

    private suspend fun addModel(engine: SlicerEngine, uri: Uri, name: String) {
        val dir = File(app.cacheDir, "model").apply { deleteRecursively(); mkdirs() }
        // libslic3r detecta o formato pela extensão, então o nome original é mantido.
        val file = File(dir, name)
        copy(uri, file)
        engine.addModel(file)
        _state.update { it.copy(modelName = it.modelName ?: name, slice = SliceState.Idle) }
        refreshScene(engine, engine.objects())
    }

    /**
     * Abre pelo tipo do arquivo: projeto 3MF (substitui objetos e perfis), G-code (direto
     * na pré-visualização) ou modelo STL/OBJ (acrescentado como em [openModel]).
     */
    fun open(uri: Uri) = launchEngine(R.string.busy_opening) { engine ->
        setup.join()
        _state.update { it.copy(busy = R.string.busy_opening) }
        val name = displayName(uri)
        when (name.substringAfterLast('.', "").lowercase()) {
            "3mf" -> {
                val file = File(File(app.cacheDir, "model").apply { deleteRecursively(); mkdirs() }, name)
                copy(uri, file)
                val vendor = engine.openProject(file)
                _state.update { it.copy(vendors = it.vendors.copy(selected = vendor), modelName = name, showPreview = false) }
                refreshPresets(engine)
                refreshScene(engine, engine.objects())
            }
            "gcode" -> {
                // Fica como o G-code atual: "Salvar G-code" grava uma cópia dele.
                copy(uri, gcodeFile)
                val stats = engine.loadGcode(gcodeFile)
                val preview = withContext(Dispatchers.Default) { Preview(engine.toolpaths()) }
                _state.update { it.copy(modelName = name, slice = SliceState.Done(stats, preview), showPreview = true) }
            }
            else -> addModel(engine, uri, name)
        }
    }

    fun saveProject(uri: Uri) = launchEngine(R.string.busy_saving_project) { engine ->
        val thumbnail = thumbnails?.invoke(PROJECT_THUMBNAIL_SIZE, PROJECT_THUMBNAIL_SIZE)
        val file = File(app.cacheDir, "project.3mf")
        engine.saveProject(file, thumbnail)
        withContext(Dispatchers.IO) {
            val output = app.contentResolver.openOutputStream(uri) ?: throw IOException("cannot open $uri")
            output.use { file.inputStream().use { input -> input.copyTo(it) } }
        }
    }

    /** Nome sugerido para o projeto: o do modelo com a extensão trocada. */
    fun projectName(): String = (_state.value.modelName?.substringBeforeLast('.') ?: "model") + ".3mf"

    fun duplicate(objectIndex: Int) = editScene { engine ->
        engine.duplicate(objectIndex)
        // A cópia vai para o fim, com a mesma malha.
        _state.value.scene?.meshes?.let { it + it[objectIndex] }
    }

    fun remove(objectIndex: Int) = editScene { engine ->
        engine.remove(objectIndex)
        val meshes = _state.value.scene?.meshes?.filterIndexed { i, _ -> i != objectIndex }
        if (meshes.isNullOrEmpty()) _state.update { it.copy(modelName = null) }
        meshes
    }

    fun arrange() = editScene { engine ->
        engine.arrange()
        null
    }

    fun select(objectIndex: Int?) = _state.update { state ->
        state.copy(scene = state.scene?.copy(selected = objectIndex))
    }

    fun selectTool(tool: Tool) = _state.update { it.copy(tool = tool) }

    fun showPreview(show: Boolean) = _state.update { it.copy(showPreview = show) }

    fun setTransform(objectIndex: Int, transform: FloatArray) = editScene { it.setTransform(objectIndex, transform); null }

    fun layOnFace(objectIndex: Int, triangle: Int) = editScene { it.layOnFace(objectIndex, triangle); null }

    fun setPlacement(objectIndex: Int, offset: FloatArray, rotation: FloatArray, scale: FloatArray) =
        editScene { it.setPlacement(objectIndex, offset, rotation, scale); null }

    fun slice() {
        sliceJob = launchEngine(null) { engine ->
            _state.update { it.copy(slice = SliceState.Running(0, "")) }
            try {
                val thumbnails = engine.gcodeThumbnailSizes().mapNotNull { (width, height) -> this.thumbnails?.invoke(width, height) }
                val stats = engine.slice(gcodeFile, thumbnails) { percent, text ->
                    _state.update { it.copy(slice = SliceState.Running(percent, text)) }
                }
                val toolpaths = engine.toolpaths()
                val preview = withContext(Dispatchers.Default) { Preview(toolpaths) }
                _state.update { it.copy(slice = SliceState.Done(stats, preview), showPreview = true) }
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
        refreshScene(engine)
    }

    private suspend fun copy(uri: Uri, file: File) = withContext(Dispatchers.IO) {
        val input = app.contentResolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
        input.use { file.outputStream().use(it::copyTo) }
    }

    private suspend fun refreshPresets(engine: SlicerEngine) {
        val presets = PresetKind.entries.associateWith {
            PresetChoice(engine.presets(it), engine.selected(it), engine.userPresets(it).toSet(), engine.values(it))
        }
        // Perfis mudaram: o G-code anterior não corresponde mais à seleção.
        _state.update { it.copy(presets = presets, slice = SliceState.Idle) }
    }

    /**
     * Relê mesa e posições do motor. [meshes]: nova lista de malhas quando os objetos
     * mudaram (a seleção é limpa); null mantém as atuais.
     */
    private suspend fun refreshScene(engine: SlicerEngine, meshes: List<ObjectMesh>? = null) {
        val bed = engine.bed()
        val placements = engine.placements()
        _state.update { state ->
            val selected = state.scene?.selected?.takeIf { meshes == null && it < placements.size }
            state.copy(scene = Scene(bed, meshes ?: state.scene?.meshes.orEmpty(), placements, selected))
        }
    }

    /** Altera a cena no motor; [edit] devolve as malhas novas, ou null se só posições mudaram. */
    private fun editScene(edit: suspend (SlicerEngine) -> List<ObjectMesh>?) = launchEngine(null) { engine ->
        var meshes: List<ObjectMesh>? = null
        try {
            meshes = edit(engine)
            _state.update { it.copy(slice = SliceState.Idle) }
        } finally {
            // Também em caso de erro: descarta a pré-visualização do viewport.
            refreshScene(engine, meshes)
        }
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
        /** Lado da miniatura do projeto, como THUMBNAIL_SIZE_3MF do Orca. */
        const val PROJECT_THUMBNAIL_SIZE = 512
    }
}
