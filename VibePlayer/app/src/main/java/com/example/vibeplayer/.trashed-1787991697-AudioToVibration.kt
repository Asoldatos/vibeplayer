package com.example.vibeplayer

import android.content.ContentResolver
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Result of analyzing an mp3: a vibration waveform ready for
 * VibrationEffect.createWaveform(timings, amplitudes, -1)
 */
data class VibrationWaveform(
    val timings: LongArray,
    val amplitudes: IntArray,
    val totalDurationMs: Long
)

/**
 * Decodes an mp3 (or any audio Android's MediaCodec supports) to PCM using
 * MediaExtractor + MediaCodec, computes a short-window RMS amplitude envelope,
 * and converts that envelope into a vibration waveform. Audio is never routed
 * to a speaker/AudioTrack anywhere in this pipeline -- decoding is used purely
 * for analysis.
 */
object AudioToVibration {

    // Window size for each amplitude sample. ~50ms matches typical ERM/LRA
    // motor response time reasonably well -- shorter windows tend to just
    // get smoothed out by the motor's own inertia.
    private const val WINDOW_MS = 50L

    // Minimum vibration amplitude Android will accept (1-255). 0 = motor off.
    private const val MIN_AMP = 1
    private const val MAX_AMP = 255

    fun analyze(
        resolver: ContentResolver,
        uri: Uri,
        onProgress: (Int) -> Unit = {}
    ): VibrationWaveform {
        val afd = resolver.openAssetFileDescriptor(uri, "r")
            ?: throw IllegalArgumentException("Could not open file")

        afd.use { assetFd ->
            val extractor = MediaExtractor()
            extractor.setDataSource(assetFd.fileDescriptor, assetFd.startOffset, assetFd.length)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            require(trackIndex >= 0 && format != null) { "No audio track found in file" }

            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) else -1L

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val samplesPerWindow = (sampleRate * channelCount * WINDOW_MS / 1000L).toInt().coerceAtLeast(1)

            // Running accumulator for the current window
            var windowSumSquares = 0.0
            var windowSampleCount = 0

            // Raw per-window RMS values (before normalization)
            val rawWindows = ArrayList<Double>()

            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false

            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            codec.queueInputBuffer(inIndex, 0, sampleSize, presentationTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        // PCM 16-bit little-endian samples
                        while (outputBuffer.remaining() >= 2) {
                            val lo = outputBuffer.get().toInt() and 0xFF
                            val hi = outputBuffer.get().toInt()
                            val sample = (hi shl 8) or lo // sign-extended 16-bit
                            windowSumSquares += (sample.toDouble()).pow(2)
                            windowSampleCount++

                            if (windowSampleCount >= samplesPerWindow) {
                                val rms = sqrt(windowSumSquares / windowSampleCount)
                                rawWindows.add(rms)
                                windowSumSquares = 0.0
                                windowSampleCount = 0
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEOS = true
                    }

                    if (durationUs > 0) {
                        val pct = ((bufferInfo.presentationTimeUs.toDouble() / durationUs) * 100).toInt()
                        onProgress(pct.coerceIn(0, 100))
                    }
                }
            }

            // Flush any partial trailing window
            if (windowSampleCount > 0) {
                rawWindows.add(sqrt(windowSumSquares / windowSampleCount))
            }

            codec.stop()
            codec.release()
            extractor.release()

            return buildWaveform(rawWindows)
        }
    }

    /**
     * Normalizes raw RMS values against the loudest window in the track,
     * applies a perceptual (square-root) curve so quieter passages are still
     * felt, then run-length-encodes consecutive similar-amplitude windows
     * into a compact waveform.
     */
    private fun buildWaveform(rawWindows: List<Double>): VibrationWaveform {
        if (rawWindows.isEmpty()) {
            return VibrationWaveform(longArrayOf(WINDOW_MS), intArrayOf(0), WINDOW_MS)
        }

        val peak = rawWindows.maxOrNull()?.takeIf { it > 0.0 } ?: 1.0

        val normalizedAmps = rawWindows.map { rms ->
            val linear = (rms / peak).coerceIn(0.0, 1.0)
            // Perceptual curve: sqrt makes quieter sections more noticeable
            val perceptual = sqrt(linear)
            val amp = (perceptual * MAX_AMP).toInt()
            if (amp <= 0) 0 else amp.coerceIn(MIN_AMP, MAX_AMP)
        }

        // Run-length encode to keep the waveform array small, merging windows
        // whose amplitude is within a small tolerance of each other.
        val tolerance = 6
        val timings = ArrayList<Long>()
        val amplitudes = ArrayList<Int>()

        var currentAmp = normalizedAmps[0]
        var currentDuration = WINDOW_MS

        for (i in 1 until normalizedAmps.size) {
            val amp = normalizedAmps[i]
            if (abs(amp - currentAmp) <= tolerance) {
                currentDuration += WINDOW_MS
            } else {
                timings.add(currentDuration)
                amplitudes.add(currentAmp)
                currentAmp = amp
                currentDuration = WINDOW_MS
            }
        }
        timings.add(currentDuration)
        amplitudes.add(currentAmp)

        val total = timings.sum()
        return VibrationWaveform(timings.toLongArray(), amplitudes.toIntArray(), total)
    }
}
