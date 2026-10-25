package io.github.andredevel.slicer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import io.github.andredevel.slicer.core.Placement
import io.github.andredevel.slicer.render.Tool
import io.github.andredevel.slicer.render.ViewportListener
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.andredevel.slicer.core.PresetKind
import io.github.andredevel.slicer.core.SliceStats
import io.github.andredevel.slicer.render.PreviewSettings
import io.github.andredevel.slicer.core.Thumbnail
import io.github.andredevel.slicer.render.ViewportView
import java.util.Locale

/** Tela principal: viewport 3D e painel com modelo → perfis → fatiar → salvar G-code. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlicerScreen(viewModel: SlicerViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    val openModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::openModel)
    }
    val saveGcode = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let(viewModel::saveGcode)
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::open)
    }
    val saveProject = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("model/3mf")) { uri ->
        uri?.let(viewModel::saveProject)
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            // Até o usuário fechar: a mensagem explica por que algo não aconteceu.
            snackbar.showSnackbar(it, withDismissAction = true, duration = SnackbarDuration.Indefinite)
            viewModel.dismissError()
        }
    }

    val enabled = state.busy == null && state.slice !is SliceState.Running
    // RF-50/52: barra superior, viewport e painel; em paisagem o painel fica à direita e
    // pode ser recolhido, em retrato vira um bottom sheet.
    // Orientação pela configuração, não por BoxWithConstraints: com o Scaffold dentro de um
    // BoxWithConstraints, o GLSurfaceView travou a thread principal (ANR) ao abrir no emulador.
    run {
        val configuration = LocalConfiguration.current
        val landscape = configuration.screenWidthDp > configuration.screenHeightDp
        val sheetMaxHeight = configuration.screenHeightDp.dp * 0.7f
        var panelVisible by rememberSaveable { mutableStateOf(true) }
        // Num celular em retrato não cabem os botões ao lado do título (ele quebrava letra a
        // letra): Projeto, Adicionar modelo e Arranjar vão para o menu "⋮".
        val compactBar = configuration.screenWidthDp < COMPACT_BAR_WIDTH_DP
        val topBar = @Composable {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    val openItem = @Composable { close: () -> Unit ->
                        DropdownMenuItem(text = { Text(stringResource(R.string.open_file)) }, onClick = {
                            close()
                            openFile.launch(arrayOf("*/*"))
                        })
                    }
                    val saveItem = @Composable { close: () -> Unit ->
                        DropdownMenuItem(text = { Text(stringResource(R.string.save_project)) }, enabled = state.objectCount > 0, onClick = {
                            close()
                            saveProject.launch(viewModel.projectName())
                        })
                    }
                    // STL/3MF chegam com MIME variado (model/stl, application/octet-stream...).
                    val addModel = { openModel.launch(arrayOf("*/*")) }
                    val canArrange = enabled && state.objectCount > 0 && !state.previewVisible
                    if (state.preview != null) {
                        TextButton(onClick = { viewModel.showPreview(!state.showPreview) }) {
                            Text(stringResource(if (state.showPreview) R.string.prepare else R.string.preview))
                        }
                    }
                    if (compactBar) {
                        var menu by remember { mutableStateOf(false) }
                        val moreOptions = stringResource(R.string.more_options)
                        Box {
                            TextButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = moreOptions }) {
                                Text("⋮", style = MaterialTheme.typography.titleLarge)
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.add_model)) }, enabled = enabled, onClick = {
                                    menu = false
                                    addModel()
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.arrange)) }, enabled = canArrange, onClick = {
                                    menu = false
                                    viewModel.arrange()
                                })
                                if (enabled) {
                                    openItem { menu = false }
                                    saveItem { menu = false }
                                }
                            }
                        }
                        return@TopAppBar
                    }
                    var projectMenu by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { projectMenu = true }, enabled = enabled) { Text(stringResource(R.string.project)) }
                        DropdownMenu(expanded = projectMenu, onDismissRequest = { projectMenu = false }) {
                            openItem { projectMenu = false }
                            saveItem { projectMenu = false }
                        }
                    }
                    TextButton(onClick = addModel, enabled = enabled) {
                        Text(stringResource(R.string.add_model))
                    }
                    TextButton(onClick = viewModel::arrange, enabled = canArrange) {
                        Text(stringResource(R.string.arrange))
                    }
                    if (landscape) {
                        TextButton(onClick = { panelVisible = !panelVisible }) {
                            Text(stringResource(if (panelVisible) R.string.hide_panel else R.string.show_panel))
                        }
                    }
                },
            )
        }
        val panel = @Composable { modifier: Modifier -> ControlPanel(state, viewModel, modifier, saveGcode::launch) }

        if (landscape) {
            Scaffold(topBar = topBar, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
                Row(Modifier.padding(padding).fillMaxSize()) {
                    Viewport(state, viewModel, Modifier.weight(1f).fillMaxHeight())
                    if (panelVisible) panel(Modifier.width(PANEL_WIDTH).fillMaxHeight())
                }
            }
        } else {
            BottomSheetScaffold(
                topBar = topBar,
                snackbarHost = { SnackbarHost(snackbar) },
                // A parte visível do sheet recolhido fica acima da barra de navegação.
                sheetPeekHeight = SHEET_PEEK_HEIGHT + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                sheetContent = { panel(Modifier.fillMaxWidth().heightIn(max = sheetMaxHeight)) },
            ) { padding ->
                // O viewport ocupa a tela toda; o sheet recolhido cobre só a faixa de baixo.
                Viewport(state, viewModel, Modifier.padding(padding).fillMaxSize())
            }
        }
    }
}

