package io.github.andredevel.slicer

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.andredevel.slicer.core.Calibration
import io.github.andredevel.slicer.core.Calibration.Test
import io.github.andredevel.slicer.core.PresetKind
import io.github.andredevel.slicer.ui.components.Banner
import io.github.andredevel.slicer.ui.components.FormField
import io.github.andredevel.slicer.ui.components.LabeledRow
import io.github.andredevel.slicer.ui.components.SelectField
import io.github.andredevel.slicer.ui.components.Tone
import java.util.Locale

/** Testes do menu Calibração do Orca, na ordem dele. */
private val TESTS = listOf(
    Test.TEMPERATURE to R.string.calib_temperature,
    Test.MAX_VOLUMETRIC_SPEED to R.string.calib_max_flowrate,
    Test.PRESSURE_ADVANCE_TOWER to R.string.calib_pressure_advance,
    Test.FLOW_RATE to R.string.calib_flow_rate,
    Test.RETRACTION to R.string.calib_retraction,
    Test.CORNERING to R.string.calib_cornering,
    Test.INPUT_SHAPING_FREQUENCY to R.string.calib_input_shaping_frequency,
    Test.INPUT_SHAPING_DAMPING to R.string.calib_input_shaping_damping,
    Test.VFA to R.string.calib_vfa,
)

/**
 * Temperatura inicial e final por tipo de filamento (Temp_Calibration_Dlg; PA-CF e Personalizado
 * como o Orca pretendia, sem o deslocamento de índice que ele tem).
 */
private val TEMPERATURES = listOf(
    "PLA" to (230 to 190), "ABS/ASA" to (270 to 230), "PETG" to (250 to 230), "PCTG" to (280 to 240),
    "TPU" to (240 to 210), "PA-CF" to (320 to 280), "PET-CF" to (320 to 280),
)

/** Tipos de input shaper que o firmware aceita (input_shaper_types_for_flavor() do Orca, sem "Disable"). */
private fun shaperTypes(flavor: String?) = when (flavor) {
    "klipper" -> listOf("Default", "ZV", "MZV", "ZVD", "EI", "2HUMP_EI", "3HUMP_EI")
    "reprapfirmware" -> listOf("Default", "MZV", "ZVD", "ZVDD", "ZVDDD", "EI2", "EI3", "DAA")
    "marlin2" -> listOf("ZV")
    else -> listOf("Default")
}

/**
 * Escolha de um teste de calibração e dos valores dele, como os diálogos de calib_dlg.cpp do Orca,
 * com os mesmos padrões. [onStart] recebe o teste e o nome da mesa; o motor valida os valores.
 */
