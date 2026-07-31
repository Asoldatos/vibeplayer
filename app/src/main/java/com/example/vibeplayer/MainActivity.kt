package com.example.vibeplayer

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.vibeplayer.databinding.ActivityMainBinding
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val uiHandler = Handler(Looper.getMainLooper())

    private var selectedUri: Uri? = null
    private var waveform: VibrationWaveform? = null
    private var isAnalyzing = false

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            handlePickedFile(uri)
        }
    }

    private var playbackHandler: Handler? = null
    private var playbackRunnable: Runnable? = null
    private var playbackIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSelect.setOnClickListener {
            pickFileLauncher.launch(arrayOf("audio/mpeg", "audio/mp3", "audio/*"))
        }

        binding.btnPlay.setOnClickListener { playVibration() }
        binding.btnStop.setOnClickListener { stopVibration() }

        updateButtons()

        if (!vibrator.hasVibrator()) {
            binding.status.text = getString(R.string.no_vibrator_warning)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !vibrator.hasAmplitudeControl()) {
            binding.status.text = getString(R.string.no_amplitude_control_warning)
        }
    }

    private fun handlePickedFile(uri: Uri) {
        selectedUri = uri
        waveform = null
        binding.fileName.text = queryFileName(uri) ?: uri.lastPathSegment ?: "Selected file"
        binding.progress.progress = 0
        binding.progress.visibility = android.view.View.VISIBLE
        binding.status.text = getString(R.string.analyzing)
        updateButtons()

        isAnalyzing = true
        thread {
            try {
                val result = AudioToVibration.analyze(contentResolver, uri) { pct ->
                    uiHandler.post { binding.progress.progress = pct }
                }
                uiHandler.post {
                    waveform = result
                    isAnalyzing = false
                    binding.status.text = getString(
                        R.string.analysis_complete,
                        result.totalDurationMs / 1000.0
                    )
                    binding.progress.visibility = android.view.View.GONE
                    updateButtons()
                }
            } catch (e: Exception) {
                uiHandler.post {
                    isAnalyzing = false
                    binding.progress.visibility = android.view.View.GONE
                    binding.status.text = getString(R.string.analysis_failed, e.message ?: "unknown error")
                    updateButtons()
                }
            }
        }
    }

    private fun playVibration() {
        val wf = waveform ?: return
        stopVibration()

        val useComposition = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !vibrator.hasAmplitudeControl() &&
            runCatching {
                vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)
            }.getOrDefault(false)

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl() -> {
                val effect = VibrationEffect.createWaveform(wf.timings, wf.amplitudes, -1)
                vibrator.vibrate(effect)
                binding.status.text = getString(R.string.playing_vibration)
            }
            useComposition -> {
                playbackHandler = Handler(Looper.getMainLooper())
                playbackIndex = 0
                binding.status.text = getString(R.string.playing_vibration)
                playNextCompositionBatch(wf)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                val effect = VibrationEffect.createWaveform(wf.timings, wf.amplitudes, -1)
                vibrator.vibrate(effect)
                binding.status.text = getString(R.string.playing_vibration_no_intensity)
            }
            else -> {
                @Suppress("DEPRECATION")
                vibrator.vibrate(wf.timings, -1)
                binding.status.text = getString(R.string.playing_vibration)
            }
        }
    }

    private fun playNextCompositionBatch(wf: VibrationWaveform) {
        if (playbackIndex >= wf.timings.size) return

        val batchLimit = 40
        val composition = VibrationEffect.startComposition()
        var batchDurationMs = 0L
        var count = 0
        var i = playbackIndex

        while (i < wf.timings.size && count < batchLimit) {
            val durationMs = wf.timings[i]
            val amp = wf.amplitudes[i]
            if (amp > 0) {
                val scale = (amp / 255f).coerceIn(0.05f, 1f)
                composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale, 0)
            }
            batchDurationMs += durationMs
            i++
            count++
        }

        playbackIndex = i
        vibrator.vibrate(composition.compose())

        if (playbackIndex < wf.timings.size) {
            val runnable = Runnable { playNextCompositionBatch(wf) }
            playbackRunnable = runnable
            playbackHandler?.postDelayed(runnable, batchDurationMs)
        } else {
            playbackHandler?.postDelayed({ binding.status.text = getString(R.string.stopped) }, batchDurationMs)
        }
    }

    private fun stopVibration() {
        playbackRunnable?.let { playbackHandler?.removeCallbacks(it) }
        playbackRunnable = null
        vibrator.cancel()
        binding.status.text = getString(R.string.stopped)
    }

    private fun updateButtons() {
        binding.btnPlay.isEnabled = waveform != null && !isAnalyzing
        binding.btnStop.isEnabled = waveform != null
        binding.btnSelect.isEnabled = !isAnalyzing
    }

    private fun queryFileName(uri: Uri): String? {
        val cursor = contentResolver.query(uri, null, null, null, null) ?: return null
        cursor.use {
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && it.moveToFirst()) {
                return it.getString(nameIndex)
            }
        }
        return null
    }

    override fun onPause() {
        super.onPause()
        stopVibration()
    }
}