@Composable
private fun Viewport(state: SlicerUiState, viewModel: SlicerViewModel, modifier: Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var view by remember { mutableStateOf<ViewportView?>(null) }
    // Barras e legenda da pré-visualização; recomeçam com cada fatiamento.
    var previewSettings by remember(state.preview) { mutableStateOf(PreviewSettings()) }
    // Menu do toque longo: objeto e posição do toque (px).
    var menu by remember { mutableStateOf<Pair<Int, Offset>?>(null) }
    // GLSurfaceView precisa acompanhar onPause/onResume da tela; o viewport também fornece as
    // miniaturas do projeto e do G-code enquanto existe.
    DisposableEffect(lifecycle, view) {
        val target = view ?: return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> target.onResume()
                Lifecycle.Event.ON_PAUSE -> target.onPause()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        val thumbnails: suspend (Int, Int) -> Thumbnail? = target::thumbnail
        viewModel.thumbnails = thumbnails
        onDispose {
            lifecycle.removeObserver(observer)
            if (viewModel.thumbnails === thumbnails) viewModel.thumbnails = null
        }
    }
    Box(modifier) {
        AndroidView(
            factory = { context ->
                ViewportView(context).also { created ->
                    created.listener = object : ViewportListener {
                        override fun onSelect(objectIndex: Int?) = viewModel.select(objectIndex)
                        override fun onTransform(objectIndex: Int, transform: FloatArray) { viewModel.setTransform(objectIndex, transform) }
                        override fun onLayOnFace(objectIndex: Int, triangle: Int) { viewModel.layOnFace(objectIndex, triangle) }
                        override fun onLongPress(objectIndex: Int, x: Float, y: Float) {
                            viewModel.select(objectIndex)
                            menu = objectIndex to Offset(x, y)
                        }
                    }
                    view = created
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = {
                it.setScene(state.scene)
                it.tool = state.tool
                it.setPreview(state.preview, state.previewVisible, previewSettings)
            },
        )
        val preview = state.preview
        val done = state.slice as? SliceState.Done
        if (state.previewVisible && preview != null && done != null) {
            PreviewOverlay(preview, done.stats, state.roleLabels, previewSettings) { previewSettings = it }
            return@Box
        }
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.scene?.selected != null) {
                for ((tool, label) in TOOLS) {
                    FilterChip(
                        selected = state.tool == tool,
                        onClick = { viewModel.selectTool(tool) },
                        label = { Text(stringResource(label)) },
                        colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface),
                    )
                }
            }
        }
        menu?.let { (objectIndex, position) ->
            Box(Modifier.offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }) {
                DropdownMenu(expanded = true, onDismissRequest = { menu = null }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.duplicate)) }, onClick = {
                        menu = null
                        viewModel.duplicate(objectIndex)
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = {
                        menu = null
                        viewModel.remove(objectIndex)
                    })
                }
            }
        }
    }
}

