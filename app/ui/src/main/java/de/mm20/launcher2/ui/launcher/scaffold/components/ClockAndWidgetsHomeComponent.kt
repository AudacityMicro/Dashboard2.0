package de.mm20.launcher2.ui.launcher.scaffold.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.mm20.launcher2.ui.dashboard.DashboardPanel
import de.mm20.launcher2.ui.dashboard.GridEditing
import de.mm20.launcher2.ui.launcher.scaffold.LauncherScaffoldState

internal object ClockAndWidgetsHomeComponent : ScaffoldComponent() {
    private val scroll = ScrollState(0)
    override val drawBackground = false
    override val hasIme = true
    override val isAtTop: State<Boolean?> = derivedStateOf { !scroll.canScrollBackward }
    override val isAtBottom: State<Boolean?> = derivedStateOf { !scroll.canScrollForward }
    @Composable override fun Component(modifier: Modifier, insets: PaddingValues, state: LauncherScaffoldState) {
        var previous by remember { mutableIntStateOf(scroll.value) }
        LaunchedEffect(scroll.value) {
            if (GridEditing.target != "home") state.onComponentScroll((scroll.value - previous).toFloat())
            previous = scroll.value
        }
        LaunchedEffect(GridEditing.target) {
            if (GridEditing.target == "home") state.lock(hideSearchBar = false) else if (GridEditing.target == null) state.unlock()
        }
        DashboardPanel(modifier.padding(insets).padding(bottom = 8.dp), scroll = scroll, scrollingEnabled = !state.isDragged)
    }
    override suspend fun onActivate(state: LauncherScaffoldState) {
        super.onActivate(state); GridEditing.currentParent = "home"
    }
    override suspend fun onDismiss(state: LauncherScaffoldState) {
        if (GridEditing.target == "home") GridEditing.active = false
        if (GridEditing.target == null) state.unlock()
        super.onDismiss(state); scroll.scrollTo(0)
    }
}
