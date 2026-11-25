package org.tapetape.v2.notify

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import org.tapetape.v2.Logx
import org.tapetape.v2.MainActivity
import org.tapetape.v2.core.ChatSummary
import org.tapetape.v2.ui.common.avatarColor

object Shortcuts {
    private const val CATEGORY = "org.tapetape.v2.category.SHARE_TARGET"
    private var last: List<String> = emptyList()

    fun id(account: Int, chat: Int) = "chat_${account}_$chat"

    fun parse(id: String?): Pair<Int, Int>? {
        val parts = id?.split("_") ?: return null
        if (parts.size != 3 || parts[0] != "chat") return null
        return parts[1].toIntOrNull()?.let { a -> parts[2].toIntOrNull()?.let { a to it } }
    }

    private fun icon(chat: ChatSummary): IconCompat {
        val size = 192
        val photo = chat.avatarPath?.let { BitmapFactory.decodeFile(it) }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (photo != null) {
            canvas.drawBitmap(Bitmap.createScaledBitmap(photo, size, size, true), 0f, 0f, null)
        } else {
            canvas.drawColor(avatarColor(chat.name).toArgb())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = size * 0.4f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(chat.name.trim().firstOrNull()?.uppercase() ?: "#", size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2, paint)
        }
        return IconCompat.createWithAdaptiveBitmap(bmp)
    }

    fun update(context: Context, account: Int, chats: List<ChatSummary>) {
        val top = chats.filter { !it.isRequest && !it.isDevice && !it.archived }.take(4)
        val ids = top.map { id(account, it.id) + it.name + it.avatarPath }
        if (ids == last) return
        last = ids
        val list = top.map { chat ->
            ShortcutInfoCompat.Builder(context, id(account, chat.id))
                .setShortLabel(chat.name.ifBlank { "#" })
                .setLongLived(true)
                .setIcon(icon(chat))
                .setCategories(setOf(CATEGORY))
                .setPerson(Person.Builder().setName(chat.name).build())
                .setIntent(
                    Intent(context, MainActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .setData(Uri.parse("tapetape://chat/$account/${chat.id}"))
                        .putExtra(Notifier.EXTRA_ACCOUNT, account)
                        .putExtra(Notifier.EXTRA_CHAT, chat.id),
                )
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, list) }
            .onSuccess { Logx.d("shortcuts updated ${list.size}") }
            .onFailure { Logx.w("shortcuts update failed", it) }
    }

    fun clear(context: Context) {
        last = emptyList()
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
    }
}
