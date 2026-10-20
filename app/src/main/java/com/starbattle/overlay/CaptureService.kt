package com.starbattle.overlay

import android.app.Activity
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
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import com.starbattle.overlay.overlay.OverlayCanvasView
import com.starbattle.overlay.overlay.OverlayTargets
import com.starbattle.overlay.solver.Cell
import com.starbattle.overlay.solver.StarBattleSolver
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Foreground service that grabs **exactly one** screen frame, solves the Star Battle board that is
 * visible on it and paints the solution with [OverlayCanvasView].

 * Capture lifecycle:

 *  1. `startForeground()` is called before anything else - Android 14 refuses
 *     `getMediaProjection()` unless a foreground service with the `mediaProjection` type is already
 *     running.
 *  2. `MediaProjection` + a single `ImageReader`/`VirtualDisplay` pair are created after a short
 *     grace period so the user can switch back to the game.
 *  3. The first frame is copied into a software `Bitmap`; the `ImageReader`, the `VirtualDisplay`
 *     and the `MediaProjection` are released immediately afterwards. No second frame is ever
 *     requested, and no capture resource outlives the analysis.
 *  4. The bitmap is analysed on a single background thread with plain arithmetic (no OpenCV, no ML
 *     kit, no allocation-heavy image ops), then recycled.

 * The service itself stays alive afterwards - idle, with no timers, wakelocks or handlers running -
 * only so the overlay window and the "Clear" notification action keep working.
 */
class CaptureService : Service() {

    companion object {
        private const val TAG = "StarBattleCapture"

        const val ACTION_START = "com.starbattle.overlay.action.START"
        const val ACTION_CLEAR = "com.starbattle.overlay.action.CLEAR"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_CAPTURE_DELAY_MS = "extra_capture_delay_ms"

        /**
         * Starts a capture session. [data] is the `Intent` returned by
         * `MediaProjectionManager.createScreenCaptureIntent()`.
         */
        fun start(context: Context, resultCode: Int, data: Intent, delayMillis: Long = DEFAULT_CAPTURE_DELAY_MS) {
            val intent = Intent(context, CaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_CAPTURE_DELAY_MS, delayMillis)
            }
            context.startForegroundService(intent)
        }

