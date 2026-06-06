package io.github.andreivinca.audioscope.audio

import android.Manifest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlin.math.max
import kotlin.math.min

/**
 * Drives simultaneous playback (AudioTrack) and capture (AudioRecord) so we can
 * measure the round-trip speaker -> air -> microphone response of the device.
 *
 * A dedicated writer thread streams the [ToneSource] output continuously between
 * [start] and [stop]; the measurement code on another thread changes the active
 * tones and reads back captured microphone samples.
 */
class AudioTestEngine(val sampleRate: Int = 48000) {

    private val tone = ToneSource(sampleRate)
    private var track: AudioTrack? = null
    private var record: AudioRecord? = null
    private var writer: Thread? = null

    @Volatile private var running = false

    /** True when capture initialised with an unprocessed-ish source (best accuracy). */
    var usingUnprocessedSource = false
        private set

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (running) return

        val outMin = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val outBytes = max(outMin, sampleRate / 4 * 2) // ~250 ms
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(outBytes)
            .build()

        record = openRecord()

        running = true
        track!!.play()
        record!!.startRecording()
        writer = Thread(::writerLoop, "audioscope-writer").apply {
            isDaemon = true
            start()
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun openRecord(): AudioRecord {
        val inMin = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val inBytes = max(inMin, sampleRate * 2) // ~1 s

        // UNPROCESSED bypasses AGC / noise suppression where the device supports
        // it, giving a truer measurement. Fall back gracefully.
        val sources = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        for ((idx, src) in sources.withIndex()) {
            val r = AudioRecord(
                src, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, inBytes,
            )
            if (r.state == AudioRecord.STATE_INITIALIZED) {
                usingUnprocessedSource = idx == 0
                Log.i(TAG, "Capture source index=$idx unprocessed=$usingUnprocessedSource")
                return r
            }
            r.release()
        }
        error("Could not initialise AudioRecord")
    }

    private fun writerLoop() {
        val block = ShortArray(1024)
        val t = track ?: return
        while (running) {
            tone.fill(block, block.size)
            if (t.write(block, 0, block.size, AudioTrack.WRITE_BLOCKING) < 0) break
        }
    }

    fun setTones(freqs: DoubleArray, amplitude: Double) = tone.setTones(freqs, amplitude)

    /**
     * Discard [millis] of microphone input to skip acoustic latency + settling.
     * [onChunk] (if given) is called with each block read, so callers can drive a
     * live meter while the tone settles.
     */
    fun flush(millis: Int, onChunk: ((ShortArray, Int) -> Unit)? = null) {
        val r = record ?: return
        var remaining = sampleRate * millis / 1000
        val buf = ShortArray(2048)
        while (remaining > 0 && running) {
            val n = r.read(buf, 0, min(buf.size, remaining))
            if (n <= 0) break
            onChunk?.invoke(buf, n)
            remaining -= n
        }
    }

    /**
     * Block until [count] microphone samples have been captured. If [onChunk] is
     * given, reads in small blocks and reports each one (copied into a scratch
     * buffer) so a live meter can refresh several times during the capture.
     */
    fun capture(count: Int, onChunk: ((ShortArray, Int) -> Unit)? = null): ShortArray {
        val r = record ?: return ShortArray(0)
        val out = ShortArray(count)
        val tmp = if (onChunk != null) ShortArray(4096) else null
        var got = 0
        while (got < count && running) {
            val want = if (tmp != null) min(tmp.size, count - got) else count - got
            val n = r.read(out, got, want)
            if (n <= 0) break
            if (onChunk != null && tmp != null) {
                System.arraycopy(out, got, tmp, 0, n)
                onChunk(tmp, n)
            }
            got += n
        }
        return out
    }

    fun stop() {
        if (!running) return
        running = false
        writer?.join(1000)
        writer = null
        runCatching {
            track?.apply {
                if (playState == AudioTrack.PLAYSTATE_PLAYING) stop()
                flush()
                release()
            }
        }
        runCatching {
            record?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
                release()
            }
        }
        track = null
        record = null
    }

    private companion object {
        const val TAG = "AudioScope"
    }
}