private val TOOLS = listOf(
    Tool.MOVE to R.string.tool_move,
    Tool.ROTATE to R.string.tool_rotate,
    Tool.SCALE to R.string.tool_scale,
    Tool.LAY_ON_FACE to R.string.tool_lay_on_face,
)

/** Campos numéricos do objeto selecionado: posição, rotação e escala. */
@Composable
private fun PlacementFields(objectIndex: Int, placement: Placement, viewModel: SlicerViewModel) {
    var uniform by rememberSaveable { mutableStateOf(true) }
    fun commit(offset: FloatArray = placement.offset, rotation: FloatArray = placement.rotation, scale: FloatArray = placement.scale) =
        viewModel.setPlacement(objectIndex, offset, rotation, scale)

    Text(stringResource(R.string.object_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.object_hint), style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { viewModel.duplicate(objectIndex) }) { Text(stringResource(R.string.duplicate)) }
        OutlinedButton(onClick = { viewModel.remove(objectIndex) }) { Text(stringResource(R.string.delete)) }
    }
    AxisRow(R.string.position_mm, placement.offset, axes = 2) { axis, value ->
        commit(offset = placement.offset.copyOf().also { it[axis] = value })
    }
    AxisRow(R.string.rotation_deg, placement.rotation) { axis, value ->
        commit(rotation = placement.rotation.copyOf().also { it[axis] = value })
    }
    AxisRow(R.string.scale_percent, FloatArray(3) { placement.scale[it] * 100f }, min = 0.01f) { axis, value ->
        val factor = value / 100f
        val scale = if (uniform) {
            // Mantém a proporção entre os eixos.
            val ratio = factor / placement.scale[axis]
            FloatArray(3) { placement.scale[it] * ratio }
        } else {
            placement.scale.copyOf().also { it[axis] = factor }
        }
        commit(scale = scale)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = uniform, onCheckedChange = { uniform = it })
        Text(stringResource(R.string.uniform_scale))
    }
}

@Composable
private fun AxisRow(label: Int, values: FloatArray, axes: Int = 3, min: Float? = null, onCommit: (axis: Int, value: Float) -> Unit) {
    Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (axis in 0 until axes) {
            NumberField("XYZ"[axis].toString(), values[axis], min, Modifier.weight(1f)) { onCommit(axis, it) }
        }
        // Mantém a largura dos campos igual entre linhas de 2 e 3 eixos.
        for (unused in axes until 3) Spacer(Modifier.weight(1f))
    }
}

/** Campo decimal que aplica o valor ao confirmar no teclado ou ao perder o foco. */
@Composable
private fun NumberField(label: String, value: Float, min: Float?, modifier: Modifier, onCommit: (Float) -> Unit) {
    val formatted = String.format(Locale.ROOT, "%.2f", value)
    var text by remember(formatted) { mutableStateOf(formatted) }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    fun commit() {
        val parsed = text.replace(',', '.').toFloatOrNull()?.takeIf { min == null || it >= min }
        if (parsed == null) text = formatted
        else if (String.format(Locale.ROOT, "%.2f", parsed) != formatted) onCommit(parsed)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = modifier.onFocusChanged {
            if (focused && !it.isFocused) commit()
            focused = it.isFocused
        },
    )
}

