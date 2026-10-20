package com.starbattle.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.PointF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import kotlin.math.hypot

/**
 * Foreground Service managing the GameGuardian-style floating HUD and transparent target overlay.
 *
 * Architecture:
 * - View A (Menu/Bubble): TYPE_APPLICATION_OVERLAY, FLAG_NOT_FOCUSABLE, smooth dragging via rawX/rawY.
 * - View B (Target Reticles): TYPE_APPLICATION_OVERLAY, FLAG_NOT_TOUCHABLE, FLAG_NOT_FOCUSABLE.
 * - Zero Idle CPU: VirtualDisplay & ImageReader are spawned on-demand for exactly ONE frame when
 *   "Solve" is pressed and destroyed immediately after.
 */
class FloatingMenuService : Service() {

    companion object {
        const val CHANNEL_ID = "star_battle_service_channel"
        const val NOTIFICATION_ID = 9021
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_TARGET_PACKAGE = "extra_target_package"

        fun startServiceIntent(context: Context, resultCode: Int, data: Intent, targetPackage: String?): Intent {
            return Intent(context, FloatingMenuService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
            }
        }
    }

    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    // Screen projection
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var captureHandlerThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    // Window Views
    private var overlayCanvasView: OverlayCanvasView? = null // View B
    private var bubbleView: View? = null                    // View A (Collapsed)
    private var menuView: View? = null                      // View A (Expanded)
    private var activeMenuLayout: View? = null
    private lateinit var layoutParamsA: WindowManager.LayoutParams
    private lateinit var layoutParamsB: WindowManager.LayoutParams

    private var isExpanded = false
    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensityDpi = 420

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        captureHandlerThread = HandlerThread("StarBattleScreenCapture").apply { start() }
        captureHandler = Handler(captureHandlerThread!!.looper)

