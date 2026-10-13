package tech.beyondscale.mitra.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import tech.beyondscale.mitra.designsystem.resources.Res
import tech.beyondscale.mitra.designsystem.resources.jetbrains_mono
import tech.beyondscale.mitra.designsystem.resources.manrope

@Immutable
data class MitraType(
    val h1: TextStyle, val h2: TextStyle, val h3: TextStyle,
    val body: TextStyle, val bodyBold: TextStyle, val sm: TextStyle, val xs: TextStyle,
    val eyebrow: TextStyle, val num: TextStyle, val numLg: TextStyle,
)

@Composable
private fun manrope() = FontFamily(listOf(400, 500, 600, 700, 800).map { w ->
    Font(Res.font.manrope, weight = FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
})

@Composable
private fun mono() = FontFamily(listOf(400, 500, 600).map { w ->
    Font(Res.font.jetbrains_mono, weight = FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
})

/** Sizes follow globals.css: body 14px/1.45, .sm 12.5, .xs 11.5, .eyebrow 11 bold +.09em, headings -0.03em. */
@Composable
internal fun mitraType(): MitraType {
    val m = manrope(); val n = mono()
    val body = TextStyle(fontFamily = m, fontSize = 14.sp, lineHeight = 20.sp)
    return MitraType(
        h1 = TextStyle(fontFamily = m, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, lineHeight = 27.sp, letterSpacing = (-0.03).em),
        h2 = TextStyle(fontFamily = m, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, lineHeight = 22.sp, letterSpacing = (-0.03).em),
        h3 = TextStyle(fontFamily = m, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 19.sp, letterSpacing = (-0.015).em),
        body = body,
        bodyBold = body.copy(fontWeight = FontWeight.SemiBold),
        sm = TextStyle(fontFamily = m, fontSize = 12.5.sp, lineHeight = 18.sp),
        xs = TextStyle(fontFamily = m, fontSize = 11.5.sp, lineHeight = 16.sp),
        eyebrow = TextStyle(fontFamily = m, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.09.em),
        num = TextStyle(fontFamily = n, fontWeight = FontWeight.Medium, fontSize = 14.sp, fontFeatureSettings = "tnum"),
        numLg = TextStyle(fontFamily = n, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, fontFeatureSettings = "tnum"),
    )
}
