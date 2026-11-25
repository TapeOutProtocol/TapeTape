package org.tapetape.v2.ui.media

import android.content.Context
import android.os.Build
import android.text.format.DateFormat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tapetape.v2.R
import org.tapetape.v2.ui.theme.LocalTapeTapeColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

@Composable
fun MediaThumbnail(path: String, mime: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val bound = if (constraints.hasBoundedWidth) constraints.maxWidth else with(LocalDensity.current) { 200.dp.roundToPx() }
        val maxSide = ((bound + 255) / 256 * 256).coerceIn(256, 1024)
        val animate = isGif(mime) && Build.VERSION.SDK_INT >= 28
        val thumb = if (animate) null else rememberThumb(path, mime, maxSide)
        val painter = if (animate) rememberAnimatedPainter(path, maxSide) else thumb?.bitmap?.let { remember(it) { BitmapPainter(it.asImageBitmap()) } }
        if (painter != null) {
            Image(painter, null, Modifier.fillMaxSize(), contentScale = contentScale)
        } else {
            Box(Modifier.matchParentSize().background(LocalTapeTapeColors.current.pinned))
        }
        if (isVideo(mime)) {
            val small = maxWidth < 96.dp
            Box(
                Modifier
                    .size(if (small) 24.dp else 40.dp)
                    .background(Color(0x99000000), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(if (small) 18.dp else 28.dp), tint = Color.White)
            }
            val duration = thumb?.durationMs ?: 0
            if (duration > 0 && !small) {
                Text(
                    formatDuration(duration),
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(Color(0x99000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun rememberThumb(path: String, mime: String, maxSide: Int): Thumb? {
    var thumb by remember(path, maxSide) { mutableStateOf(MediaDecode.cached(path, maxSide)) }
    LaunchedEffect(path, maxSide) {
        if (thumb == null) thumb = MediaDecode.thumb(path, mime, maxSide)
    }
    return thumb
}

@Composable
fun MediaGrid(items: List<MediaItem>, onOpen: (index: Int) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val c = LocalTapeTapeColors.current
    val sections = remember(items) {
        items.withIndex()
            .groupBy { sectionLabel(context, it.value.time) }
            .map { (label, list) -> label to list.chunked(3) }
    }
    LazyColumn(modifier.fillMaxSize()) {
        sections.forEach { (label, rows) ->
            stickyHeader(key = "h:$label") {
                Text(
                    label,
                    color = c.preview,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(c.pinned)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            rows.forEach { row ->
                item(key = "r:${row.first().value.id}") {
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { (index, item) ->
                            MediaThumbnail(
                                item.path,
                                item.mime,
                                Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .padding(1.5.dp)
                                    .clickable { onOpen(index) },
                            )
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

private fun sectionLabel(context: Context, ms: Long): String {
    val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    val lastMonth = today.minusMonths(1)
    return when {
        d > today.minusDays(7) -> context.getString(R.string.this_week)
        d.year == today.year && d.month == today.month -> context.getString(R.string.this_month)
        d.year == lastMonth.year && d.month == lastMonth.month -> context.getString(R.string.last_month)
        else -> DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "yMMMM"), ms).toString()
    }
}
