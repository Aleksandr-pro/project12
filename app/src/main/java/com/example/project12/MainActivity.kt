package com.example.project12

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnToggle: Button
    private var isRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnToggle = findViewById(R.id.btnToggle)

        btnToggle.setOnClickListener { toggleService() }
        updateUI()
    }

    private fun toggleService() {
        if (!hasPermissions()) {
            requestPermissions()
            return
        }

        if (isRunning) {
            stopAssistantService()
        } else {
            startAssistantService()
        }
    }

    private fun startAssistantService() {
        val intent = Intent(this, VoiceAssistantService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        isRunning = true
        updateUI()
    }

    private fun stopAssistantService() {
        val intent = Intent(this, VoiceAssistantService::class.java)
        stopService(intent)
        isRunning = false
        updateUI()
    }

    private fun updateUI() {
        tvStatus.text = if (isRunning) getString(R.string.status_running) else getString(R.string.status_stopped)
        tvStatus.setTextColor(
            if (isRunning) ContextCompat.getColor(this, R.color.statusRunning)
            else ContextCompat.getColor(this, R.color.statusStopped)
        )
        btnToggle.text = if (isRunning) getString(R.string.btn_stop) else getString(R.string.btn_start)
    }

    private fun hasPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1001)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && hasPermissions()) {
            startAssistantService()
        } else {
            tvStatus.text = getString(R.string.error_permissions)
        }
    }
}
