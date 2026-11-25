package org.tapetape.v2.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import org.tapetape.v2.Logx

sealed interface Screen : java.io.Serializable {
    data object Welcome : Screen
    data object CreateProfile : Screen
    data object ImportProfile : Screen
    data object ChatList : Screen
    data class Chat(val id: Int, val replyTo: Int? = null) : Screen
    data object Contacts : Screen
    data object Qr : Screen
    data object Square : Screen
    data object Settings : Screen
    data object Profile : Screen
    data object Appearance : Screen
    data object ChatsSettings : Screen
    data object Blocked : Screen
    data object Archive : Screen
    data class Forward(val msgIds: List<Int>) : Screen
    data class ChatProfile(val chatId: Int) : Screen
    data class ShareContact(val contactId: Int) : Screen
    data object AllMedia : Screen
    data class NewGroup(val editChatId: Int? = null, val cloneFrom: Int? = null) : Screen
    data class GroupQr(val chatId: Int) : Screen
    data object NotificationSettings : Screen
    data object Wallpaper : Screen
    data object Connectivity : Screen
    data object Proxy : Screen
    data object Log : Screen
    data class Share(val text: String?, val files: List<String>, val mimes: List<String>, val chatId: Int? = null) : Screen
    data object AddProfile : Screen
    data object Receive : Screen
    data object Withdraw : Screen
    data class SendTransfer(val chatId: Int) : Screen
    data class SendRedPacket(val chatId: Int) : Screen
    data class TransferInfo(val msgId: Int) : Screen
    data class RedPacketClaim(val msgId: Int) : Screen
    data class RedPacketDetail(val msgId: Int) : Screen
    data class Shop(val url: String) : Screen
}

class Navigator(initial: List<Screen>) {
    val stack = mutableStateListOf(*initial.toTypedArray())
    var popping by mutableStateOf(false)
        private set
    internal var onRemoved: (Screen) -> Unit = {}

    val top: Screen get() = stack.last()

    fun push(screen: Screen) {
        Logx.d("nav push $screen")
        popping = false
        stack.add(screen)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        popping = true
        onRemoved(stack.removeAt(stack.lastIndex))
        return true
    }

    fun reset(screen: Screen) {
        Logx.d("nav reset $screen")
        popping = false
        stack.toList().forEach(onRemoved)
        stack.clear()
        stack.add(screen)
    }

    companion object {
        val Saver = listSaver<Navigator, Screen>({ it.stack.toList() }, { Navigator(it) })
    }

    fun replace(screen: Screen) {
        popping = false
        onRemoved(stack.removeAt(stack.lastIndex))
        stack.add(screen)
    }
}

val LocalNav = staticCompositionLocalOf<Navigator> { error("no navigator") }

private const val CHAT_ANIM_MS = 150

@Composable
fun NavHost(nav: Navigator, content: @Composable (Screen) -> Unit) {
    val holder = rememberSaveableStateHolder()
    nav.onRemoved = { holder.removeState(it.toString()) }
    BackHandler(nav.stack.size > 1) { nav.pop() }
    AnimatedContent(
        targetState = nav.top,
        transitionSpec = { transition(initialState, targetState, nav.popping) },
        label = "nav",
    ) { screen ->
        holder.SaveableStateProvider(screen.toString()) { content(screen) }
    }
}

private fun transition(from: Screen, to: Screen, popping: Boolean): ContentTransform {
    val spec = tween<Float>(CHAT_ANIM_MS, easing = LinearOutSlowInEasing)
    return when {
        !popping && to is Screen.Chat ->
            slideInHorizontally(tween(CHAT_ANIM_MS, easing = LinearOutSlowInEasing)) { it } togetherWith
                (scaleOut(spec, 0.85f) + fadeOut(spec, 0.6f))
        popping && from is Screen.Chat ->
            ContentTransform(
                scaleIn(spec, 0.85f) + fadeIn(spec, 0.6f),
                slideOutHorizontally(tween(CHAT_ANIM_MS, easing = LinearOutSlowInEasing)) { it },
                targetContentZIndex = -1f,
                sizeTransform = null,
            )
        else -> ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
    }
}
