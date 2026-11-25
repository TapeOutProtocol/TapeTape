package org.tapetape.v2.ui.wallet

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tapetape.v2.Logx
import org.tapetape.v2.R
import org.tapetape.v2.TapeTape
import org.tapetape.v2.core.SendEstimate
import org.tapetape.v2.core.Token
import org.tapetape.v2.core.TxStatus
import org.tapetape.v2.core.WalletTx
import org.tapetape.v2.ui.LocalNav
import org.tapetape.v2.ui.Screen
import org.tapetape.v2.ui.common.MenuItem
import org.tapetape.v2.ui.common.ProgressDialog
import org.tapetape.v2.ui.common.TapeTapeDialog
import org.tapetape.v2.ui.common.TapeTapeMenu
import org.tapetape.v2.ui.common.TapeTapeToolbar
import org.tapetape.v2.ui.common.UnderlineField
import org.tapetape.v2.ui.common.copyToClipboard
import org.tapetape.v2.ui.qr.qrBitmap
import org.tapetape.v2.ui.theme.FailedRed
import org.tapetape.v2.ui.theme.LocalTapeTapeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.math.RoundingMode

val WalletBlue = Color(0xFF42A5F5)

fun fmtAmount(value: String?, scale: Int = 5): String {
    val v = value?.toBigDecimalOrNull() ?: return "--"
    val r = v.setScale(scale, RoundingMode.DOWN).stripTrailingZeros()
    return if (r.signum() == 0) "0" else r.toPlainString()
}

const val FEE_CHANGED = "fee-changed"

fun sendFailure(context: Context, code: String?, feeSymbol: String, balanceRes: Int = R.string.wallet_insufficient_balance): String {
    val text = walletError(context, code, feeSymbol, balanceRes)
    return if (code == FEE_CHANGED) text else context.getString(R.string.v2_transaction_failed_with, text)
}

fun walletError(context: Context, code: String?, feeSymbol: String = "BNB", balanceRes: Int = R.string.wallet_insufficient_balance): String = when (code) {
    null -> context.getString(R.string.error)
    "invalid-address" -> context.getString(R.string.wallet_invalid_address)
    "invalid-amount" -> context.getString(R.string.wallet_invalid_amount)
    "invalid-count" -> context.getString(R.string.red_packet_invalid_count)
    "insufficient-balance" -> context.getString(balanceRes)
    "insufficient-gas" -> context.getString(R.string.v2_insufficient_gas, feeSymbol)
    "amount-too-small" -> context.getString(R.string.amount_per_packet_too_small)
    "fee-too-high" -> context.getString(R.string.v2_packet_fee_too_high)
    "already-claimed" -> context.getString(R.string.red_packet_already_claimed)
    "finished" -> context.getString(R.string.red_packet_all_claimed)
    "expired" -> context.getString(R.string.red_packet_expired)
    "refunded" -> context.getString(R.string.v2_packet_refunded)
    "not-claimable" -> context.getString(R.string.v2_not_claimable)
    "not-refundable" -> context.getString(R.string.v2_not_refundable)
    "tx-failed" -> context.getString(R.string.transaction_failed)
    "tx-timeout" -> context.getString(R.string.transaction_timeout)
    FEE_CHANGED -> context.getString(R.string.v2_fee_changed)
    else -> code
}

fun nativeSymbol(token: Token?): String =
    TapeTape.core.walletTokens().firstOrNull { it.chainId == token?.chainId && it.address == null }?.symbol ?: "BNB"

