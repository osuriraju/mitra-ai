package tech.beyondscale.mitra.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** Noir tokens from mitra-frontend/src/app/globals.css (`.frame` and `.frame[data-mode=light]`). */
@Immutable
data class MitraColors(
    val bg: Color, val surface: Color, val surface2: Color,
    val text: Color, val muted: Color, val faint: Color, val border: Color,
    val accent: Color, val onAccent: Color,
    val success: Color, val warning: Color, val danger: Color, val info: Color,
    val navBg: Color, val isDark: Boolean,
) {
    // color-mix(in srgb, X n%, surface) equivalents
    val accentSoft: Color get() = lerp(surface, accent, 0.12f)
    val successSoft: Color get() = lerp(surface, success, 0.14f)
    val warningSoft: Color get() = lerp(surface, warning, 0.16f)
    val dangerSoft: Color get() = lerp(surface, danger, 0.12f)
    val track: Color get() = lerp(surface, text, 0.08f)
}

val NoirDark = MitraColors(
    bg = Color(0xFF0A0B0F), surface = Color(0xFF13151C), surface2 = Color(0xFF1B1E27),
    text = Color(0xFFECEEF3), muted = Color(0xFF8C92A3), faint = Color(0xFF565C6C), border = Color.White.copy(alpha = 0.09f),
    accent = Color(0xFF2DD4BF), onAccent = Color(0xFF04110F),
    success = Color(0xFF4ADE80), warning = Color(0xFFFBBF24), danger = Color(0xFFFB7185), info = Color(0xFF60A5FA),
    navBg = Color(0xFF0E1015), isDark = true,
)

val NoirLight = MitraColors(
    bg = Color(0xFFEDEFF3), surface = Color(0xFFFBFBFD), surface2 = Color(0xFFE4E7EE),
    text = Color(0xFF0E1016), muted = Color(0xFF5C6273), faint = Color(0xFF9AA0AF), border = Color(0xFFD6D9E2),
    accent = Color(0xFF0D9488), onAccent = Color.White,
    success = Color(0xFF15803D), warning = Color(0xFFB45309), danger = Color(0xFFE11D48), info = Color(0xFF2563EB),
    navBg = Color(0xFFF5F6F9), isDark = false,
)