@Composable
fun CalibrationDialog(state: SlicerUiState, onDismiss: () -> Unit, onStart: (Calibration, String) -> Unit) {
    val printer = state.presets[PresetKind.PRINTER]?.values?.values.orEmpty()
    val flavor = printer["gcode_flavor"]
    val rrf = flavor == "reprapfirmware"
    // Cornering com junction deviation: Marlin 2 com machine_max_junction_deviation > 0.
    val junctionDeviation = flavor == "marlin2" &&
        (printer["machine_max_junction_deviation"]?.substringBefore(',')?.toDoubleOrNull() ?: 0.0) > 0.0
    val shapers = shaperTypes(flavor)

    var test by rememberSaveable { mutableStateOf(Test.TEMPERATURE) }
    var paMethod by rememberSaveable { mutableStateOf(Test.PRESSURE_ADVANCE_TOWER) }
    var bowden by rememberSaveable { mutableStateOf(false) }
    var printNumbers by rememberSaveable { mutableStateOf(true) }
    // PA padrão: listas de números inteiros separados por vírgula, como no Orca.
    var accelerations by rememberSaveable { mutableStateOf("") }
    var speeds by rememberSaveable { mutableStateOf("") }
    var flowType by rememberSaveable { mutableStateOf(2) }
    var flowMonotonic by rememberSaveable { mutableStateOf(false) }
    var model by rememberSaveable { mutableStateOf(0) }
    var shaper by rememberSaveable { mutableStateOf(shapers.first()) }
    // Tipo de filamento da torre de temperatura: o do primeiro filamento, se for um da lista.
    var filamentType by rememberSaveable {
        val type = state.filamentTypes.firstOrNull()?.uppercase(Locale.ROOT)
        mutableStateOf(TEMPERATURES.firstOrNull { (name, _) -> type != null && type in name.split('/') }?.first)
    }
    val fields = remember { mutableStateMapOf<String, String>() }
    var invalid by remember { mutableStateOf(false) }

    // Padrões de cada diálogo do Orca; voltam a eles ao trocar de teste ou de método.
    fun reset() {
        fields.clear()
        invalid = false
        val defaults: Map<String, Double> = when (test) {
            Test.TEMPERATURE -> {
                val (start, end) = TEMPERATURES.firstOrNull { it.first == filamentType }?.second ?: (230 to 190)
                mapOf("start" to start.toDouble(), "end" to end.toDouble())
            }
            Test.MAX_VOLUMETRIC_SPEED -> mapOf("start" to 5.0, "end" to 20.0, "step" to 0.5)
            Test.PRESSURE_ADVANCE_TOWER, Test.PRESSURE_ADVANCE_LINE, Test.PRESSURE_ADVANCE_PATTERN ->
                if (paMethod == Test.PRESSURE_ADVANCE_PATTERN) mapOf("start" to 0.0, "end" to if (bowden) 1.0 else 0.08, "step" to if (bowden) 0.05 else 0.005)
                else mapOf("start" to 0.0, "end" to if (bowden) 1.0 else 0.1, "step" to if (bowden) 0.02 else 0.002)
            Test.FLOW_RATE -> emptyMap()
            Test.RETRACTION -> mapOf("start" to 0.0, "end" to 2.0, "step" to 0.1)
            Test.CORNERING -> if (junctionDeviation) mapOf("start" to 0.0, "end" to 0.25) else mapOf("start" to 1.0, "end" to 15.0)
            Test.INPUT_SHAPING_FREQUENCY -> mapOf("freqStartX" to 15.0, "freqEndX" to 110.0, "freqStartY" to 15.0, "freqEndY" to 110.0, "start" to 0.15)
            Test.INPUT_SHAPING_DAMPING -> mapOf("freqStartX" to 30.0, "freqStartY" to 30.0, "start" to 0.0, "end" to 0.4)
            Test.VFA -> mapOf("start" to 40.0, "end" to 200.0, "step" to 10.0)
        }
        defaults.forEach { (key, value) -> fields[key] = format(value) }
    }
    if (fields.isEmpty() && test != Test.FLOW_RATE) reset()

    val name = stringResource(TESTS.first { it.first == test }.second)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.calibration)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectField(
                    test.name, TESTS.map { it.first.name }, { test = Test.valueOf(it); reset() },
                    Modifier.fillMaxWidth(), label = stringResource(R.string.calibration_test), labels = TESTS.map { stringResource(it.second) },
                )
                @Composable
                fun number(key: String, @StringRes text: Int, suffix: String? = null) = FormField(
                    fields[key].orEmpty(), { fields[key] = it; invalid = false }, stringResource(text) + (suffix?.let { " ($it)" } ?: ""),
                    isError = invalid && parse(fields[key]) == null, monospace = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                @Composable
                fun choice(@StringRes text: Int, options: List<Int>, selected: Int, onSelect: (Int) -> Unit) = SelectField(
                    selected.toString(), options.indices.map { it.toString() }, { onSelect(it.toInt()) },
                    Modifier.fillMaxWidth(), label = stringResource(text), labels = options.map { stringResource(it) },
                )
                when (test) {
                    Test.TEMPERATURE -> {
                        SelectField(
                            filamentType, TEMPERATURES.map { it.first }, { filamentType = it; reset() },
                            Modifier.fillMaxWidth(), label = stringResource(R.string.calib_filament_type),
                        )
                        number("start", R.string.calib_start_temp, "°C")
                        number("end", R.string.calib_end_temp, "°C")
                        Text(stringResource(R.string.calib_temperature_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    Test.PRESSURE_ADVANCE_TOWER -> {
                        val methods = listOf(Test.PRESSURE_ADVANCE_TOWER, Test.PRESSURE_ADVANCE_LINE, Test.PRESSURE_ADVANCE_PATTERN)
                        choice(R.string.calib_pa_method, listOf(R.string.calib_pa_tower, R.string.calib_pa_line, R.string.calib_pa_pattern), methods.indexOf(paMethod)) {
                            paMethod = methods[it]; reset()
                        }
                        choice(R.string.calib_extruder_type, listOf(R.string.calib_extruder_direct, R.string.calib_extruder_bowden), if (bowden) 1 else 0) {
                            bowden = it == 1; reset()
                        }
                        number("start", R.string.calib_start_pa)
                        number("end", R.string.calib_end_pa)
                        number("step", R.string.calib_pa_step)
                        if (paMethod == Test.PRESSURE_ADVANCE_LINE) {
                            LabeledRow(stringResource(R.string.calib_print_numbers)) { Switch(printNumbers, { printNumbers = it }) }
                        }
                        if (paMethod == Test.PRESSURE_ADVANCE_PATTERN) {
                            @Composable
                            fun list(value: String, onChange: (String) -> Unit, @StringRes text: Int, unit: String) = FormField(
                                value, { onChange(it.filter { c -> c.isDigit() || c == ',' }); invalid = false }, stringResource(text) + " ($unit)",
                                monospace = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                            list(accelerations, { accelerations = it }, R.string.calib_accelerations, "mm/s²")
                            list(speeds, { speeds = it }, R.string.calib_speeds, "mm/s")
                            Text(stringResource(R.string.calib_pa_pattern_hint), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Test.FLOW_RATE -> {
                        choice(
                            R.string.calib_flow_type,
                            listOf(R.string.calib_flow_pass1, R.string.calib_flow_pass2, R.string.calib_flow_yolo, R.string.calib_flow_yolo_perfectionist),
                            flowType,
                        ) { flowType = it }
                        choice(R.string.calib_top_pattern, listOf(R.string.calib_pattern_archimedean, R.string.calib_pattern_monotonic), if (flowMonotonic) 1 else 0) {
                            flowMonotonic = it == 1
                        }
                    }
                    Test.MAX_VOLUMETRIC_SPEED -> {
                        number("start", R.string.calib_start_volumetric, "mm³/s")
                        number("end", R.string.calib_end_volumetric, "mm³/s")
                        number("step", R.string.calib_step, "mm³/s")
                    }
                    Test.RETRACTION -> {
                        number("start", R.string.calib_start_retraction, "mm")
                        number("end", R.string.calib_end_retraction, "mm")
                        number("step", R.string.calib_step, "mm")
                    }
                    Test.VFA -> {
                        number("start", R.string.calib_start_speed, "mm/s")
                        number("end", R.string.calib_end_speed, "mm/s")
                        number("step", R.string.calib_step, "mm/s")
                    }
                    Test.CORNERING -> {
                        choice(R.string.calib_test_model, listOf(R.string.calib_model_ringing, R.string.calib_model_fast, R.string.calib_model_scv), model) { model = it }
                        val unit = if (junctionDeviation) "mm" else "mm/s"
                        number("start", R.string.calib_start, unit)
                        number("end", R.string.calib_end, unit)
                        Text(
                            stringResource(if (junctionDeviation) R.string.calib_cornering_jd_hint else R.string.calib_cornering_jerk_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Test.INPUT_SHAPING_FREQUENCY, Test.INPUT_SHAPING_DAMPING -> {
                        choice(R.string.calib_test_model, listOf(R.string.calib_model_ringing, R.string.calib_model_fast), model.coerceAtMost(1)) { model = it }
                        SelectField(
                            shaper.takeIf { it in shapers } ?: shapers.first(), shapers, { shaper = it },
                            Modifier.fillMaxWidth(), label = stringResource(R.string.calib_shaper_type),
                        )
                        if (test == Test.INPUT_SHAPING_FREQUENCY) {
                            number("freqStartX", if (rrf) R.string.calib_freq_start else R.string.calib_freq_start_x, "Hz")
                            number("freqEndX", if (rrf) R.string.calib_freq_end else R.string.calib_freq_end_x, "Hz")
                            if (!rrf) {
                                number("freqStartY", R.string.calib_freq_start_y, "Hz")
                                number("freqEndY", R.string.calib_freq_end_y, "Hz")
                            }
                            number("start", R.string.calib_damping)
                            Text(stringResource(R.string.calib_damping_hint), style = MaterialTheme.typography.bodySmall)
                        } else {
                            number("freqStartX", if (rrf) R.string.calib_freq else R.string.calib_freq_x, "Hz")
                            if (!rrf) number("freqStartY", R.string.calib_freq_y, "Hz")
                            number("start", R.string.calib_damping_start)
                            number("end", R.string.calib_damping_end)
                            Text(stringResource(R.string.calib_frequency_hint), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Test.PRESSURE_ADVANCE_LINE, Test.PRESSURE_ADVANCE_PATTERN -> Unit
                }
                Banner(Tone.WARNING, stringResource(R.string.calibration_replaces))
                if (invalid) Banner(Tone.ERROR, stringResource(R.string.calibration_invalid_number))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                fun value(key: String) = parse(fields[key]) ?: 0.0
                val keys = fields.keys.filter { !(rrf && it.endsWith("Y")) }
                if (keys.any { parse(fields[it]) == null }) {
                    invalid = true
                    return@TextButton
                }
                val y = { key: String -> if (rrf) value(key.dropLast(1) + "X") else value(key) }
                val calibration = Calibration(
                    test = if (test == Test.PRESSURE_ADVANCE_TOWER) paMethod else test,
                    start = value("start"), end = value("end"), step = value("step"),
                    printNumbers = printNumbers,
                    flowLinear = flowType >= 2, flowPass = flowType % 2 + 1, flowMonotonic = flowMonotonic,
                    model = model, shaperType = shaper.takeIf { it in shapers } ?: shapers.first(),
                    freqStartX = value("freqStartX"), freqEndX = value("freqEndX"),
                    freqStartY = y("freqStartY"), freqEndY = y("freqEndY"),
                    speeds = numbers(speeds), accelerations = numbers(accelerations),
                )
                onDismiss()
                onStart(calibration, name)
            }) { Text(stringResource(R.string.calibration_start)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** ParseStringValues() do Orca: os inteiros positivos de uma lista separada por vírgula. */
private fun numbers(text: String): List<Double> = text.split(',').mapNotNull { it.trim().toIntOrNull()?.takeIf { n -> n > 0 }?.toDouble() }

/** Número com vírgula ou ponto. */
private fun parse(text: String?): Double? = text?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() }

/** Até três casas, sem zeros à direita, no idioma do aparelho. */
private fun format(value: Double): String =
    String.format(Locale.getDefault(), "%.3f", value).trimEnd('0').trimEnd('.', ',')