        /** Removes the overlay without starting a new capture. */
        fun clear(context: Context) {
            val intent = Intent(context, CaptureService::class.java).apply {
                action = ACTION_CLEAR
            }
            context.startService(intent)
        }
    }

    private lateinit var mainHandler: Handler
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "star-battle-analysis").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    private var captureThread: HandlerThread? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null

    private var overlay: OverlayCanvasView? = null

    private var pendingCapture: Runnable? = null
    private val frameClaimed = AtomicBoolean(false)
    private var releasing = false
    private var destroyed = false

    // -------------------------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        mainHandler = Handler(Looper.getMainLooper())
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CLEAR -> {
                clearOverlay()
                shutdown()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                // Must be foreground *before* MediaProjection is touched on Android 14.
                startForegroundWithType("Capturing the puzzle")
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = intent.projectionResultData()
                val delay = intent.getLongExtra(EXTRA_CAPTURE_DELAY_MS, DEFAULT_CAPTURE_DELAY_MS)
                    .coerceIn(0L, 15_000L)
                beginCapture(resultCode, data, delay)
                return START_NOT_STICKY
            }

            else -> {
                // The system will not restart us (START_NOT_STICKY), but be defensive.
                Log.i(TAG, "Service started without an action - stopping")
                shutdown()
                return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        cancelPendingCapture()
        releaseProjection()
        overlay?.hide()
        overlay = null
        if (!worker.isShutdown) worker.shutdown()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------------------------
    // Capture
    // -------------------------------------------------------------------------------------------

    private fun beginCapture(resultCode: Int, data: Intent?, delayMillis: Long) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.e(TAG, "MediaProjection consent missing (resultCode=$resultCode)")
            report("Screen capture permission was denied")
            return
        }
        if (projection != null) {
            Log.i(TAG, "A capture session is already running")
            return
        }

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val newProjection = try {
            manager.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            Log.e(TAG, "getMediaProjection() failed", t)
            null
        }
        if (newProjection == null) {
            report("Could not start screen capture")
            return
        }

        projection = newProjection
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection was stopped")
                releaseProjection()
            }
        }
        projectionCallback = callback
        newProjection.registerCallback(callback, mainHandler)

        if (delayMillis > 0L) {
            val runnable = Runnable { startVirtualDisplay() }
            pendingCapture = runnable
            mainHandler.postDelayed(runnable, delayMillis)
        } else {
            startVirtualDisplay()
        }
    }

    /**
     * Creates the one and only virtual display + image reader pair.
     */
    private fun startVirtualDisplay() {
        pendingCapture = null
        if (destroyed || projection == null || imageReader != null) return

        val metrics = readScreenMetrics()
        val reader = try {
            ImageReader.newInstance(metrics.width, metrics.height, PixelFormat.RGBA_8888, 2)
        } catch (t: Throwable) {
            Log.e(TAG, "Could not create the ImageReader", t)
            null
        }
        if (reader == null) {
            report("Could not allocate the screen buffer")
            return
        }
        imageReader = reader

        val thread = HandlerThread("star-battle-capture").apply { start() }
        captureThread = thread
        val handler = Handler(thread.looper)
        reader.setOnImageAvailableListener({ r -> onFrameAvailable(r) }, handler)

        val display = try {
            projection?.createVirtualDisplay(
                "StarBattleCapture",
                metrics.width,
                metrics.height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )
        } catch (t: Throwable) {
            Log.e(TAG, "createVirtualDisplay() failed", t)
            null
        }
        if (display == null) {
            Log.e(TAG, "Virtual display could not be created")
            releaseProjection()
            report("Screen capture could not start")
            return
        }
        virtualDisplay = display
    }

    /**
     * Runs on the capture thread. Copies the first frame, then releases everything capture related.
     */
    private fun onFrameAvailable(reader: ImageReader) {
        if (!frameClaimed.compareAndSet(false, true)) return

        val image: Image? = try {
            reader.acquireLatestImage()
        } catch (t: Throwable) {
            Log.e(TAG, "acquireLatestImage() failed", t)
            null
        }
        if (image == null) {
            // Nothing usable yet - allow the next callback to try again.
            frameClaimed.set(false)
            return
        }

        val bitmap: Bitmap? = try {
            imageToBitmap(image)
        } catch (t: Throwable) {
            Log.e(TAG, "Could not convert the captured image", t)
            null
        } finally {
            try {
                image.close()
            } catch (t: Throwable) {
                Log.w(TAG, "Closing the image failed", t)
            }
        }

        // One frame is all we need: drop the projection and its buffers immediately.
        mainHandler.post { releaseProjection() }

        if (bitmap == null) {
            report("Could not read the captured frame")
            return
        }
        analyzeAsync(bitmap)
    }

    /**
     * Converts the RGBA_8888 image into a software bitmap, stripping the row padding.
     */
    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        buffer.rewind()

        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val imageWidth = image.width
        val imageHeight = image.height
        val rowPadding = rowStride - pixelStride * imageWidth

        if (rowPadding <= 0) {
            val bitmap = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            return bitmap
        }

        val paddedWidth = imageWidth + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, imageHeight, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, imageWidth, imageHeight)
        padded.recycle()
        return cropped
    }

    /**
     * Idempotent teardown of every capture resource. Runs on the main thread.
     */
    private fun releaseProjection() {
        if (releasing) return
        releasing = true
        try {
            val display = virtualDisplay
            virtualDisplay = null
            if (display != null) {
                try {
                    display.release()
                } catch (t: Throwable) {
                    Log.w(TAG, "Releasing the virtual display failed", t)
                }
            }

            val reader = imageReader
            imageReader = null
            if (reader != null) {
                try {
                    reader.setOnImageAvailableListener(null, null)
                } catch (t: Throwable) {
                    Log.w(TAG, "Detaching the image listener failed", t)
                }
                try {
                    reader.close()
                } catch (t: Throwable) {
                    Log.w(TAG, "Closing the ImageReader failed", t)
                }
            }

            val thread = captureThread
            captureThread = null
            if (thread != null) {
                thread.quitSafely()
            }

            val callback = projectionCallback
            projectionCallback = null
            val activeProjection = projection
            projection = null
            if (activeProjection != null) {
                if (callback != null) {
                    try {
                        activeProjection.unregisterCallback(callback)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Unregistering the projection callback failed", t)
                    }
                }
                try {
                    activeProjection.stop()
                } catch (t: Throwable) {
                    Log.w(TAG, "Stopping the projection failed", t)
                }
            }
        } finally {
            releasing = false
        }
    }

    private fun cancelPendingCapture() {
        val runnable = pendingCapture
        if (runnable != null) {
            mainHandler.removeCallbacks(runnable)
            pendingCapture = null
        }
    }

    private fun readScreenMetrics(): ScreenMetrics {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val densityDpi = resources.displayMetrics.densityDpi
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            return ScreenMetrics(bounds.width(), bounds.height(), densityDpi)
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return ScreenMetrics(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    // -------------------------------------------------------------------------------------------
    // Analysis + overlay
    // -------------------------------------------------------------------------------------------

    private fun analyzeAsync(bitmap: Bitmap) {
        if (destroyed || worker.isShutdown) {
            bitmap.recycle()
            return
        }
        updateNotification("Analysing the puzzle...")
        worker.execute {
            val analysis = try {
                PuzzleAnalyzer().analyze(bitmap)
            } catch (t: Throwable) {
                Log.e(TAG, "Puzzle analysis failed", t)
                null
            } finally {
                bitmap.recycle()
            }
            mainHandler.post {
                if (!destroyed) onAnalysisReady(analysis)
            }
        }
    }

    private fun onAnalysisReady(analysis: PuzzleAnalysis?) {
        if (analysis == null) {
            report("Could not locate a Star Battle board on screen")
            return
        }
        if (analysis.solution.isEmpty()) {
            report("No valid solution for the detected ${analysis.n}x${analysis.n} board")
            return
        }

        val view = overlay ?: OverlayCanvasView(this).also { overlay = it }
        view.setTargets(
            OverlayTargets(
                cells = analysis.solution,
                gridLeft = analysis.left,
                gridTop = analysis.top,
                cellWidth = analysis.cellWidth,
                cellHeight = analysis.cellHeight,
                frameWidth = analysis.frameWidth,
                frameHeight = analysis.frameHeight
            )
        )
        view.show()
        updateNotification("Solved ${analysis.n}x${analysis.n} - ${analysis.solution.size} stars marked")
        Log.i(
            TAG,
            "Solution for N=${analysis.n}: " +
                analysis.solution.joinToString { "(${it.row},${it.col})" }
        )
    }

    private fun clearOverlay() {
        overlay?.hide()
    }

    private fun report(message: String) {
        Log.w(TAG, message)
        updateNotification(message)
        val text = message
        mainHandler.post {
            if (!destroyed) Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
        }
    }

    private fun shutdown() {
        cancelPendingCapture()
        releaseProjection()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // -------------------------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows the Star Battle capture state"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundWithType(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        if (destroyed) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_OPEN,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val clearIntent = PendingIntent.getService(
            this,
            REQUEST_CODE_CLEAR,
            Intent(this, CaptureService::class.java).setAction(ACTION_CLEAR),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Star Battle Overlay")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Clear overlay",
                    clearIntent
                ).build()
            )
            .build()
    }

    private fun readScreenMetrics(): ScreenMetrics {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val densityDpi = resources.displayMetrics.densityDpi
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            return ScreenMetrics(bounds.width(), bounds.height(), densityDpi)
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return ScreenMetrics(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    // -------------------------------------------------------------------------------------------
    // Analysis + overlay
    // -------------------------------------------------------------------------------------------

    private fun analyzeAsync(bitmap: Bitmap) {
        if (destroyed || worker.isShutdown) {
            bitmap.recycle()
            return
        }
        updateNotification("Analysing the puzzle...")
        worker.execute {
            val analysis = try {
                PuzzleAnalyzer().analyze(bitmap)
            } catch (t: Throwable) {
                Log.e(TAG, "Puzzle analysis failed", t)
                null
            } finally {
                bitmap.recycle()
            }
            mainHandler.post {
                if (!destroyed) onAnalysisReady(analysis)
            }
        }
    }

    private fun onAnalysisReady(analysis: PuzzleAnalysis?) {
        if (analysis == null) {
            report("Could not locate a Star Battle board on screen")
            return
        }
        if (analysis.solution.isEmpty()) {
            report("No valid solution for the detected ${analysis.n}x${analysis.n} board")
            return
        }

        val view = overlay ?: OverlayCanvasView(this).also { overlay = it }
        view.setTargets(
            OverlayTargets(
                cells = analysis.solution,
                gridLeft = analysis.left,
                gridTop = analysis.top,
                cellWidth = analysis.cellWidth,
                cellHeight = analysis.cellHeight,
                frameWidth = analysis.frameWidth,
                frameHeight = analysis.frameHeight
            )
        )
        view.show()
        updateNotification("Solved ${analysis.n}x${analysis.n} - ${analysis.solution.size} stars marked")
        Log.i(
            TAG,
            "Solution for N=${analysis.n}: " +
                analysis.solution.joinToString { "(${it.row},${it.col})" }
        )
    }

    private fun clearOverlay() {
        overlay?.hide()
    }

    private fun report(message: String) {
        Log.w(TAG, message)
        updateNotification(message)
        val text = message
        mainHandler.post {
            if (!destroyed) Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
        }
    }

    private fun shutdown() {
        cancelPendingCapture()
        releaseProjection()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // -------------------------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows the Star Battle capture state"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundWithType(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        if (destroyed) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_OPEN,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val clearIntent = PendingIntent.getService(
            this,
            REQUEST_CODE_CLEAR,
            Intent(this, CaptureService::class.java).setAction(ACTION_CLEAR),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Star Battle Overlay")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Clear overlay",
                    clearIntent
                ).build()
            )
            .build()
    }

    private data class ScreenMetrics(val width: Int, val height: Int, val densityDpi: Int)
}

