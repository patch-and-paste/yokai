package eu.kanade.tachiyomi.ui.source.group

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Label
import androidx.core.os.bundleOf
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.databinding.SourceGroupBrowseControllerBinding
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.base.controller.BaseLegacyController
import eu.kanade.tachiyomi.ui.main.FloatingSearchInterface
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.source.SourceAdapter
import eu.kanade.tachiyomi.ui.source.SourceItem
import eu.kanade.tachiyomi.ui.source.SourcePresenter
import eu.kanade.tachiyomi.ui.source.browse.BrowseSourceController
import eu.kanade.tachiyomi.ui.source.openCatalogue
import eu.kanade.tachiyomi.util.view.activityBinding
import eu.kanade.tachiyomi.util.view.liftAppbarWith
import eu.kanade.tachiyomi.util.view.setAction
import eu.kanade.tachiyomi.util.view.setOnQueryTextChangeListener
import eu.kanade.tachiyomi.util.view.snack
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import eu.kanade.tachiyomi.widget.EmptyView
import eu.kanade.tachiyomi.widget.LinearLayoutManagerAccurateOffset
import uy.kohesive.injekt.injectLazy
import yokai.i18n.MR
import yokai.presentation.source.group.SourceGroupsController
import yokai.util.lang.getString

/**
 * Browse screen for a single source group. Renders with the same adapter and rows the main Browse
 * list uses, so pinning, "Latest" and swipe-to-hide all behave the way they do there.
 */
class SourceGroupBrowseController(bundle: Bundle) :
    BaseLegacyController<SourceGroupBrowseControllerBinding>(bundle),
    FlexibleAdapter.OnItemClickListener,
    SourceAdapter.SourceListener,
    FloatingSearchInterface {

    constructor(groupId: String) : this(bundleOf(GROUP_ID to groupId))

    private val preferences: PreferencesHelper by injectLazy()

    private val groupId: String = bundle.getString(GROUP_ID).orEmpty()

    private var adapter: SourceAdapter? = null
    private var snackbar: Snackbar? = null
    private var groupName: String? = null

    private val presenter = SourceGroupBrowsePresenter(this, groupId)

    init {
        setHasOptionsMenu(true)
    }

    override fun getTitle(): String? = groupName

    override fun getSearchTitle(): String? = groupName

    override fun createBinding(inflater: LayoutInflater) =
        SourceGroupBrowseControllerBinding.inflate(inflater)

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)
        liftAppbarWith(binding.sourceRecycler, true)

        adapter = SourceAdapter(this, this)
        binding.sourceRecycler.layoutManager = LinearLayoutManagerAccurateOffset(view.context)
        binding.sourceRecycler.adapter = adapter
        adapter?.isSwipeEnabled = true
        adapter?.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY

        presenter.onCreate()
    }

    override fun onDestroyView(view: View) {
        snackbar?.dismiss()
        snackbar = null
        adapter = null
        presenter.onDestroy()
        super.onDestroyView(view)
    }

    fun setSources(sources: List<IFlexible<*>>, name: String) {
        groupName = name
        setTitle()
        adapter?.updateDataSet(sources, false)

        if (sources.isEmpty()) {
            binding.emptyView.show(
                Icons.Filled.Label,
                view?.context?.getString(MR.strings.information_empty_source_group).orEmpty(),
                listOf(EmptyView.Action(MR.strings.add_sources_to_group) { openGroupManagement() }),
            )
        } else {
            binding.emptyView.hide()
        }
    }

    /** The group was deleted from under us. */
    fun onGroupMissing() {
        if (isAttached) router.popCurrentController()
    }

    override fun onItemClick(view: View, position: Int): Boolean {
        val item = adapter?.getItem(position) as? SourceItem ?: return false
        openCatalogue(item.source, BrowseSourceController(item.source), preferences)
        return false
    }

    override fun onPinClick(position: Int) {
        val item = adapter?.getItem(position) as? SourceItem ?: return
        val isPinned = item.isPinned ?: item.header?.code?.equals(SourcePresenter.PINNED_KEY) ?: false
        pinCatalogue(item.source, isPinned)
    }

    override fun onLatestClick(position: Int) {
        val item = adapter?.getItem(position) as? SourceItem ?: return
        openCatalogue(item.source, BrowseSourceController(item.source, useLatest = true), preferences)
    }

    override fun onHideClick(position: Int) {
        val source = (adapter?.getItem(position) as? SourceItem)?.source ?: return
        val current = preferences.hiddenSources().get()
        preferences.hiddenSources().set(current + source.id.toString())

        presenter.loadSources()

        snackbar = view?.snack(MR.strings.source_hidden, Snackbar.LENGTH_INDEFINITE) {
            setAction(MR.strings.undo) {
                val newCurrent = preferences.hiddenSources().get()
                preferences.hiddenSources().set(newCurrent - source.id.toString())
                presenter.loadSources()
            }
        }
        (activity as? MainActivity)?.setUndoSnackBar(snackbar)
    }

    private fun pinCatalogue(source: Source, isPinned: Boolean) {
        val current = preferences.pinnedCatalogues().get()
        if (isPinned) {
            preferences.pinnedCatalogues().set(current - source.id.toString())
        } else {
            preferences.pinnedCatalogues().set(current + source.id.toString())
        }

        presenter.loadSources()
    }

    private fun openGroupManagement() {
        router.pushController(SourceGroupsController().withFadeTransaction())
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.source_group_browse, menu)

        val searchView = activityBinding?.searchToolbar?.searchView
        activityBinding?.searchToolbar?.searchQueryHint = groupName?.let {
            view?.context?.getString(MR.strings.search_group_hint, it)
        } ?: view?.context?.getString(MR.strings.global_search)

        // Searching from inside a group only ever covers that group's sources.
        setOnQueryTextChangeListener(searchView, true) {
            if (!it.isNullOrBlank()) {
                router.pushController(
                    SourceGroupSearchController(groupId, it).withFadeTransaction(),
                )
            }
            true
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_edit_group_sources -> {
                openGroupManagement()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        const val GROUP_ID = "source_group_id"
    }
}
