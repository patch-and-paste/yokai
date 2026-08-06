package eu.kanade.tachiyomi.ui.source

import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.util.system.withUIContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * Presenter of [BrowseController]
 * Function calls should be done from here. UI calls should be done from the controller.
 *
 * @param sourceManager manages the different sources.
 * @param preferences application preferences.
 */
class SourcePresenter(
    val controller: BrowseController,
    val sourceManager: SourceManager = Injekt.get(),
    val extensionManager: ExtensionManager = Injekt.get(),
    private val preferences: PreferencesHelper = Injekt.get(),
) {

    private val getSourceGroups: GetSourceGroups by injectLazy()

    private var scope = CoroutineScope(Job() + Dispatchers.Default)
    var sources = getEnabledSources()

    /** Group rows followed by the ungrouped sources, so this holds more than just [SourceItem]. */
    var browseItems = emptyList<IFlexible<*>>()
    var lastUsedItem: SourceItem? = null

    var lastUsedJob: Job? = null
    private var groupsJob: Job? = null

    fun onCreate() {
        if (lastSources != null) {
            if (browseItems.isEmpty()) {
                browseItems = lastSources ?: emptyList()
            }
            lastUsedItem = lastUsedItemRem
            lastSources = null
            lastUsedItemRem = null
        }

        // Load enabled and last used sources
        loadSources()
    }

    /**
     * Unsubscribe and create a new subscription to fetch enabled sources.
     */
    private fun loadSources() {
        scope.launch {
            val groups = getSourceGroups.all()
            val groupedIds = groups.flatMapTo(mutableSetOf()) { it.sourceIds }
            val enabledIds = sources.mapTo(mutableSetOf()) { it.id }

            // Dropping grouped sources before the split below takes them out of both their language
            // section and the pinned section in one go.
            val ungrouped = sources.filterNot { it.id in groupedIds }

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
            val byLang = ungrouped.groupByTo(map) { it.lang }
            var sourceItems: List<IFlexible<*>> = byLang.flatMap {
                val langItem = LangItem(it.key)
                it.value.map { source ->
                    val isPinned = source.id.toString() in pinnedCatalogues
                    if (source.id.toString() in pinnedCatalogues) {
                        pinnedSources.add(SourceItem(source, LangItem(PINNED_KEY)))
                    }

                    SourceItem(source, langItem, isPinned)
                }
            }

            if (pinnedSources.isNotEmpty()) {
                sourceItems = pinnedSources + sourceItems
            }

            val groupHeader = LangItem(GROUPS_KEY)
            val groupItems = groups
                .filter { it.showInBrowse }
                .map { group ->
                    SourceGroupItem(
                        groupId = group.id,
                        name = group.name,
                        sourceCount = group.sourceIds.count { it in enabledIds },
                        header = groupHeader,
                    )
                }

            browseItems = groupItems + sourceItems

            lastUsedItem = getLastUsedSource(preferences.lastUsedCatalogueSource().get(), groupedIds)
            withUIContext {
                controller.setSources(browseItems, lastUsedItem)
                loadLastUsedSource()
                loadSourceGroups()
            }
        }
    }

    private fun loadLastUsedSource() {
        lastUsedJob?.cancel()
        lastUsedJob = preferences.lastUsedCatalogueSource().changes()
            .drop(1)
            .onEach {
                lastUsedItem = getLastUsedSource(it, getSourceGroups.groupedSourceIds())
                withUIContext {
                    controller.setLastUsedSource(lastUsedItem)
                }
            }.launchIn(scope)
    }

    private fun loadSourceGroups() {
        groupsJob?.cancel()
        groupsJob = getSourceGroups.changes()
            .drop(1)
            .onEach {
                withUIContext { updateSources() }
            }.launchIn(scope)
    }

    private fun getLastUsedSource(value: Long, groupedIds: Set<Long>): SourceItem? {
        return (sourceManager.get(value) as? CatalogueSource)?.let { source ->
            // A grouped source shouldn't announce itself at the top of the main Browse list.
            if (source.id in groupedIds) return@let null

            val pinnedCatalogues = preferences.pinnedCatalogues().get()
            val isPinned = source.id.toString() in pinnedCatalogues
            if (isPinned) {
                null
            } else {
                SourceItem(source, LangItem(LAST_USED_KEY), isPinned)
            }
        }
    }

    fun updateSources() {
        sources = getEnabledSources()
        loadSources()
    }

    fun onDestroy() {
        lastSources = browseItems
        lastUsedItemRem = lastUsedItem
    }

    /**
     * Returns a list of enabled sources ordered by language and name.
     *
     * @return list containing enabled sources.
     */
    private fun getEnabledSources(): List<CatalogueSource> {
        val languages = preferences.enabledLanguages().get()
        val hiddenCatalogues = preferences.hiddenSources().get()

        return sourceManager.getCatalogueSources()
            .filter { it.lang in languages || it.id == LocalSource.ID }
            .filterNot { it.id.toString() in hiddenCatalogues }
            .sortedBy { "(${it.lang}) ${it.name}" }
    }

    companion object {
        const val PINNED_KEY = "pinned"
        const val LAST_USED_KEY = "last_used"
        const val GROUPS_KEY = "source_groups"

        private var lastSources: List<IFlexible<*>>? = null
        private var lastUsedItemRem: SourceItem? = null

        /** Drops the cross-instance cache so the next Browse load rebuilds from preferences. */
        fun invalidateCache() {
            lastSources = null
            lastUsedItemRem = null
        }

        fun onLowMemory() = invalidateCache()
    }
}
