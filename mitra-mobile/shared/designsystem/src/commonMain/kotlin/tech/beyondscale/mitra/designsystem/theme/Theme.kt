package tech.beyondscale.mitra.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

val LocalMitraColors = staticCompositionLocalOf { NoirDark }
val LocalMitraType = staticCompositionLocalOf<MitraType> { error("MitraTheme is missing") }

object Mitra {
    val colors: MitraColors @Composable @ReadOnlyComposable get() = LocalMitraColors.current
    val type: MitraType @Composable @ReadOnlyComposable get() = LocalMitraType.current
}

/** --gap, --pad, --r, --r-sm, --r-lg from globals.css */
object MitraDimens {
    val gap = 14.dp
    val pad = 16.dp
    val radius = 10.dp
    val radiusSm = 7.dp
    val radiusLg = 14.dp
}

@Composable
fun MitraTheme(dark: Boolean, content: @Composable () -> Unit) {
    val c = if (dark) NoirDark else NoirLight
    val type = mitraType()
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c.accent, onPrimary = c.onAccent,
        background = c.bg, onBackground = c.text,
        surface = c.surface, onSurface = c.text,
        surfaceVariant = c.surface2, onSurfaceVariant = c.muted,
        surfaceContainerLowest = c.bg, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
        surfaceContainerHigh = c.surface2, surfaceContainerHighest = c.surface2,
        outline = c.border, outlineVariant = c.border, error = c.danger,
        inverseSurface = c.text, inverseOnSurface = c.bg,
    )
    CompositionLocalProvider(LocalMitraColors provides c, LocalMitraType provides type) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography(
                headlineSmall = type.h1, titleLarge = type.h2, titleMedium = type.h3,
                bodyLarge = type.body, bodyMedium = type.body, bodySmall = type.sm, labelLarge = type.bodyBold,
            ),
            shapes = Shapes(
                small = RoundedCornerShape(MitraDimens.radiusSm),
                medium = RoundedCornerShape(MitraDimens.radius),
                large = RoundedCornerShape(MitraDimens.radiusLg),
            ),
            content = content,
        )
    }
}