@Composable
fun WalletCard(cardBg: Color) {
    val nav = LocalNav.current
    val c = LocalTapeTapeColors.current
    val account = TapeTape.account ?: return
    val tokens = remember { TapeTape.core.walletTokens() }
    var balances by remember { mutableStateOf<Map<String, String?>?>(null) }
    LaunchedEffect(account) {
        val loaded = withTimeoutOrNull(30_000) { runCatching { account.balances() }.onFailure { Logx.w("balances failed", it) }.getOrNull() }
        Logx.d("balances loaded ${loaded?.size}")
        balances = loaded.orEmpty().associate { it.tokenId to it.amount }
    }
    org.tapetape.v2.ui.settings.SettingsCard(cardBg) {
        tokens.groupBy { it.chainName }.entries.forEachIndexed { i, (chain, list) ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.divider))
            Text(
                chain,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = c.hint,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp),
            )
            list.forEach { t ->
                Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t.symbol, fontSize = 16.sp, color = c.text, modifier = Modifier.weight(1f))
                    val value = balances?.let { b -> fmtAmount(b[t.id]) } ?: "…"
                    Text(value, fontSize = 16.sp, color = c.text, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.divider))
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            WalletAction(R.drawable.ic_qr_code, stringResource(R.string.wallet_receive), Modifier.weight(1f)) { nav.push(Screen.Receive) }
            Box(Modifier.width(0.5.dp).height(24.dp).background(c.divider))
            WalletAction(R.drawable.ic_send_white_24dp, stringResource(R.string.wallet_withdraw), Modifier.weight(1f)) { nav.push(Screen.Withdraw) }
        }
    }
}

@Composable
private fun WalletAction(icon: Int, label: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxHeight().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Image(painterResource(icon), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(WalletBlue))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = WalletBlue)
    }
}

@Composable
private fun GreyButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalTapeTapeColors.current
    Box(
        modifier
            .height(44.dp)
            .shadow(2.dp, RoundedCornerShape(2.dp))
            .background(if (c.dark) Color(0xFF3A3A3C) else Color(0xFFE0E0E0), RoundedCornerShape(2.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (enabled) c.text else c.hint)
    }
}

