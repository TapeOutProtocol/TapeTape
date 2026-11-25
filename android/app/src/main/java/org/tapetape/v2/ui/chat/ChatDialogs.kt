package org.tapetape.v2.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.emoji2.emojipicker.EmojiPickerView
import org.tapetape.v2.R
import org.tapetape.v2.TapeTape
import org.tapetape.v2.core.ReactionBy
import org.tapetape.v2.ui.common.Avatar
import org.tapetape.v2.ui.common.coreQuery
import org.tapetape.v2.ui.common.TapeTapeDialog
import org.tapetape.v2.ui.theme.LocalTapeTapeColors

private val EphemeralOptions = listOf(
    0 to R.string.off,
    60 to R.string.after_1_minute,
    300 to R.string.after_5_minutes,
    1800 to R.string.after_30_minutes,
    3600 to R.string.autodel_after_1_hour,
    86400 to R.string.autodel_after_1_day,
    604800 to R.string.autodel_after_1_week,
    3024000 to R.string.after_5_weeks,
)

@Composable
fun EphemeralDialog(current: Int, onDismiss: () -> Unit, onSet: (Int) -> Unit) {
    val c = LocalTapeTapeColors.current
    val uri = LocalUriHandler.current
    var choice by remember { mutableIntStateOf(current) }
    TapeTapeDialog(
        onDismiss,
        title = stringResource(R.string.ephemeral_messages),
        confirm = stringResource(R.string.ok) to { onSet(choice) },
        dismiss = stringResource(R.string.cancel) to onDismiss,
        neutral = stringResource(R.string.learn_more) to { uri.openUri("https://tapetape.org") },
    ) {
        Column(Modifier.padding(top = 8.dp)) {
            EphemeralOptions.forEach { (seconds, label) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { choice = seconds }
                        .padding(start = 20.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = seconds == choice,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF2090EA), unselectedColor = c.hint),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    )
                    Text(stringResource(label), fontSize = 16.sp, color = c.text)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.ephemeral_messages_hint),
                fontSize = 13.sp,
                color = c.hint,
                modifier = Modifier.padding(horizontal = 30.dp),
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

private val MuteOptions = listOf<Pair<Long?, Int>>(
    3600_000L to R.string.mute_for_one_hour,
    7200_000L to R.string.mute_for_two_hours,
    86400_000L to R.string.mute_for_one_day,
    7 * 86400_000L to R.string.mute_for_seven_days,
    null to R.string.mute_forever,
)

@Composable
fun MuteDialog(onDismiss: () -> Unit, onMute: (Long?) -> Unit) {
    val c = LocalTapeTapeColors.current
    TapeTapeDialog(onDismiss, title = stringResource(R.string.menu_mute), dismiss = stringResource(R.string.cancel) to onDismiss) {
        Column(Modifier.padding(top = 4.dp)) {
            MuteOptions.forEach { (ms, label) ->
                Text(
                    stringResource(label),
                    fontSize = 16.sp,
                    color = c.text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onMute(ms?.let { System.currentTimeMillis() + it }) }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
fun ReactionsDialog(msgId: Int, onDismiss: () -> Unit, onRemoveMine: () -> Unit) {
    val c = LocalTapeTapeColors.current
    val account = TapeTape.account ?: return
    val list by coreQuery(emptyList<ReactionBy>(), msgId) { account.messageReactions(msgId) }
    TapeTapeDialog(onDismiss, title = stringResource(R.string.reactions), confirm = stringResource(R.string.ok) to onDismiss) {
        Column(Modifier.padding(top = 4.dp)) {
            list.forEach { r ->
                val mine = r.contactId == null
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = mine) {
                            onRemoveMine()
                            onDismiss()
                        }
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(r.name, null, 36.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(if (mine) stringResource(R.string.self) else r.name, fontSize = 16.sp, color = c.text, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(r.emoji, fontSize = 24.sp)
                }
            }
        }
    }
}

@Composable
fun EmojiPickerDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val c = LocalTapeTapeColors.current
    TapeTapeDialog(onDismiss, dismiss = stringResource(R.string.cancel) to onDismiss) {
        AndroidView(
            factory = { ctx -> EmojiPickerView(ctx).apply { setOnEmojiPickedListener { onPick(it.emoji) } } },
            modifier = Modifier.fillMaxWidth().height(380.dp).background(c.dialog),
        )
    }
}
