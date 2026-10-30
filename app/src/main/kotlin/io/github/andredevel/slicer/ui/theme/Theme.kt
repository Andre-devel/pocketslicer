package io.github.andredevel.slicer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Cores que o Material 3 não tem: estados de sucesso/aviso/informação, os eixos X/Y/Z e o
 * fundo do viewport. Mesmo papel de "tom/contêiner/texto sobre o contêiner" do esquema M3.
 */
@Immutable
data class ExtendedColors(
    val success: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val info: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
    /** Rótulos dos eixos X, Y e Z, nessa ordem. */
    val axes: List<Color>,
    /** Fundo da navegação lateral e da barra inferior, um tom abaixo do conteúdo. */
    val navigation: Color,
    /** Tema escuro: o viewport 3D (OpenGL) escolhe a paleta por isto. */
    val dark: Boolean,
)

// Grafite quente com um único laranja de sinal; vermelho só para erro.
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF8A3D),
    onPrimary = Color(0xFF24120A),
    primaryContainer = Color(0xFF3A2416),
    onPrimaryContainer = Color(0xFFFFB27F),
    secondary = Color(0xFFC9CDD2),
    onSecondary = Color(0xFF1B1C1E),
    secondaryContainer = Color(0xFF2E3237),
    onSecondaryContainer = Color(0xFFECEDEF),
    tertiary = Color(0xFF6EA8FF),
    onTertiary = Color(0xFF111214),
    background = Color(0xFF17181B),
    onBackground = Color(0xFFECEDEF),
    surface = Color(0xFF1C1E21),
    onSurface = Color(0xFFECEDEF),
    surfaceVariant = Color(0xFF24272B),
    onSurfaceVariant = Color(0xFFA7ADB5),
    surfaceDim = Color(0xFF131518),
    surfaceBright = Color(0xFF2E3237),
    surfaceContainerLowest = Color(0xFF111214),
    surfaceContainerLow = Color(0xFF17181B),
    surfaceContainer = Color(0xFF1F2226),
    surfaceContainerHigh = Color(0xFF24272B),
    surfaceContainerHighest = Color(0xFF2E3237),
    inverseSurface = Color(0xFFECEDEF),
    inverseOnSurface = Color(0xFF1B1C1E),
    inversePrimary = Color(0xFFC2540F),
    outline = Color(0xFF7D838B),
    outlineVariant = Color(0xFF2E3237),
    error = Color(0xFFFF6B5E),
    onError = Color(0xFF24120A),
    errorContainer = Color(0xFF3B1A17),
    onErrorContainer = Color(0xFFFFD9D4),
    scrim = Color(0xFF000000),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFC2540F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFBE3D2),
    onPrimaryContainer = Color(0xFF9A3F06),
    secondary = Color(0xFF3D4046),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE6E3DE),
    onSecondaryContainer = Color(0xFF1B1C1E),
    tertiary = Color(0xFF2F6FD0),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFAF9F7),
    onBackground = Color(0xFF1B1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1C1E),
    surfaceVariant = Color(0xFFECEAE6),
    onSurfaceVariant = Color(0xFF5D6168),
    surfaceDim = Color(0xFFF3F2EF),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAF9F7),
    surfaceContainer = Color(0xFFF3F2EF),
    surfaceContainerHigh = Color(0xFFECEAE6),
    surfaceContainerHighest = Color(0xFFE6E3DE),
    inverseSurface = Color(0xFF1C1E21),
    inverseOnSurface = Color(0xFFECEDEF),
    inversePrimary = Color(0xFFFF8A3D),
    outline = Color(0xFF868A90),
    outlineVariant = Color(0xFFD9D6D0),
    error = Color(0xFFC2453C),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFE4E0),
    onErrorContainer = Color(0xFF8C1D16),
    scrim = Color(0xFF000000),
)

private val DarkExtended = ExtendedColors(
    success = Color(0xFF5FD38D),
    warning = Color(0xFFF5C451),
    warningContainer = Color(0xFF3A3014),
    onWarningContainer = Color(0xFFF6E2AE),
    info = Color(0xFF6EA8FF),
    infoContainer = Color(0xFF1F2226),
    onInfoContainer = Color(0xFFC9CDD2),
    axes = listOf(Color(0xFFFF5C5C), Color(0xFF5FD38D), Color(0xFF6EA8FF)),
    navigation = Color(0xFF131518),
    dark = true,
)

private val LightExtended = ExtendedColors(
    success = Color(0xFF1F8A4C),
    warning = Color(0xFF8A6200),
    warningContainer = Color(0xFFFFF1CC),
    onWarningContainer = Color(0xFF5C4300),
    info = Color(0xFF2F6FD0),
    infoContainer = Color(0xFFECEAE6),
    onInfoContainer = Color(0xFF3D4046),
    axes = listOf(Color(0xFFC9302A), Color(0xFF1F8A4C), Color(0xFF2F6FD0)),
    navigation = Color(0xFFF3F2EF),
    dark = false,
)

private val LocalExtendedColors = staticCompositionLocalOf { DarkExtended }

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Tema do app: paleta fixa do redesenho (não usa as cores do papel de parede). */
@Composable
fun PocketSlicerTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalExtendedColors provides if (dark) DarkExtended else LightExtended) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

/** Acesso às partes do tema fora do [MaterialTheme]. */
object PocketTheme {
    val colors: ExtendedColors
        @Composable @ReadOnlyComposable get() = LocalExtendedColors.current
}
