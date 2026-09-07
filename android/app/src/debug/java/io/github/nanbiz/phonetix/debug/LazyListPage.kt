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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
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

    /** How each row was laid out, so where it is can be reported line by line. */
    private val layouts = HashMap<String, androidx.compose.ui.text.TextLayoutResult>()

    /** Rows tall enough to be measured, and never two alike. */
    private val ROWS: List<String> =
        (0 until TestWords.DISTINCT.size * 3).map { i ->
            "$i ${TestWords.DISTINCT[i % TestWords.DISTINCT.size]}"
        }

    /**
     * The same list, but each row a message rather than a line.
     *
     * What a reader looks at is a conversation: one item of the list is a paragraph that wraps
     * over a dozen lines of the screen, and a transcription is placed inside it from the
     * character positions the app reports. A list of one-line rows never exercises that - the
     * row and the line are the same thing - and it is inside a wrapped message that the
     * transcriptions were seen sitting on the wrong words.
     */
    private val MESSAGES: List<String> =
        (0 until TestWords.DISTINCT.size / 2).map { i ->
            "$i " + TestWords.DISTINCT.drop(i * 2).take(2).joinToString(" ")
        }

    /**
     * The page, and the two things a movement needs from it: where it is, and put it there.
     *
     * Compose does not keep the scroll position as a number of pixels, so it is worked out
     * from the row it is showing and how far into that row it is - which is what the reader
     * sees, and what the transcriptions have to follow.
     */
    fun build(
        context: Context,
        scope: CoroutineScope,
        asMessages: Boolean = false,
        onReady: (ScrollMotion.Page) -> Unit,
    ): View {
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
                items(if (asMessages) MESSAGES else ROWS) { row ->
                    Text(
                        text = row,
                        color = ComposeColor.White,
                        fontSize = 18.sp,
                        onTextLayout = { layouts[row] = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            // Where this row is on the screen, as it moves. A test that only
                            // knows where the transcriptions are cannot tell one sitting on
                            // its word from one left behind on somebody else's - both are
                            // level with a line of text - and this is a page that hands its
                            // rows to different lines as it scrolls, so there is no fixed
                            // layout to work it out from either.
                            .onGloballyPositioned { at ->
                                if (!DebugMarks.on) return@onGloballyPositioned
                                val top = at.positionInWindow().y.toInt()
                                val laid = layouts[row]
                                if (laid == null) {
                                    Log.d(
                                        TAG,
                                        "PLACEDAT ${SystemClock.uptimeMillis()} $top " +
                                            "${at.size.height} $row",
                                    )
                                    return@onGloballyPositioned
                                }
                                // A line of the screen, not a row: a message wraps over
                                // several, and a transcription two lines from its word is
                                // still inside the message that holds it.
                                val now = SystemClock.uptimeMillis()
                                for (i in 0 until laid.lineCount) {
                                    val lineTop = top + laid.getLineTop(i).toInt()
                                    val height = (laid.getLineBottom(i) - laid.getLineTop(i)).toInt()
                                    val said = row.substring(
                                        laid.getLineStart(i), laid.getLineEnd(i),
                                    ).trim()
                                    if (said.isEmpty()) continue
                                    Log.d(TAG, "PLACEDAT $now $lineTop $height $said")
                                }
                            }
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
