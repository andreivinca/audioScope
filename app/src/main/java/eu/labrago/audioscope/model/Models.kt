package eu.labrago.audioscope.model

import kotlin.math.ln
import kotlin.math.pow

enum class TestMode { SWEEP, MULTITONE, LIVE }

/**
 * One measurement:
 *  - [frequencyHz]: the frequency the app emitted (Sweep) or the bin shown (Live).
 *  - [detectedHz]: the loudest frequency the mic actually heard for this point.
 *  - [levelDb]: that peak's received level (dBFS).
 * A faithful speaker has detectedHz == frequencyHz.
 */
data class MeasurementPoint(
    val frequencyHz: Double,
    val detectedHz: Double,
    val levelDb: Double,
)

data class SweepParams(
    val startHz: Double = 20.0,
    val endHz: Double = 20_000.0,
    val pointsPerOctave: Int = 6,
    val amplitude: Double = 0.6,
    val settleMs: Int = 300, // wireless/car links need ~200 ms latency + codec settling
) {
    /** Log-spaced frequency list from [startHz] to [endHz]. */
    fun frequencies(): DoubleArray {
        val octaves = ln(endHz / startHz) / ln(2.0)
        val count = (octaves * pointsPerOctave).toInt() + 1
        return DoubleArray(count) { startHz * 2.0.pow(it.toDouble() / pointsPerOctave) }
    }
}

data class MultiToneParams(
    val startHz: Double = 40.0,
    val endHz: Double = 16_000.0,
    val toneCount: Int = 12,
    val amplitude: Double = 0.6,
    val settleMs: Int = 300,
) {
    /** [toneCount] log-spaced frequencies played simultaneously. */
    fun frequencies(): DoubleArray {
        if (toneCount <= 1) return doubleArrayOf(startHz)
        val ratio = (endHz / startHz).pow(1.0 / (toneCount - 1))
        return DoubleArray(toneCount) { startHz * ratio.pow(it.toDouble()) }
    }
}

data class ScopeUiState(
    val mode: TestMode = TestMode.SWEEP,
    val running: Boolean = false,
    val progress: Float = 0f,
    val currentFreq: Double? = null,
    val points: List<MeasurementPoint> = emptyList(),
    val status: String = "Ready",
    val hasMicPermission: Boolean = false,
    val unprocessedCapture: Boolean = false,
    val inputVolume: Double = 0.0,       // 0..100 volume of the loudest frequency right now (the bar)
    val peakHz: Double? = null,          // loudest frequency the mic hears right now
    val gateVolume: Double = 20.0,       // ignore peaks quieter than this (0..100)
    val sweep: SweepParams = SweepParams(),
    val multi: MultiToneParams = MultiToneParams(),
)
