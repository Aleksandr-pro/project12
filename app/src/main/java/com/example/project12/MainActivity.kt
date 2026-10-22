package com.example.project12

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.project12.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnToggle.setOnClickListener { toggleService() }
        updateUI()
    }

    private fun toggleService() {
        if (!hasPermissions()) {
            requestPermissions()
            return
        }

        if (isRunning) {
            stopService()
        } else {
            startService()
        }
    }

    private fun startService() {
        val intent = android.content.Intent(this, VoiceAssistantService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        isRunning = true
        updateUI()
    }

    private fun stopService() {
        val intent = android.content.Intent(this, VoiceAssistantService::class.java)
        stopService(intent)
        isRunning = false
        updateUI()
    }

    private fun updateUI() {
        binding.tvStatus.text = if (isRunning) getString(R.string.status_running) else getString(R.string.status_stopped)
        binding.tvStatus.setTextColor(
            if (isRunning) ContextCompat.getColor(this, R.color.statusRunning)
            else ContextCompat.getColor(this, R.color.statusStopped)
        )
        binding.btnToggle.text = if (isRunning) getString(R.string.btn_stop) else getString(R.string.btn_start)
    }

    private fun hasPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.INTERNET),
            1001
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startService()
        } else {
            binding.tvStatus.text = getString(R.string.error_permissions)
        }
    }
}