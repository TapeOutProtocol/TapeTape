package org.tapetape.v2.ui.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tanh

class VoiceEffectTest {

    @Test
    fun originalKeepsSamples() {
        val input = tone(2.0)
        val output = VoiceEffect.ORIGINAL.apply(input, RATE)
        assertArrayEquals(input, output, 0f)
        assertEquals(TONE, frequency(output), TONE * 0.01)
    }

    @Test
    fun robotMatchesIosPreset() {
        val input = tone(2.0)
        val output = VoiceEffect.ROBOT.apply(input, RATE)

        assertEquals(VoiceEffect.ROBOT.outputLength(input.size), output.size)
        assertEquals(input.size / 1.1, output.size.toDouble(), 1.0)

        val expected = TONE * 2.0.pow(-7 / 12.0)
        assertEquals(expected, frequency(output), expected * 0.01)

        assertEquals(0.5 * 0.61035156 * tanh(1.2 * 6.5536 * 0.5), peak(output), 0.01)
    }

    @Test
    fun durationRoundsUpToWholeSeconds() {
        assertEquals(1000L, roundUpSeconds(1.0))
        assertEquals(4000L, roundUpSeconds(4000.0))
        assertEquals(5000L, roundUpSeconds(4001.0))
        assertEquals(4000L, roundUpSeconds(4300 / 1.1))
    }

    private fun tone(seconds: Double) = FloatArray((seconds * RATE).toInt()) { (0.5 * sin(2 * PI * TONE * it / RATE)).toFloat() }

    private fun frequency(samples: FloatArray): Double {
        val crossings = mutableListOf<Double>()
        for (i in RATE / 4 until samples.size) {
            if (samples[i - 1] < 0 && samples[i] >= 0) {
                crossings += i - 1 + samples[i - 1] / (samples[i - 1] - samples[i]).toDouble()
            }
        }
        val periods = crossings.zipWithNext { a, b -> ((b - a) * 10).roundToLong() }.groupingBy { it }.eachCount()
        val mostCommon = periods.maxBy { it.value }.key
        return RATE / (mostCommon / 10.0)
    }

    private fun peak(samples: FloatArray): Double {
        var max = 0.0
        for (i in RATE / 4 until samples.size) max = maxOf(max, abs(samples[i]).toDouble())
        return max
    }

    private companion object {
        const val RATE = 22050
        const val TONE = 220.0
    }
}