        queryScreenMetrics()
        createNotificationChannel()
        setupViewBOverlay()
        setupViewAMenu()
    }

    private fun queryScreenMetrics() {
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensityDpi = metrics.densityDpi
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Star Battle HUD Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Zero-idle-CPU Star Battle puzzle solver overlay"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Star Battle Mod Active")
            .setContentText("HUD ready. Tap bubble in-game to solve.")
            .setSmallIcon(R.drawable.ic_star_logo)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Initialize MediaProjection from intent tokens
        intent?.let {
            val resultCode = it.getIntExtra(EXTRA_RESULT_CODE, 0)
            val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                it.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            }
            if (resultCode != 0 && resultData != null && mediaProjection == null) {
                mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
            }
        }

        return START_NOT_STICKY
    }

    /**
     * View B: Fullscreen transparent Canvas View.
     * Must be TYPE_APPLICATION_OVERLAY, FLAG_NOT_TOUCHABLE, and FLAG_NOT_FOCUSABLE.
     */
    private fun setupViewBOverlay() {
        overlayCanvasView = OverlayCanvasView(this)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParamsB = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        windowManager.addView(overlayCanvasView, layoutParamsB)
    }

    /**
     * View A: Floating Mod Menu / GameGuardian Bubble.
     * Uses TYPE_APPLICATION_OVERLAY, FLAG_NOT_FOCUSABLE.
     * Implements smooth dragging via event.rawX and event.rawY.
     */
    private fun setupViewAMenu() {
        val inflater = LayoutInflater.from(this)
        bubbleView = inflater.inflate(R.layout.layout_floating_bubble, null)
        menuView = inflater.inflate(R.layout.layout_floating_menu, null)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParamsA = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 350
        }

        // Attach OnTouchListener to bubble for dragging and tapping
        setupDraggableTouchListener(bubbleView!!, isMenuRoot = false)
        bubbleView!!.findViewById<View>(R.id.bubble_container)?.setOnClickListener {
            expandMenu()
        }

        // Setup expanded menu controls
        setupExpandedMenuControls()

        // Initially attach the collapsed bubble
        activeMenuLayout = bubbleView
        windowManager.addView(activeMenuLayout, layoutParamsA)
    }

    private fun setupExpandedMenuControls() {
        val menu = menuView ?: return

        // Drag handle zone in the menu header
        val header = menu.findViewById<View>(R.id.menu_header)
        setupDraggableTouchListener(header, isMenuRoot = true)

        // Minimize Button
        menu.findViewById<ImageButton>(R.id.btn_minimize)?.setOnClickListener {
            collapseMenu()
        }

        // Solve Button
        menu.findViewById<Button>(R.id.btn_solve)?.setOnClickListener {
            executeSolveAction()
        }

        // Clear Button
        menu.findViewById<Button>(R.id.btn_clear)?.setOnClickListener {
            overlayCanvasView?.clearTargets()
            updateMenuStatus("CLEARED", "#00FF88")
        }

        // Close Button
        menu.findViewById<Button>(R.id.btn_close)?.setOnClickListener {
            stopSelf()
        }
    }

    private fun expandMenu() {
        if (isExpanded) return
        isExpanded = true

        windowManager.removeView(activeMenuLayout)
        activeMenuLayout = menuView
        windowManager.addView(activeMenuLayout, layoutParamsA)
    }

    private fun collapseMenu() {
        if (!isExpanded) return
        isExpanded = false

        windowManager.removeView(activeMenuLayout)
        activeMenuLayout = bubbleView
        windowManager.addView(activeMenuLayout, layoutParamsA)
    }

    /**
     * Implements smooth dragging using event.rawX and event.rawY.
     * Prevents drag jitter and cleanly isolates taps from drags.
     */
    private fun setupDraggableTouchListener(targetView: View, isMenuRoot: Boolean) {
        targetView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var touchStartX = 0f
            private var touchStartY = 0f
            private var isDragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParamsA.x
                        initialY = layoutParamsA.y
                        touchStartX = event.rawX
                        touchStartY = event.rawY
                        isDragging = false
                        return false // Allow child click events if not dragged
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchStartX).toInt()
                        val dy = (event.rawY - touchStartY).toInt()

                        if (!isDragging && hypot(dx.toDouble(), dy.toDouble()) > 12) {
                            isDragging = true
                        }

                        if (isDragging) {
                            layoutParamsA.x = initialX + dx
                            layoutParamsA.y = initialY + dy
                            try {
                                windowManager.updateViewLayout(activeMenuLayout, layoutParamsA)
                            } catch (_: Exception) {}
                            return true
                        }
                    }

                    MotionEvent.ACTION_UP -> {
                        if (isDragging) {
                            return true // Consumed drag event
                        } else if (!isMenuRoot) {
                            // Tap on bubble
                            expandMenu()
                            return true
                        }
                    }
                }
                return false
            }
        })
    }

    /**
     * Single-shot screen capture and solver pipeline:
     * 1. Acquire exactly ONE frame from MediaProjection via ImageReader.
     * 2. Immediately close image and tear down capture surfaces (Zero Idle CPU).
     * 3. Detect grid and group RGB center-pixels into region IDs.
     * 4. Solve Star Battle CSP.
     * 5. Animate target solution reticles on View B.
     */
    private fun executeSolveAction() {
        val proj = mediaProjection
        if (proj == null) {
            Toast.makeText(this, "Media projection not ready. Re-launch mod from app.", Toast.LENGTH_SHORT).show()
            return
        }

        updateMenuStatus("CAPTURING...", "#FFB300")
        queryScreenMetrics()

        // Temporarily hide View A so it does not occlude the puzzle board in the screenshot
        activeMenuLayout?.visibility = View.INVISIBLE

        val imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        var virtualDisplay: VirtualDisplay? = null

        imageReader.setOnImageAvailableListener(object : ImageReader.OnImageAvailableListener {
            private var processed = false

            override fun onImageAvailable(reader: ImageReader) {
                if (processed) return
                val image = reader.acquireLatestImage() ?: return
                processed = true

                // Restore View A visibility immediately
                mainHandler.post {
                    activeMenuLayout?.visibility = View.VISIBLE
                    updateMenuStatus("ANALYZING...", "#00E5FF")
                }

                backgroundExecutor.execute {
                    try {
                        val plane = image.planes[0]
                        val buffer = plane.buffer
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride
                        val rowPadding = rowStride - pixelStride * screenWidth

                        val rawBitmap = Bitmap.createBitmap(
                            screenWidth + rowPadding / pixelStride,
                            screenHeight,
                            Bitmap.Config.ARGB_8888
                        )
                        rawBitmap.copyPixelsFromBuffer(buffer)

                        // 100% required: close image immediately to release native graphic buffer
                        image.close()

                        // Release virtual display & reader right away (Zero Idle CPU)
                        virtualDisplay?.release()
                        imageReader.close()

                        val screenBitmap = if (rowPadding == 0) {
                            rawBitmap
                        } else {
                            val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, screenWidth, screenHeight)
                            rawBitmap.recycle()
                            cropped
                        }

                        processFrameAndSolve(screenBitmap)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        mainHandler.post {
                            updateMenuStatus("ERROR", "#FF5252")
                        }
                    }
                }
            }
        }, captureHandler)

        virtualDisplay = proj.createVirtualDisplay(
            "StarBattleCapture",
            screenWidth,
            screenHeight,
            screenDensityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            captureHandler
        )
    }

    private fun processFrameAndSolve(bitmap: Bitmap) {
        val detection = GridDetector.detectAndParse(bitmap)
        bitmap.recycle() // Release screenshot memory

        if (detection == null) {
            mainHandler.post {
                updateMenuStatus("BOARD NOT FOUND", "#FF5252")
                Toast.makeText(this, "Could not detect puzzle board. Open game first.", Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Pass extracted matrix to pure CSP solver
        val solution = StarBattleSolver.solve(detection.n, detection.regionMatrix)

        mainHandler.post {
            if (solution != null && solution.isNotEmpty()) {
                val targetPoints = solution.map { (r, c) ->
                    PointF(
                        detection.boardBounds.left + (c + 0.5f) * detection.cellWidth,
                        detection.boardBounds.top + (r + 0.5f) * detection.cellHeight
                    )
                }

                overlayCanvasView?.setTargetCoordinates(targetPoints, detection.boardBounds)
                updateMenuStatus("SOLVED! (${detection.n}x${detection.n})", "#00FF88")
                collapseMenu()
            } else {
                updateMenuStatus("NO SOLUTION", "#FF5252")
                Toast.makeText(this, "Solver could not find solution for detected regions", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateMenuStatus(text: String, hexColor: String) {
        val menu = menuView ?: return
        val tvStatus = menu.findViewById<TextView>(R.id.tv_status)
        val dot = menu.findViewById<View>(R.id.status_dot)

        tvStatus?.text = text
        tvStatus?.setTextColor(android.graphics.Color.parseColor(hexColor))
        dot?.setBackgroundColor(android.graphics.Color.parseColor(hexColor))
    }

    override fun onDestroy() {
        super.onDestroy()
        captureHandlerThread?.quitSafely()
        backgroundExecutor.shutdown()

        try {
            overlayCanvasView?.let { windowManager.removeView(it) }
            activeMenuLayout?.let { windowManager.removeView(it) }
        } catch (_: Exception) {}

        mediaProjection?.stop()
        mediaProjection = null
    }
}
