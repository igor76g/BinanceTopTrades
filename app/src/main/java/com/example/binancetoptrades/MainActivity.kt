package com.example.binancetoptrades

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.binancetoptrades.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                toast("Notifications are blocked - you will not see the list. Enable them in system settings.")
            }
            startMonitoring()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        showSettings(SettingsStore.load(this))

        binding.saveButton.setOnClickListener { readAndSaveSettings() }
        binding.startButton.setOnClickListener {
            if (readAndSaveSettings() != null) ensurePermissionThenStart()
        }
        binding.stopButton.setOnClickListener {
            stopService(Intent(this, MonitorService::class.java))
        }

        // Live status and list from the service
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { MonitorService.status.collect { binding.statusText.text = it } }
                launch { MonitorService.listText.collect { binding.listText.text = it } }
            }
        }
    }

    private fun showSettings(s: Settings) {
        binding.volumeInput.setText(s.minVolumeMillionUsdt.toString())
        binding.natrInput.setText(s.minNatr.toString())
        binding.tradesInput.setText(s.minTrades.toString())
        binding.countInput.setText(s.topCount.toString())
        binding.windowInput.setText(s.windowMinutes.toString())
        binding.intervalInput.setText(s.checkIntervalMinutes.toString())
        binding.emptyCheck.isChecked = s.notifyWhenEmpty
        binding.soundCheck.isChecked = s.soundEveryUpdate
    }

    /** Validates the form and stores the settings. Returns null if some field is invalid. */
    private fun readAndSaveSettings(): Settings? {
        val volume = binding.volumeInput.text.toString().trim().toDoubleOrNull()
        val natr = binding.natrInput.text.toString().trim().toDoubleOrNull()
        val trades = binding.tradesInput.text.toString().trim().toIntOrNull()
        val count = binding.countInput.text.toString().trim().toIntOrNull()
        val window = binding.windowInput.text.toString().trim().toIntOrNull()
        val interval = binding.intervalInput.text.toString().trim().toIntOrNull()

        if (volume == null || volume < 0 ||
            natr == null || natr < 0 ||
            trades == null || trades < 0 ||
            count == null || count <= 0 ||
            window == null || window <= 0 ||
            interval == null || interval <= 0
        ) {
            toast("Please fill in all fields with valid numbers")
            return null
        }

        val settings = Settings(
            minVolumeMillionUsdt = volume,
            minNatr = natr,
            minTrades = trades,
            topCount = count,
            windowMinutes = window,
            checkIntervalMinutes = interval,
            notifyWhenEmpty = binding.emptyCheck.isChecked,
            soundEveryUpdate = binding.soundCheck.isChecked
        )
        SettingsStore.save(this, settings)
        toast("Settings saved")
        return settings
    }

    private fun ensurePermissionThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startMonitoring()
        }
    }

    /** Without this exemption Android (Doze / battery saver) stops monitoring when the screen is off. */
    private fun requestBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                toast("Open Settings > Apps > Binance Top Trades > Battery > Unrestricted")
            }
        }
    }

    private fun startMonitoring() {
        requestBatteryExemption()
        ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java))
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