/**
 * The `Intent` that carries the MediaProjection token, fetched in a version-safe way.
 */
private fun Intent.projectionResultData(): Intent? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(CaptureService.EXTRA_RESULT_DATA, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(CaptureService.EXTRA_RESULT_DATA)
    }

/**
 * Result of one frame analysis, in captured-frame pixel space.
 */
internal data class PuzzleAnalysis(
    val n: Int,
    val left: Float,
    val top: Float,
    val cellWidth: Float,
    val cellHeight: Float,
    val frameWidth: Int,
    val frameHeight: Int,
    val regions: Array<IntArray>,
    val solution: List<Cell>
)

/**
 * Pure-arithmetic board detector.

 * Pipeline, all of it plain integer/double math over an `IntArray` of pixels:

 *  1. **Background estimate.** The frame is downscaled, the dominant colour is found with a
 *     5-bit-per-channel histogram; that colour is the app background around the board.
 *  2. **Board bounds.** Pixels that differ from the background form a mask; an iterative
 *     4-neighbour flood fill isolates the largest connected blob (the board itself). Text, buttons
 *     and status icons are small blobs and are discarded.
 *  3. **Edge profiles.** For every column x, `vertical[x]` counts how many rows have a colour
 *     discontinuity between x and x+1. Every cell boundary - grid line or border between two
 *     regions - is such a discontinuity, UI noise is not (it spans far fewer rows).
 *  4. **Bounds snapping.** The outer board edges are snapped to the strongest nearby line.
 *  5. **Grid size N.** The edge profile is periodic with the cell pitch, so the pitch is recovered
 *     with a normalised autocorrelation and `N = round(boardWidth / pitch)`. A second estimate from
 *     "how much of the edge energy lands on the N+1 boundaries" (coverage) cross-checks it, and the
 *     two axes must agree. The candidate that keeps cells square wins.
 *  6. **Cell sampling.** Each cell is sampled with a small patch median at its centre, which is
 *     robust against JPEG-ish noise and anti-aliased grid lines.
 *  7. **Region grouping.** Sampled colours are merged with a Manhattan-distance tolerance and the
 *     first matching colour becomes the region id handed to [StarBattleSolver].

 * The detector assumes the puzzle is rendered axis aligned, occupies a rectangular area and draws
 * its cell boundaries (true for every Star Battle app). It never allocates per-pixel objects, never
 * loads a native library and never touches the network.
 */
