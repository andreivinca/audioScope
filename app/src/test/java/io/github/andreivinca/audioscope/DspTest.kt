package io.github.andreivinca.audioscope

import io.github.andreivinca.audioscope.dsp.Dsp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class DspTest {

    private val sampleRate = 48000
    private val count = 16384

    @Test
    fun testGoertzel() {
        val freq = 1000.0
        val samples = ShortArray(count)
        for (i in 0 until count) {
            val s = sin(2.0 * PI * freq * i / sampleRate)
            samples[i] = (s * 32767.0).toInt().toShort()
        }

        val amp = Dsp.goertzelAmplitude(samples, count, sampleRate, freq)
        // Expected amplitude is 1.0 (0 dBFS)
        assertEquals(1.0, amp, 0.01)

        val db = Dsp.linToDb(amp)
        assertEquals(0.0, db, 0.1)
    }

    @Test
    fun testFft() {
        val freqs = doubleArrayOf(100.0, 500.0, 1000.0, 5000.0)
        val samples = ShortArray(count)
        for (i in 0 until count) {
            var s = 0.0
            for (f in freqs) {
                s += sin(2.0 * PI * f * i / sampleRate)
            }
            // Normalize so sum doesn't clip
            s /= freqs.size
            samples[i] = (s * 32767.0).toInt().toShort()
        }

        val amps = Dsp.spectrumAmplitudes(samples, count, sampleRate, freqs)
        
        for (amp in amps) {
            // Each tone should be 1/freqs.size linear amplitude
            assertEquals(1.0 / freqs.size, amp, 0.05)
        }
    }
}