@Composable
private fun ControlPanel(
    state: SlicerUiState,
    viewModel: SlicerViewModel,
    modifier: Modifier,
    saveGcode: (String) -> Unit,
) {
    val slicing = state.slice is SliceState.Running
    val enabled = state.busy == null && !slicing
    var filter by rememberSaveable(stateSaver = ParameterFilterSaver) { mutableStateOf(ParameterFilter()) }
    // Lista preguiçosa: no modo avançado são centenas de parâmetros.
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "slice") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    if (state.objectCount == 0) stringResource(R.string.no_model)
                    else pluralStringResource(R.plurals.object_count, state.objectCount, state.objectCount),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (state.anyOutside) {
                    Text(stringResource(R.string.outside_warning), color = MaterialTheme.colorScheme.error)
                }
                state.busy?.let {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(it))
                }
                SliceControls(state, viewModel, enabled, saveGcode)
                HorizontalDivider()
            }
        }

        val selected = state.scene?.selected
        val placement = state.selectedPlacement
        if (selected != null && placement != null) {
            item(key = "object") {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PlacementFields(selected, placement, viewModel)
                    HorizontalDivider()
                }
            }
        }

        item(key = "presets") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Dropdown(R.string.vendor, state.vendors, enabled, viewModel::selectVendor)
                Dropdown(R.string.printer, state.presets[PresetKind.PRINTER], enabled) { viewModel.selectPreset(PresetKind.PRINTER, it) }
                Dropdown(R.string.process, state.presets[PresetKind.PROCESS], enabled) { viewModel.selectPreset(PresetKind.PROCESS, it) }
                Dropdown(R.string.filament, state.presets[PresetKind.FILAMENT], enabled) { viewModel.selectPreset(PresetKind.FILAMENT, it) }
                HorizontalDivider()
            }
        }

        parameterItems(state, viewModel, filter, { filter = it }, enabled)
    }
}

@Composable
private fun SliceControls(state: SlicerUiState, viewModel: SlicerViewModel, enabled: Boolean, saveGcode: (String) -> Unit) {
    when (val slice = state.slice) {
        SliceState.Idle -> Button(onClick = viewModel::slice, enabled = state.canSlice) {
            Text(stringResource(R.string.slice))
        }
        is SliceState.Running -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LinearProgressIndicator(progress = { slice.percent / 100f }, modifier = Modifier.fillMaxWidth())
            Text("${slice.percent}% ${slice.text}")
            OutlinedButton(onClick = viewModel::cancelSlice) { Text(stringResource(R.string.cancel)) }
        }
        is SliceState.Done -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stats(slice.stats), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = { saveGcode(viewModel.gcodeName()) }, enabled = enabled) {
                    Text(stringResource(R.string.save_gcode))
                }
                OutlinedButton(onClick = viewModel::slice, enabled = state.canSlice) {
                    Text(stringResource(R.string.slice_again))
                }
            }
        }
    }
}

private val PANEL_WIDTH = 360.dp
private const val COMPACT_BAR_WIDTH_DP = 600
private val SHEET_PEEK_HEIGHT = 160.dp

private val ParameterFilterSaver = listSaver<ParameterFilter, Any>(
    save = { listOf(it.kind.ordinal, it.advanced, it.query) },
    restore = { ParameterFilter(PresetKind.entries[it[0] as Int], it[1] as Boolean, it[2] as String) },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dropdown(label: Int, choice: PresetChoice?, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = choice?.options.orEmpty()
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it && enabled && options.isNotEmpty() }) {
        OutlinedTextField(
            // Nomes longos são cortados no fim; o "*" de alterado fica visível no começo.
            value = (if (choice?.dirty == true) "* " else "") + choice?.selected.orEmpty(),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(stringResource(label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        if (option != choice?.selected) onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun stats(stats: SliceStats): String {
    val minutes = (stats.printTimeSeconds / 60).toLong()
    val time = String.format(Locale.ROOT, "%dh%02dmin", minutes / 60, minutes % 60)
    val volume = String.format(Locale.getDefault(), "%.2f", stats.filamentMm3 / 1000.0)
    return stringResource(R.string.slice_stats, time, volume)
}
