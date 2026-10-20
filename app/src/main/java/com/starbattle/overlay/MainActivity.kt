package com.starbattle.overlay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Modern cyberpunk setup dashboard for configuring overlay permissions,
 * capturing MediaProjection consent, and launching the Star Battle floating mod HUD.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvOverlayStatus: TextView
    private lateinit var btnGrantOverlay: Button
    private lateinit var etTargetPackage: EditText
    private lateinit var btnLaunch: Button

    private lateinit var mediaProjectionManager: MediaProjectionManager

    // Overlay settings launcher
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateOverlayStatus()
    }

    // Media projection consent launcher
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startFloatingModServiceAndLaunchGame(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Screen capture consent is required to detect puzzles", Toast.LENGTH_LONG).show()
        }
    }

    // Notification permission launcher for Android 13+ (API 33+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Notification granted/denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        initViews()
        requestNotificationPermissionIfRequired()
    }

    override fun onResume() {
        super.onResume()
        updateOverlayStatus()
    }

    private fun initViews() {
        tvOverlayStatus = findViewById(R.id.tv_overlay_status)
        btnGrantOverlay = findViewById(R.id.btn_grant_overlay)
        etTargetPackage = findViewById(R.id.et_target_package)
        btnLaunch = findViewById(R.id.btn_launch_game_and_menu)

        btnGrantOverlay.setOnClickListener {
            requestOverlayPermission()
        }

        btnLaunch.setOnClickListener {
            handleLaunchSequence()
        }
    }

    private fun updateOverlayStatus() {
        val hasOverlay = Settings.canDrawOverlays(this)
        if (hasOverlay) {
            tvOverlayStatus.text = "GRANTED"
            tvOverlayStatus.setTextColor(Color.parseColor("#00FF88"))
            btnGrantOverlay.isEnabled = false
            btnGrantOverlay.text = "Permission Active ✓"
            btnGrantOverlay.alpha = 0.5f
        } else {
            tvOverlayStatus.text = "NOT GRANTED"
            tvOverlayStatus.setTextColor(Color.parseColor("#FF5252"))
            btnGrantOverlay.isEnabled = true
            btnGrantOverlay.text = getString(R.string.btn_grant_overlay)
            btnGrantOverlay.alpha = 1.0f
        }
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun requestNotificationPermissionIfRequired() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun handleLaunchSequence() {
        // 1. Verify Overlay Permission
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please grant Overlay Permission first", Toast.LENGTH_SHORT).show()
            requestOverlayPermission()
            return
        }

        // 2. Request MediaProjection Consent
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        mediaProjectionLauncher.launch(captureIntent)
    }

    private fun startFloatingModServiceAndLaunchGame(resultCode: Int, data: Intent) {
        val targetPackage = etTargetPackage.text.toString().trim()

        // 3. Start Floating Foreground Service
        val serviceIntent = FloatingMenuService.startServiceIntent(
            context = this,
            resultCode = resultCode,
            data = data,
            targetPackage = targetPackage.ifEmpty { null }
        )
        ContextCompat.startForegroundService(this, serviceIntent)

        // 4. Open Target Game
        if (targetPackage.isNotEmpty()) {
            val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                Toast.makeText(
                    this,
                    "Mod active! Package '$targetPackage' not installed. Open your game manually.",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            Toast.makeText(this, "Floating Mod Menu Launched!", Toast.LENGTH_SHORT).show()
        }

        // Minimize launcher activity to bring game into focus
        moveTaskToBack(true)
    }
}
