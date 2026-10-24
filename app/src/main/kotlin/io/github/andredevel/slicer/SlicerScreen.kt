package io.github.andredevel.slicer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.andredevel.slicer.core.PresetKind
import io.github.andredevel.slicer.core.SliceStats
import io.github.andredevel.slicer.render.Scene
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

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // Paisagem: viewport à esquerda e painel à direita; retrato: painel embaixo.
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            if (maxWidth > maxHeight) {
                Row(Modifier.fillMaxSize()) {
                    Viewport(state.scene, Modifier.weight(1f).fillMaxHeight())
                    ControlPanel(state, viewModel, Modifier.width(PANEL_WIDTH).fillMaxHeight(), openModel::launch, saveGcode::launch)
                }
            } else {
                val panelMaxHeight = maxHeight / 2
                Column(Modifier.fillMaxSize()) {
                    Viewport(state.scene, Modifier.weight(1f).fillMaxWidth())
                    ControlPanel(state, viewModel, Modifier.fillMaxWidth().heightIn(max = panelMaxHeight), openModel::launch, saveGcode::launch)
                }
            }
        }
    }
}

@Composable
private fun Viewport(scene: Scene?, modifier: Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var view by remember { mutableStateOf<ViewportView?>(null) }
    // GLSurfaceView precisa acompanhar onPause/onResume da tela.
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
        onDispose { lifecycle.removeObserver(observer) }
    }
    AndroidView(factory = { ViewportView(it).also { created -> view = created } }, modifier = modifier, update = { it.setScene(scene) })
}

@Composable
private fun ControlPanel(
    state: SlicerUiState,
    viewModel: SlicerViewModel,
    modifier: Modifier,
    openModel: (Array<String>) -> Unit,
    saveGcode: (String) -> Unit,
) {
    val slicing = state.slice is SliceState.Running
    val enabled = state.busy == null && !slicing
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // STL/3MF chegam com MIME variado (model/stl, application/octet-stream...).
            Button(onClick = { openModel(arrayOf("*/*")) }, enabled = enabled) {
                Text(stringResource(R.string.open_model))
            }
            Text(state.modelName ?: stringResource(R.string.no_model))
        }

        Dropdown(R.string.vendor, state.vendors, enabled, viewModel::selectVendor)
        Dropdown(R.string.printer, state.presets[PresetKind.PRINTER], enabled) { viewModel.selectPreset(PresetKind.PRINTER, it) }
        Dropdown(R.string.process, state.presets[PresetKind.PROCESS], enabled) { viewModel.selectPreset(PresetKind.PROCESS, it) }
        Dropdown(R.string.filament, state.presets[PresetKind.FILAMENT], enabled) { viewModel.selectPreset(PresetKind.FILAMENT, it) }

        state.busy?.let {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(it))
        }

        when (val slice = state.slice) {
            SliceState.Idle -> Button(onClick = viewModel::slice, enabled = state.canSlice) {
                Text(stringResource(R.string.slice))
            }
            is SliceState.Running -> {
                LinearProgressIndicator(progress = { slice.percent / 100f }, modifier = Modifier.fillMaxWidth())
                Text("${slice.percent}% ${slice.text}")
                OutlinedButton(onClick = viewModel::cancelSlice) { Text(stringResource(R.string.cancel)) }
            }
            is SliceState.Done -> {
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
}

private val PANEL_WIDTH = 360.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dropdown(label: Int, choice: PresetChoice?, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = choice?.options.orEmpty()
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it && enabled && options.isNotEmpty() }) {
        OutlinedTextField(
            value = choice?.selected.orEmpty(),
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
