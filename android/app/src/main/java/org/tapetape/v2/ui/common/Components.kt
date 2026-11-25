package org.tapetape.v2.ui.common

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import org.tapetape.v2.R
import org.tapetape.v2.ui.theme.LocalTapeTapeColors
import org.tapetape.v2.ui.theme.fixedSp

@Composable
fun TapeTapeToolbar(
    title: String?,
    navIcon: Int? = R.drawable.ic_arrow_back_white_24dp,
    onNav: () -> Unit = {},
    background: Color = LocalTapeTapeColors.current.toolbar,
    titleContent: (@Composable RowScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(background)
            .statusBarsPadding()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (navIcon != null) {
            Spacer(Modifier.width(4.dp))
            ToolbarIcon(navIcon, onNav)
            Spacer(Modifier.width(if (titleContent == null) 20.dp else 4.dp))
        } else {
            Spacer(Modifier.width(16.dp))
        }
        if (titleContent != null) {
            titleContent()
        } else {
            Text(
                title.orEmpty(),
                color = Color.White,
                fontSize = fixedSp(20f),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        actions()
        Spacer(Modifier.width(4.dp))
    }
}

@Composable
fun ToolbarIcon(res: Int, onClick: () -> Unit, tint: Color = Color.White) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(res), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(tint))
    }
}

@Composable
fun ToolbarIcon(icon: ImageVector, onClick: () -> Unit, tint: Color = Color.White) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = tint)
    }
}

val MenuOverlap = DpOffset(0.dp, (-48).dp)

data class MenuItem(val label: String, val color: Color? = null, val submenu: Boolean = false, val onClick: () -> Unit)

@Composable
fun OverflowMenu(items: List<MenuItem>, tint: Color = Color.White) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolbarIcon(Icons.Default.MoreVert, { open = true }, tint = tint)
        TapeTapeMenu(open, { open = false }, items, offset = MenuOverlap)
    }
}

@Composable
fun TapeTapeMenu(open: Boolean, onDismiss: () -> Unit, items: List<MenuItem>, header: String? = null, offset: DpOffset = DpOffset.Zero) {
    val c = LocalTapeTapeColors.current
    DropdownMenu(
        expanded = open,
        onDismissRequest = onDismiss,
        containerColor = c.menu,
        shape = RoundedCornerShape(2.dp),
        offset = offset,
        modifier = Modifier.widthIn(min = 196.dp),
    ) {
        if (header != null) Text(header, fontSize = 14.sp, color = c.hint, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        items.forEach { item ->
            DropdownMenuItem(
                text = { Text(item.label, fontSize = 16.sp, fontWeight = FontWeight.Normal, color = item.color ?: c.text, modifier = Modifier.padding(horizontal = 4.dp)) },
                onClick = {
                    onDismiss()
                    item.onClick()
                },
                trailingIcon = if (item.submenu) ({ SubmenuArrow(c.hint) }) else null,
                modifier = Modifier.height(48.dp),
            )
        }
    }
}

@Composable
private fun SubmenuArrow(color: Color) {
    Canvas(Modifier.size(10.dp)) {
        drawPath(Path().apply { moveTo(size.width * 0.25f, 0f); lineTo(size.width * 0.75f, size.height / 2); lineTo(size.width * 0.25f, size.height); close() }, color)
    }
}

private val avatarPalette = listOf(
    0xFFC200FC, 0xFF007FB1, 0xFF008675, 0xFF00829D, 0xFF008865, 0xFFE65100, 0xFF5E35B1, 0xFFD81B60,
    0xFF3949AB, 0xFF00897B, 0xFF8D6E63, 0xFF546E7A, 0xFFC62828, 0xFF2E7D32, 0xFFAD1457, 0xFF0277BD,
).map { Color(it) }

fun avatarColor(key: String): Color {
    val crc = java.util.zip.CRC32().apply { update(key.lowercase().toByteArray()) }.value
    return avatarPalette[(crc % avatarPalette.size).toInt()]
}

@Composable
fun Avatar(name: String, path: String?, size: Dp, modifier: Modifier = Modifier) {
    val bitmap = remember(path) { path?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() } }
    if (bitmap != null) {
        Image(bitmap, null, modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
    } else {
        Box(
            modifier
                .size(size)
                .clip(CircleShape)
                .background(avatarColor(name)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.trim().firstOrNull()?.uppercase() ?: "#",
                color = Color.White,
                fontSize = fixedSp(size.value * 0.5f),
            )
        }
    }
}

@Composable
fun chatTitle(name: String, isSelf: Boolean, isDevice: Boolean) = when {
    isSelf -> stringResource(R.string.saved_messages)
    isDevice -> stringResource(R.string.device_talk)
    else -> name
}

@Composable
fun ChatAvatar(name: String, path: String?, isSelf: Boolean, size: Dp, modifier: Modifier = Modifier) {
    if (!isSelf) return Avatar(name, path, size, modifier)
    Box(modifier.size(size).clip(CircleShape).background(Color(0xFF79E9D8)), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.ic_saved_messages), null, Modifier.size(size * 0.5f))
    }
}

