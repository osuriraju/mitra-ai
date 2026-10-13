package tech.beyondscale.mitra.feature.common

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import tech.beyondscale.mitra.designsystem.components.EmptyState
import tech.beyondscale.mitra.designsystem.theme.MitraDimens

/** Placeholder for modules that arrive in later phases. */
@Composable
fun ComingSoonScreen(title: String, icon: ImageVector) {
    EmptyState(
        icon = icon,
        title = "$title is on its way",
        body = "This module is being rebuilt for Android. Until then, use it on the web — everything stays in sync.",
        modifier = Modifier.fillMaxSize().padding(MitraDimens.pad),
    )
}
