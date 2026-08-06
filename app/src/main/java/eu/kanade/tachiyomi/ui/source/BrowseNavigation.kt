package eu.kanade.tachiyomi.ui.source

import com.bluelinelabs.conductor.Controller
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.ui.source.browse.BrowseSourceController
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date

/**
 * Opens a source, recording it as the last used one unless incognito mode is on.
 *
 * Shared by [BrowseController] and the per-group browse screen so both honour the incognito guard.
 */
fun Controller.openCatalogue(
    source: CatalogueSource,
    controller: BrowseSourceController,
    preferences: PreferencesHelper = Injekt.get(),
) {
    if (!preferences.incognitoMode().get()) {
        preferences.lastUsedCatalogueSource().set(source.id)
        if (source !is LocalSource) {
            val list = preferences.lastUsedSources().get().toMutableSet()
            list.removeAll { it.startsWith("${source.id}:") }
            list.add("${source.id}:${Date().time}")
            val sortedList = list.filter { it.split(":").size == 2 }
                .sortedByDescending { it.split(":").last().toLong() }
            preferences.lastUsedSources()
                .set(sortedList.take(2).toSet())
        }
    }
    router.pushController(controller.withFadeTransaction())
}
