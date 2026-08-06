package eu.kanade.tachiyomi.ui.source

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.databinding.SourceGroupItemBinding
import eu.kanade.tachiyomi.ui.base.holder.BaseFlexibleViewHolder
import yokai.i18n.MR
import yokai.util.lang.getString

class SourceGroupHolder(view: View, adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>) :
    BaseFlexibleViewHolder(view, adapter) {

    private val binding = SourceGroupItemBinding.bind(view)

    fun bind(item: SourceGroupItem) {
        binding.title.text = item.name
        binding.subtitle.text = itemView.context.getString(
            MR.plurals.source_group_sources,
            item.sourceCount,
            item.sourceCount,
        )
    }
}
