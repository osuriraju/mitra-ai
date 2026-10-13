package tech.beyondscale.mitra.feature.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import tech.beyondscale.mitra.designsystem.components.EmptyState
import tech.beyondscale.mitra.designsystem.components.Eyebrow
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.StatCard
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens

@Composable
fun TodayRoute(vm: TodayViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    TodayScreen(state)
}

@Composable
fun TodayScreen(state: TodayUiState) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(MitraDimens.gap),
    ) {
        Column(Modifier.padding(horizontal = MitraDimens.pad), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Eyebrow(state.dateLine)
            Text(state.greeting, style = Mitra.type.h1, color = Mitra.colors.text)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = MitraDimens.pad), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.stats, key = { it.label }) { StatCard(it.label, it.value, it.sub) }
        }
        MitraCard(Modifier.padding(horizontal = MitraDimens.pad)) {
            EmptyState(
                icon = MitraIcons.Sun,
                title = "Your day starts here",
                body = "Priorities, habits, money and your check-in will appear here as each module arrives in the app.",
            )
        }
    }
}
