package io.github.nanbiz.phonetix.debug

import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A page built the way the apps a reader actually looks at are built.
 *
 * A framework list recycles its rows and describes itself as a tree of views. A Compose list
 * recycles too, and describes itself through semantics instead - different nodes, different
 * identities, different answers to being asked where a line is. Both matter, so the overlay
 * is held against both rather than against whichever one happens to be easier to drive.
 */
object LazyListPage {

    private const val TAG = "PhonetixTest"

    /** Rows tall enough to be measured, and never two alike. */
    private val ROWS: List<String> =
        (0 until TestWords.DISTINCT.size * 3).map { i ->
            "$i ${TestWords.DISTINCT[i % TestWords.DISTINCT.size]}"
        }

    /**
     * The page, and the two things a movement needs from it: where it is, and put it there.
     *
     * Compose does not keep the scroll position as a number of pixels, so it is worked out
     * from the row it is showing and how far into that row it is - which is what the reader
     * sees, and what the transcriptions have to follow.
     */
    fun build(context: Context, scope: CoroutineScope, onReady: (ScrollMotion.Page) -> Unit): View {
        val view = ComposeView(context)
        view.setContent {
            val state: LazyListState = rememberLazyListState()
            LaunchedEffect(state) {
                onReady(page(view, state, scope))
                // The page's own account of where it is, reported as it changes, which is the
                // ground truth a test measures the overlay against.
                snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
                    .collect { Log.d(TAG, "SCROLLY ${SystemClock.uptimeMillis()} ${at(state)}") }
            }
            LazyColumn(
                state = state,
                modifier = Modifier
                    .fillMaxSize()
                    .background(ComposeColor(Color.BLACK)),
            ) {
                items(ROWS) { row ->
                    Text(
                        text = row,
                        color = ComposeColor.White,
                        fontSize = 18.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
        return view
    }

    private fun at(state: LazyListState): Int {
        val height = state.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0
        return state.firstVisibleItemIndex * height + state.firstVisibleItemScrollOffset
    }

    private fun page(view: View, state: LazyListState, scope: CoroutineScope) =
        ScrollMotion.Page(
            view = view,
            at = { at(state) },
            moveTo = { y ->
                val by = (y - at(state)).toFloat()
                if (by != 0f) scope.launch { state.scrollBy(by) }
            },
        )
}
