package com.example.project12

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.sqrt

class WakeWordMonitor(
    private val sampleRate: Int = 16000,
    private val energyThreshold: Double = VoiceAssistantService.ENERGY_THRESHOLD,
    private val onWake: () -> Unit
) {

    private var isRunning = false
    private var thread: Thread? = null

    fun start() {
        if (isRunning) return
        isRunning = true
        thread = Thread {
            runMonitoring()
        }
        thread?.start()
    }

    fun stopMonitoring() {
        isRunning = false
        thread?.interrupt()
        thread = null
    }

    private fun runMonitoring() {
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(1024)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        recorder.startRecording()
        val buffer = ShortArray(bufferSize / 2)

        try {
            while (isRunning && !Thread.currentThread().isInterrupted) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val energy = calculateEnergy(buffer, read)
                    if (energy > energyThreshold) {
                        onWake()
                        break
                    }
                }
            }
        } finally {
            recorder.stop()
            recorder.release()
        }
    }

    private fun calculateEnergy(samples: ShortArray, length: Int): Double {
        var sum = 0.0
        for (i in 0 until length) {
            val s = samples[i].toDouble() / 32768.0
            sum += s * s
        }
        return sqrt(sum / length) * 10000.0
    }
}