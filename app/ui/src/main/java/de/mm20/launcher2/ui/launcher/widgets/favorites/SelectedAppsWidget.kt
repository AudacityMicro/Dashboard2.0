package de.mm20.launcher2.ui.launcher.widgets.favorites

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.mm20.launcher2.applications.AppRepository
import de.mm20.launcher2.data.customattrs.CustomAttributesRepository
import de.mm20.launcher2.data.customattrs.utils.withCustomLabels
import de.mm20.launcher2.searchable.SavableSearchableRepository
import de.mm20.launcher2.searchable.VisibilityLevel
import de.mm20.launcher2.ui.R
import de.mm20.launcher2.ui.launcher.search.common.grid.SearchResultGrid
import de.mm20.launcher2.ui.locals.LocalGridSettings
import de.mm20.launcher2.widgets.AppsWidget
import kotlinx.coroutines.flow.combine
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class SelectedAppsWidgetVM : ViewModel(), KoinComponent {
    private val repository: AppRepository by inject()
    private val saved: SavableSearchableRepository by inject()
    private val attributes: CustomAttributesRepository by inject()

    // Match the full launcher key (including profile), never package name alone. Hidden and
    // unavailable apps disappear without discarding their saved selection or altering favorites.
    val apps = combine(repository.findMany(), saved.getKeys(includeTypes = listOf("app"),
        minVisibility = VisibilityLevel.Hidden, maxVisibility = VisibilityLevel.Hidden,
        limit = Int.MAX_VALUE)) { installed, hidden ->
        val hiddenKeys = hidden.toHashSet()
        installed.filter { it.key !in hiddenKeys }
    }.withCustomLabels(attributes)
}

@Composable
fun SelectedAppsWidget(widget: AppsWidget, onWidgetUpdate: (AppsWidget) -> Unit) {
    val model: SelectedAppsWidgetVM = viewModel(key = "selected-apps-${widget.id}")
    val apps by model.apps.collectAsStateWithLifecycle(emptyList())
    var choosing by rememberSaveable(widget.id.toString()) { mutableStateOf(false) }
    val byKey = remember(apps) { apps.associateBy { it.key } }
    val selected = remember(byKey, widget.config.selectedAppKeys) {
        widget.config.selectedAppKeys.distinct().mapNotNull(byKey::get)
    }
    val grid = LocalGridSettings.current
    Column(Modifier.fillMaxSize().testTag("selected-apps-${widget.id}")) {
        Box(Modifier.fillMaxWidth()) {
            IconButton(onClick = { choosing = true }, modifier = Modifier.align(Alignment.TopEnd)
                .testTag("selected-apps-choose-${widget.id}")) {
                Icon(painterResource(R.drawable.more_vert_24px), stringResource(R.string.widget_select_apps))
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            val columns = ((maxWidth - 8.dp) / (grid.iconSize.dp + 24.dp)).toInt()
                .coerceIn(1, grid.columnCount.coerceAtLeast(1))
            if (selected.isNotEmpty()) SearchResultGrid(selected, showLabels = false,
                columns = columns, allowLongClick = false)
        }
    }
    if (choosing) {
        // Draft changes are local to this dialog, survive rotation, and take effect only on Save.
        var draft by rememberSaveable(widget.id.toString()) {
            mutableStateOf(ArrayList(widget.config.selectedAppKeys.distinct()))
        }
        var query by rememberSaveable { mutableStateOf("") }
        val choices = remember(apps, query) {
            apps.filter { it.label.contains(query, ignoreCase = true) }
                .sortedBy { it.label.lowercase() }
        }
        AlertDialog(onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.widget_select_apps)) },
            text = {
                Column {
                    OutlinedTextField(query, onValueChange = { query = it }, singleLine = true,
                        label = { Text(stringResource(R.string.widget_search_apps)) },
                        modifier = Modifier.fillMaxWidth())
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag("selected-apps-choices")) {
                        items(choices, key = { it.key }) { app ->
                            Row(Modifier.fillMaxWidth().testTag("selected-app-choice-${app.key}")
                                .toggleable(value = app.key in draft, role = Role.Checkbox,
                                    onValueChange = { checked ->
                                        draft = ArrayList(if (checked) draft + app.key else draft - app.key)
                                    }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = app.key in draft, onCheckedChange = null)
                                Text(app.label, modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = {
                onWidgetUpdate(widget.copy(config = widget.config.copy(selectedAppKeys = draft.toList())))
                choosing = false
            }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { choosing = false }) {
                Text(stringResource(android.R.string.cancel))
            } })
    }
}
