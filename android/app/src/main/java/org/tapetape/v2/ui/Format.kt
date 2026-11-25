package org.tapetape.v2.ui

import android.content.Context
import android.net.Uri
import android.text.format.DateFormat
import org.tapetape.v2.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

private fun localDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

private fun pattern(skeleton: String) = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton)

private fun format(skeleton: String, ms: Long) = DateFormat.format(pattern(skeleton), ms).toString()

private fun time(context: Context, ms: Long) = DateFormat.getTimeFormat(context).format(Date(ms))

fun listTime(context: Context, ms: Long): String {
    if (ms <= 0) return ""
    val d = localDate(ms)
    val today = LocalDate.now()
    return when {
        d == today -> time(context, ms)
        d > today.minusDays(7) -> format("EEE", ms)
        d.year == today.year -> format("MMMd", ms)
        else -> format("yMMMd", ms)
    }
}

fun bubbleTime(context: Context, ms: Long): String {
    val d = localDate(ms)
    val today = LocalDate.now()
    return when {
        d == today -> time(context, ms)
        d > today.minusDays(7) -> format("EEE", ms) + " " + time(context, ms)
        d.year == today.year -> format("MMMd", ms) + ", " + time(context, ms)
        else -> format("yMMMd", ms) + ", " + time(context, ms)
    }
}

fun dayHeader(context: Context, ms: Long): String {
    val d = localDate(ms)
    val today = LocalDate.now()
    return when {
        d == today -> context.getString(R.string.today)
        d == today.minusDays(1) -> context.getString(R.string.yesterday)
        d > today.minusDays(7) -> format("EEEE", ms)
        d.year == today.year -> format("EEEEMMMMd", ms)
        else -> format("yMMMMd", ms)
    }
}

fun sameDay(a: Long, b: Long) = localDate(a) == localDate(b)

data class Invite(val link: String, val account: String, val name: String, val group: String?)

val INVITE_HOSTS = setOf("i.tapetape.org")

val PROXY_SCHEMES = setOf("ss", "socks5", "socks5h")

fun parseInvite(text: String): Invite? {
    val uri = runCatching { Uri.parse(text.trim()) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.host !in INVITE_HOSTS) return null
    val params = uri.encodedFragment.orEmpty().split("&").mapNotNull {
        val i = it.indexOf('=')
        if (i > 0) it.substring(0, i) to Uri.decode(it.substring(i + 1)) else null
    }.toMap()
    val account = params["a"] ?: return null
    val name = params["n"].orEmpty().ifBlank { account.removePrefix("key:").substringBefore("@") }
    return Invite(text.trim(), account, name, params["g"]?.let { params["gn"].orEmpty() })
}

fun relayAddress(account: String) = account.removePrefix("key:").substringBeforeLast(":4246")
