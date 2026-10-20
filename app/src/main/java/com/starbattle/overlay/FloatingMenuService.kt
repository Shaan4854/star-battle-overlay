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
import android.graphics.Color
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
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.hypot

/**
 * Crash-Proof Foreground Service managing the GameGuardian-style floating HUD
 * and transparent target overlay for Star Battle / LinkedIn Queens.
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
    private var projectionResultCode = 0
    private var projectionResultData: Intent? = null
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
    private var isSolving = false

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
            if (resultCode != 0 && resultData != null) {
                projectionResultCode = resultCode
                projectionResultData = resultData
                initOrRenewMediaProjection()
            }
        }

        return START_NOT_STICKY
    }

    private fun initOrRenewMediaProjection(): MediaProjection? {
        try {
            if (projectionResultCode != 0 && projectionResultData != null) {
                mediaProjection?.stop()
                val proj = mediaProjectionManager?.getMediaProjection(projectionResultCode, projectionResultData!!)
                // CRITICAL FOR ANDROID 14 (API 34): Must register callback before creating virtual display
                proj?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        super.onStop()
                        mediaProjection = null
                    }
                }, captureHandler)
                mediaProjection = proj
                return proj
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return mediaProjection
    }

    /**
     * View B: Fullscreen transparent Canvas View.
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

        setupDraggableTouchListener(bubbleView!!, isMenuRoot = false)
        bubbleView!!.findViewById<View>(R.id.bubble_container)?.setOnClickListener {
            expandMenu()
        }

        setupExpandedMenuControls()

        activeMenuLayout = bubbleView
        windowManager.addView(activeMenuLayout, layoutParamsA)
    }

    private fun setupExpandedMenuControls() {
        val menu = menuView ?: return

        val header = menu.findViewById<View>(R.id.menu_header)
        setupDraggableTouchListener(header, isMenuRoot = true)

        menu.findViewById<ImageButton>(R.id.btn_minimize)?.setOnClickListener {
            collapseMenu()
        }

        menu.findViewById<Button>(R.id.btn_solve)?.setOnClickListener {
            if (!isSolving) {
                executeSolveAction()
            }
        }

        menu.findViewById<Button>(R.id.btn_clear)?.setOnClickListener {
            overlayCanvasView?.clearTargets()
            updateMenuStatus("CLEARED", "#00FF88")
        }

        menu.findViewById<Button>(R.id.btn_close)?.setOnClickListener {
            stopSelf()
        }
    }

    private fun expandMenu() {
        if (isExpanded) return
        isExpanded = true

        try {
            windowManager.removeView(activeMenuLayout)
            activeMenuLayout = menuView
            windowManager.addView(activeMenuLayout, layoutParamsA)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun collapseMenu() {
        if (!isExpanded) return
        isExpanded = false

        try {
            windowManager.removeView(activeMenuLayout)
            activeMenuLayout = bubbleView
            windowManager.addView(activeMenuLayout, layoutParamsA)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

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
                        return false
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
                            return true
                        } else if (!isMenuRoot) {
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
     * Crash-Proof Single-Shot Screen Capture & Solver.
     */
    private fun executeSolveAction() {
        isSolving = true
        var proj = mediaProjection
        if (proj == null) {
            proj = initOrRenewMediaProjection()
        }

        if (proj == null) {
            isSolving = false
            Toast.makeText(this, "Screen capture permission expired. Tap 'Launch Game & Menu' in app.", Toast.LENGTH_LONG).show()
            updateMenuStatus("RELAUNCH APP", "#FF5252")
            return
        }

        updateMenuStatus("CAPTURING...", "#FFB300")
        queryScreenMetrics()

        // Temporarily hide View A so it doesn't obstruct screenshot
        activeMenuLayout?.alpha = 0f

        var virtualDisplay: VirtualDisplay? = null

        try {
            val imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            val handler = captureHandler ?: Handler(Looper.getMainLooper())

            imageReader.setOnImageAvailableListener(object : ImageReader.OnImageAvailableListener {
                private var handled = false

                override fun onImageAvailable(reader: ImageReader) {
                    if (handled) return
                    handled = true

                    mainHandler.post {
                        activeMenuLayout?.alpha = 1.0f
                        updateMenuStatus("ANALYZING...", "#00E5FF")
                    }

                    val image = reader.acquireLatestImage()
                    if (image == null) {
                        isSolving = false
                        mainHandler.post { updateMenuStatus("IMAGE EMPTY", "#FF5252") }
                        return
                    }

                    backgroundExecutor.execute {
                        try {
                            val bitmap = extractBitmapSafely(image, screenWidth, screenHeight)
                            image.close() // Immediately close image to prevent memory leak

                            try {
                                virtualDisplay?.release()
                                imageReader.close()
                            } catch (_: Exception) {}

                            if (bitmap != null) {
                                processFrameAndSolve(bitmap)
                            } else {
                                mainHandler.post {
                                    isSolving = false
                                    updateMenuStatus("CAPTURE FAILED", "#FF5252")
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            mainHandler.post {
                                isSolving = false
                                updateMenuStatus("PARSE ERROR", "#FF5252")
                            }
                        }
                    }
                }
            }, handler)

            virtualDisplay = proj.createVirtualDisplay(
                "StarBattleCapture",
                screenWidth,
                screenHeight,
                screenDensityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                handler
            )
        } catch (e: Exception) {
            e.printStackTrace()
            activeMenuLayout?.alpha = 1.0f
            isSolving = false
            updateMenuStatus("CAPTURE ERROR", "#FF5252")
            Toast.makeText(this, "Capture error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Safely reads pixel data from android.media.Image without throwing BufferUnderflowException.
     */
    private fun extractBitmapSafely(image: android.media.Image, width: Int, height: Int): Bitmap? {
        return try {
            val plane = image.planes[0]
            val buffer: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(width * height)

            val rowData = ByteArray(rowStride)
            for (row in 0 until height) {
                val bytesToRead = if (row == height - 1) {
                    buffer.remaining().coerceAtMost(rowStride)
                } else {
                    rowStride
                }
                buffer.get(rowData, 0, bytesToRead)

                val offset = row * width
                for (col in 0 until width) {
                    val pixelIndex = col * pixelStride
                    if (pixelIndex + 3 < bytesToRead) {
                        val r = rowData[pixelIndex].toInt() and 0xFF
                        val g = rowData[pixelIndex + 1].toInt() and 0xFF
                        val b = rowData[pixelIndex + 2].toInt() and 0xFF
                        val a = rowData[pixelIndex + 3].toInt() and 0xFF
                        pixels[offset + col] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            }

            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun processFrameAndSolve(bitmap: Bitmap) {
        try {
            val detection = GridDetector.detectAndParse(bitmap)
            bitmap.recycle()

            if (detection == null) {
                mainHandler.post {
                    isSolving = false
                    updateMenuStatus("BOARD NOT FOUND", "#FF5252")
                    Toast.makeText(this, "Could not locate puzzle grid. Ensure board is visible.", Toast.LENGTH_SHORT).show()
                }
                return
            }

            val solution = StarBattleSolver.solve(detection.n, detection.regionMatrix)

            mainHandler.post {
                isSolving = false
                if (solution != null && solution.isNotEmpty()) {
                    val targetPoints = solution.map { (r, c) ->
                        PointF(
                            detection.boardBounds.left + (c + 0.5f) * detection.cellWidth,
                            detection.boardBounds.top + (r + 0.5f) * detection.cellHeight
                        )
                    }

                    overlayCanvasView?.setTargetCoordinates(targetPoints, detection.boardBounds)
                    updateMenuStatus("SOLVED! (${detection.n} Queens)", "#00FF88")
                    collapseMenu()
                } else {
                    updateMenuStatus("NO SOLUTION", "#FF5252")
                    Toast.makeText(this, "Solver could not find valid solution for detected regions", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            mainHandler.post {
                isSolving = false
                updateMenuStatus("SOLVER ERROR", "#FF5252")
            }
        }
    }

    private fun updateMenuStatus(text: String, hexColor: String) {
        val menu = menuView ?: return
        val tvStatus = menu.findViewById<TextView>(R.id.tv_status)
        val dot = menu.findViewById<View>(R.id.status_dot)

        tvStatus?.text = text
        tvStatus?.setTextColor(Color.parseColor(hexColor))
        dot?.setBackgroundColor(Color.parseColor(hexColor))
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
