package org.tapetape.v2.ui.settings

import android.app.Activity
import android.app.KeyguardManager
import android.view.WindowManager
import androidx.compose.runtime.DisposableEffect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tapetape.v2.Logx
import org.tapetape.v2.R
import org.tapetape.v2.TapeTape
import org.tapetape.v2.ui.common.TapeTapeDialog
import org.tapetape.v2.ui.common.copyToClipboard
import org.tapetape.v2.ui.common.toast
import org.tapetape.v2.ui.relayAddress
import org.tapetape.v2.ui.theme.LocalTapeTapeColors

@Composable
fun rememberDeviceAuth(title: String): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val ok = result.resultCode == Activity.RESULT_OK
        Logx.i("device auth ok=$ok")
        if (ok) pending?.invoke()
        pending = null
    }
    return remember(launcher, title) {
        { action ->
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            @Suppress("DEPRECATION")
            val intent = if (keyguard.isDeviceSecure) keyguard.createConfirmDeviceCredentialIntent(title, context.getString(R.string.enter_system_secret_to_continue)) else null
            if (intent == null) {
                Logx.d("device not secured, skipping auth")
                action()
            } else {
                pending = action
                launcher.launch(intent)
            }
        }
    }
}

@Composable
fun SecureWindow() {
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

@Composable
fun MnemonicDialog(onDismiss: () -> Unit) {
    val c = LocalTapeTapeColors.current
    val context = LocalContext.current
    val account = TapeTape.account ?: return
    val words = remember { account.mnemonic() }
    val info = remember { account.info() }
    TapeTapeDialog(
        onDismiss,
        title = stringResource(R.string.wallet_mnemonic_info_title),
        secure = true,
        neutral = stringResource(R.string.wallet_copy_mnemonic) to {
            copyToClipboard(context, words, sensitive = true)
            toast(context, context.getString(R.string.wallet_mnemonic_copied))
            Logx.i("mnemonic copied")
        },
        confirm = stringResource(R.string.ok) to onDismiss,
    ) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
            if (words.isBlank()) {
                Text(stringResource(R.string.wallet_no_mnemonic), color = c.dialogText, fontSize = 16.sp)
            } else {
                SelectionContainer {
                    Column {
                        Text(stringResource(R.string.wallet_mnemonic_label), color = c.dialogText, fontSize = 14.sp)
                        Text(words, color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp, modifier = Modifier.padding(vertical = 6.dp))
                        Text(stringResource(R.string.wallet_evm_address_label), color = c.dialogText, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                        Text(info.address, color = c.text, fontSize = 15.sp)
                        Text(stringResource(R.string.wallet_email_address_label), color = c.dialogText, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                        Text(relayAddress(info.account), color = c.text, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}
