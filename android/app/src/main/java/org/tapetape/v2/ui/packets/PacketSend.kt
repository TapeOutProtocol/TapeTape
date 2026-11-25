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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import org.tapetape.v2.ui.wallet.FEE_CHANGED
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tapetape.v2.Logx
import org.tapetape.v2.R
import org.tapetape.v2.TapeTape
import org.tapetape.v2.core.SendEstimate
import org.tapetape.v2.core.Token
import org.tapetape.v2.ui.LocalNav
import org.tapetape.v2.ui.common.ProgressDialog
import org.tapetape.v2.ui.common.TapeTapeDialog
import org.tapetape.v2.ui.common.TapeTapeToolbar
import org.tapetape.v2.ui.common.ToolbarIcon
import org.tapetape.v2.ui.common.coreQuery
import org.tapetape.v2.ui.common.toast
import org.tapetape.v2.ui.relayAddress
import org.tapetape.v2.ui.theme.LocalTapeTapeColors
import org.tapetape.v2.ui.wallet.fmtAmount
import org.tapetape.v2.ui.wallet.sendFailure
import org.tapetape.v2.ui.wallet.walletError
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val TransferGreen = Color(0xFF17C660)
private val PacketCoral = Color(0xFFFF6B5A)

private class SendColors(val page: Color, val card: Color, val text: Color, val hint: Color, val divider: Color)

@Composable
private fun sendColors(ios: Boolean): SendColors {
    val c = LocalTapeTapeColors.current
    return if (c.dark) SendColors(Color.Black, Color(0xFF1C1C1E), Color.White, Color(0xFF8E8E93), Color(0xFF38383A))
    else SendColors(if (ios) Color(0xFFF2F2F7) else Color(0xFFF2F2F2), Color.White, Color.Black, Color(0xFF8E8E93), Color(0xFFE5E5EA))
}

private fun cleanAmount(v: String): String {
    val digits = v.filter { it.isDigit() || it == '.' }
    val dot = digits.indexOf('.')
    return if (dot < 0) digits else digits.substring(0, dot + 1) + digits.substring(dot + 1).replace(".", "")
}

@Composable
private fun rememberPacketEstimate(tokenId: String, amount: String, count: Int, refresh: Int, transferChat: Int? = null): SendEstimate? {
    val account = TapeTape.account ?: return null
    val e by produceState<SendEstimate?>(null, tokenId, amount, count, refresh, transferChat) {
        delay(350)
        value = runCatching {
            if (transferChat != null) account.estimateTransfer(transferChat, tokenId, amount.ifBlank { "0" })
            else account.estimatePacket(tokenId, amount.ifBlank { "0" }, count)
        }
            .onFailure { Logx.w("estimate packet failed", it) }.getOrNull()
    }
    return e
}