internal class PuzzleAnalyzer {

    companion object {
        private const val MIN_GRID = 2
        private const val MAX_GRID = 14
        private const val MIN_BOARD_PX = 120

        /** Sum of absolute channel differences (0..765) that still counts as "background". */
        private const val BACKGROUND_TOLERANCE = 70

        /** Sum of absolute channel differences that counts as a grid/region border. */
        private const val EDGE_TOLERANCE = 40

        /** Two sampled cell colours closer than this belong to the same region. */
        private const val COLOR_MERGE_TOLERANCE = 48

        /** Minimum normalised autocorrelation to trust the pitch estimate. */
        private const val MIN_PERIOD_CORRELATION = 0.20

        /** Minimum share of edge energy that must land on the grid boundaries. */
        private const val MIN_COVERAGE = 0.50f

        /** Largest accepted cell width/height ratio before the grid is rejected. */
        private const val MAX_CELL_ASPECT = 1.30f
    }

    fun analyze(bitmap: Bitmap): PuzzleAnalysis? {
        val frameWidth = bitmap.width
        val frameHeight = bitmap.height
        if (frameWidth < 64 || frameHeight < 64) return null

        val pixels = IntArray(frameWidth * frameHeight)
        bitmap.getPixels(pixels, 0, frameWidth, 0, 0, frameWidth, frameHeight)

        val searchBox = findBoardBox(bitmap) ?: return null

        var left = searchBox.left
        var top = searchBox.top
        var right = searchBox.right
        var bottom = searchBox.bottom
        if (right - left < MIN_BOARD_PX || bottom - top < MIN_BOARD_PX) return null

        // --- edge profiles over the whole frame (cheap, one pass each) -------------------------
        val vertical = IntArray(frameWidth)
        for (y in 0 until frameHeight) {
            val rowOffset = y * frameWidth
            for (x in 0 until frameWidth - 1) {
                if (colorDistance(pixels[rowOffset + x], pixels[rowOffset + x + 1]) > EDGE_TOLERANCE) {
                    vertical[x]++
                }
            }
        }
        val horizontal = IntArray(frameHeight)
        for (y in 0 until frameHeight - 1) {
            val rowOffset = y * frameWidth
            val nextOffset = rowOffset + frameWidth
            for (x in 0 until frameWidth) {
                if (colorDistance(pixels[rowOffset + x], pixels[nextOffset + x]) > EDGE_TOLERANCE) {
                    horizontal[y]++
                }
            }
        }

        // --- snap the blob bounds onto the strongest lines nearby ------------------------------
        val snapX = max(6, ((right - left) * 0.04f).roundToInt())
        val snapY = max(6, ((bottom - top) * 0.04f).roundToInt())
        left = snapToStrongest(vertical, left, -1, snapX, 0, right - 1)
        right = snapToStrongest(vertical, right, 1, snapX, left + 1, frameWidth - 2)
        top = snapToStrongest(horizontal, top, -1, snapY, 0, bottom - 1)
        bottom = snapToStrongest(horizontal, bottom, 1, snapY, top + 1, frameHeight - 2)

        if (right - left < MIN_BOARD_PX || bottom - top < MIN_BOARD_PX) return null

        // --- recover N and build the most plausible analysis -----------------------------------
        val candidates = rankGridSizes(vertical, left, right, horizontal, top, bottom)
        if (candidates.isEmpty()) return null

        var fallback: PuzzleAnalysis? = null
        for (candidate in candidates) {
            val analysis = buildAnalysis(
                candidate, pixels, frameWidth, frameHeight, left, top, right, bottom
            ) ?: continue
            // A real Star Battle board has exactly N regions and is solvable - the strongest
            // possible confirmation that the candidate grid size is the right one.
            if (analysis.solution.size == candidate && countRegions(analysis.regions) == candidate) {
                return analysis
            }
            if (fallback == null) fallback = analysis
        }
        return fallback
    }

