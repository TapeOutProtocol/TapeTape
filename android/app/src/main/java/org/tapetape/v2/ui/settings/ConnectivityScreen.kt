package org.tapetape.v2.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tapetape.v2.R
import org.tapetape.v2.TapeTape
import org.tapetape.v2.core.Connectivity
import org.tapetape.v2.core.MessageState
import org.tapetape.v2.ui.LocalNav
import org.tapetape.v2.ui.common.TapeTapeToolbar
import org.tapetape.v2.ui.common.coreQuery
import org.tapetape.v2.ui.theme.LocalTapeTapeColors
import java.io.File

private data class Status(val connectivity: Connectivity, val node: String, val pending: Int, val bytes: Long)

@Composable
fun ConnectivityScreen() {
    val nav = LocalNav.current
    val c = LocalTapeTapeColors.current
    val context = LocalContext.current
    val account = TapeTape.account ?: return
    val status by coreQuery<Status?>(null) {
        val info = account.info()
        val pending = (account.chats(null) + account.archivedChats()).count { it.lastFromMe && it.lastState == MessageState.Pending }
        val bytes = File(TapeTape.dataDir(), "accounts/${info.id}").walkTopDown().filter { it.isFile }.sumOf { it.length() }
        Status(account.connectivity(), info.node, pending, bytes)
    }
    Column(Modifier.fillMaxSize().background(c.background)) {
        TapeTapeToolbar(stringResource(R.string.connectivity), onNav = { nav.pop() })
        val s = status ?: return@Column
        val (label, color) = when (s.connectivity) {
            Connectivity.Connected -> stringResource(R.string.connectivity_connected) to Color(0xFF34C759)
            Connectivity.Connecting -> stringResource(R.string.connectivity_connecting) to Color(0xFFFFCC00)
            Connectivity.NotConnected -> stringResource(R.string.connectivity_not_connected) to Color(0xFFFF3B30)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Heading(stringResource(R.string.incoming_messages))
            StatusLine(color, "${s.node}: $label")
            Spacer(Modifier.size(20.dp))
            Heading(stringResource(R.string.outgoing_messages))
            if (s.pending > 0) StatusLine(Color(0xFFFFCC00), stringResource(R.string.sending))
            else StatusLine(color, "${s.node}: $label")
            Spacer(Modifier.size(20.dp))
            Heading(stringResource(R.string.storage_on_domain, android.os.Build.MODEL))
            Text(Formatter.formatShortFileSize(context, s.bytes), color = c.text, fontSize = 16.sp)
        }
    }
}

@Composable
private fun Heading(text: String) {
    val c = LocalTapeTapeColors.current
    Text(text, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun StatusLine(color: Color, text: String) {
    val c = LocalTapeTapeColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(color, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(text, color = c.text, fontSize = 16.sp)
    }
}
