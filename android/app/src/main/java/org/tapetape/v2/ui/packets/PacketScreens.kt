package org.tapetape.v2.ui.packets

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tapetape.v2.Logx
import org.tapetape.v2.R
import org.tapetape.v2.TapeTape
import org.tapetape.v2.core.Message
import org.tapetape.v2.core.Packet
import org.tapetape.v2.core.PacketState
import org.tapetape.v2.ui.LocalNav
import org.tapetape.v2.ui.Screen
import org.tapetape.v2.ui.common.Avatar
import org.tapetape.v2.ui.common.ProgressDialog
import org.tapetape.v2.ui.common.TapeTapeDialog
import org.tapetape.v2.ui.common.TapeTapeToolbar
import org.tapetape.v2.ui.common.coreQuery
import org.tapetape.v2.ui.common.toast
import org.tapetape.v2.ui.theme.LocalTapeTapeColors
import org.tapetape.v2.ui.wallet.fmtAmount
import org.tapetape.v2.ui.wallet.walletError
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.text.DateFormat
import java.util.Date

private fun dateTime(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

private fun Packet.remaining(): String =
    fmtAmount(((amount.toBigDecimalOrNull() ?: BigDecimal.ZERO) - (status.claimedAmount.toBigDecimalOrNull() ?: BigDecimal.ZERO)).toPlainString())

@Composable
private fun rememberPacketMessage(msgId: Int): Message? {
    val account = TapeTape.account ?: return null
    val m by coreQuery(null, msgId) { account.message(msgId) }
    LaunchedEffect(msgId) { refreshPacket(msgId) }
    return m
}

@Composable
private fun claimProgressText(): String {
    var step by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        delay(2500)
        step = 1
        delay(3500)
        step = 2
    }
    return stringResource(listOf(R.string.pending_confirmation, R.string.transaction_submitted, R.string.waiting_confirmation)[step])
}

@Composable
private fun ClaimProgress() = ProgressDialog(claimProgressText())

@Composable
private fun ownerName(m: Message, p: Packet): String? {
    val account = TapeTape.account ?: return m.senderName
    if (!p.mine) return m.senderName
    val info by coreQuery(account.info()) { account.info() }
    return info.name.ifBlank { null }
}

@Composable
private fun packetTitle(name: String?) = name?.let { stringResource(R.string.red_packet_sender_format, it) } ?: stringResource(R.string.red_packet)

@Composable
private fun RefundAction(msgId: Int, p: Packet, color: Color) {
    val context = LocalContext.current
    val account = TapeTape.account ?: return
    val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    Box(
        Modifier
            .padding(horizontal = 40.dp)
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .clickable { ask = true },
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.v2_refund), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
    if (ask) TapeTapeDialog(
        { ask = false },
        message = stringResource(R.string.v2_refund_confirm, p.remaining(), p.symbol),
        dismiss = stringResource(R.string.cancel) to { ask = false },
        confirm = stringResource(R.string.v2_refund) to {
            ask = false
            busy = true
            Logx.i("refund packet $msgId")
            scope.launch {
                runCatching { account.refundPacket(msgId) }
                    .onSuccess {
                        Logx.i("refund done $msgId")
                        toast(context, context.getString(R.string.v2_refund_done))
                        refreshPacket(msgId)
                    }
                    .onFailure {
                        Logx.w("refund failed", it)
                        failure = walletError(context, it.message, "BNB")
                    }
                busy = false
            }
        },
    )
    if (busy) ProgressDialog(stringResource(R.string.v2_refunding))
    failure?.let { TapeTapeDialog({ failure = null }, title = stringResource(R.string.error), message = it, confirm = stringResource(R.string.ok) to { failure = null }) }
}