    private fun buildAnalysis(
        n: Int,
        pixels: IntArray,
        frameWidth: Int,
        frameHeight: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): PuzzleAnalysis? {
        val gridWidth = right - left
        val gridHeight = bottom - top
        if (cellAspect(n, gridWidth, gridHeight) > MAX_CELL_ASPECT) return null

        val cellWidth = gridWidth.toFloat() / n
        val cellHeight = gridHeight.toFloat() / n
        if (cellWidth < 8f || cellHeight < 8f) return null

        val patchRadius = min(6, max(1, (min(cellWidth, cellHeight) / 6f).toInt()))
        val prototypes = ArrayList<Int>(n)
        val regions = Array(n) { IntArray(n) }

        for (row in 0 until n) {
            val centreY = (top + (row + 0.5f) * cellHeight).roundToInt()
            for (col in 0 until n) {
                val centreX = (left + (col + 0.5f) * cellWidth).roundToInt()
                val sample = samplePatch(pixels, frameWidth, frameHeight, centreX, centreY, patchRadius)

                var regionId = -1
                for (index in prototypes.indices) {
                    if (colorDistance(prototypes[index], sample) <= COLOR_MERGE_TOLERANCE) {
                        regionId = index
                        break
                    }
                }
                if (regionId < 0) {
                    prototypes.add(sample)
                    regionId = prototypes.size - 1
                }
                regions[row][col] = regionId
            }
        }

        return PuzzleAnalysis(
            n = n,
            left = left.toFloat(),
            top = top.toFloat(),
            cellWidth = cellWidth,
            cellHeight = cellHeight,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            regions = regions,
            solution = StarBattleSolver.solve(n, regions)
        )
    }

