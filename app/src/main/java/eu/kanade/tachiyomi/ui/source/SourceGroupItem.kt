package eu.kanade.tachiyomi.ui.source

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractSectionableItem
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R

/**
 * Row for a user-defined source group. Tapping it opens the group's own browse screen.
 *
 * @param sourceCount how many of the group's members are actually installed and enabled, so the
 * subtitle doesn't count sources the user can't reach.
 */
class SourceGroupItem(
    val groupId: String,
    val name: String,
    val sourceCount: Int,
    header: LangItem? = null,
) : AbstractSectionableItem<SourceGroupHolder, LangItem>(header) {

    override fun getLayoutRes(): Int {
        return R.layout.source_group_item
    }

    override fun isSwipeable(): Boolean {
        return false
    }

    override fun createViewHolder(
        view: View,
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
    ): SourceGroupHolder {
        return SourceGroupHolder(view, adapter)
    }

    override fun bindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
        holder: SourceGroupHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        holder.bind(this)
    }

    override fun equals(other: Any?): Boolean {
        if (other is SourceGroupItem) {
            return groupId == other.groupId &&
                name == other.name &&
                sourceCount == other.sourceCount
        }
        return false
    }

    override fun hashCode(): Int {
        var result = groupId.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + sourceCount
        return result
    }
}
