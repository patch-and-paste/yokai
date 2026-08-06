package yokai.presentation.source.group.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.core.graphics.drawable.toBitmap
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.icon

/**
 * Extension icons come back as [android.graphics.drawable.Drawable]s, which Compose can't draw
 * directly. Keyed on the source id so the conversion happens once per row rather than per
 * recomposition.
 */
@Composable
fun rememberSourcePainter(source: Source): Painter? = remember(source.id) {
    val drawable = source.icon() ?: return@remember null
    runCatching { BitmapPainter(drawable.toBitmap().asImageBitmap()) }.getOrNull()
}
