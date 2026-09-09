package io.github.nanbiz.phonetix.service

import android.content.Context
import android.view.View
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * What a Compose view needs in order to live in a window a service put up.
 *
 * An activity hands its content a lifecycle, a saved-state registry and a view-model store
 * without being asked. A service has none of those, and Compose refuses to draw without them,
 * so this is the smallest honest set: a lifecycle that is resumed while the window is up and
 * destroyed when it comes down, and stores that are emptied with it.
 *
 * Nothing here is saved across a process restart, and nothing should be: an overlay window is
 * about the word under the reader's finger right now, and there is no such word after a
 * restart.
 */
class OverlayHost(context: Context) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    /** A Compose view that can be added to a window of this service's. */
    val view: ComposeView = ComposeView(context).also { composed ->
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
        composed.setViewTreeLifecycleOwner(this)
        composed.setViewTreeViewModelStoreOwner(this)
        composed.setViewTreeSavedStateRegistryOwner(this)
    }

    /** The window is up and the view may draw. */
    fun shown() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    /** The window is coming down. Called before the view leaves the window manager, because a
     *  Compose view torn down after its lifecycle ends leaks the composition it was holding. */
    fun hidden() {
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }

    /** Whether this view is the one in that window, for a caller keeping one host at a time. */
    fun holds(other: View?): Boolean = other === view
}
