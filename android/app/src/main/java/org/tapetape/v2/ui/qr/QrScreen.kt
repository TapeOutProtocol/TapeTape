package org.tapetape.v2.ui.qr

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tapetape.v2.Logx
import org.tapetape.v2.R
import org.tapetape.v2.TapeTape
import org.tapetape.v2.ui.LocalNav
import org.tapetape.v2.ui.common.Avatar
import org.tapetape.v2.ui.common.MenuItem
import org.tapetape.v2.ui.common.OverflowMenu
import org.tapetape.v2.ui.common.TapeTapeDialog
import org.tapetape.v2.ui.common.TapeTapeToolbar
import org.tapetape.v2.ui.common.copyToClipboard
import org.tapetape.v2.ui.common.coreQuery
import org.tapetape.v2.ui.common.readClipboard
import org.tapetape.v2.ui.common.toast
import org.tapetape.v2.ui.theme.LocalTapeTapeColors

@Composable
fun QrScreen() {
    val nav = LocalNav.current
    val c = LocalTapeTapeColors.current
    val context = LocalContext.current
    val account = TapeTape.account ?: return
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var scanned by remember { mutableStateOf<String?>(null) }
    var showLink by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var resets by remember { mutableIntStateOf(0) }
    val info by coreQuery(account.info()) { account.info() }
    val link by coreQuery("", resets) { account.inviteLink() }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val text = decodeImage(context, uri)
            if (text == null) toast(context, context.getString(R.string.qrscan_failed)) else scanned = text
        }
    }

    Column(Modifier.fillMaxSize().background(c.background)) {
        TapeTapeToolbar(stringResource(R.string.menu_new_contact), onNav = { nav.pop() }) {
            OverflowMenu(
                listOf(
                    MenuItem(stringResource(R.string.paste_from_clipboard)) {
                        val text = readClipboard(context)
                        if (text.isNullOrBlank()) toast(context, context.getString(R.string.qrscan_failed)) else scanned = text.trim()
                    },
                    MenuItem(stringResource(R.string.load_qr_code_as_image)) {
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    MenuItem(stringResource(R.string.withdraw_qr_code)) { confirmReset = true },
                ),
            )
        }
        Row(Modifier.fillMaxWidth().background(c.toolbar).height(48.dp)) {
            listOf(R.string.qrshow_title, R.string.qrscan_title).forEachIndexed { i, label ->
                Column(
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clickable { tab = i },
                ) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(label).uppercase(),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Box(Modifier.fillMaxWidth().height(3.dp).background(if (tab == i) Color.White else Color.Transparent))
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab == 0) {
                InviteCard(info.name, info.avatarPath, link, stringResource(R.string.qrshow_join_contact_hint, info.name), onLink = { showLink = true }, onScan = { tab = 1 })
            } else {
                QrScanner(active = scanned == null) { scanned = it }
            }
        }
    }

    scanned?.let { ScanResult(it) { scanned = null } }
    if (showLink) {
        TapeTapeDialog(
            { showLink = false },
            message = stringResource(R.string.share_invite_link_explain),
            neutral = stringResource(R.string.menu_copy_to_clipboard) to {
                copyToClipboard(context, link)
                toast(context, context.getString(R.string.copied_to_clipboard))
                showLink = false
            },
            dismiss = stringResource(R.string.cancel) to { showLink = false },
            confirm = stringResource(R.string.menu_share) to {
                showLink = false
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), null))
            },
        ) {
            LinkBox(link)
        }
    }
    if (confirmReset) {
        TapeTapeDialog(
            { confirmReset = false },
            message = stringResource(R.string.withdraw_verifycontact_explain),
            dismiss = stringResource(R.string.cancel) to { confirmReset = false },
            confirm = stringResource(R.string.reset) to {
                confirmReset = false
                account.resetInvite()
                resets++
                Logx.i("invite reset")
            },
        )
    }
}

@Composable
fun LinkBox(link: String) {
    val c = LocalTapeTapeColors.current
    Text(
        link,
        fontSize = 16.sp,
        color = c.dialogText,
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .padding(bottom = 16.dp)
            .fillMaxWidth()
            .background(c.linkBox, RoundedCornerShape(12.dp))
            .padding(16.dp),
    )
}

@Composable
fun InviteCard(name: String, avatar: String?, link: String, hint: String, onLink: () -> Unit, onScan: (() -> Unit)?) {
    val c = LocalTapeTapeColors.current
    val qr = remember(link) { if (link.isEmpty()) null else BitmapPainter(qrBitmap(link).asImageBitmap(), filterQuality = FilterQuality.None) }
    Box(Modifier.fillMaxSize()) {
        Image(painterResource(c.wallpaper), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(31.dp))
            Column(
                Modifier
                    .width(308.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(c.qrCard)
                    .border(BorderStroke(1.dp, c.qrCardBorder), RoundedCornerShape(20.dp))
                    .padding(top = 44.dp, bottom = 64.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(241.dp), contentAlignment = Alignment.Center) {
                    if (qr != null) Image(qr, null, Modifier.size(241.dp))
                    Box(Modifier.size(66.dp).clip(CircleShape).background(c.qrCard), contentAlignment = Alignment.Center) {
                        Avatar(name, avatar, 57.dp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    hint,
                    color = Color.Black,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Spacer(Modifier.height(32.dp))
            Row {
                PillButton(R.drawable.ic_link_24, stringResource(R.string.link), onLink)
                if (onScan != null) {
                    Spacer(Modifier.width(16.dp))
                    PillButton(R.drawable.ic_qr_code_scanner_24, stringResource(R.string.scan), onScan)
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PillButton(icon: Int, label: String, onClick: () -> Unit) {
    val c = LocalTapeTapeColors.current
    Row(
        Modifier
            .height(46.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(c.pillButton)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(icon), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(Color.White))
        Spacer(Modifier.width(8.dp))
        Text(label.uppercase(), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
