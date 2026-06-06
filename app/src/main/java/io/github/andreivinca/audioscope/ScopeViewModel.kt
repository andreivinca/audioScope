package io.github.andreivinca.audioscope

import android.Manifest
import androidx.annotation.RequiresPermission
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.andreivinca.audioscope.audio.AudioTestEngine
import io.github.andreivinca.audioscope.dsp.Dsp
import io.github.andreivinca.audioscope.model.MeasurementPoint
import io.github.andreivinca.audioscope.model.MultiToneParams
import io.github.andreivinca.audioscope.model.ScopeUiState
import io.github.andreivinca.audioscope.model.SweepParams
import io.github.andreivinca.audioscope.model.TestMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.pow
import kotlin.math.sqrt

class ScopeViewModel : ViewModel() {

    private val _state = MutableStateFlow(ScopeUiState())
    val state: StateFlow<ScopeUiState> = _state.asStateFlow()

    private var job: Job? = null

    // Analysis window length (power of two for the FFT path). ~341 ms @ 48 kHz.
    private val analysisSamples = 16_384

    fun setPermission(granted: Boolean) =
        _state.update { it.copy(hasMicPermission = granted) }

    fun setGate(volume: Double) = _state.update { it.copy(gateVolume = volume) }

    fun setMode(mode: TestMode) {
        if (_state.value.running) return
        _state.update { it.copy(mode = mode, points = emptyList(), status = "Ready") }
    }

    fun updateSweep(transform: (SweepParams) -> SweepParams) =
        _state.update { it.copy(sweep = transform(it.sweep)) }

    fun updateMulti(transform: (MultiToneParams) -> MultiToneParams) =
        _state.update { it.copy(multi = transform(it.multi)) }

    fun toggle() {
        if (_state.value.running) stop() else start()
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun start() {
        if (!_state.value.hasMicPermission || job != null) return
        _state.update {
            it.copy(running = true, progress = 0f, points = emptyList(), inputVolume = 0.0, peakHz = null, status = "Starting…")
        }
        job = viewModelScope.launch(Dispatchers.Default) {
            val engine = AudioTestEngine()
            try {
                engine.start()
                _state.update { it.copy(unprocessedCapture = engine.usingUnprocessedSource) }
                when (_state.value.mode) {
                    TestMode.SWEEP -> runSweep(engine, _state.value.sweep)
                    TestMode.MULTITONE -> runMultiTone(engine, _state.value.multi)
                    TestMode.LIVE -> runLive(engine)
                }
                if (coroutineContext.isActive) {
                    _state.update { it.copy(status = "Done — ${it.points.size} points") }
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                _state.update { it.copy(status = "Stopped") }
            } catch (t: Throwable) {
                _state.update { it.copy(status = "Error: ${t.message}") }
            } finally {
                engine.stop()
                _state.update { it.copy(running = false, currentFreq = null, progress = 1f) }
                job = null
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun runSweep(engine: AudioTestEngine, params: SweepParams) {
        val freqs = params.frequencies()
        val collected = ArrayList<MeasurementPoint>(freqs.size)
        // Refresh the input-volume bar from each mic block as a tone settles +
        // is captured, so the bar updates ~10×/sec instead of once per tone.
        val onChunk: (ShortArray, Int) -> Unit = { buf, n ->
            val vol = Dsp.levelPercent(Dsp.detectPeak(buf, n, engine.sampleRate, minHz = 15.0).levelDb)
            _state.update { it.copy(inputVolume = vol) }
        }
        for ((i, f) in freqs.withIndex()) {
            coroutineContext.ensureActive()
            engine.setTones(doubleArrayOf(f), params.amplitude)
            engine.flush(params.settleMs, onChunk)
            val samples = engine.capture(analysisSamples, onChunk)
            coroutineContext.ensureActive()
            // Listen → loudest frequency → its value. We don't assume the speaker
            // played f; we report whatever frequency actually came back loudest.
            val peak = Dsp.detectPeak(samples, analysisSamples, engine.sampleRate, minHz = 15.0)
            collected += MeasurementPoint(f, peak.freqHz, peak.levelDb)
            val vol = Dsp.levelPercent(peak.levelDb)
            val progress = (i + 1f) / freqs.size
            _state.update {
                it.copy(
                    points = ArrayList(collected),
                    currentFreq = f,
                    progress = progress,
                    inputVolume = vol,
                    peakHz = if (vol >= it.gateVolume) peak.freqHz else null,
                    status = "Sweeping ${f.toInt()} Hz  (${i + 1}/${freqs.size})",
                )
            }
        }
    }

    /**
     * Live microphone analyser: emit nothing, just capture the mic continuously,
     * FFT each window and show the spectrum in real time. This is the mode that
     * reacts to whatever the mic hears (a guitar, a voice, a whistle).
     */
    private suspend fun runLive(engine: AudioTestEngine) {
        engine.setTones(doubleArrayOf(1000.0), 0.0) // silent — we only listen
        val freqs = liveDisplayFreqs()
        val window = 4096 // ~85 ms @ 48 kHz -> ~12 updates/sec
        _state.update { it.copy(status = "Listening — make a sound near the mic") }
        while (coroutineContext.isActive) {
            val samples = engine.capture(window)
            if (samples.size < window) break
            val amps = Dsp.spectrumAmplitudes(samples, window, engine.sampleRate, freqs)
            val points = freqs.indices.map {
                MeasurementPoint(freqs[it], freqs[it], Dsp.linToDb(amps[it]))
            }
            // The loudest frequency right now, sub-bin accurate.
            val pk = Dsp.detectPeak(samples, window, engine.sampleRate)
            val vol = Dsp.levelPercent(pk.levelDb)
            _state.update {
                it.copy(
                    points = points,
                    inputVolume = vol,
                    peakHz = if (vol >= it.gateVolume) pk.freqHz else null,
                )
            }
        }
    }

    private fun liveDisplayFreqs(): DoubleArray {
        val start = 30.0
        val end = 20_000.0
        val n = 96
        val ratio = (end / start).pow(1.0 / (n - 1))
        return DoubleArray(n) { start * ratio.pow(it.toDouble()) }
    }

    private suspend fun runMultiTone(engine: AudioTestEngine, params: MultiToneParams) {
        val freqs = params.frequencies()
        engine.setTones(freqs, params.amplitude)
        _state.update { it.copy(status = "Exciting ${freqs.size} tones…") }
        engine.flush(params.settleMs)
        val samples = engine.capture(analysisSamples)
        coroutineContext.ensureActive()
        val fs = engine.sampleRate
        // Each tone owns the band up to the midpoint (in log space) to its
        // neighbours; find the loudest frequency inside that band — same idea as
        // the sweep, just one capture with every tone present at once.
        val ratio = if (params.toneCount > 1)
            (params.endHz / params.startHz).pow(1.0 / (params.toneCount - 1)) else 1.7
        val halfBand = sqrt(ratio)
        val points = freqs.map { f ->
            val pk = Dsp.detectPeak(samples, analysisSamples, fs, minHz = f / halfBand, maxHz = f * halfBand)
            MeasurementPoint(f, pk.freqHz, pk.levelDb)
        }
        _state.update {
            it.copy(
                points = points,
                progress = 1f,
                inputVolume = Dsp.levelPercent(points.maxOf { p -> p.levelDb }),
                status = "Captured ${points.size} bands",
            )
        }
    }
}
