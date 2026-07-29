package eu.kanade.tachiyomi.ui.extension.details

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.ConcatAdapter
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.isLTR
import android.R as AR

class ExtensionSettingsDividerItemDecoration(context: Context) : androidx.recyclerview.widget.RecyclerView.ItemDecoration() {

    private val divider: Drawable

    init {
        val a = context.obtainStyledAttributes(intArrayOf(AR.attr.listDivider))
        divider = a.getDrawable(0)!!
        a.recycle()
    }

    @SuppressLint("RestrictedApi")
    override fun onDraw(c: Canvas, parent: androidx.recyclerview.widget.RecyclerView, state: androidx.recyclerview.widget.RecyclerView.State) {
        val concatAdapter = parent.adapter as? ConcatAdapter ?: return
        val preferences = concatAdapter.adapters.lastOrNull() as? PreferenceGroupAdapter ?: return
        // The header and the repo list sit in front of the preferences and don't count towards them
        val offset = concatAdapter.adapters.takeWhile { it !== preferences }.sumOf { it.itemCount }

        val childCount = parent.childCount
        for (i in 0 until childCount - 1) {
            val child = parent.getChildAt(i)
            val next = parent.getChildAdapterPosition(child) - offset + 1
            // A line above every source but the first, keeping each one grouped with its preferences
            if (next in 1 until preferences.itemCount && preferences.getItem(next) is SwitchPreferenceCompat) {
                val params = child.layoutParams as androidx.recyclerview.widget.RecyclerView.LayoutParams
                val top = child.bottom + params.bottomMargin
                val bottom = top + divider.intrinsicHeight
                val left = parent.paddingStart + if (parent.context.resources.isLTR) 12.dpToPx else 0
                val right =
                    parent.width - parent.paddingEnd - if (!parent.context.resources.isLTR) 12.dpToPx else 0

                divider.setBounds(left, top, right, bottom)
                divider.draw(c)
            }
        }
    }

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: androidx.recyclerview.widget.RecyclerView,
        state: androidx.recyclerview.widget.RecyclerView.State,
    ) {
        outRect.set(0, 0, 0, divider.intrinsicHeight)
    }
}
