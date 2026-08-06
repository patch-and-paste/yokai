package eu.kanade.tachiyomi.ui.source.globalsearch

import android.os.Bundle
import android.os.Parcelable
import android.util.SparseArray
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.kanade.tachiyomi.util.system.getSparseParcelableArrayCompat

/**
 * Adapter that holds the search cards.
 *
 * @param controller instance of [GlobalSearchController].
 */
class GlobalSearchAdapter(val controller: GlobalSearchController) :
    FlexibleAdapter<GlobalSearchItem>(null, controller, true) {

    val titleClickListener: OnTitleClickListener = controller

    /**
     * Bundle where the view state of the holders is saved.
     */
    private var bundle = Bundle()

    init {
        // Sources finish one at a time and reorder the list as they do. Both of these are off by
        // default: without the first, updates take the legacy path, which skips reordering entirely
        // and rebinds every row in place. Without the second, the diff reorders by removing and
        // re-inserting rows rather than moving them, which rebinds them too.
        setAnimateChangesWithDiffUtil(true)
        setNotifyMoveOfFilteredItems(true)
    }

    override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int, payloads: List<Any?>) {
        super.onBindViewHolder(holder, position, payloads)
        restoreHolderState(holder)
    }

    override fun onViewRecycled(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        saveHolderState(holder, bundle)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val holdersBundle = Bundle()
        allBoundViewHolders.forEach { saveHolderState(it, holdersBundle) }
        outState.putBundle(HOLDER_BUNDLE_KEY, holdersBundle)
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        bundle = savedInstanceState.getBundle(HOLDER_BUNDLE_KEY)!!
    }

    /**
     * Saves the view state of the given holder.
     *
     * @param holder The holder to save.
     * @param outState The bundle where the state is saved.
     */
    private fun saveHolderState(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, outState: Bundle) {
        val key = holderStateKey(holder)
        val holderState = SparseArray<Parcelable>()
        holder.itemView.saveHierarchyState(holderState)
        outState.putSparseParcelableArray(key, holderState)
    }

    /**
     * Restores the view state of the given holder.
     *
     * @param holder The holder to restore.
     */
    private fun restoreHolderState(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder) {
        val key = holderStateKey(holder)
        val holderState = bundle.getSparseParcelableArrayCompat(key, Parcelable::class.java)
        if (holderState != null) {
            holder.itemView.restoreHierarchyState(holderState)
            bundle.remove(key)
        }
    }

    /**
     * Keyed by source rather than position: rows are reordered as sources finish, so a position key
     * would restore one source's card scroll onto another's.
     */
    private fun holderStateKey(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder) =
        "holder_${holder.itemId}"

    interface OnTitleClickListener {
        fun onTitleClick(position: Int)
    }

    private companion object {
        const val HOLDER_BUNDLE_KEY = "holder_bundle"
    }
}