@Composable
fun AvatarPicker(path: String?, size: Dp, onClick: () -> Unit) {
    val bitmap = remember(path) { path?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() } }
    val mod = Modifier
        .size(size)
        .clip(CircleShape)
        .clickable(onClick = onClick)
    if (bitmap != null) {
        Image(bitmap, null, mod, contentScale = ContentScale.Crop)
    } else {
        Box(mod.background(LocalTapeTapeColors.current.avatarPlaceholder), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_camera_alt_white_24dp), null, Modifier.size(size * 0.38f))
        }
    }
}

@Composable
fun TapeTapeDialog(
    onDismiss: () -> Unit,
    title: String? = null,
    message: String? = null,
    confirm: Pair<String, () -> Unit>? = null,
    dismiss: Pair<String, () -> Unit>? = null,
    neutral: Pair<String, () -> Unit>? = null,
    confirmColor: Color? = null,
    cancelable: Boolean = true,
    secure: Boolean = false,
    content: (@Composable () -> Unit)? = null,
) {
    val c = LocalTapeTapeColors.current
    val policy = if (secure) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit
    Dialog(onDismiss, DialogProperties(cancelable, cancelable, policy, usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(horizontal = 26.dp)
                .fillMaxWidth()
                .background(c.dialog, RoundedCornerShape(2.dp))
                .padding(top = 24.dp, bottom = 8.dp),
        ) {
            if (title != null) {
                Text(
                    title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = c.text,
                    modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 11.dp),
                )
            }
            if (message != null) {
                Text(
                    message,
                    fontSize = 16.sp,
                    color = c.text,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp),
                )
            }
            content?.invoke()
            if (confirm != null || dismiss != null || neutral != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    neutral?.let { DialogButton(it.first, c.dialogAccent, it.second) }
                    Spacer(Modifier.weight(1f))
                    dismiss?.let { DialogButton(it.first, c.dialogAccent, it.second) }
                    confirm?.let { DialogButton(it.first, confirmColor ?: c.dialogAccent, it.second) }
                }
            }
        }
    }
}

@Composable
fun DialogButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(2.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.uppercase(), color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp, maxLines = 1)
    }
}

@Composable
fun ProgressDialog(text: String, onCancel: (() -> Unit)? = null) {
    val c = LocalTapeTapeColors.current
    Dialog({}, DialogProperties(false, false, usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(horizontal = 26.dp)
                .fillMaxWidth()
                .background(c.dialog, RoundedCornerShape(2.dp))
                .padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(40.dp), color = c.dialogAccent, strokeWidth = 4.dp)
                Spacer(Modifier.width(24.dp))
                Text(text, fontSize = 16.sp, color = c.text)
            }
            if (onCancel != null) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    DialogButton(androidx.compose.ui.res.stringResource(R.string.cancel), c.dialogAccent, onCancel)
                }
            }
        }
    }
}

@Composable
fun UnderlineField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    label: String? = null,
) {
    val c = LocalTapeTapeColors.current
    var focused by remember { mutableStateOf(false) }
    Column(modifier) {
        if (label != null && value.isNotEmpty()) {
            Text(label, fontSize = 13.sp, color = if (focused) c.dialogAccent else c.hint)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = minLines,
            keyboardOptions = keyboardOptions,
            textStyle = TextStyle(fontSize = 18.sp, color = c.text),
            cursorBrush = SolidColor(c.dialogAccent),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Column {
                    Box(Modifier.padding(top = 8.dp, bottom = 8.dp)) {
                        if (value.isEmpty()) Text(hint, fontSize = 18.sp, color = c.hint)
                        inner()
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(if (focused) 2.dp else 1.dp)
                            .background(if (focused) c.dialogAccent else c.hint),
                    )
                }
            },
        )
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalTapeTapeColors.current
    Box(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(c.primaryButton)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.uppercase(), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalTapeTapeColors.current
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(BorderStroke(1.dp, c.secondaryOutline), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.uppercase(), color = c.secondaryText, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

fun toast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

fun copyToClipboard(context: Context, text: String, sensitive: Boolean = false) {
    val clip = ClipData.newPlainText(null, text)
    if (sensitive) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
        }
    }
    val manager = context.getSystemService(ClipboardManager::class.java)
    manager.setPrimaryClip(clip)
    if (sensitive) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val current = runCatching { readClipboard(context) }.getOrNull()
            if (current == null || current == text) {
                if (Build.VERSION.SDK_INT >= 28) manager.clearPrimaryClip() else manager.setPrimaryClip(ClipData.newPlainText(null, ""))
                org.tapetape.v2.Logx.d("sensitive clipboard cleared")
            }
        }, 60_000)
    }
}

fun readClipboard(context: Context): String? =
    context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
