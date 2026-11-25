package org.tapetape.v2

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow

object Prefs {
    private lateinit var prefs: SharedPreferences
    val changes = MutableStateFlow(0)

    fun init(context: Context) {
        prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
    }

    private fun put(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit(action = block)
        changes.value++
    }

    var enterSends: Boolean
        get() = prefs.getBoolean("enter_sends", false)
        set(v) = put { putBoolean("enter_sends", v) }

    var sound: String?
        get() = prefs.getString("sound", null)
        set(v) = put { putString("sound", v) }

    var vibrate: Boolean
        get() = prefs.getBoolean("vibrate", true)
        set(v) = put { putBoolean("vibrate", v) }

    var led: Int
        get() = prefs.getInt("led", 0)
        set(v) = put { putInt("led", v) }

    var show: Int
        get() = prefs.getInt("show", 0)
        set(v) = put { putInt("show", v) }

    var priority: Int
        get() = prefs.getInt("priority", 1)
        set(v) = put { putInt("priority", v) }

    var inChatSounds: Boolean
        get() = prefs.getBoolean("in_chat_sounds", true)
        set(v) = put { putBoolean("in_chat_sounds", v) }

    var forceBackground: Boolean
        get() = prefs.getBoolean("force_background", false)
        set(v) = put { putBoolean("force_background", v) }

    fun chatSound(account: Int, chat: Int): String? = prefs.getString("sound_${account}_$chat", null)

    fun setChatSound(account: Int, chat: Int, uri: String?) = put {
        if (uri == null) remove("sound_${account}_$chat") else putString("sound_${account}_$chat", uri)
    }

    fun chatVibrate(account: Int, chat: Int): Int = prefs.getInt("vibrate_${account}_$chat", 0)

    fun setChatVibrate(account: Int, chat: Int, mode: Int) = put { putInt("vibrate_${account}_$chat", mode) }

    fun wallpaper(account: Int): String? = prefs.getString("wallpaper_$account", null)

    fun setWallpaper(account: Int, path: String?) = put {
        if (path == null) remove("wallpaper_$account") else putString("wallpaper_$account", path)
    }
}
