package de.mm20.launcher2.ui.dashboard

import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged

internal val LocalGridContentHeight = compositionLocalOf<((Int) -> Unit)?> { null }

/** Apply inside the scroll modifier, before padding, to measure natural content rather than viewport height. */
@Composable
internal fun Modifier.reportGridContentHeight(): Modifier {
    val report = LocalGridContentHeight.current ?: return this
    return wrapContentHeight(align = Alignment.Top, unbounded = true).onSizeChanged { report(it.height) }
}
