package org.tapetape.v2.ui.packets

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tapetape.v2.Logx
import org.tapetape.v2.R
import org.tapetape.v2.TapeTape
import org.tapetape.v2.core.Message
import org.tapetape.v2.core.Packet
import org.tapetape.v2.core.PacketKind
import org.tapetape.v2.core.PacketState
import org.tapetape.v2.ui.Navigator
import org.tapetape.v2.ui.Screen
import org.tapetape.v2.ui.chat.Footer
import org.tapetape.v2.ui.common.avatarColor
import org.tapetape.v2.ui.wallet.fmtAmount

val Coral = Color(0xFFE17055)
val Gold = Color(0xFFFDCB6E)
val TransferOrange = Color(0xFFFA9E38)
val ClaimedBeige = Color(0xFFF5E5D3)
val ClaimedBrown = Color(0xFF996644)

private const val REFRESH_MS = 15_000L

fun openPacket(nav: Navigator, m: Message) {
    val p = m.packet ?: return
    if (p.status.state == PacketState.Creating) return
    Logx.d("open packet ${m.id} ${p.kind} ${p.status.state}")
    nav.push(
        when {
            p.kind == PacketKind.Transfer -> Screen.TransferInfo(m.id)
            p.status.canClaim -> Screen.RedPacketClaim(m.id)
            else -> Screen.RedPacketDetail(m.id)
        },
    )
}

fun Packet.active() = status.state == PacketState.Creating ||
    (status.state == PacketState.Open && (status.canClaim || (mine && kind == PacketKind.Transfer)))

fun Packet.amountText() = fmtAmount(amount) + " " + symbol

fun Packet.statusText(context: Context): String? = when (status.state) {
    PacketState.Creating -> context.getString(R.string.sending)
    PacketState.Failed -> context.getString(if (mine) R.string.transaction_failed else R.string.v2_packet_invalid)
    PacketState.Expired -> context.getString(R.string.red_packet_expired)
    PacketState.Refunded -> context.getString(R.string.v2_packet_refunded)
    PacketState.Finished -> when {
        kind == PacketKind.Transfer -> context.getString(if (mine) R.string.transfer_received_by_other else R.string.transfer_received_by_me)
        status.myAmount != null -> context.getString(R.string.red_packet_already_claimed)
        else -> context.getString(R.string.red_packet_all_claimed)
    }
    PacketState.Open -> when {
        !mine && status.checkedAt == 0L -> context.getString(R.string.v2_packet_checking)
        kind == PacketKind.Transfer -> context.getString(if (mine) R.string.transfer_pending else if (status.canClaim) R.string.transfer_receive else R.string.v2_not_claimable)
        status.myAmount != null -> context.getString(R.string.red_packet_already_claimed)
        else -> null
    }
}

suspend fun refreshPacket(msgId: Int) {
    val account = TapeTape.account ?: return
    runCatching { account.packetStatus(msgId) }
        .onSuccess { Logx.d("packet $msgId status ${it.state}") }
        .onFailure { Logx.w("packet status failed", it) }
}

@Composable
fun PacketBubble(m: Message, p: Packet, out: Boolean, isGroup: Boolean) {
    LaunchedEffect(m.id, p.status.state) {
        val stale = System.currentTimeMillis() - p.status.checkedAt > REFRESH_MS
        val live = p.status.state in setOf(PacketState.Creating, PacketState.Open, PacketState.Expired)
        if (live && stale) refreshPacket(m.id)
    }
    Column(horizontalAlignment = if (out) Alignment.End else Alignment.Start) {
        if (isGroup && !out) {
            Text(m.senderName, color = avatarColor(m.senderName), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
        }
        if (p.kind == PacketKind.Transfer) TransferCard(p) else RedPacketCard(p)
        Footer(m, out, Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp), onImage = false)
    }
}

@Composable
private fun TransferCard(p: Packet) {
    val context = LocalContext.current
    val active = p.active()
    val fg = if (active) Color.White else Color(0xFF8E8E93)
    Column(
        Modifier
            .width(280.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) TransferOrange else Color(0xFFEDE3D6))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(50.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                if (active) Image(painterResource(R.drawable.ic_arrow_right), null, Modifier.size(28.dp))
                else Image(painterResource(R.drawable.ic_check_circle), null, Modifier.size(30.dp), colorFilter = ColorFilter.tint(Color(0xFFC9B79C)))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.amountText(), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (p.text.isNotBlank()) Text(p.text, fontSize = 11.sp, color = fg.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                p.statusText(context)?.let { Text(it, fontSize = 12.sp, color = fg) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(fg.copy(alpha = 0.4f)))
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.transfer), fontSize = 12.sp, color = fg)
    }
}

@Composable
private fun RedPacketCard(p: Packet) {
    val context = LocalContext.current
    val active = p.active()
    val fg = if (active) Color.White else ClaimedBrown
    val label = if (p.symbol == "BNB") stringResource(R.string.red_packet) else stringResource(R.string.red_packet) + " · " + p.symbol
    Row(
        Modifier
            .width(320.dp)
            .height(80.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (active) Modifier.background(Brush.linearGradient(listOf(Coral, Gold)))
                else Modifier.background(ClaimedBeige),
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(R.drawable.ic_gift), null, Modifier.size(40.dp), colorFilter = ColorFilter.tint(if (active) Color.White else Color(0xFFB39977)))
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.Center) {
            Text(
                p.text.ifBlank { stringResource(R.string.red_packet_greeting_default) },
                fontSize = 16.sp, fontWeight = FontWeight.Bold, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            p.statusText(context)?.let { Text(it, fontSize = 12.sp, color = fg.copy(alpha = 0.9f), maxLines = 1) }
            Text(label, fontSize = 12.sp, color = fg.copy(alpha = 0.85f), maxLines = 1)
        }
    }
}

@Composable
fun PacketClaimedPill(text: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(ClaimedBeige)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(R.drawable.ic_gift), null, Modifier.size(14.dp), colorFilter = ColorFilter.tint(Color(0xFFE17055)))
            Spacer(Modifier.width(5.dp))
            Text(text, fontSize = 13.sp, color = ClaimedBrown)
        }
    }
}
