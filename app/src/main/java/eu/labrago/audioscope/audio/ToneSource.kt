package eu.labrago.audioscope.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Generates the playback signal one block at a time using per-tone phase
 * accumulators, so the waveform stays continuous (no clicks) even when the set
 * of frequencies changes mid-stream. Thread-safe for a single writer thread
 * reading while another thread calls [setTones].
 */
class ToneSource(private val sampleRate: Int) {

    private companion object {
        const val MAX_TONES = 64
    }

    private val phases = DoubleArray(MAX_TONES)

    @Volatile private var freqs: DoubleArray = doubleArrayOf(1000.0)
    @Volatile private var amplitude: Double = 0.5
    @Volatile private var lastCount = 0

    fun setTones(newFreqs: DoubleArray, amp: Double) {
        synchronized(phases) {
            val n = newFreqs.size.coerceIn(1, MAX_TONES)
            if (n != lastCount) {
                // Schroeder-style phase spread keeps the summed crest factor low so
                // multi-tone playback doesn't clip.
                for (t in 0 until n) phases[t] = PI * t * t / n
                lastCount = n
            }
            amplitude = amp
            freqs = if (newFreqs.size <= MAX_TONES) newFreqs else newFreqs.copyOf(MAX_TONES)
        }
    }

    /** Fill [out] (first [count] entries) with the next signal block as 16-bit PCM. */
    fun fill(out: ShortArray, count: Int) {
        val f: DoubleArray
        val n: Int
        val amp: Double
        
        synchronized(phases) {
            f = freqs
            n = f.size
            amp = amplitude
            val scale = amp / sqrt(n.toDouble()) // random-phase sum grows ~sqrt(n)
            for (i in 0 until count) {
                var s = 0.0
                for (t in 0 until n) {
                    var p = phases[t] + 2.0 * PI * f[t] / sampleRate
                    if (p > 2.0 * PI) p -= 2.0 * PI
                    phases[t] = p
                    s += sin(p)
                }
                s *= scale
                if (s > 1.0) s = 1.0 else if (s < -1.0) s = -1.0
                out[i] = (s * 32767.0).toInt().toShort()
            }
        }
    }
}
