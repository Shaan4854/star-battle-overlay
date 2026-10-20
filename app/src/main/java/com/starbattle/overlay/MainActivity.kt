package com.starbattle.overlay

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * Single-screen control panel.

 * The app needs exactly two grants:
 *
 *  1. `SYSTEM_ALERT_WINDOW`, so the transparent solution overlay can be drawn above the game;
 *  2. a one-time `MediaProjection` consent token, so a single frame can be read.

 * As soon as the token arrives the activity hands it to [CaptureService] and moves itself to the
 * back, so the game is on screen when the service grabs its one and only frame.
 */
class MainActivity : Activity() {

    companion object {
        private const val REQUEST_OVERLAY = 1001
        private const val REQUEST_PROJECTION = 1002
        private const val REQUEST_NOTIFICATIONS = 1003

        /** Time the user gets to bring the puzzle app to the front. */
        private const val CAPTURE_DELAY_MS = 2000L
    }

    private lateinit var statusView: TextView
    private lateinit var overlayButton: Button
    private lateinit var captureButton: Button
    private lateinit var clearButton: Button

    private val projectionManager: MediaProjectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    // -------------------------------------------------------------------------------------------
    // Flow
    // -------------------------------------------------------------------------------------------

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        try {
            startActivityForResult(intent, REQUEST_OVERLAY)
        } catch (e: ActivityNotFoundException) {
            // Some OEM builds only expose the global switch.
            try {
                startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION), REQUEST_OVERLAY)
            } catch (second: ActivityNotFoundException) {
                Toast.makeText(
                    this,
                    "This device has no overlay permission screen. Enable \"Display over other apps\" manually.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun requestScreenCapture() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Grant the overlay permission first", Toast.LENGTH_SHORT).show()
            requestOverlayPermission()
            return
        }
        try {
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_PROJECTION)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Screen capture is not available on this device", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_PROJECTION -> {
                if (resultCode == RESULT_OK && data != null) {
                    CaptureService.start(this, resultCode, data, CAPTURE_DELAY_MS)
                    refreshUi()
                    // Leave the stage to the game: the service grabs its frame in ~2 s.
                    if (!moveTaskToBack(true)) {
                        Toast.makeText(
                            this,
                            "Switch to the puzzle now - capturing in 2 seconds",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
                    refreshUi()
                }
            }

            REQUEST_OVERLAY -> refreshUi()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATIONS
                )
            }
        }
    }

    private fun refreshUi() {
        val overlayGranted = Settings.canDrawOverlays(this)
        overlayButton.text = if (overlayGranted) {
            "Overlay permission granted"
        } else {
            "1. Grant overlay permission"
        }
        overlayButton.isEnabled = !overlayGranted
        captureButton.isEnabled = overlayGranted

        statusView.text = if (overlayGranted) {
            "Overlay ready.\n\nOpen the Star Battle puzzle, then return here and tap " +
                "\"Capture puzzle\". You have about two seconds to switch back to the game " +
                "before the single frame is taken."
        } else {
            "The overlay permission is still missing.\n\nAndroid calls this \"Display over " +
                "other apps\". It is required to draw the green stars on top of the game."
        }
    }

    // -------------------------------------------------------------------------------------------
    // UI
    // -------------------------------------------------------------------------------------------

    private fun buildContentView(): ScrollView {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).roundToInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(0xFFF6F7F9.toInt())
        }

        val title = TextView(this).apply {
            text = "Star Battle Overlay"
            textSize = 24f
            setTextColor(0xFF101418.toInt())
            gravity = Gravity.START
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "Marks the solution on top of the game - no ads, no analytics, one frame only."
            textSize = 14f
            setTextColor(0xFF4A5260.toInt())
            setPadding(0, dp(6), 0, dp(20))
        }
        root.addView(subtitle)

        overlayButton = Button(this).apply {
            text = "1. Grant overlay permission"
            isAllCaps = false
            setOnClickListener { requestOverlayPermission() }
        }
        root.addView(overlayButton, buttonLayoutParams())

        captureButton = Button(this).apply {
            text = "2. Capture puzzle"
            isAllCaps = false
            setOnClickListener { requestScreenCapture() }
        }
        root.addView(captureButton, buttonLayoutParams())

        clearButton = Button(this).apply {
            text = "Clear overlay"
            isAllCaps = false
            setOnClickListener {
                CaptureService.clear(this@MainActivity)
                Toast.makeText(this@MainActivity, "Overlay cleared", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(clearButton, buttonLayoutParams())

        statusView = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF2A3038.toInt())
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(16), 0, dp(16))
        }
        root.addView(statusView)

        val notes = TextView(this).apply {
            text = "How it works\n" +
                "\u2022 One screen frame is captured with MediaProjection, then the projection " +
                "is released immediately.\n" +
                "\u2022 The board rectangle, its grid size and the region colours are computed " +
                "with plain pixel arithmetic.\n" +
                "\u2022 A backtracking solver places one star per row, column and region.\n" +
                "\u2022 The overlay is not touchable: every tap goes straight to the game."
            textSize = 13f
            setTextColor(0xFF5A6270.toInt())
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        root.addView(notes)

        return ScrollView(this).apply {
            setBackgroundColor(0xFFF6F7F9.toInt())
            addView(root)
        }
    }

    private fun buttonLayoutParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (10 * resources.displayMetrics.density).roundToInt()
        }
}
