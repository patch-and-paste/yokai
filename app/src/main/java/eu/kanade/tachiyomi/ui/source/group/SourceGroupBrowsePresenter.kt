package eu.kanade.tachiyomi.ui.source.group

import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.source.LangItem
import eu.kanade.tachiyomi.ui.source.SourceItem
import eu.kanade.tachiyomi.ui.source.SourcePresenter
import eu.kanade.tachiyomi.util.system.withUIContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import yokai.domain.source.interactor.GetSourceGroups
import java.util.TreeMap

/**
 * Presenter of [SourceGroupBrowseController]. Builds the same list [SourcePresenter] does, narrowed
 * to one group's members.
 */
class SourceGroupBrowsePresenter(
    private val controller: SourceGroupBrowseController,
    private val groupId: String,
    private val sourceManager: SourceManager = Injekt.get(),
    private val preferences: PreferencesHelper = Injekt.get(),
) {

    private val getSourceGroups: GetSourceGroups by injectLazy()

    private var scope = CoroutineScope(Job() + Dispatchers.Default)

    fun onCreate() {
        if (!scope.isActive) scope = CoroutineScope(Job() + Dispatchers.Default)
        loadSources()
        getSourceGroups.changes()
            .drop(1)
            .onEach { loadSources() }
            .launchIn(scope)
    }

    fun onDestroy() {
        scope.cancel()
    }

    fun loadSources() {
        scope.launch {
            val group = getSourceGroups.byId(groupId)
            if (group == null) {
                withUIContext { controller.onGroupMissing() }
                return@launch
            }

            val memberIds = group.sourceIds.toSet()
            val members = getEnabledSources().filter { it.id in memberIds }

            val pinnedSources = mutableListOf<SourceItem>()
            val pinnedCatalogues = preferences.pinnedCatalogues().get()

            val map = TreeMap<String, MutableList<CatalogueSource>> { d1, d2 ->
                // Catalogues without a lang defined will be placed at the end
                when {
                    d1 == "" && d2 != "" -> 1
                    d2 == "" && d1 != "" -> -1
                    else -> d1.compareTo(d2)
                }
            }
            val byLang = members.groupByTo(map) { it.lang }
            var items: List<IFlexible<*>> = byLang.flatMap {
                val langItem = LangItem(it.key)
                it.value.map { source ->
                    val isPinned = source.id.toString() in pinnedCatalogues
                    if (isPinned) {
                        pinnedSources.add(SourceItem(source, LangItem(SourcePresenter.PINNED_KEY)))
                    }

                    SourceItem(source, langItem, isPinned)
                }
            }

            if (pinnedSources.isNotEmpty()) {
                items = pinnedSources + items
            }

            withUIContext { controller.setSources(items, group.name) }
        }
    }

    /**
     * Same definition of "enabled" the main Browse list uses, so hiding a source keeps working the
     * same way inside a group.
     */
    private fun getEnabledSources(): List<CatalogueSource> {
        val languages = preferences.enabledLanguages().get()
        val hiddenCatalogues = preferences.hiddenSources().get()

        return sourceManager.getCatalogueSources()
            .filter { it.lang in languages || it.id == LocalSource.ID }
            .filterNot { it.id.toString() in hiddenCatalogues }
            .sortedBy { "(${it.lang}) ${it.name}" }
    }
}