    private fun countRegions(regions: Array<IntArray>): Int {
        var highest = -1
        for (row in regions) {
            for (value in row) {
                if (value > highest) highest = value
            }
        }
        return highest + 1
    }

    // -------------------------------------------------------------------------------------------
    // Board bounds
    // -------------------------------------------------------------------------------------------

    private data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /**
     * Downscales the frame, masks everything that is not the background colour and returns the
     * bounding box of the largest connected blob, expressed in full-resolution pixels.
     */
    private fun findBoardBox(bitmap: Bitmap): Box? {
        val frameWidth = bitmap.width
        val frameHeight = bitmap.height
        val scale = max(1, min(frameWidth, frameHeight) / 180)
        val smallWidth = max(2, frameWidth / scale)
        val smallHeight = max(2, frameHeight / scale)

        val small = Bitmap.createScaledBitmap(bitmap, smallWidth, smallHeight, true)
        val smallPixels = IntArray(smallWidth * smallHeight)
        small.getPixels(smallPixels, 0, smallWidth, 0, 0, smallWidth, smallHeight)
        if (small !== bitmap) small.recycle()

        val background = dominantColor(smallPixels)
        val mask = BooleanArray(smallPixels.size)
        for (index in smallPixels.indices) {
            mask[index] = colorDistance(smallPixels[index], background) > BACKGROUND_TOLERANCE
        }

        val blob = largestComponentBox(mask, smallWidth, smallHeight) ?: return null
        if (blob.area * 100 < smallPixels.size * 4) return null

        val left = (blob.box.left * scale).coerceIn(0, frameWidth - 1)
        val top = (blob.box.top * scale).coerceIn(0, frameHeight - 1)
        val right = (((blob.box.right + 1) * scale) - 1).coerceIn(0, frameWidth - 1)
        val bottom = (((blob.box.bottom + 1) * scale) - 1).coerceIn(0, frameHeight - 1)
        if (right - left < 8 || bottom - top < 8) return null
        return Box(left, top, right, bottom)
    }

    private data class Component(val box: Box, val area: Int)