@Composable
private fun TokenPicker(tokens: List<Token>, onPick: (Token) -> Unit, onDismiss: () -> Unit) {
    val c = LocalTapeTapeColors.current
    TapeTapeDialog(onDismiss, title = stringResource(R.string.select_token), dismiss = stringResource(R.string.cancel) to onDismiss) {
        Column {
            tokens.forEach { t ->
                Text(
                    t.symbol, fontSize = 17.sp, color = c.text,
                    modifier = Modifier.fillMaxWidth().clickable { onPick(t) }.padding(horizontal = 24.dp, vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun IconSquare(color: Color, glyph: String?, size: Dp = 24.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(5.dp)).background(color), contentAlignment = Alignment.Center) {
        if (glyph != null) Text(glyph, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SuffixInput(
    value: String, onChange: (String) -> Unit, hint: String, suffix: String, suffixSize: Int,
    keyboard: KeyboardType, sc: SendColors, modifier: Modifier = Modifier,
) {
    BasicTextField(
        value, onChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        textStyle = TextStyle(fontSize = 16.sp, color = sc.text, textAlign = TextAlign.End),
        cursorBrush = SolidColor(sc.text),
        modifier = modifier,
        decorationBox = { inner ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    if (value.isEmpty()) Text(hint, fontSize = 16.sp, color = sc.hint, textAlign = TextAlign.End)
                    inner()
                }
                Text(suffix, fontSize = suffixSize.sp, color = sc.hint, modifier = Modifier.padding(start = 10.dp))
            }
        },
    )
}

@Composable
private fun PlainInput(value: String, onChange: (String) -> Unit, hint: String, color: Color, hintColor: Color, modifier: Modifier = Modifier) {
    BasicTextField(
        value, onChange,
        singleLine = true,
        textStyle = TextStyle(fontSize = 16.sp, color = color),
        cursorBrush = SolidColor(color),
        modifier = modifier,
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(hint, fontSize = 16.sp, color = hintColor)
                inner()
            }
        },
    )
}

@Composable
private fun BigButton(text: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(50.dp)
            .shadow(2.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SendTransferScreen(chatId: Int) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val account = TapeTape.account ?: return
    val sc = sendColors(true)
    val tokens = remember { TapeTape.core.walletTokens().filter { it.packets } }
    val chat by coreQuery(null, chatId) { account.chat(chatId) }
    val contact by coreQuery(null, chat?.contactId) { chat?.contactId?.let { account.contact(it) } }
    var tokenId by rememberSaveable { mutableStateOf(tokens.first().id) }
    var amount by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val token = tokens.first { it.id == tokenId }
    var refresh by remember { mutableIntStateOf(0) }
    val e = rememberPacketEstimate(tokenId, amount, 1, refresh, transferChat = chatId)

    Column(Modifier.fillMaxSize().background(sc.page)) {
        TapeTapeToolbar(stringResource(R.string.send_transfer), onNav = { nav.pop() })
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Column(
                Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(sc.card)
                    .padding(start = 16.dp),
            ) {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.transfer_to, chat?.name.orEmpty()), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = sc.text)
                Spacer(Modifier.height(6.dp))
                Text(contact?.let { relayAddress(it.account) }.orEmpty(), fontSize = 13.sp, color = sc.hint, modifier = Modifier.padding(end = 132.dp))
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(sc.divider))
                Text(stringResource(R.string.v2_transfer_refund_hint), fontSize = 14.sp, color = sc.hint, modifier = Modifier.padding(vertical = 16.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(sc.divider))
                Row(Modifier.fillMaxWidth().height(50.dp).clickable { picking = true }.padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    EmojiSquare(Color(0xFF007AFF), "💎")
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.transfer_token), fontSize = 16.sp, color = sc.text, modifier = Modifier.weight(1f))
                    Text("${token.symbol} (${e?.let { fmtAmount(it.balance) } ?: "..."})", fontSize = 16.sp, color = sc.hint)
                    Image(painterResource(R.drawable.ic_chevron_right), null, Modifier.padding(start = 12.dp).size(18.dp), colorFilter = ColorFilter.tint(sc.hint))
                }
                Box(Modifier.padding(start = 36.dp).fillMaxWidth().height(1.dp).background(sc.divider))
                Row(Modifier.fillMaxWidth().height(50.dp).padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    EmojiSquare(Color(0xFF34C759), "💰")
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.transfer_amount), fontSize = 16.sp, color = sc.text)
                    SuffixInput(amount, { amount = cleanAmount(it) }, "0.0", token.symbol, 15, KeyboardType.Decimal, sc, Modifier.weight(1f).padding(start = 12.dp))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(sc.divider))
                PlainInput(
                    note, { note = it.take(60) }, stringResource(R.string.transfer_note_placeholder), sc.text, sc.hint.copy(alpha = 0.6f),
                    Modifier.fillMaxWidth().padding(end = 16.dp, top = 16.dp, bottom = 18.dp),
                )
            }
            Spacer(Modifier.height(32.dp))
            Text(
                (if (amount.isBlank()) "0.00" else amount) + " " + token.symbol,
                fontSize = 48.sp, fontWeight = FontWeight.Bold, color = sc.text, textAlign = TextAlign.Center, lineHeight = 54.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(32.dp))
            BigButton(stringResource(R.string.transfer_send_button), TransferGreen, Modifier.padding(horizontal = 40.dp).fillMaxWidth()) {
                val error = e?.error
                when {
                    e == null -> Unit
                    error != null -> toast(context, walletError(context, error, e.feeSymbol, R.string.transfer_insufficient_balance))
                    else -> confirm = true
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.v2_estimated_gas_fee) + " ~" + (e?.let { fmtAmount(it.fee, 8) + " " + it.feeSymbol } ?: "..."),
                fontSize = 13.sp, color = sc.hint, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (picking) TokenPicker(tokens, { tokenId = it.id; amount = ""; picking = false }) { picking = false }
    if (confirm) {
        TapeTapeDialog(
            { confirm = false },
            title = stringResource(R.string.confirm_send_transfer),
            message = stringResource(R.string.wallet_withdraw_confirm_message, amount, token.symbol, contact?.address?.substringBefore("@").orEmpty()),
            dismiss = stringResource(R.string.cancel) to { confirm = false },
            confirm = stringResource(R.string.ok) to {
                confirm = false
                sending = true
                Logx.i("send transfer $tokenId")
                val fee = e?.fee
                TapeTape.scope.launch {
                    runCatching { account.sendTransfer(chatId, tokenId, amount, note.trim(), fee) }
                        .onSuccess {
                            Logx.i("transfer sent msg=$it")
                            toast(context, context.getString(R.string.transfer_send_success))
                            nav.pop()
                        }
                        .onFailure {
                            Logx.w("send transfer failed", it)
                            if (it.message == FEE_CHANGED) refresh++
                            failure = sendFailure(context, it.message, e?.feeSymbol ?: "BNB", R.string.transfer_insufficient_balance)
                        }
                    sending = false
                }
            },
        )
    }
    if (sending) ProgressDialog(stringResource(R.string.v2_transfer_sending))
    failure?.let { TapeTapeDialog({ failure = null }, message = it, confirm = stringResource(R.string.ok) to { failure = null }) }
}

