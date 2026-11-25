package org.tapetape.v2.notify

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.media.SoundPool
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import org.tapetape.v2.Logx
import org.tapetape.v2.MainActivity
import org.tapetape.v2.Prefs
import org.tapetape.v2.R
import org.tapetape.v2.TapeTape
import org.tapetape.v2.core.MessageKind
import org.tapetape.v2.core.Notification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

val LedColors = listOf(
    R.string.purple to 0xFFAA00FF.toInt(),
    R.string.green to 0xFF00FF00.toInt(),
    R.string.red to 0xFFFF0000.toInt(),
    R.string.blue to 0xFF0000FF.toInt(),
    R.string.orange to 0xFFFF9800.toInt(),
    R.string.cyan to 0xFF00FFFF.toInt(),
    R.string.magenta to 0xFFFF00FF.toInt(),
    R.string.white to 0xFFFFFFFF.toInt(),
    R.string.none to 0,
)

object Notifier {
    private const val GROUP = "chats"
    private const val ACTION_REPLY = "org.tapetape.v2.REPLY"
    private const val ACTION_READ = "org.tapetape.v2.MARK_READ"
    const val EXTRA_ACCOUNT = "account"
    const val EXTRA_CHAT = "chat"
    private const val KEY_TEXT = "text"

    private val lines = mutableMapOf<Pair<Int, Int>, MutableList<NotificationCompat.MessagingStyle.Message>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile var openChat: Pair<Int, Int>? = null
    private var sounds: SoundPool? = null
    private var soundIn = 0
    private var soundOut = 0

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    fun refreshChannels(context: Context) {
        val nm = manager(context)
        nm.notificationChannels.filter { it.id.startsWith("msg_") }.forEach { nm.deleteNotificationChannel(it.id) }
        Logx.d("notification channels reset")
    }

