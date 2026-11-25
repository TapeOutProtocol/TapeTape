package org.tapetape.v2.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tapetape.v2.R

@Composable
fun ToolButton(res: Int, label: String, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (active) Color.White else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (active) Color.Black else Color.White.copy(alpha = if (enabled) 1f else 0.35f)
        Image(painterResource(res), label, Modifier.size(28.dp), colorFilter = ColorFilter.tint(tint))
    }
}

@Composable
fun ColorBar(pos: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val change by rememberUpdatedState(onChange)
    Canvas(
        modifier
            .height(40.dp)
            .pointerInput(Unit) {
                val r = 12.dp.toPx()
                fun at(x: Float) = ((x - r) / (size.width - 2 * r)).coerceIn(0f, 1f)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    change(at(down.position.x))
                    track(down) { change(at(it.position.x)) }
                }
            },
    ) {
        val r = 12.dp.toPx()
        val h = 8.dp.toPx()
        drawRoundRect(
            Brush.horizontalGradient(paletteColors, startX = r, endX = size.width - r),
            Offset(r, center.y - h / 2),
            Size(size.width - 2 * r, h),
            CornerRadius(h / 2),
        )
        val x = r + pos * (size.width - 2 * r)
        drawCircle(Color.White, r, Offset(x, center.y))
        drawCircle(Color(colorAt(pos)), r - 2.5.dp.toPx(), Offset(x, center.y))
    }
}

@Composable
fun WidthPicker(level: Int, onChange: (Int) -> Unit) {
    Row {
        listOf(8, 13, 19).forEachIndexed { i, d ->
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable { onChange(i) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(d.dp)
                        .clip(CircleShape)
                        .background(if (i == level) Color.White else Color.White.copy(alpha = 0.35f)),
                )
            }
        }
    }
}

@Composable
fun TextEditor(initial: String, colorPos: Float, onColor: (Float) -> Unit, onDone: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler { onDone(value.text) }
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .pointerInput(Unit) { detectTapGestures { onDone(value.text) } }
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ColorBar(colorPos, onColor, Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.done),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onDone(value.text) }
                    .padding(8.dp),
            )
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                textStyle = TextStyle(
                    color = Color(colorAt(colorPos)),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    shadow = Shadow(Color(0x66000000), Offset(0f, 2f), 6f),
                ),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .focusRequester(focus),
            )
        }
    }
}

/** Sticker categories: system emoji, so the app ships no sticker images. */
private val STICKERS: List<List<String>> = listOf(
    "😀 😃 😄 😁 😆 😅 😂 🤣 😊 😇 🙂 😉 😍 🥰 😘 😋 😜 🤪 😎 🤩 🥳 😏 😒 😔 😢 😭 😤 😡 🤯 😱 😴 🤔 🤗 🤫 🙄 😬 🤐 🥺 👍 👎 👏 🙏 💪 👋 ❤️ 💔 🔥 ✨ 💯",
    "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🐺 🐗 🐴 🦄 🐝 🐛 🦋 🐌 🐞 🐢 🐍 🦎 🐙 🦑 🦀 🐠 🐟 🐬 🐳 🦈 🐊 🐘 🦒 🐪 🦘 🐿️ 🦔 🦥",
    "🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🥑 🥦 🌽 🥕 🥐 🍞 🧀 🥚 🍳 🥞 🥓 🍗 🍖 🌭 🍔 🍟 🍕 🥪 🌮 🍝 🍜 🍣 🍱 🥟 🍦 🍩 🍪 🎂 🍰 🍫 🍬 ☕ 🍵 🧋",
    "👕 👔 👗 👘 👙 👚 🧥 🥼 🦺 👖 🩳 🧦 🧤 🧣 👒 🎩 🧢 👑 👓 🕶️ 🥽 👜 👛 🎒 👞 👟 🥾 👠 👡 👢 💍 💄 🌂 ☂️ 🎀",
    "☀️ 🌤️ ⛅ 🌥️ ☁️ 🌦️ 🌧️ ⛈️ 🌩️ 🌨️ ❄️ ☃️ ⛄ 🌬️ 💨 🌪️ 🌫️ 🌈 ☔ ⚡ 🌙 ⭐ 🌟 🌍 🌊",
).map { it.split(' ') }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerSheet(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF1C1C1E),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            STICKERS.forEachIndexed { i, items ->
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (i == tab) Color(0x33FFFFFF) else Color.Transparent)
                        .clickable { tab = i },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(items.first(), fontSize = 26.sp)
                }
            }
        }
        LazyVerticalGrid(
            GridCells.Adaptive(72.dp),
            Modifier
                .fillMaxWidth()
                .height(400.dp),
            contentPadding = PaddingValues(8.dp),
        ) {
            items(STICKERS[tab], key = { it }) { emoji ->
                Box(
                    Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onPick(emoji) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(emoji, fontSize = 40.sp)
                }
            }
        }
    }
}
