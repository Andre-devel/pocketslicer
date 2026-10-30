package io.github.andredevel.slicer

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.andredevel.slicer.ui.components.AppButton
import io.github.andredevel.slicer.ui.components.ButtonStyle
import io.github.andredevel.slicer.ui.components.InfoCard
import io.github.andredevel.slicer.ui.components.LabeledRow
import io.github.andredevel.slicer.ui.components.PanelColumn
import io.github.andredevel.slicer.ui.components.ScreenHeader
import io.github.andredevel.slicer.ui.components.SectionLabel
import io.github.andredevel.slicer.ui.components.SelectField
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** Preferências do app em SharedPreferences. [currency]: código ISO 4217; null segue o idioma. */
class SettingsStore(private val prefs: SharedPreferences) {
    var currency: String?
        get() = prefs.getString(KEY_CURRENCY, null)
        set(value) = prefs.edit().apply { if (value == null) remove(KEY_CURRENCY) else putString(KEY_CURRENCY, value) }.apply()

    /** Procurar e baixar versões novas ao abrir o app. */
    var autoUpdate: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPDATE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_UPDATE, value).apply()

    var theme: ThemeMode
        get() = prefs.getString(KEY_THEME, null)?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: ThemeMode.SYSTEM
        set(value) = prefs.edit().putString(KEY_THEME, value.name).apply()

    /** Escala da interface em % ([UI_SCALES]); null: [defaultUiScale]. */
    var uiScale: Int?
        get() = prefs.getInt(KEY_UI_SCALE, 0).takeIf { it in UI_SCALES }
        set(value) = prefs.edit().apply { if (value == null) remove(KEY_UI_SCALE) else putInt(KEY_UI_SCALE, value) }.apply()

    /** Última procura automática por versão nova (System.currentTimeMillis). */
    var lastUpdateCheck: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0)
        set(value) = prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, value).apply()

    private companion object {
        const val KEY_CURRENCY = "currency"
        const val KEY_AUTO_UPDATE = "auto_update"
        const val KEY_THEME = "theme"
        const val KEY_UI_SCALE = "ui_scale"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check"
    }
}

/** Tema claro ou escuro, ou o do sistema. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Escalas da interface oferecidas em Ajustes, em %. */
val UI_SCALES = listOf(80, 90, 100, 115)

/**
 * Escala padrão: 90% em tablets (menor lado >= 600 dp), onde o tamanho do Material
 * deixa caber pouco na tela; 100% nos celulares.
 */
fun defaultUiScale(smallestScreenWidthDp: Int): Int = if (smallestScreenWidthDp >= 600) 90 else 100

/** Moedas oferecidas na tela de Configurações. */
val CURRENCIES = listOf("BRL", "USD", "EUR", "GBP", "CAD", "AUD", "CHF", "JPY", "CNY", "INR", "MXN", "ARS", "CLP", "COP", "PEN", "UYU", "PLN", "SEK", "NOK", "DKK", "CZK")

/**
 * Moeda do idioma do app: a do país do [locale] ou, sem país, BRL para português (o app é
 * traduzido para pt-BR) e USD nos demais.
 */
fun defaultCurrency(locale: Locale): String =
    runCatching { Currency.getInstance(locale).currencyCode }.getOrNull()
        ?: if (locale.language == "pt") "BRL" else "USD"

/** Formata valores em [currency] (null: [defaultCurrency]) no idioma atual do app. */
@Composable
fun rememberCostFormat(currency: String?): (Double) -> String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale, currency) {
        val format = NumberFormat.getCurrencyInstance(locale).apply { this.currency = Currency.getInstance(currency ?: defaultCurrency(locale)) }
        return@remember { value: Double -> format.format(value) }
    }
}

