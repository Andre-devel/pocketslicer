package io.github.andredevel.slicer

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.andredevel.slicer.ui.components.InfoCard
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

    private companion object {
        const val KEY_CURRENCY = "currency"
    }
}

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

/** Configurações do app: por enquanto, a moeda do custo do filamento. */
@Composable
fun SettingsScreen(state: SlicerUiState, viewModel: SlicerViewModel, modifier: Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    Column(modifier) {
        ScreenHeader(stringResource(R.string.settings))
        PanelColumn(Modifier.verticalScroll(rememberScrollState()).widthIn(max = 560.dp)) {
            SectionLabel(stringResource(R.string.cost))
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
        }
    }
}

/** "BRL · Real brasileiro". */
private fun currencyLabel(code: String, locale: Locale) = "$code · ${Currency.getInstance(code).getDisplayName(locale)}"
