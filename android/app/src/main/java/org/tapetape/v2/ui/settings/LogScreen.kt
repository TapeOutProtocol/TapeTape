package org.tapetape.v2.ui.settings

import android.os.Process
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tapetape.v2.Logx
import org.tapetape.v2.R
import org.tapetape.v2.ui.LocalNav
import org.tapetape.v2.ui.common.MenuItem
import org.tapetape.v2.ui.common.OverflowMenu
import org.tapetape.v2.ui.common.TapeTapeToolbar
import org.tapetape.v2.ui.common.copyToClipboard
import org.tapetape.v2.ui.theme.LocalTapeTapeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun readLog(): List<String> = runCatching {
    val p = ProcessBuilder("logcat", "-d", "-v", "time", "--pid=${Process.myPid()}").redirectErrorStream(true).start()
    p.inputStream.bufferedReader().readLines().takeLast(3000)
}.onFailure { Logx.w("read log failed", it) }.getOrDefault(emptyList())

@Composable
fun LogScreen() {
    val nav = LocalNav.current
    val c = LocalTapeTapeColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lines by produceState<List<String>?>(null) { value = withContext(Dispatchers.IO) { readLog() } }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val text = lines?.joinToString("\n") ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch(Dispatchers.IO) {
            runCatching { context.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) } }.onFailure { Logx.w("save log failed", it) }
        }
    }
    Column(Modifier.fillMaxSize().background(c.background)) {
        TapeTapeToolbar(stringResource(R.string.pref_log_header), onNav = { nav.pop() }) {
            OverflowMenu(
                listOf(
                    MenuItem(stringResource(R.string.menu_copy_to_clipboard)) { lines?.let { copyToClipboard(context, it.joinToString("\n")) } },
                    MenuItem(stringResource(R.string.menu_save_log)) { save.launch("tapetape-log.txt") },
                ),
            )
        }
        SelectionContainer {
            LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                items(lines.orEmpty()) { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = c.text, lineHeight = 14.sp) }
            }
        }
    }
}