/** Configurações do app: o tema, a moeda do custo do filamento e as atualizações. */
@Composable
fun SettingsScreen(state: SlicerUiState, viewModel: SlicerViewModel, modifier: Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val theme by viewModel.theme.collectAsState()
    val uiScale by viewModel.uiScale.collectAsState()
    Column(modifier) {
        ScreenHeader(stringResource(R.string.settings))
        PanelColumn(Modifier.verticalScroll(rememberScrollState()).widthIn(max = 560.dp)) {
            SectionLabel(stringResource(R.string.appearance))
            SelectField(
                theme.name,
                ThemeMode.entries.map { it.name },
                { viewModel.setTheme(ThemeMode.valueOf(it)) },
                Modifier.fillMaxWidth(),
                label = stringResource(R.string.theme),
                labels = listOf(stringResource(R.string.theme_system), stringResource(R.string.theme_light), stringResource(R.string.theme_dark)),
            )
            // A escala padrão depende da tela real, não da já escalada pelo app.
            val defaultScale = defaultUiScale(LocalContext.current.resources.configuration.smallestScreenWidthDp)
            SelectField(
                uiScale?.toString().orEmpty(),
                listOf("") + UI_SCALES.map { it.toString() },
                { viewModel.setUiScale(it.toIntOrNull()) },
                Modifier.fillMaxWidth(),
                label = stringResource(R.string.ui_scale),
                labels = listOf(stringResource(R.string.ui_scale_auto, defaultScale)) + UI_SCALES.map { "$it%" },
            )
            SectionLabel(stringResource(R.string.cost), Modifier.padding(top = 16.dp))
            val options = listOf<String?>(null) + CURRENCIES
            val default = defaultCurrency(locale)
            val labels = options.map { code ->
                if (code == null) stringResource(R.string.currency_from_language, currencyLabel(default, locale))
                else currencyLabel(code, locale)
            }
            SelectField(
                state.currency.orEmpty(),
                options.map { it.orEmpty() },
                { viewModel.setCurrency(it.ifEmpty { null }) },
                Modifier.fillMaxWidth(),
                label = stringResource(R.string.currency),
                labels = labels,
            )
            InfoCard(stringResource(R.string.currency_hint), Modifier.padding(top = 4.dp), icon = R.drawable.ic_info)
            UpdateSection(state, viewModel)
        }
    }
}

/** Versão instalada, atualização automática e a procura/instalação de versão nova (GitHub Releases). */
@Composable
private fun UpdateSection(state: SlicerUiState, viewModel: SlicerViewModel) {
    SectionLabel(stringResource(R.string.updates), Modifier.padding(top = 16.dp))
    LabeledRow(stringResource(R.string.update_auto), detail = stringResource(R.string.update_auto_hint)) {
        Switch(checked = state.autoUpdate, onCheckedChange = viewModel::setAutoUpdate)
    }
    val update = state.update
    val status = when (update) {
        UpdateState.Idle -> null
        UpdateState.Checking -> stringResource(R.string.update_checking)
        UpdateState.UpToDate -> stringResource(R.string.update_up_to_date)
        is UpdateState.Downloading -> stringResource(R.string.update_downloading, update.release.version, (update.progress * 100).toInt())
        is UpdateState.Ready -> stringResource(R.string.update_ready, update.release.version)
        is UpdateState.Installing -> stringResource(R.string.update_installing)
        is UpdateState.Failed -> stringResource(R.string.update_failed, update.error)
    }
    LabeledRow(stringResource(R.string.update_installed_version, viewModel.installedVersion), detail = status) {
        if (update is UpdateState.Ready) {
            AppButton(stringResource(R.string.update_install), viewModel::promptUpdate, icon = R.drawable.ic_download)
        } else {
            val busy = update == UpdateState.Checking || update is UpdateState.Downloading || update is UpdateState.Installing
            AppButton(stringResource(R.string.update_check), { viewModel.checkUpdate() }, style = ButtonStyle.OUTLINED, icon = R.drawable.ic_refresh, enabled = !busy)
        }
    }
    if (update is UpdateState.Downloading) LinearProgressIndicator({ update.progress }, Modifier.fillMaxWidth())
    val notes = (update as? UpdateState.Ready)?.release?.notes?.takeIf { it.isNotBlank() }
    if (notes != null) InfoCard(notes, Modifier.padding(top = 4.dp), title = stringResource(R.string.update_notes))
}

/** "BRL · Real brasileiro". */
private fun currencyLabel(code: String, locale: Locale) = "$code · ${Currency.getInstance(code).getDisplayName(locale)}"
