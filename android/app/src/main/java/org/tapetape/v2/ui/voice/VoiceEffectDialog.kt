package org.tapetape.v2.ui.voice

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tapetape.v2.Logx
import org.tapetape.v2.R
import org.tapetape.v2.ui.common.TapeTapeDialog
import org.tapetape.v2.ui.common.toast
import org.tapetape.v2.ui.theme.LocalTapeTapeColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil

fun roundUpSeconds(ms: Double): Long = ceil(ms / 1000).toLong() * 1000

private fun effectFile(recording: File, effect: VoiceEffect): File =
    if (effect == VoiceEffect.ORIGINAL) recording else File(recording.path.removeSuffix(".m4a") + "-" + effect.name.lowercase() + ".m4a")

@Composable
fun VoiceEffectDialog(recording: File, recordedMs: Long, onSend: (File, Long) -> Unit, onClose: () -> Unit) {
    val c = LocalTapeTapeColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val files = remember { mutableStateMapOf(VoiceEffect.ORIGINAL to recording) }
    var selected by remember { mutableStateOf(VoiceEffect.ORIGINAL) }
    var samples by remember { mutableStateOf<FloatArray?>(null) }
    var sent by remember { mutableStateOf<File?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            VoiceEffect.entries.map { effectFile(recording, it) }.filter { it != sent }.forEach { it.delete() }
        }
    }

    fun durationOf(effect: VoiceEffect) = roundUpSeconds(recordedMs / effect.speed.toDouble())

    fun select(effect: VoiceEffect) {
        selected = effect
        if (effect in files) return
        Logx.d("voice effect render $effect")
        scope.launch {
            try {
                files[effect] = withContext(Dispatchers.Default) {
                    val start = System.currentTimeMillis()
                    val input = samples ?: VoiceCodec.decode(recording).also { samples = it }
                    val out = effect.apply(input, VoiceCodec.SAMPLE_RATE)
                    val file = effectFile(recording, effect)
                    VoiceCodec.encode(out, file)
                    if (!isActive) file.delete()
                    Logx.i("voice effect $effect ${input.size} -> ${out.size} samples in ${System.currentTimeMillis() - start} ms")
                    file
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logx.w("voice effect $effect failed", e)
                toast(context, context.getString(R.string.chat_unable_to_record_audio))
                onClose()
            }
        }
    }

    fun send() {
        val file = files[selected] ?: return
        sent = file
        val duration = durationOf(selected)
        Logx.i("voice send effect=$selected ${file.name} duration=$duration")
        onSend(file, duration)
        onClose()
    }

    TapeTapeDialog(
        onDismiss = onClose,
        title = stringResource(R.string.voice_effect_title),
        dismiss = stringResource(R.string.cancel) to onClose,
        confirm = stringResource(R.string.menu_send) to ::send,
        cancelable = false,
    ) {
        BackHandler(onBack = onClose)
        val file = files[selected]
        Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 32.dp), contentAlignment = Alignment.CenterStart) {
            if (file != null) {
                AudioPlayer(file.path, durationOf(selected), Modifier.fillMaxWidth())
            } else {
                CircularProgressIndicator(Modifier.size(32.dp).align(Alignment.Center), color = c.dialogAccent, strokeWidth = 3.dp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            VoiceEffect.entries.forEach { effect ->
                Row(
                    Modifier
                        .weight(1f)
                        .clickable(enabled = file != null) { select(effect) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected == effect, { select(effect) }, enabled = file != null)
                    Text(stringResource(effect.title), fontSize = 14.sp, color = c.text)
                }
            }
        }
    }
}
