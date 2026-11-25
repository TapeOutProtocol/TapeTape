package org.tapetape.v2.ui.chat

import android.content.Context
import org.tapetape.v2.R
import org.tapetape.v2.TapeTape
import org.tapetape.v2.core.Message
import org.tapetape.v2.core.SystemMsg

private fun person(context: Context, contactId: Int?, name: String): String {
    if (contactId == null) return context.getString(R.string.self)
    val address = runCatching { TapeTape.account?.contact(contactId)?.account }.getOrNull()?.let { org.tapetape.v2.ui.relayAddress(it) }
    return if (address.isNullOrBlank()) name else "$name ($address)"
}

fun systemText(context: Context, m: Message): String {
    val actor = m.fromContact?.let { person(context, it, m.senderName) }
    fun pick(you: Int, other: Int, vararg args: String) =
        if (actor == null) context.getString(you, *args) else context.getString(other, *args, actor)
    return when (val s = m.system) {
        null -> m.text
        is SystemMsg.TimerChanged -> timerText(context, m)
        is SystemMsg.GroupCreated -> context.getString(R.string.chat_new_group_hint)
        is SystemMsg.GroupNameChanged ->
            if (s.oldName.isNotEmpty()) pick(R.string.group_name_changed_by_you, R.string.group_name_changed_by_other, s.oldName, s.name)
            else pick(R.string.v2_group_name_changed_by_you, R.string.v2_group_name_changed_by_other, s.name)
        SystemMsg.GroupAvatarChanged -> pick(R.string.group_image_changed_by_you, R.string.group_image_changed_by_other)
        is SystemMsg.MemberAdded -> pick(R.string.add_member_by_you, R.string.add_member_by_other, person(context, s.contactId, s.name))
        is SystemMsg.MemberRemoved -> pick(R.string.remove_member_by_you, R.string.remove_member_by_other, person(context, s.contactId, s.name))
        SystemMsg.MemberLeft -> pick(R.string.group_left_by_you, R.string.group_left_by_other)
        is SystemMsg.PacketClaimed -> packetClaimedText(context, s)
    }
}

private fun contactName(id: Int): String? = runCatching { TapeTape.account?.contact(id)?.name }.getOrNull()?.takeIf { it.isNotBlank() }

private fun packetClaimedText(context: Context, s: SystemMsg.PacketClaimed): String {
    val claimer = s.claimer?.let { contactName(it) ?: return context.getString(R.string.v2_red_packet_claimed_fallback) }
    val owner = s.owner?.let { contactName(it) ?: return context.getString(R.string.v2_red_packet_claimed_fallback) }
    return when {
        claimer == null && owner == null -> context.getString(R.string.v2_you_claimed_own_red_packet)
        claimer == null -> context.getString(R.string.v2_you_claimed_red_packet, owner)
        owner == null -> context.getString(R.string.v2_claimed_your_red_packet, claimer)
        else -> context.getString(R.string.red_packet_claim_notification, claimer, owner)
    }
}
