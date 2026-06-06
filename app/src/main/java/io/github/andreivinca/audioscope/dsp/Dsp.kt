package io.github.andreivinca.audioscope.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Signal-analysis primitives. All routines normalise so that a full-scale
 * (amplitude 1.0) sine at the analysed frequency yields a linear magnitude of
 * ~1.0, i.e. 0 dBFS.
 */
object Dsp {

    /** Hann window coefficient for sample [i] of a window of length [n]. */
    private fun hann(i: Int, n: Int): Double =
        0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))

    fun linToDb(x: Double): Double = 20.0 * log10(max(x, 1e-9))

    /** Lowest level shown on the 0..100 indicator scale (dBFS). */
    const val LEVEL_FLOOR_DB = -70.0

    /**
     * Map a dBFS level onto the shared 0..100 indicator scale used by BOTH the
     * input meter and the noise gate, so the two read on the same numbers.
     */
    fun levelPercent(db: Double): Double =
        ((db - LEVEL_FLOOR_DB) / (0.0 - LEVEL_FLOOR_DB)).coerceIn(0.0, 1.0) * 100.0

    /**
     * Goertzel estimate of the amplitude of [freq] within the first [count]
     * samples (16-bit PCM) sampled at [sampleRate]. A Hann window is applied to
     * suppress spectral leakage. Returns a linear amplitude (0 dBFS == 1.0).
     */
    fun goertzelAmplitude(samples: ShortArray, count: Int, sampleRate: Int, freq: Double): Double {
        val omega = 2.0 * PI * freq / sampleRate
        val coeff = 2.0 * cos(omega)
        var sPrev = 0.0
        var sPrev2 = 0.0
        var windowSum = 0.0
        for (i in 0 until count) {
            val w = hann(i, count)
            windowSum += w
            val x = (samples[i] / 32768.0) * w
            val s = x + coeff * sPrev - sPrev2
            sPrev2 = sPrev
            sPrev = s
        }
        val power = sPrev2 * sPrev2 + sPrev * sPrev - coeff * sPrev * sPrev2
        val magnitude = sqrt(max(0.0, power))
        // Coherent gain of the window for a single bin is windowSum/2.
        return magnitude / (windowSum / 2.0)
    }

    /**
     * Amplitude of the strongest tone within ±[tolerance] (fractional) of [freq],
     * scanned in [steps] points. This mirrors the multi-tone FFT's nearest-bin
     * search: the exact-frequency Goertzel is a razor-thin filter (~6 Hz wide
     * here), so when a wireless/car link plays the tone back slightly off-pitch
     * (its own clock + resampling, an offset that grows with frequency) the energy
     * lands outside the filter and the highs read as near-silence. Searching a
     * small window recovers it.
     */
    fun goertzelPeak(
        samples: ShortArray,
        count: Int,
        sampleRate: Int,
        freq: Double,
        tolerance: Double = 0.04,
        steps: Int = 8,
    ): Double {
        var best = 0.0
        for (k in 0..steps) {
            val f = freq * (1.0 - tolerance + 2.0 * tolerance * k / steps)
            val a = goertzelAmplitude(samples, count, sampleRate, f)
            if (a > best) best = a
        }
        return best
    }

    /** A detected spectral peak: its frequency (Hz) and level (dBFS). */
    data class Peak(val freqHz: Double, val levelDb: Double)

    /**
     * THE core routine: FFT the first power-of-two prefix of [count] samples and
     * return the loudest frequency within [[minHz], [maxHz]], with sub-bin accuracy
     * via parabolic interpolation. "Listen → loudest frequency → its value."
     * Restricting the band lets the multi-tone test pick out one tone at a time.
     */
    fun detectPeak(
        samples: ShortArray,
        count: Int,
        sampleRate: Int,
        minHz: Double = 30.0,
        maxHz: Double = 24_000.0,
    ): Peak {
        var n = 1
        while (n * 2 <= count) n = n shl 1
        if (n < 4) return Peak(0.0, linToDb(0.0))
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        var windowSum = 0.0
        for (i in 0 until n) {
            val w = hann(i, n)
            windowSum += w
            re[i] = (samples[i] / 32768.0) * w
        }
        Fft.transform(re, im)
        val half = n / 2
        val mag = DoubleArray(half) { sqrt(re[it] * re[it] + im[it] * im[it]) }
        val maxBin = (maxHz * n / sampleRate).toInt().coerceIn(1, half - 1)
        val minBin = (minHz * n / sampleRate).toInt().coerceIn(1, maxBin)
        var peakBin = minBin
        for (b in minBin..maxBin) if (mag[b] > mag[peakBin]) peakBin = b
        // Parabolic interpolation around the peak for a precise frequency.
        var delta = 0.0
        if (peakBin in 1 until half - 1) {
            val a = mag[peakBin - 1]
            val b0 = mag[peakBin]
            val c = mag[peakBin + 1]
            val denom = a - 2 * b0 + c
            if (denom != 0.0) delta = (0.5 * (a - c) / denom).coerceIn(-0.5, 0.5)
        }
        val freq = (peakBin + delta) * sampleRate / n
        val amp = 2.0 * mag[peakBin] / windowSum
        return Peak(freq, linToDb(amp))
    }

    /** Broadband RMS level (linear, 0 dBFS == 1.0) of [count] samples. */
    fun rms(samples: ShortArray, count: Int): Double {
        var acc = 0.0
        for (i in 0 until count) {
            val x = samples[i] / 32768.0
            acc += x * x
        }
        return sqrt(acc / count) * sqrt(2.0) // scale so a full-scale sine == 1.0
    }

    /**
     * Single FFT over the largest power-of-two prefix of [count] samples, then
     * read back the amplitude at each requested frequency in [freqs]. Used for
     * the multi-tone test where every band is excited simultaneously.
     */
    fun spectrumAmplitudes(
        samples: ShortArray,
        count: Int,
        sampleRate: Int,
        freqs: DoubleArray,
    ): DoubleArray {
        var n = 1
        while (n * 2 <= count) n = n shl 1
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        var windowSum = 0.0
        for (i in 0 until n) {
            val w = hann(i, n)
            windowSum += w
            re[i] = (samples[i] / 32768.0) * w
        }
        Fft.transform(re, im)

        val out = DoubleArray(freqs.size)
        for (fi in freqs.indices) {
            val bin = (freqs[fi] * n / sampleRate).roundToInt()
            // Take the strongest of the nearest bins to tolerate non-integer bins.
            var best = 0.0
            for (b in (bin - 1)..(bin + 1)) {
                if (b in 1 until n / 2) {
                    val mag = 2.0 * sqrt(re[b] * re[b] + im[b] * im[b]) / windowSum
                    if (mag > best) best = mag
                }
            }
            out[fi] = best
        }
        return out
    }
}

/** In-place iterative radix-2 Cooley–Tukey FFT. Array length must be a power of two. */
object Fft {
    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wLenR = cos(ang)
            val wLenI = sin(ang)
            var i = 0
            while (i < n) {
                var wr = 1.0
                var wi = 0.0
                val half = len / 2
                for (k in 0 until half) {
                    val a = i + k
                    val b = i + k + half
                    val vr = re[b] * wr - im[b] * wi
                    val vi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - vr
                    im[b] = im[a] - vi
                    re[a] += vr
                    im[a] += vi
                    val nwr = wr * wLenR - wi * wLenI
                    wi = wr * wLenI + wi * wLenR
                    wr = nwr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
