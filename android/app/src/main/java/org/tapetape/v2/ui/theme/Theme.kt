package org.tapetape.v2.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tapetape.v2.R
import org.tapetape.v2.ThemeMode

data class TapeTapeColors(
    val dark: Boolean,
    val toolbar: Color,
    val selectionToolbar: Color,
    val background: Color,
    val name: Color,
    val preview: Color,
    val pinned: Color,
    val divider: Color,
    val selected: Color,
    val badge: Color,
    val badgeMuted: Color,
    val outBubble: Color,
    val outMeta: Color,
    val inBubble: Color,
    val inMeta: Color,
    val bubbleText: Color,
    val roundButton: Color,
    val primaryButton: Color,
    val secondaryOutline: Color,
    val secondaryText: Color,
    val wallpaper: Int,
    val tabIcon: Color?,
    val tabLabel: Color,
    val inputBar: Color,
    val hint: Color,
    val text: Color,
    val dialog: Color,
    val dialogText: Color,
    val dialogAccent: Color,
    val menu: Color,
    val avatarPlaceholder: Color,
    val actionCircle: Color,
    val actionText: Color,
    val linkBox: Color,
    val qrCard: Color,
    val qrCardBorder: Color,
    val pillButton: Color,
    val quoteBar: Color,
)

val Accent = Color(0xFF42A5F5)
val ListTicks = Color(0xFF70B15C)
val FailedRed = Color(0xFFE53935)

private val Light = TapeTapeColors(
    dark = false,
    toolbar = Color.Black,
    selectionToolbar = Color(0xFF808080),
    background = Color.White,
    name = Color(0xFF070C14),
    preview = Color(0xFF62656A),
    pinned = Color(0xFFEEEEEE),
    divider = Color(0xFFE0E0E0),
    selected = Color(0x552090EA),
    badge = Color(0xFF3792FC),
    badgeMuted = Color(0xFFB6B6BB),
    outBubble = Color(0xFFEFFFDE),
    outMeta = Color(0xFF70B15C),
    inBubble = Color.White,
    inMeta = Color(0xFF808080),
    bubbleText = Color.Black,
    roundButton = Color.Black,
    primaryButton = Color.Black,
    secondaryOutline = Color.Black,
    secondaryText = Color.Black,
    wallpaper = R.drawable.background_hd,
    tabIcon = null,
    tabLabel = Color(0xFF999999),
    inputBar = Color.White,
    hint = Color(0xFF757575),
    text = Color(0xFF212121),
    dialog = Color.White,
    dialogText = Color(0xFF6D6D6D),
    dialogAccent = Color(0xFF008577),
    menu = Color(0xFFFAFAFA),
    avatarPlaceholder = Color(0xFFBDBDBD),
    actionCircle = Color(0xFF505050),
    actionText = Color(0xFF757575),
    linkBox = Color(0xFFEEEEEE),
    qrCard = Color(0xFFF2F2F2),
    qrCardBorder = Color(0xFFBDBDBD),
    pillButton = Color.Black,
    quoteBar = Color(0xFF70B15C),
)

private val Dark = TapeTapeColors(
    dark = true,
    toolbar = Color(0xFF111111),
    selectionToolbar = Color(0xFF3A3A3A),
    background = Color.Black,
    name = Color.White,
    preview = Color(0xFFA8A9AA),
    pinned = Color(0xFF222222),
    divider = Color(0x33FFFFFF),
    selected = Color(0x552090EA),
    badge = Color(0xFF3792FC),
    badgeMuted = Color(0xFF3B3B3B),
    outBubble = Color(0xFF4D4D4D),
    outMeta = Color(0xFFCDB7B5),
    inBubble = Color.Black,
    inMeta = Color(0xFFEFEFEF),
    bubbleText = Color(0xFFEFEFEF),
    roundButton = Color(0xFF4D4D4D),
    primaryButton = Color(0xFF383838),
    secondaryOutline = Color(0xFF808080),
    secondaryText = Color.White,
    wallpaper = R.drawable.background_hd_dark,
    tabIcon = Color(0xFFE0E0E0),
    tabLabel = Color(0xFF999999),
    inputBar = Color(0xFF111111),
    hint = Color(0xFFA8A9AA),
    text = Color(0xFFEFEFEF),
    dialog = Color(0xFF424242),
    dialogText = Color(0xFFBDBDBD),
    dialogAccent = Color(0xFF4DB6AC),
    menu = Color(0xFF2B2B2B),
    avatarPlaceholder = Color(0xFFBDBDBD),
    actionCircle = Color(0xFF808080),
    actionText = Color.White,
    linkBox = Color(0xFF555555),
    qrCard = Color(0xFFF2F2F2),
    qrCardBorder = Color(0xFFBDBDBD),
    pillButton = Color(0xFF383838),
    quoteBar = Color(0xFFCDB7B5),
)

val LocalTapeTapeColors = staticCompositionLocalOf { Light }

@Composable
fun isDark(mode: ThemeMode) = when (mode) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

@Composable
fun TapeTapeTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) Dark else Light
    val scheme = if (dark) {
        darkColorScheme(primary = Accent, background = colors.background, surface = colors.background, onBackground = colors.text, onSurface = colors.text)
    } else {
        lightColorScheme(primary = Accent, background = colors.background, surface = colors.background, onBackground = colors.text, onSurface = colors.text)
    }
    CompositionLocalProvider(LocalTapeTapeColors provides colors) {
        MaterialTheme(colorScheme = scheme) {
            CompositionLocalProvider(
                LocalTextStyle provides LocalTextStyle.current.copy(letterSpacing = 0.sp, lineHeight = TextUnit.Unspecified),
                content = content,
            )
        }
    }
}

@Composable
fun fixedSp(dp: Float): TextUnit = with(LocalDensity.current) { dp.dp.toSp() }