@Composable
private fun ExpiryLine(p: Packet, color: Color) {
    val at = p.status.expiresAt ?: return
    if (p.status.state != PacketState.Open) return
    Text(stringResource(R.string.v2_expires_at, dateTime(at)), fontSize = 13.sp, color = color, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

@Composable
fun TransferInfoScreen(msgId: Int) {
    val nav = LocalNav.current
    val c = LocalTapeTapeColors.current
    val context = LocalContext.current
    val account = TapeTape.account ?: return
    val m = rememberPacketMessage(msgId)
    val p = m?.packet
    val scope = rememberCoroutineScope()
    var claiming by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val claim = p != null && p.status.canClaim && !p.mine
    val page = if (c.dark) Color.Black else Color(0xFFF2F2F7)
    val card = if (c.dark) Color(0xFF1C1C1E) else Color.White
    val grey = Color(0xFF8E8E93)

    Column(Modifier.fillMaxSize().background(page)) {
        TapeTapeToolbar(stringResource(if (claim) R.string.claim_transfer else R.string.transfer_detail), onNav = { nav.pop() })
        if (m == null || p == null) return@Column
        val s = p.status
        val (icon, tint, sentence) = when {
            s.state == PacketState.Failed -> Triple(R.drawable.ic_error, Color(0xFFFF3B30), p.statusText(context).orEmpty())
            s.state == PacketState.Refunded -> Triple(R.drawable.ic_time, grey, stringResource(R.string.v2_transfer_status_refunded))
            s.state == PacketState.Expired -> Triple(R.drawable.ic_time, grey, stringResource(R.string.v2_transfer_status_expired))
            s.state == PacketState.Finished -> Triple(
                R.drawable.ic_check_circle, Color(0xFF17C660),
                stringResource(if (p.mine) R.string.transfer_status_claimed_by_receiver else R.string.transfer_status_claimed_by_me),
            )
            p.mine -> Triple(R.drawable.ic_time, Color(0xFF007AFF), stringResource(R.string.transfer_status_sent_by_you))
            else -> Triple(R.drawable.ic_time, Color(0xFF007AFF), stringResource(R.string.transfer_waiting_for_you))
        }
        Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(card).padding(vertical = 28.dp, horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(painterResource(icon), null, Modifier.size(64.dp), colorFilter = ColorFilter.tint(tint))
                Spacer(Modifier.height(14.dp))
                Text(sentence, fontSize = 16.sp, color = c.text, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(p.amountText(), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = c.text, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                if (p.text.isNotBlank()) InfoRow(stringResource(R.string.transfer_note), p.text, grey, c.text)
                InfoRow(stringResource(R.string.transfer_time), dateTime(m.time), grey, c.text)
                Spacer(Modifier.height(8.dp))
                val chat by coreQuery(null, m.chatId) { account.chat(m.chatId) }
                Text(
                    if (p.mine) stringResource(R.string.transfer_to, chat?.name.orEmpty())
                    else stringResource(R.string.transfer_from, m.senderName),
                    fontSize = 14.sp, color = grey,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (claim) {
                Box(
                    Modifier.padding(horizontal = 40.dp, vertical = 8.dp).fillMaxWidth().height(50.dp)
                        .clip(RoundedCornerShape(8.dp)).background(Color(0xFF17C660))
                        .clickable {
                            claiming = true
                            Logx.i("claim transfer $msgId")
                            scope.launch {
                                runCatching { account.claimPacket(msgId) }
                                    .onSuccess {
                                        Logx.i("transfer claimed $msgId")
                                        toast(context, context.getString(R.string.transfer_claimed, fmtAmount(it), p.symbol))
                                    }
                                    .onFailure {
                                        Logx.w("claim transfer failed", it)
                                        failure = walletError(context, it.message, "BNB")
                                    }
                                claiming = false
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.transfer_receive), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (s.canRefund && p.mine) RefundAction(msgId, p, Color(0xFFFA9E38))
            Spacer(Modifier.height(12.dp))
            ExpiryLine(p, grey)
            if (s.state == PacketState.Open) {
                Text(
                    stringResource(R.string.v2_transfer_refund_notice), fontSize = 12.sp, color = grey, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
    }
    if (claiming) ClaimProgress()
    failure?.let { TapeTapeDialog({ failure = null }, title = stringResource(R.string.error), message = it, confirm = stringResource(R.string.ok) to { failure = null }) }
}

@Composable
private fun InfoRow(label: String, value: String, labelColor: Color, valueColor: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, fontSize = 14.sp, color = labelColor)
        Spacer(Modifier.width(16.dp))
        Text(value, fontSize = 14.sp, color = valueColor, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

@Composable
fun RedPacketClaimScreen(msgId: Int) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val account = TapeTape.account ?: return
    val m = rememberPacketMessage(msgId)
    val p = m?.packet
    val scope = rememberCoroutineScope()
    var claiming by remember { mutableStateOf(false) }
    var got by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val avatar = remember(m?.fromContact) { m?.fromContact?.let { runCatching { account.contact(it).avatarPath }.getOrNull() } }

    got?.let {
        LaunchedEffect(it) {
            delay(1500)
            nav.replace(Screen.RedPacketDetail(msgId))
        }
    }

    Column(Modifier.fillMaxSize().background(Coral)) {
        TapeTapeToolbar(null, onNav = { nav.pop() }, background = Coral)
        if (m == null || p == null) return@Column
        val name = ownerName(m, p)
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))
            Avatar(name.orEmpty(), avatar, 40.dp)
            Spacer(Modifier.height(10.dp))
            Text(packetTitle(name), fontSize = 18.sp, color = Color.White, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(16.dp))
            Text(p.text.ifBlank { stringResource(R.string.red_packet_greeting_default) }, fontSize = 22.sp, color = Gold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(64.dp))
            val amount = got
            when {
                amount != null -> {
                    Text(stringResource(R.string.you_received), fontSize = 18.sp, color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Text(fmtAmount(amount) + " " + p.symbol, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Gold)
                }
                p.status.canClaim -> Box(
                    Modifier.size(150.dp).clip(CircleShape).background(Gold).clickable(enabled = !claiming) {
                        claiming = true
                        Logx.i("claim red packet $msgId")
                        scope.launch {
                            runCatching { account.claimPacket(msgId) }
                                .onSuccess {
                                    Logx.i("red packet claimed $msgId")
                                    got = it
                                }
                                .onFailure {
                                    Logx.w("claim red packet failed", it)
                                    failure = walletError(context, it.message, "BNB")
                                }
                            claiming = false
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.red_packet_open), fontSize = 48.sp, color = Coral, fontWeight = FontWeight.Bold)
                }
                else -> {
                    Text(p.statusText(context) ?: stringResource(R.string.v2_not_claimable), fontSize = 18.sp, color = Color.White)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.red_packet_view_detail), fontSize = 15.sp, color = Gold,
                        modifier = Modifier.clickable { nav.replace(Screen.RedPacketDetail(msgId)) }.padding(8.dp),
                    )
                }
            }
            Spacer(Modifier.height(48.dp))
            ExpiryLine(p, Color.White.copy(alpha = 0.8f))
        }
    }
    if (claiming) ClaimProgress()
    failure?.let { TapeTapeDialog({ failure = null }, title = stringResource(R.string.error), message = it, confirm = stringResource(R.string.ok) to { failure = null }) }
}

private val hexColors = listOf(0xFFE17055, 0xFF3498DB, 0xFF27AE60, 0xFF9B59B6, 0xFFE6922E, 0xFF16A085, 0xFFD35400, 0xFF2C3E50)

private fun shortAddress(a: String) = if (a.length > 12) a.take(6) + "..." + a.takeLast(4) else a

@Composable
fun RedPacketDetailScreen(msgId: Int) {
    val nav = LocalNav.current
    val c = LocalTapeTapeColors.current
    val account = TapeTape.account ?: return
    val m by coreQuery(null, msgId) { account.message(msgId) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        failed = runCatching { account.packetStatus(msgId) }.onFailure { Logx.w("packet detail refresh failed", it) }.isFailure
    }
    val p = m?.packet
    val grey = Color(0xFF8E8E93)

    Column(Modifier.fillMaxSize().background(c.background)) {
        TapeTapeToolbar(stringResource(R.string.red_packet_detail), onNav = { nav.pop() }, background = Coral)
        val msg = m
        if (msg == null || p == null) return@Column
        val s = p.status
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStartPercent = 50, bottomEndPercent = 50)).background(Coral)
                    .padding(top = 12.dp, bottom = 36.dp, start = 24.dp, end = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(packetTitle(ownerName(msg, p)), fontSize = 18.sp, color = Color.White, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text(p.text.ifBlank { stringResource(R.string.red_packet_greeting_default) }, fontSize = 15.sp, color = Gold, textAlign = TextAlign.Center)
                s.myAmount?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(fmtAmount(it) + " " + p.symbol, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(
                    R.string.red_packet_summary, s.claimedCount, s.totalCount,
                    fmtAmount(s.claimedAmount) + " " + p.symbol, fmtAmount(p.amount) + " " + p.symbol,
                ),
                fontSize = 14.sp, color = grey, modifier = Modifier.padding(horizontal = 16.dp),
            )
            p.statusText(LocalContext.current)?.takeIf { s.state != PacketState.Open }?.let {
                Text(it, fontSize = 14.sp, color = Coral, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.red_packet_claim_records), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            s.records.forEach { r ->
                val hex = r.address.removePrefix("0x").take(2).ifBlank { "??" }
                val color = Color(hexColors[Math.floorMod(r.address.lowercase().hashCode(), hexColors.size)])
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                        Text(hex.uppercase(), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(shortAddress(r.address), fontSize = 15.sp, color = c.text)
                    if (r.me) Text(" " + stringResource(R.string.red_packet_me_suffix).trim(), fontSize = 15.sp, color = Color(0xFFE6922E))
                    Spacer(Modifier.weight(1f))
                    Text(fmtAmount(r.amount) + " " + p.symbol, fontSize = 15.sp, color = c.text)
                }
            }
            Spacer(Modifier.height(20.dp))
            if (s.canRefund && p.mine) {
                RefundAction(msgId, p, Coral)
                Spacer(Modifier.height(12.dp))
            }
            ExpiryLine(p, grey)
            Text(
                stringResource(R.string.v2_red_packet_refund_notice), fontSize = 12.sp, color = grey, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
    if (failed) TapeTapeDialog(
        { failed = false },
        title = stringResource(R.string.error),
        message = stringResource(R.string.v2_packet_load_failed),
        dismiss = stringResource(R.string.cancel) to { failed = false },
        confirm = stringResource(R.string.retry) to {
            failed = false
            attempt++
        },
    )
}