    private fun channel(context: Context, account: Int, chat: Int, chatName: String): String {
        val chatSound = Prefs.chatSound(account, chat)
        val chatVibrate = Prefs.chatVibrate(account, chat)
        val sound = chatSound ?: Prefs.sound
        val vibrate = if (chatVibrate == 0) Prefs.vibrate else chatVibrate == 1
        val custom = chatSound != null || chatVibrate != 0
        val key = "$sound|$vibrate|${Prefs.led}|${Prefs.priority}|${if (custom) "$account/$chat" else ""}"
        val id = "msg_" + Integer.toHexString(key.hashCode())
        val nm = manager(context)
        if (nm.getNotificationChannel(id) != null) return id
        nm.createNotificationChannelGroup(NotificationChannelGroup(GROUP, context.getString(R.string.pref_chats)))
        val importance = if (Prefs.priority == 0) NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_HIGH
        val ch = NotificationChannel(id, if (custom) chatName else context.getString(R.string.def), importance).apply {
            group = GROUP
            enableVibration(vibrate)
            val led = LedColors[Prefs.led].second
            enableLights(led != 0)
            if (led != 0) lightColor = led
            val uri = when {
                sound == null -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                sound.isEmpty() -> null
                else -> Uri.parse(sound)
            }
            setSound(uri, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT).build())
        }
        nm.createNotificationChannel(ch)
        Logx.d("channel $id created custom=$custom")
        return id
    }

    private fun body(context: Context, n: Notification): String {
        val media = when (n.kind) {
            MessageKind.Image -> "📷 " + context.getString(R.string.image)
            MessageKind.Gif -> context.getString(R.string.gif)
            MessageKind.Sticker -> context.getString(R.string.sticker)
            MessageKind.Video -> "🎥 " + context.getString(R.string.video)
            MessageKind.Voice -> "🎤 " + context.getString(R.string.voice_message)
            MessageKind.Audio -> "🎵 " + context.getString(R.string.audio)
            MessageKind.File -> "📎 " + context.getString(R.string.file)
            MessageKind.Vcard -> "👤 " + context.getString(R.string.contact)
            else -> null
        }
        val text = when {
            media == null -> n.text
            n.text.isBlank() -> media
            else -> media.substringBefore(' ') + " " + n.text
        }
        return if (n.reaction != null) context.getString(R.string.reaction_by_other, n.senderName, n.reaction, text) else text
    }

    fun post(context: Context, n: Notification) {
        if (n.muted && !(n.mention && runCatching { TapeTape.core.account(n.accountId).settings().mentions }.getOrDefault(false))) return
        if (openChat == (n.accountId to n.chatId)) {
            if (Prefs.inChatSounds && n.reaction == null) play(context, true)
            return
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Logx.d("notifications disabled in system")
            return
        }
        val key = n.accountId to n.chatId
        val body = body(context, n)
        val builder = NotificationCompat.Builder(context, channel(context, n.accountId, n.chatId, n.chatName))
            .setSmallIcon(R.drawable.icon_notification)
            .setColor(0xFF000000.toInt())
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (Prefs.priority == 2) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setGroup("account_${n.accountId}")
            .setContentIntent(openIntent(context, n.accountId, n.chatId))
            .setDeleteIntent(actionIntent(context, ACTION_READ, n.accountId, n.chatId, clear = true))
        val accounts = TapeTape.core.accounts()
        if (accounts.size > 1) accounts.firstOrNull { it.id == n.accountId }?.let { builder.setSubText(it.tag.ifBlank { it.name }) }
        when (Prefs.show) {
            0 -> {
                val all = synchronized(lines) {
                    lines.getOrPut(key) { mutableListOf() }.apply {
                        add(NotificationCompat.MessagingStyle.Message(body, System.currentTimeMillis(), Person.Builder().setName(n.senderName).build()))
                        while (size > 7) removeAt(0)
                    }.toList()
                }
                val style = NotificationCompat.MessagingStyle(Person.Builder().setName(context.getString(R.string.self)).build())
                    .setGroupConversation(n.isGroup)
                if (n.isGroup) style.setConversationTitle(n.chatName)
                all.forEach { style.addMessage(it) }
                builder.setStyle(style)
                    .setContentTitle(n.chatName)
                    .setContentText(if (n.isGroup) "${n.senderName}: $body" else body)
                    .setShortcutId(Shortcuts.id(n.accountId, n.chatId))
                    .addAction(
                        NotificationCompat.Action.Builder(R.drawable.ic_reply_white_24dp, context.getString(R.string.notify_reply_button), actionIntent(context, ACTION_REPLY, n.accountId, n.chatId))
                            .addRemoteInput(RemoteInput.Builder(KEY_TEXT).setLabel(context.getString(R.string.notify_reply_button)).build())
                            .setAllowGeneratedReplies(true)
                            .build(),
                    )
                    .addAction(0, context.getString(R.string.mark_as_read_short), actionIntent(context, ACTION_READ, n.accountId, n.chatId))
            }
            1 -> builder.setContentTitle(n.chatName).setContentText(context.getString(R.string.notify_new_message))
            else -> builder.setContentTitle(context.getString(R.string.notify_new_message))
        }
        manager(context).notify(key.hashCode(), builder.build())
        Logx.d("notified acc=${n.accountId} chat=${n.chatId} msg=${n.msgId}")
    }

    fun cancel(context: Context, account: Int, chat: Int) {
        val key = account to chat
        val had = synchronized(lines) { lines.remove(key) != null }
        if (had) manager(context).cancel(key.hashCode())
    }

    fun play(context: Context, incoming: Boolean) {
        val pool = sounds ?: SoundPool.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
            .setMaxStreams(2)
            .build()
            .also {
                sounds = it
                soundIn = it.load(context, R.raw.sound_in, 1)
                soundOut = it.load(context, R.raw.sound_out, 1)
            }
        pool.play(if (incoming) soundIn else soundOut, 0.6f, 0.6f, 1, 0, 1f)
    }

    fun openIntent(context: Context, account: Int, chat: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction("org.tapetape.v2.OPEN_CHAT")
            .setData(Uri.parse("tapetape://chat/$account/$chat"))
            .putExtra(EXTRA_ACCOUNT, account)
            .putExtra(EXTRA_CHAT, chat)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, (account to chat).hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun actionIntent(context: Context, action: String, account: Int, chat: Int, clear: Boolean = false): PendingIntent {
        val intent = Intent(context, NotifyReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("tapetape://chat/$account/$chat?clear=$clear"))
            .putExtra(EXTRA_ACCOUNT, account)
            .putExtra(EXTRA_CHAT, chat)
            .putExtra("clear", clear)
        val mutable = if (action == ACTION_REPLY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
    }

    internal fun handle(context: Context, intent: Intent, done: () -> Unit) {
        val account = intent.getIntExtra(EXTRA_ACCOUNT, -1)
        val chat = intent.getIntExtra(EXTRA_CHAT, -1)
        if (intent.getBooleanExtra("clear", false)) {
            synchronized(lines) { lines.remove(account to chat) }
            done()
            return
        }
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()
        scope.launch {
            runCatching {
                val acc = TapeTape.core.account(account)
                if (intent.action == ACTION_REPLY && !text.isNullOrBlank()) {
                    acc.sendText(chat, text, null)
                    acc.sendPending()
                    Logx.i("notification reply sent acc=$account chat=$chat")
                }
                acc.markRead(chat)
                Logx.i("notification mark read acc=$account chat=$chat")
            }.onFailure { Logx.w("notification action failed", it) }
            cancel(context, account, chat)
            done()
        }
    }
}

class NotifyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        TapeTape.scope.launch {
            TapeTape.awaitReady()
            Notifier.handle(context, intent) { pending.finish() }
        }
    }
}
