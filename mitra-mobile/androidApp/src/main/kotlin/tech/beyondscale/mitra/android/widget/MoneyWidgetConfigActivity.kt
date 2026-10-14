package tech.beyondscale.mitra.android.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import tech.beyondscale.mitra.android.R
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraSwitchRow
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens
import tech.beyondscale.mitra.designsystem.theme.MitraTheme
import tech.beyondscale.mitra.domain.model.ThemeMode
import tech.beyondscale.mitra.domain.repository.PreferencesRepository

/**
 * Per-widget options for the Money widget: just "Hide amounts". Opened by the launcher on placement (before
 * Android 12; optional after) and on reconfigure. Reads no account data — only this widget's own Glance state and
 * the device theme preference.
 */
class MoneyWidgetConfigActivity : ComponentActivity() {
    private val prefs: PreferencesRepository by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Until Done: backing out of the first placement cancels it (the launcher drops the widget).
        setResult(RESULT_CANCELED, resultIntent(appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val glanceId = runCatching { GlanceAppWidgetManager(this).getGlanceIdBy(appWidgetId) }.getOrNull()
        if (glanceId == null) {
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            val theme by prefs.theme.collectAsState(ThemeMode.Dark)
            val dark = when (theme) {
                ThemeMode.Dark -> true
                ThemeMode.Light -> false
                ThemeMode.Auto -> isSystemInDarkTheme()
            }
            MitraTheme(dark) {
                val scope = rememberCoroutineScope()
                var hide by remember { mutableStateOf(false) }
                var loaded by remember { mutableStateOf(false) }
                var saving by remember { mutableStateOf(false) }
                LaunchedEffect(glanceId) {
                    val state: Preferences = getAppWidgetState(this@MoneyWidgetConfigActivity, PreferencesGlanceStateDefinition, glanceId)
                    val stored = state[MoneyWidget.HIDE_AMOUNTS]
                    // Reconfiguring a widget that is already placed: backing out keeps it (and its current option).
                    if (stored != null) setResult(RESULT_OK, resultIntent(appWidgetId))
                    hide = stored ?: false
                    loaded = true
                }
                ConfigScreen(
                    hide = hide,
                    enabled = loaded && !saving,
                    onHideChange = { hide = it },
                    onDone = {
                        saving = true
                        scope.launch { save(glanceId, appWidgetId, hide) }
                    },
                )
            }
        }
    }

    private suspend fun save(glanceId: GlanceId, appWidgetId: Int, hide: Boolean) {
        updateAppWidgetState(this, glanceId) { it[MoneyWidget.HIDE_AMOUNTS] = hide }
        MoneyWidget().update(this, glanceId)
        setResult(RESULT_OK, resultIntent(appWidgetId))
        finish()
    }

    private fun resultIntent(appWidgetId: Int) = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
}

@Composable
private fun ConfigScreen(hide: Boolean, enabled: Boolean, onHideChange: (Boolean) -> Unit, onDone: () -> Unit) {
    val c = Mitra.colors
    val title = stringResource(R.string.money_widget_config_title)
    Column(
        Modifier.fillMaxSize().background(c.bg).safeDrawingPadding().padding(MitraDimens.pad),
    ) {
        Text(title, style = Mitra.type.h2, color = c.text, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(16.dp))
        MitraSwitchRow(
            title = stringResource(R.string.money_widget_hide_amounts),
            sub = stringResource(R.string.money_widget_hide_amounts_sub),
            checked = hide,
            onCheckedChange = { if (enabled) onHideChange(it) },
        )
        Spacer(Modifier.height(24.dp))
        MitraButton(
            text = stringResource(R.string.money_widget_config_done),
            onClick = onDone,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
