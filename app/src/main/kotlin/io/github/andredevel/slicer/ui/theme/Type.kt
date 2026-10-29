package io.github.andredevel.slicer.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.andredevel.slicer.R

// Fontes variáveis (minSdk 29): um arquivo por família, um peso por instância.
@OptIn(ExperimentalTextApi::class)
private fun variable(resource: Int, vararg weights: FontWeight) = FontFamily(
    weights.map { Font(resource, it, variationSettings = FontVariation.Settings(FontVariation.weight(it.weight))) },
)

val PlexSans = variable(R.font.ibm_plex_sans, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)

/** Números, chaves de opção e endereços: medidas alinhadas em coluna. */
val JetBrainsMono = variable(R.font.jetbrains_mono, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)

private val base = Typography()

private fun TextStyle.plex(weight: FontWeight) = copy(fontFamily = PlexSans, fontWeight = weight)

val AppTypography = Typography(
    displayLarge = base.displayLarge.plex(FontWeight.SemiBold),
    displayMedium = base.displayMedium.plex(FontWeight.SemiBold),
    displaySmall = base.displaySmall.plex(FontWeight.SemiBold),
    headlineLarge = base.headlineLarge.plex(FontWeight.SemiBold),
    headlineMedium = base.headlineMedium.plex(FontWeight.SemiBold),
    headlineSmall = base.headlineSmall.plex(FontWeight.SemiBold),
    titleLarge = base.titleLarge.plex(FontWeight.SemiBold).copy(fontSize = 20.sp),
    titleMedium = base.titleMedium.plex(FontWeight.SemiBold),
    titleSmall = base.titleSmall.plex(FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.plex(FontWeight.Normal),
    bodyMedium = base.bodyMedium.plex(FontWeight.Normal),
    bodySmall = base.bodySmall.plex(FontWeight.Normal),
    labelLarge = base.labelLarge.plex(FontWeight.SemiBold),
    labelMedium = base.labelMedium.plex(FontWeight.Medium),
    labelSmall = base.labelSmall.plex(FontWeight.Medium),
)

/** Estilos monoespaçados; tamanhos alinhados aos do [AppTypography]. */
object MonoStyles {
    /** Valor de campo numérico e medidas em listas. */
    val value = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp)

    /** Chaves de opção do Orca, endereços IP, contadores. */
    val small = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp)

    /** Rótulo de seção ("PERFIL"): caixa alta espaçada. */
    val section = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.2.sp)

    /** Números de destaque (tempo estimado). */
    val display = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 38.sp)

    /** Números médios (cartões de resumo). */
    val title = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp)
}
