package com.example.project12

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.sqrt

class WakeWordMonitor(
    private val sampleRate: Int = 16000,
    private val energyThreshold: Double = VoiceAssistantService.ENERGY_THRESHOLD,
    private val onWake: () -> Unit
) {

    @Volatile
    private var isRunning = false
    private var thread: Thread? = null

    fun start() {
        if (isRunning) return
        isRunning = true
        thread = Thread {
            runMonitoring()
        }.also { it.start() }
    }

    fun stopMonitoring() {
        isRunning = false
        thread?.interrupt()
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun runMonitoring() {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            isRunning = false
            return
        }
        val bufferSize = minBuf.coerceAtLeast(2048)

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (_: Exception) {
            isRunning = false
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            isRunning = false
            return
        }

        var triggered = false
        val buffer = ShortArray(bufferSize / 2)

        try {
            recorder.startRecording()
            while (isRunning && !Thread.currentThread().isInterrupted) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val energy = calculateEnergy(buffer, read)
                    if (energy > energyThreshold) {
                        triggered = true
                        break
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore interruption / stop exceptions
        } finally {
            isRunning = false
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
            } catch (_: Exception) {
            }
            try {
                recorder.release()
            } catch (_: Exception) {
            }
        }

        if (triggered) {
            onWake()
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
