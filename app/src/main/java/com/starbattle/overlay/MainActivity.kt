package com.starbattle.overlay

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Modern In-App LinkedIn Queens / Star Battle Solver Dashboard.
 *
 * Capabilities:
 * - Auto-detects grid & colors from picked screenshots or live screen capture.
 * - Interactive Starting Layout with brush painting and size / preset selectors.
 * - Instant CSP Backtracking solver with Solved Layout & gold crown display.
 * - Optional GameGuardian-style Floating HUD launcher for in-game overlay.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var gridView: InteractiveGridView
    private lateinit var tvStatus: TextView
    private lateinit var tabStarting: Button
    private lateinit var tabSolved: Button
    private lateinit var btnSolveMain: Button
    private lateinit var btnPickScreenshot: Button
    private lateinit var btnScanScreen: Button
    private lateinit var btnLaunchFloatingHud: Button
    private lateinit var btnHelpRules: Button
    private lateinit var layoutSizeButtons: LinearLayout
    private lateinit var layoutPresetButtons: LinearLayout
    private lateinit var layoutPaletteSwatches: LinearLayout

    private lateinit var mediaProjectionManager: MediaProjectionManager

    // Screenshot picker from gallery
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { processPickedImage(it) }
    }

    // MediaProjection consent launcher for live screen capture
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            startFloatingService(result.resultCode, data)
        } else {
            Toast.makeText(this, "Screen capture consent cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    // Overlay permission launcher
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            requestMediaProjectionConsent()
        } else {
            Toast.makeText(this, "Overlay permission is required for floating HUD", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        initViews()
        setupSizeButtons()
        setupPresetButtons()
        setupPaletteSwatches()

        // Load Preset 1 by default
        loadPreset(1)
    }

    private fun initViews() {
        gridView = findViewById(R.id.interactive_grid_view)
        tvStatus = findViewById(R.id.tv_detect_status)
        tabStarting = findViewById(R.id.tab_starting)
        tabSolved = findViewById(R.id.tab_solved)
        btnSolveMain = findViewById(R.id.btn_solve_main)
        btnPickScreenshot = findViewById(R.id.btn_pick_screenshot)
        btnScanScreen = findViewById(R.id.btn_scan_screen)
        btnLaunchFloatingHud = findViewById(R.id.btn_launch_floating_hud)
        btnHelpRules = findViewById(R.id.btn_help_rules)
        layoutSizeButtons = findViewById(R.id.layout_size_buttons)
        layoutPresetButtons = findViewById(R.id.layout_preset_buttons)
        layoutPaletteSwatches = findViewById(R.id.layout_palette_swatches)

        // Tab switcher
        tabStarting.setOnClickListener { switchTab(InteractiveGridView.Mode.STARTING_LAYOUT) }
        tabSolved.setOnClickListener { switchTab(InteractiveGridView.Mode.SOLVED_LAYOUT) }

        // Solve button
        btnSolveMain.setOnClickListener { solveCurrentGrid() }

        // Pick screenshot button
        btnPickScreenshot.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        // Scan live screen button
        btnScanScreen.setOnClickListener {
            checkOverlayAndLaunchFloatingMod()
        }

        // Floating HUD launcher
        btnLaunchFloatingHud.setOnClickListener {
            checkOverlayAndLaunchFloatingMod()
        }

        // Rules modal
        btnHelpRules.setOnClickListener {
            showRulesDialog()
        }
    }

    private fun switchTab(mode: InteractiveGridView.Mode) {
        gridView.mode = mode
        if (mode == InteractiveGridView.Mode.STARTING_LAYOUT) {
            tabStarting.setBackgroundResource(R.drawable.bg_button_solve)
            tabStarting.setTextColor(Color.parseColor("#0A0D14"))
            tabSolved.setBackgroundColor(Color.TRANSPARENT)
            tabSolved.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        } else {
            tabSolved.setBackgroundResource(R.drawable.bg_button_solve)
            tabSolved.setTextColor(Color.parseColor("#0A0D14"))
            tabStarting.setBackgroundColor(Color.TRANSPARENT)
            tabStarting.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun setupSizeButtons() {
        layoutSizeButtons.removeAllViews()
        val density = resources.displayMetrics.density

        for (size in 4..12) {
            val btn = Button(this).apply {
                text = "$size"
                textSize = 12f
                setTextColor(Color.WHITE)
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_button_clear)
                layoutParams = LinearLayout.LayoutParams(
                    (38 * density).toInt(),
                    (34 * density).toInt()
                ).apply {
                    marginEnd = (6 * density).toInt()
                }
                setOnClickListener {
                    gridView.n = size
                    setupPaletteSwatches()
                    switchTab(InteractiveGridView.Mode.STARTING_LAYOUT)
                    tvStatus.text = "Size set to ${size}x$size. Tap cells to paint."
                }
            }
            layoutSizeButtons.addView(btn)
        }
    }

    private fun setupPresetButtons() {
        layoutPresetButtons.removeAllViews()
        val density = resources.displayMetrics.density

        for (preset in Presets.PRESET_LIST) {
            val btn = Button(this).apply {
                text = "${preset.id}"
                textSize = 12f
                setTextColor(Color.WHITE)
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_button_clear)
                layoutParams = LinearLayout.LayoutParams(
                    (38 * density).toInt(),
                    (34 * density).toInt()
                ).apply {
                    marginEnd = (6 * density).toInt()
                }
                setOnClickListener {
                    loadPreset(preset.id)
                }
            }
            layoutPresetButtons.addView(btn)
        }

        // Clear Button
        val clearBtn = Button(this).apply {
            text = "Clear"
            textSize = 11f
            setTextColor(Color.parseColor("#FF5252"))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_button_close)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (34 * density).toInt()
            ).apply {
                marginStart = (4 * density).toInt()
            }
            setOnClickListener {
                gridView.clearGrid()
                switchTab(InteractiveGridView.Mode.STARTING_LAYOUT)
                tvStatus.text = "Grid cleared."
            }
        }
        layoutPresetButtons.addView(clearBtn)
    }

    private fun loadPreset(presetId: Int) {
        val preset = Presets.PRESET_LIST.find { it.id == presetId } ?: return
        gridView.setGridData(preset.size, preset.grid)
        setupPaletteSwatches()
        switchTab(InteractiveGridView.Mode.STARTING_LAYOUT)

        // Automatically solve preset
        val solution = StarBattleSolver.solve(preset.size, preset.grid)
        if (solution != null) {
            gridView.setSolution(solution)
            tvStatus.text = "Loaded ${preset.name} • Ready to solve!"
        }
    }

    private fun setupPaletteSwatches() {
        layoutPaletteSwatches.removeAllViews()
        val density = resources.displayMetrics.density
        val totalColors = gridView.n

        for (i in 0 until totalColors) {
            val color = gridView.palette[i % gridView.palette.size]
            val swatch = View(this).apply {
                val drawable = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    if (i == gridView.activeColorId) {
                        setStroke((3 * density).toInt(), Color.WHITE)
                    } else {
                        setStroke((1 * density).toInt(), Color.TRANSPARENT)
                    }
                }
                background = drawable
                layoutParams = LinearLayout.LayoutParams(
                    (36 * density).toInt(),
                    (36 * density).toInt()
                ).apply {
                    marginEnd = (8 * density).toInt()
                }
                setOnClickListener {
                    gridView.activeColorId = i
                    setupPaletteSwatches()
                }
            }
            layoutPaletteSwatches.addView(swatch)
        }
    }

    private fun processPickedImage(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (bitmap == null) {
                Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
                return
            }

            tvStatus.text = "Auto-detecting board and colors from screenshot..."
            tvStatus.setTextColor(Color.parseColor("#00E5FF"))

            val detection = GridDetector.detectAndParse(bitmap, preferredN = 0)
            if (detection != null) {
                gridView.setGridData(detection.n, detection.regionMatrix)
                setupPaletteSwatches()
                switchTab(InteractiveGridView.Mode.STARTING_LAYOUT)
                tvStatus.text = "Detected ${detection.n}x${detection.n} board with ${detection.sampledColors.size} regions! Solving..."
                tvStatus.setTextColor(Color.parseColor("#00FF88"))

                // Solve detected grid
                val solution = StarBattleSolver.solve(detection.n, detection.regionMatrix)
                if (solution != null && solution.isNotEmpty()) {
                    gridView.setSolution(solution)
                    switchTab(InteractiveGridView.Mode.SOLVED_LAYOUT)
                    tvStatus.text = "PUZZLE SOLVED! (${detection.n} Queens placed)"
                    Toast.makeText(this, "Puzzle Solved Successfully!", Toast.LENGTH_SHORT).show()
                } else {
                    tvStatus.text = "Detected ${detection.n}x${detection.n} grid. Adjust any mismatched cells and tap Solve."
                }
            } else {
                tvStatus.text = "Could not detect board. Make sure the screenshot shows the full puzzle grid."
                tvStatus.setTextColor(Color.parseColor("#FF5252"))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error parsing image: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun solveCurrentGrid() {
        val n = gridView.n
        val grid = gridView.grid

        // Verify if all cells are assigned
        var unassignedCount = 0
        for (r in 0 until n) {
            for (c in 0 until n) {
                if (grid[r][c] < 0) unassignedCount++
            }
        }

        if (unassignedCount > 0) {
            Toast.makeText(this, "Please fill in all $unassignedCount remaining cells or pick a screenshot", Toast.LENGTH_LONG).show()
            return
        }

        val solution = StarBattleSolver.solve(n, grid)
        if (solution != null && solution.isNotEmpty()) {
            gridView.setSolution(solution)
            switchTab(InteractiveGridView.Mode.SOLVED_LAYOUT)
            tvStatus.text = "SOLVED! Exact solution with $n Queens"
            tvStatus.setTextColor(Color.parseColor("#00FF88"))
            Toast.makeText(this, "👑 Solution found!", Toast.LENGTH_SHORT).show()
        } else {
            tvStatus.text = "No valid solution exists for current color layout. Check rules."
            tvStatus.setTextColor(Color.parseColor("#FF5252"))
            Toast.makeText(this, "No valid solution. Check for touching regions.", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkOverlayAndLaunchFloatingMod() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            requestMediaProjectionConsent()
        }
    }

    private fun requestMediaProjectionConsent() {
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        projectionLauncher.launch(captureIntent)
    }

    private fun startFloatingService(resultCode: Int, data: Intent) {
        val serviceIntent = FloatingMenuService.startServiceIntent(
            context = this,
            resultCode = resultCode,
            data = data,
            targetPackage = "com.linkedin.android"
        )
        ContextCompat.startForegroundService(this, serviceIntent)
        Toast.makeText(this, "Floating Mod Active! Open LinkedIn Queens to solve in-game.", Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
    }

    private fun showRulesDialog() {
        AlertDialog.Builder(this)
            .setTitle("👑 LinkedIn Queens Rules")
            .setMessage(
                "1. Exactly ONE Queen in each row.\n\n" +
                "2. Exactly ONE Queen in each column.\n\n" +
                "3. Exactly ONE Queen in each color region.\n\n" +
                "4. NO TWO QUEENS CAN TOUCH!\n" +
                "All 8 surrounding squares (orthogonal and diagonal) are strictly forbidden."
            )
            .setPositiveButton("Got It", null)
            .show()
    }
}