@Composable
private fun EmojiSquare(color: Color, emoji: String) {
    Box(Modifier.size(24.dp).clip(RoundedCornerShape(5.dp)).background(color), contentAlignment = Alignment.Center) {
        Text(emoji, fontSize = 13.sp)
    }
}

@Composable
fun SendRedPacketScreen(chatId: Int) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val account = TapeTape.account ?: return
    val sc = sendColors(false)
    val c = LocalTapeTapeColors.current
    val tokens = remember { TapeTape.core.walletTokens().filter { it.packets } }
    val chat by coreQuery(null, chatId) { account.chat(chatId) }
    var tokenId by rememberSaveable { mutableStateOf(tokens.first().id) }
    var amount by rememberSaveable { mutableStateOf("") }
    var countText by rememberSaveable { mutableStateOf("") }
    var greeting by rememberSaveable { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val token = tokens.first { it.id == tokenId }
    val count = countText.toIntOrNull() ?: 0
    var refresh by remember { mutableIntStateOf(0) }
    val e = rememberPacketEstimate(tokenId, amount, count.coerceAtLeast(1), refresh)
    val toolbarBg = if (c.dark) Color(0xFF1C1C1E) else Color.White

    Column(Modifier.fillMaxSize().background(sc.page)) {
        Box(Modifier.fillMaxWidth().background(Color.Black).statusBarsPadding())
        Box(Modifier.fillMaxWidth().shadow(2.dp).background(toolbarBg).height(56.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.align(Alignment.CenterStart).padding(start = 4.dp)) {
                ToolbarIcon(R.drawable.ic_arrow_back_white_24dp, { nav.pop() }, tint = sc.text)
            }
            Text(stringResource(R.string.send_red_packet), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = sc.text)
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(start = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.red_packet_lucky), fontSize = 16.sp, color = Color(0xFFE6922E))
                Image(painterResource(R.drawable.ic_dropdown), null, Modifier.padding(start = 4.dp).size(18.dp), colorFilter = ColorFilter.tint(Color(0xFFE6922E)))
            }
            Text(
                stringResource(R.string.red_packet_group_members, chat?.memberCount ?: 0), fontSize = 12.sp, color = sc.hint,
                modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 8.dp),
            )
            Column(Modifier.fillMaxWidth().background(sc.card)) {
                Row(packetRow { picking = true }, verticalAlignment = Alignment.CenterVertically) {
                    IconSquare(Color(0xFF3498DB), null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.transfer_token), fontSize = 16.sp, color = sc.text, modifier = Modifier.weight(1f))
                    Text("${token.symbol} (${e?.let { fmtAmount(it.balance) } ?: "..."})", fontSize = 16.sp, color = sc.hint)
                    Image(painterResource(R.drawable.ic_chevron_right), null, Modifier.padding(start = 12.dp).size(18.dp), colorFilter = ColorFilter.tint(sc.hint))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(sc.divider))
                Row(packetRow(), verticalAlignment = Alignment.CenterVertically) {
                    IconSquare(PacketCoral, null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.red_packet_count_label), fontSize = 16.sp, color = sc.text)
                    SuffixInput(
                        countText, { v -> countText = v.filter { it.isDigit() }.take(4) }, "0", stringResource(R.string.red_packet_count_unit), 18,
                        KeyboardType.Number, sc, Modifier.weight(1f).padding(start = 12.dp),
                    )
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(sc.divider))
                Row(packetRow(), verticalAlignment = Alignment.CenterVertically) {
                    IconSquare(Color(0xFFE6922E), stringResource(R.string.red_packet_lucky_icon))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.red_packet_total_amount, token.symbol), fontSize = 16.sp, color = sc.text)
                    SuffixInput(amount, { amount = cleanAmount(it) }, "0.0", token.symbol, 15, KeyboardType.Decimal, sc, Modifier.weight(1f).padding(start = 12.dp))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(sc.divider))
                Row(packetRow(), verticalAlignment = Alignment.CenterVertically) {
                    PlainInput(greeting, { greeting = it.take(60) }, stringResource(R.string.red_packet_greeting_default), sc.text, sc.hint, Modifier.weight(1f))
                    Text(stringResource(R.string.emoji_smile), fontSize = 22.sp)
                }
            }
            Spacer(Modifier.height(32.dp))
            Text(
                (if (amount.isBlank()) "0" else amount) + " " + token.symbol,
                fontSize = 48.sp, fontWeight = FontWeight.Bold, color = sc.text, textAlign = TextAlign.Center, lineHeight = 54.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(32.dp))
            BigButton(stringResource(R.string.red_packet_send_button).uppercase(), PacketCoral, Modifier.padding(horizontal = 64.dp).fillMaxWidth()) {
                val error = if (count < 1) "invalid-count" else e?.error
                when {
                    error != null -> toast(context, walletError(context, error, e?.feeSymbol ?: "BNB", R.string.red_packet_insufficient_balance))
                    e == null -> Unit
                    else -> confirm = true
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.v2_estimated_gas_fee) + " ~" + (e?.let { fmtAmount(it.fee, 8) + " " + it.feeSymbol } ?: "..."),
                fontSize = 13.sp, color = sc.hint, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.v2_red_packet_refund_notice), fontSize = 12.sp, color = sc.hint, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }

    if (picking) TokenPicker(tokens, { tokenId = it.id; amount = ""; picking = false }) { picking = false }
    if (confirm) {
        TapeTapeDialog(
            { confirm = false },
            title = stringResource(R.string.confirm_send_red_packet),
            message = stringResource(R.string.red_packet_confirm_send, amount, token.symbol, count),
            dismiss = stringResource(R.string.cancel) to { confirm = false },
            confirm = stringResource(R.string.ok) to {
                confirm = false
                sending = true
                Logx.i("send red packet $tokenId count=$count")
                val fee = e?.fee
                TapeTape.scope.launch {
                    runCatching { account.sendRedPacket(chatId, tokenId, amount, count, greeting.trim(), fee) }
                        .onSuccess {
                            Logx.i("red packet sent msg=$it")
                            toast(context, context.getString(R.string.red_packet_send_success))
                            nav.pop()
                        }
                        .onFailure {
                            Logx.w("send red packet failed", it)
                            if (it.message == FEE_CHANGED) refresh++
                            failure = sendFailure(context, it.message, e?.feeSymbol ?: "BNB", R.string.red_packet_insufficient_balance)
                        }
                    sending = false
                }
            },
        )
    }
    if (sending) ProgressDialog(stringResource(R.string.red_packet_sending))
    failure?.let { TapeTapeDialog({ failure = null }, message = it, confirm = stringResource(R.string.ok) to { failure = null }) }
}

private fun packetRow(onClick: (() -> Unit)? = null): Modifier =
    Modifier.fillMaxWidth().heightIn(min = 50.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 12.dp)