@Composable
fun ReceiveScreen() {
    val nav = LocalNav.current
    val c = LocalTapeTapeColors.current
    val context = LocalContext.current
    val account = TapeTape.account ?: return
    val address = remember { account.walletAddress() }
    val qr = remember(address) { qrBitmap(address).asImageBitmap() }
    var copied by remember { mutableStateOf(false) }
    if (copied) LaunchedEffect(Unit) {
        delay(1500)
        copied = false
    }
    Column(Modifier.fillMaxSize().background(c.background)) {
        TapeTapeToolbar(stringResource(R.string.wallet_receive), onNav = { nav.pop() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.v2_wallet_networks), fontSize = 14.sp, color = c.text.copy(alpha = 0.7f))
            Spacer(Modifier.height(16.dp))
            Box(Modifier.background(Color.White).padding(8.dp)) {
                Image(qr, null, Modifier.size(180.dp), filterQuality = FilterQuality.None)
            }
            Spacer(Modifier.height(28.dp))
            Text(stringResource(R.string.wallet_receive_address), fontSize = 14.sp, color = c.text.copy(alpha = 0.7f))
            Spacer(Modifier.height(8.dp))
            Text(
                address,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                color = c.hint,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (c.dark) Color(0xFF1C1C1E) else Color(0xFFF2F2F2))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            )
            Spacer(Modifier.height(28.dp))
            GreyButton(stringResource(if (copied) R.string.wallet_address_copied else R.string.wallet_copy_address), modifier = Modifier.fillMaxWidth()) {
                copyToClipboard(context, address)
                copied = true
                Logx.i("wallet address copied")
            }
            Spacer(Modifier.height(36.dp))
            Text(stringResource(R.string.v2_wallet_receive_warning), fontSize = 12.sp, color = Color(0xFFE53935), textAlign = TextAlign.Center, lineHeight = 15.sp)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun WithdrawScreen() {
    val nav = LocalNav.current
    val c = LocalTapeTapeColors.current
    val context = LocalContext.current
    val account = TapeTape.account ?: return
    val tokens = remember { TapeTape.core.walletTokens() }
    val self = remember { account.walletAddress() }
    var tokenId by rememberSaveable { mutableStateOf(tokens.first().id) }
    var to by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var hash by remember { mutableStateOf<String?>(null) }
    val token = tokens.first { it.id == tokenId }
    var refresh by remember { mutableIntStateOf(0) }
    val estimate by produceState<SendEstimate?>(null, tokenId, to, amount, refresh) {
        delay(350)
        value = runCatching { account.estimateSend(tokenId, to.trim().ifBlank { self }, amount.trim().ifBlank { "0" }) }
            .onFailure { Logx.w("estimate send failed", it) }.getOrNull()
    }
    val e = estimate
    val addressError = to.isNotBlank() && e?.error == "invalid-address"
    val amountError = amount.isNotBlank() && e?.error != null && e.error != "invalid-address"
    val valid = to.isNotBlank() && amount.isNotBlank() && e != null && e.error == null

    Column(Modifier.fillMaxSize().background(c.background)) {
        TapeTapeToolbar(stringResource(R.string.wallet_withdraw), onNav = { nav.pop() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            PendingSends(tokens)
            Spacer(Modifier.height(16.dp))
            Label(stringResource(R.string.wallet_withdraw_address))
            UnderlineField(to, { to = it.trim() }, "0x...", Modifier.fillMaxWidth())
            if (addressError) ErrorText(walletError(context, e?.error))
            Spacer(Modifier.height(20.dp))
            Label(stringResource(R.string.wallet_withdraw_token))
            Box {
                Row(
                    Modifier.fillMaxWidth().clickable { picking = true }.padding(vertical = 12.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.v2_token_with_chain, token.symbol, token.chainName), fontSize = 16.sp, color = c.text, modifier = Modifier.weight(1f))
                    Image(painterResource(R.drawable.ic_dropdown), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(c.hint))
                }
                TapeTapeMenu(picking, { picking = false }, tokens.map { t ->
                    MenuItem(stringResource(R.string.v2_token_with_chain, t.symbol, t.chainName)) {
                        tokenId = t.id
                        amount = ""
                    }
                })
            }
            Text(
                stringResource(R.string.wallet_balance_format, e?.let { fmtAmount(it.balance) } ?: "...", token.symbol),
                fontSize = 12.sp, color = c.hint,
            )
            Spacer(Modifier.height(16.dp))
            Label(stringResource(R.string.wallet_withdraw_amount))
            Row(verticalAlignment = Alignment.CenterVertically) {
                UnderlineField(
                    amount, { v -> amount = v.filter { it.isDigit() || it == '.' } }, "0.0", Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Text(
                    stringResource(R.string.wallet_withdraw_max), color = WalletBlue, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { e?.max?.let { amount = fmtAmount(it, token.decimals).takeIf { v -> v != "--" } ?: "" } }.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            if (amountError) ErrorText(walletError(context, e?.error, e?.feeSymbol ?: nativeSymbol(token)))
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.wallet_withdraw_gas_fee), fontSize = 14.sp, color = c.text.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
                Text(e?.let { "~" + fmtAmount(it.fee, 8) + " " + it.feeSymbol } ?: "...", fontSize = 14.sp, color = c.text.copy(alpha = 0.7f))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.wallet_withdraw_receive_amount), fontSize = 14.sp, color = c.text.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
                Text(fmtAmount(e?.takeIf { it.error == null }?.amount ?: "0", token.decimals) + " " + token.symbol, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.text)
            }
            Spacer(Modifier.height(24.dp))
            GreyButton(stringResource(R.string.wallet_withdraw_confirm), enabled = valid, modifier = Modifier.fillMaxWidth()) { confirm = true }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirm) {
        TapeTapeDialog(
            { confirm = false },
            message = stringResource(R.string.wallet_withdraw_confirm_message, fmtAmount(e?.amount ?: amount, token.decimals), token.symbol, to),
            dismiss = stringResource(R.string.cancel) to { confirm = false },
            confirm = stringResource(R.string.ok) to {
                confirm = false
                sending = true
                Logx.i("withdraw $tokenId")
                val fee = e?.fee
                TapeTape.scope.launch {
                    runCatching { account.sendToken(tokenId, to, amount.trim(), fee) }
                        .onSuccess {
                            Logx.i("withdraw broadcast")
                            hash = it
                        }
                        .onFailure {
                            Logx.w("withdraw failed", it)
                            if (it.message == FEE_CHANGED) refresh++
                            failure = sendFailure(context, it.message, nativeSymbol(token))
                        }
                    sending = false
                }
            },
        )
    }
    if (sending) ProgressDialog(stringResource(R.string.one_moment))
    failure?.let { TapeTapeDialog({ failure = null }, message = it, confirm = stringResource(R.string.ok) to { failure = null }) }
    hash?.let { h -> TxStatusDialog(token.chainId, h) { nav.pop() } }
}

@Composable
private fun PendingSends(tokens: List<Token>) {
    val account = TapeTape.account ?: return
    val c = LocalTapeTapeColors.current
    var sends by remember { mutableStateOf(emptyList<WalletTx>()) }
    var statuses by remember { mutableStateOf(emptyMap<String, TxStatus>()) }
    LaunchedEffect(Unit) {
        while (true) {
            val current = withContext(Dispatchers.IO) { runCatching { account.pendingSends() }.onFailure { Logx.w("pending sends failed", it) }.getOrDefault(emptyList()) }
            if (current.isNotEmpty() && sends.isEmpty()) Logx.i("pending sends ${current.size}")
            sends = (sends + current).distinctBy { it.txHash }
            sends.filter { statuses[it.txHash] != TxStatus.Confirmed && statuses[it.txHash] != TxStatus.Failed }.forEach { t ->
                val s = runCatching { account.txStatus(t.chainId, t.txHash) }.onFailure { Logx.w("tx status failed", it) }.getOrNull() ?: return@forEach
                if (s == TxStatus.Confirmed || s == TxStatus.Failed) Logx.i("pending send $s")
                statuses = statuses + (t.txHash to s)
            }
            delay(5000)
        }
    }
    if (sends.isEmpty()) return
    Spacer(Modifier.height(16.dp))
    Label(stringResource(R.string.v2_pending_txs))
    sends.forEach { t ->
        val token = tokens.firstOrNull { it.id == t.tokenId }
        val line = when (statuses[t.txHash]) {
            TxStatus.Confirmed -> stringResource(R.string.transaction_success)
            TxStatus.Failed -> stringResource(R.string.transaction_failed)
            TxStatus.NotFound -> stringResource(R.string.v2_tx_not_found_maybe)
            else -> stringResource(R.string.waiting_confirmation)
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            // 广场网页发起的合约调用：金额只是附带的原生币，显示成「网页交易 → 合约」，不冒充提现金额
            val amount = fmtAmount(t.amount, token?.decimals ?: 8) + " " + (token?.symbol ?: "")
            val head = if (!t.dapp) amount
            else stringResource(R.string.v2_wallet_dapp_tx) + if (t.amount != "0") " · $amount" else ""
            Text(head + " → " + t.to.take(8) + "…" + t.to.takeLast(6), fontSize = 14.sp, color = c.text)
            Text(line + " · " + t.txHash.take(10) + "…" + t.txHash.takeLast(8), fontSize = 12.sp, color = if (statuses[t.txHash] == TxStatus.Failed) FailedRed else c.hint)
        }
    }
}

@Composable
fun TxStatusDialog(chainId: Long, hash: String, onDone: () -> Unit) {
    val account = TapeTape.account ?: return
    var status by remember { mutableStateOf(TxStatus.Pending) }
    LaunchedEffect(hash) {
        repeat(60) {
            status = runCatching { account.txStatus(chainId, hash) }.onFailure { Logx.w("tx status failed", it) }.getOrDefault(status)
            if (status == TxStatus.Confirmed || status == TxStatus.Failed) {
                Logx.i("tx $status")
                return@LaunchedEffect
            }
            delay(3000)
        }
    }
    val line = when (status) {
        TxStatus.Confirmed -> stringResource(R.string.transaction_success)
        TxStatus.Failed -> stringResource(R.string.transaction_failed)
        else -> stringResource(R.string.waiting_confirmation)
    }
    TapeTapeDialog(
        onDone,
        title = stringResource(R.string.v2_withdraw_submitted),
        message = line + "\n\n" + hash.take(10) + "…" + hash.takeLast(8),
        confirm = stringResource(R.string.ok) to onDone,
    )
}

@Composable
private fun Label(text: String) {
    val c = LocalTapeTapeColors.current
    Text(text, fontSize = 14.sp, color = c.text.copy(alpha = 0.7f), modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun ErrorText(text: String) {
    Text(text, fontSize = 13.sp, color = Color(0xFFE53935), modifier = Modifier.padding(top = 4.dp))
}