    private fun largestComponentBox(mask: BooleanArray, width: Int, height: Int): Component? {
        val visited = BooleanArray(mask.size)
        val stack = IntArray(mask.size)
        var best: Component? = null

        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            var top = 0
            stack[top++] = start
            visited[start] = true

            var minX = Int.MAX_VALUE
            var maxX = -1
            var minY = Int.MAX_VALUE
            var maxY = -1
            var area = 0

            while (top > 0) {
                val index = stack[--top]
                area++
                val x = index % width
                val y = index / width
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y

                if (x > 0) {
                    val n = index - 1
                    if (mask[n] && !visited[n]) {
                        visited[n] = true
                        stack[top++] = n
                    }
                }
                if (x + 1 < width) {
                    val n = index + 1
                    if (mask[n] && !visited[n]) {
                        visited[n] = true
                        stack[top++] = n
                    }
                }
                if (y > 0) {
                    val n = index - width
                    if (mask[n] && !visited[n]) {
                        visited[n] = true
                        stack[top++] = n
                    }
                }
                if (y + 1 < height) {
                    val n = index + width
                    if (mask[n] && !visited[n]) {
                        visited[n] = true
                        stack[top++] = n
                    }
                }
            }

            if (best == null || area > best.area) {
                best = Component(Box(minX, minY, maxX, maxY), area)
            }
        }
        return best
    }

    /**
     * Most frequent colour of the downscaled frame, quantised to 5 bits per channel.
     */
    private fun dominantColor(pixels: IntArray): Int {
        val counts = IntArray(32 * 32 * 32)
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val key = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
            counts[key]++
        }
        var bestKey = 0
        var bestCount = -1
        for (key in counts.indices) {
            if (counts[key] > bestCount) {
                bestCount = counts[key]
                bestKey = key
            }
        }
        val r = (((bestKey shr 10) and 0x1F) shl 3) or 0x04
        val g = (((bestKey shr 5) and 0x1F) shl 3) or 0x04
        val b = ((bestKey and 0x1F) shl 3) or 0x04
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    // -------------------------------------------------------------------------------------------
    // Grid size
    // -------------------------------------------------------------------------------------------

    /**
     * Grid sizes worth trying, best first: the autocorrelation pitch of each axis, then every size
     * ordered by how much edge energy its boundaries cover. The caller confirms the winner with
     * the "exactly N regions and solvable" test, which resolves the remaining ambiguity when a
     * puzzle only draws borders between different regions.
     */
    private fun rankGridSizes(
        vertical: IntArray,
        left: Int,
        right: Int,
        horizontal: IntArray,
        top: Int,
        bottom: Int
    ): List<Int> {
        val scores = FloatArray(MAX_GRID + 1)
        for (candidate in MIN_GRID..MAX_GRID) {
            val coverageX = coverage(
                vertical, left, right, candidate, coverageBand(right - left, candidate)
            )
            val coverageY = coverage(
                horizontal, top, bottom, candidate, coverageBand(bottom - top, candidate)
            )
            scores[candidate] = min(coverageX, coverageY)
        }

        val ordered = LinkedHashSet<Int>()
        periodCandidate(vertical, left, right)?.let { if (it in MIN_GRID..MAX_GRID) ordered.add(it) }
        periodCandidate(horizontal, top, bottom)?.let { if (it in MIN_GRID..MAX_GRID) ordered.add(it) }

        val byCoverage = (MIN_GRID..MAX_GRID).sortedByDescending { scores[it] }
        for (candidate in byCoverage) {
            if (scores[candidate] >= MIN_COVERAGE) ordered.add(candidate)
        }
        ordered.addAll(byCoverage)

        val result = ArrayList<Int>(ordered.size)
        for (candidate in ordered) {
            if (cellAspect(candidate, right - left, bottom - top) <= MAX_CELL_ASPECT) {
                result.add(candidate)
            }
        }
        return result
    }

    private fun periodCandidate(profile: IntArray, start: Int, end: Int): Int? {
        val span = end - start
        if (span < MIN_GRID * 8) return null
        val minPeriod = max(8, span / MAX_GRID)
        val maxPeriod = span / MIN_GRID
        if (maxPeriod <= minPeriod) return null
        val period = fundamentalPeriod(profile, start, span, minPeriod, maxPeriod)
        if (period <= 0) return null
        return (span.toDouble() / period).roundToInt()
    }

    private fun cellAspect(candidate: Int, gridWidth: Int, gridHeight: Int): Float {
        val cellWidth = gridWidth.toFloat() / candidate
        val cellHeight = gridHeight.toFloat() / candidate
        return max(cellWidth, cellHeight) / min(cellWidth, cellHeight)
    }

    /**
     * Normalised autocorrelation of `profile[start until start + span]`, returning the smallest
     * strong period in pixels, or 0 when the profile is not periodic.
     */
    private fun fundamentalPeriod(
        profile: IntArray,
        start: Int,
        span: Int,
        minPeriod: Int,
        maxPeriod: Int
    ): Int {
        if (span <= minPeriod * 2) return 0

        var sum = 0L
        for (i in 0 until span) sum += profile[start + i]
        val mean = sum.toDouble() / span
        val centered = DoubleArray(span) { profile[start + it] - mean }

        val maxLag = min(maxPeriod, span - minPeriod)
        if (maxLag <= minPeriod) return 0

        val correlations = DoubleArray(maxLag + 1)
        var bestLag = 0
        var bestValue = -2.0

        for (lag in minPeriod..maxLag) {
            var numerator = 0.0
            var energyA = 0.0
            var energyB = 0.0
            var i = 0
            val limit = span - lag
            while (i < limit) {
                val a = centered[i]
                val b = centered[i + lag]
                numerator += a * b
                energyA += a * a
                energyB += b * b
                i++
            }
            if (energyA <= 1e-9 || energyB <= 1e-9) continue
            val correlation = numerator / sqrt(energyA * energyB)
            correlations[lag] = correlation
            if (correlation > bestValue) {
                bestValue = correlation
                bestLag = lag
            }
        }

        if (bestLag == 0 || bestValue < MIN_PERIOD_CORRELATION) return 0

        // The true pitch is the smallest lag that is (nearly) as periodic as the best multiple.
        for (lag in minPeriod..bestLag) {
            val value = correlations[lag]
            if (value < bestValue * 0.90) continue
            val leftOk = lag <= minPeriod || value >= correlations[lag - 1]
            val rightOk = lag >= maxLag || value >= correlations[lag + 1]
            if (leftOk && rightOk) return lag
        }
        return bestLag
    }

    /**
     * Share (0..1) of the edge energy that falls within `band` pixels of one of the `n + 1`
     * cell boundaries.
     */
    private fun coverage(profile: IntArray, start: Int, end: Int, n: Int, band: Int): Float {
        val span = end - start
        if (span <= 0 || n <= 0) return 0f

        var total = 0L
        for (index in start until end) total += profile[index]
        if (total <= 0L) return 0f

        val covered = BooleanArray(span)
        for (boundaryIndex in 0..n) {
            val position = start + (boundaryIndex * span) / n
            val from = max(start, position - band)
            val to = min(end - 1, position + band)
            for (index in from..to) covered[index - start] = true
        }

        var coveredEnergy = 0L
        for (index in start until end) {
            if (covered[index - start]) coveredEnergy += profile[index]
        }
        return coveredEnergy.toFloat() / total
    }

    private fun coverageBand(span: Int, n: Int): Int =
        max(2, min(8, span / n / 10))

    private fun snapToStrongest(
        profile: IntArray,
        position: Int,
        direction: Int,
        window: Int,
        minIndex: Int,
        maxIndex: Int
    ): Int {
        val clamped = position.coerceIn(minIndex, maxIndex)
        var best = clamped
        var bestValue = profile[clamped]
        var step = 1
        while (step <= window) {
            val candidate = clamped + direction * step
            if (candidate < minIndex || candidate > maxIndex) break
            if (profile[candidate] > bestValue) {
                bestValue = profile[candidate]
                best = candidate
            }
            step++
        }
        return best
    }

    // -------------------------------------------------------------------------------------------
    // Colour sampling
    // -------------------------------------------------------------------------------------------

    /**
     * Median colour of a square patch, computed per channel. Sorting a handful of bytes is far
     * cheaper than any convolution and rejects single-pixel noise completely.
     */
    private fun samplePatch(
        pixels: IntArray,
        width: Int,
        height: Int,
        centreX: Int,
        centreY: Int,
        radius: Int
    ): Int {
        val size = (2 * radius + 1) * (2 * radius + 1)
        val reds = IntArray(size)
        val greens = IntArray(size)
        val blues = IntArray(size)
        var index = 0

        for (dy in -radius..radius) {
            val y = (centreY + dy).coerceIn(0, height - 1)
            val rowOffset = y * width
            for (dx in -radius..radius) {
                val x = (centreX + dx).coerceIn(0, width - 1)
                val pixel = pixels[rowOffset + x]
                reds[index] = (pixel shr 16) and 0xFF
                greens[index] = (pixel shr 8) and 0xFF
                blues[index] = pixel and 0xFF
                index++
            }
        }

        reds.sort()
        greens.sort()
        blues.sort()
        val middle = size / 2
        return (0xFF shl 24) or (reds[middle] shl 16) or (greens[middle] shl 8) or blues[middle]
    }

    private fun colorDistance(first: Int, second: Int): Int {
        val dr = abs(((first shr 16) and 0xFF) - ((second shr 16) and 0xFF))
        val dg = abs(((first shr 8) and 0xFF) - ((second shr 8) and 0xFF))
        val db = abs((first and 0xFF) - (second and 0xFF))
        return dr + dg + db
    }
}
