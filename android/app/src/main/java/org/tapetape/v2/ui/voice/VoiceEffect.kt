package org.tapetape.v2.ui.voice

import androidx.annotation.StringRes
import org.tapetape.v2.R
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tanh

enum class VoiceEffect(
    @StringRes val title: Int,
    val speed: Float,
    val pitchSemitones: Float,
    val distortionPregain: Float,
    val distortionPostgain: Float,
) {
    ORIGINAL(R.string.voice_effect_original, 1.0f, 0f, 0f, 0f),
    ROBOT(R.string.voice_effect_robot, 1.1f, -7f, 1.2f, 0.5f);

    fun outputLength(inputLength: Int): Int = (inputLength / speed).toInt()

    fun apply(input: FloatArray, sampleRate: Int): FloatArray {
        val output = if (speed == 1f) input.copyOf() else resample(input, speed)
        val shift = pitchSemitones - 12 * ln(speed.toDouble()) / ln(2.0)
        if (abs(shift) > 1e-6) {
            val scale = sampleRate / PITCH_RATE.toFloat()
            pitchShift(output, shift, PITCH_WINDOW * scale, PITCH_CROSSFADE * scale)
        }
        if (distortionPregain > 0) distort(output, distortionPregain, distortionPostgain)
        return output
    }

    private companion object {
        const val PITCH_WINDOW = 1024
        const val PITCH_CROSSFADE = 512
        const val PITCH_RATE = 44100
        const val DELAY_SIZE = 65536

        fun resample(input: FloatArray, speed: Float): FloatArray {
            val output = FloatArray((input.size / speed).toInt())
            for (i in output.indices) {
                val position = i * speed.toDouble()
                val index = position.toInt()
                val next = if (index + 1 < input.size) input[index + 1] else input[index]
                output[i] = input[index] + (next - input[index]) * (position - index).toFloat()
            }
            return output
        }

        fun pitchShift(samples: FloatArray, semitones: Double, window: Float, crossfade: Float) {
            val step = (1 + window - 2.0.pow(semitones / 12)).toFloat()
            val fade = 1f / crossfade
            val delay = FloatArray(DELAY_SIZE)
            var offset = 0f
            for (i in samples.indices) {
                delay[i and (DELAY_SIZE - 1)] = samples[i]
                offset = (offset + step) % window
                val mix = min(offset * fade, 1f)
                samples[i] = tap(delay, i, offset) * mix + tap(delay, i, offset + window) * (1 - mix)
            }
        }

        fun tap(delay: FloatArray, now: Int, offset: Float): Float {
            val whole = offset.toInt()
            val fraction = offset - whole
            return delay[(now - whole) and (DELAY_SIZE - 1)] * (1 - fraction) +
                delay[(now - whole - 1) and (DELAY_SIZE - 1)] * fraction
        }

        fun distort(samples: FloatArray, pregain: Float, postgain: Float) {
            val drive = pregain * 6.5536
            val gain = postgain * 0.61035156
            for (i in samples.indices) samples[i] = (gain * tanh(drive * samples[i])).toFloat()
        }
    }
}
