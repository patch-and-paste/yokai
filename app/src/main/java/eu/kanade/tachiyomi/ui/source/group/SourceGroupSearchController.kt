package eu.kanade.tachiyomi.ui.source.group

import android.os.Bundle
import android.view.Menu
import androidx.core.os.bundleOf
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.source.globalsearch.GlobalSearchController
import eu.kanade.tachiyomi.ui.source.globalsearch.GlobalSearchPresenter
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.source.interactor.GetSourceGroups

/**
 * Global search narrowed to one group's sources.
 *
 * Passing the members as `sourcesToUse` short-circuits the enabled-source filter, so a group
 * excluded from global search is still fully searchable from its own screen.
 */
class SourceGroupSearchController(
    private val groupId: String? = null,
    initialQuery: String? = null,
) : GlobalSearchController(
    initialQuery,
    bundle = bundleOf(GROUP_ID to groupId, QUERY to initialQuery),
) {

    @Suppress("unused")
    constructor(bundle: Bundle) : this(
        bundle.getString(GROUP_ID),
        bundle.getString(QUERY),
    )

    init {
        setHasOptionsMenu(true)
    }

    override val presenter = GlobalSearchPresenter(
        initialQuery,
        sourcesToUse = resolveMembers(groupId),
    )

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        // Meaningless here: the source list is already fixed to the group.
        menu.findItem(R.id.action_search_all_sources)?.isVisible = false
    }

    companion object {
        const val GROUP_ID = "source_group_id"
        const val QUERY = "query"

        private fun resolveMembers(groupId: String?): List<CatalogueSource> {
            val group = groupId?.let { Injekt.get<GetSourceGroups>().byId(it) } ?: return emptyList()
            val memberIds = group.sourceIds.toSet()
            return Injekt.get<SourceManager>().getCatalogueSources()
                .filter { it.id in memberIds }
                .sortedBy { "(${it.lang}) ${it.name}" }
        }
    }
}
