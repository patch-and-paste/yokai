package eu.kanade.tachiyomi.ui.source.globalsearch

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractFlexibleItem
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.domain.manga.models.Manga
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import yokai.domain.manga.models.cover

// FIXME: Migrate to compose
class GlobalSearchMangaItem(
    initialManga: Manga,
    private val mangaFlow: Flow<Manga?>,
) : AbstractFlexibleItem<GlobalSearchMangaHolder>() {

    val mangaId: Long? = initialManga.id
    var manga: Manga = initialManga
        private set
    private val scope = MainScope()
    private var job: Job? = null

    /**
     * The backing query listens on the whole `mangas` table, so every insert made by a source that
     * is still searching re-emits here. Narrowing to the fields this card actually draws keeps those
     * writes from rebinding the holder and restarting the cover load.
     *
     * Deliberately not `distinctUntilChanged`: Manga equality is url+source only, so it would
     * swallow favorite and cover changes too.
     */
    private val renderFlow = mangaFlow
        .filterNotNull()
        .distinctUntilChangedBy { Triple(it.title, it.favorite, it.cover()) }

    override fun getLayoutRes(): Int {
        return R.layout.source_global_search_controller_card_item
    }

    override fun createViewHolder(view: View, adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>): GlobalSearchMangaHolder {
        return GlobalSearchMangaHolder(view, adapter as GlobalSearchCardAdapter)
    }

    override fun bindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
        holder: GlobalSearchMangaHolder,
        position: Int,
        payloads: MutableList<Any?>?,
    ) {
        // Bind up front: a recycled holder shows the previous card until the flow gets around to
        // emitting, and stable ids make that reuse likely.
        holder.bind(manga)
        job?.cancel()
        job = scope.launch {
            renderFlow.collectLatest {
                manga = it
                holder.bind(manga)
            }
        }
    }

    override fun unbindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>?,
        holder: GlobalSearchMangaHolder?,
        position: Int
    ) {
        job?.cancel()
        job = null
    }

    override fun equals(other: Any?): Boolean {
        if (other is GlobalSearchMangaItem) {
            return mangaId == other.mangaId
        }
        return false
    }

    override fun hashCode(): Int {
        return mangaId?.toInt() ?: 0
    }
}
