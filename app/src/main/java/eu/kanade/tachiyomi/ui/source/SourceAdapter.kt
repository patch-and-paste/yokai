package eu.kanade.tachiyomi.ui.source

import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.extension.ExtensionManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Adapter that holds the catalogue cards. Shared by [BrowseController] and the per-group browse
 * screen, so it talks to a [SourceListener] rather than to a specific controller.
 */
class SourceAdapter(
    val sourceListener: SourceListener,
    clickListener: OnItemClickListener,
) : FlexibleAdapter<IFlexible<*>>(null, clickListener, true) {

    init {
        setDisplayHeadersAtStartUp(true)
    }

    val enabledLanguages = Injekt.get<PreferencesHelper>().enabledLanguages().get()

    val extensionManager: ExtensionManager = Injekt.get()

    override fun onItemSwiped(position: Int, direction: Int) {
        super.onItemSwiped(position, direction)
        sourceListener.onHideClick(position)
    }

    interface SourceListener {
        fun onPinClick(position: Int)
        fun onLatestClick(position: Int)
        fun onHideClick(position: Int)
    }
}
