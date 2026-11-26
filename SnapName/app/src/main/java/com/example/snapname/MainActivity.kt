package com.example.snapname

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    // A capture mode: either one of the phone's camera extensions, or Ultra HDR
    private data class Mode(
        val name: String,
        val extension: Int = ExtensionMode.NONE,
        val ultraHdr: Boolean = false
    )

    private var cameraProvider: ProcessCameraProvider? = null
    private var extensions: ExtensionsManager? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private val backCamera = CameraSelector.DEFAULT_BACK_CAMERA
    private val standardMode = Mode("Photo")
    private var modes = listOf(standardMode)
    private var selectedMode = standardMode
    private var modeTabs = emptyList<TextView>()
    private lateinit var modeStrip: LinearLayout
    private var captureRotation = Surface.ROTATION_0
    private var saving = false
    private lateinit var status: TextView
    private lateinit var shutter: ImageButton
    private lateinit var sizeTabs: List<TextView>
    private lateinit var root: FrameLayout

    // Long side divided by short side
    private val ratios = linkedMapOf(
        "4:3" to 4f / 3f,
        "3:2" to 3f / 2f,
        "16:10" to 16f / 10f,
        "16:9" to 16f / 9f,
        "1:1" to 1f
    )
    private var selectedRatio = "4:3"

    private val labeler by lazy {
        ImageLabeling.getClient(
            ImageLabelerOptions.Builder().setConfidenceThreshold(0.6f).build()
        )
    }

    private val permission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    private lateinit var preview: PreviewView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the layout fixed to the phone; labels are rotated separately
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        preview = PreviewView(this)
        status = TextView(this).apply {
            setTextColor(-1)
            setBackgroundColor(0x99000000.toInt())
            textSize = 16f
            setPadding(32, 24, 32, 24)
            text = "Ready"
        }
        shutter = ImageButton(this).apply {
            background = ShutterDrawable()
            contentDescription = "Take photo"
            isEnabled = false
            setOnClickListener { takePhoto() }
        }
        selectedRatio = getPreferences(MODE_PRIVATE).getString("ratio", null)
            ?.takeIf { it in ratios } ?: "4:3"
        val tabStrip = createSizeTabs()
        modeStrip = createStrip().apply { visibility = View.GONE }
        root = FrameLayout(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(0xFF000000.toInt())
            addView(preview, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(shutter, FrameLayout.LayoutParams(dp(80), dp(80),
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 64 })
            addView(tabStrip, FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                    bottomMargin = 64 + dp(92)
                })
            addView(modeStrip, FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                    bottomMargin = 64 + dp(156)
                })
        }
        // Resize the preview once the screen size is known, and if it changes
        root.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r - l != oldR - oldL || b - t != oldB - oldT) root.post {
                applyRatio()
                status.maxWidth = root.width - dp(32)
                placeStatus()
            }
        }
        // Reposition the message whenever its text changes its size
        status.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeStatus() }
        // The crop depends on the preview's shape, so reconnect when it changes
        preview.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r - l != oldR - oldL || b - t != oldB - oldT) preview.post { bindCamera() }
        }
        setUpZoomAndFocus()
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) startCamera()
        else if (savedInstanceState == null) permission.launch(Manifest.permission.CAMERA)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun createStrip() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(0x99000000.toInt())
    }

    // Adds a tab that can be tapped, or swiped along the row to move between tabs
    @SuppressLint("ClickableViewAccessibility")
    private fun addTab(
        strip: LinearLayout, name: String, width: Int,
        onTap: () -> Unit, onSwipe: (Int) -> Unit
    ): TextView {
        var downX = 0f
        val tab = TextView(this).apply {
            text = name
            textSize = 14f
            gravity = Gravity.CENTER
            setOnClickListener { onTap() }
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                    }
                    MotionEvent.ACTION_UP -> {
                        val moved = event.rawX - downX
                        if (!view.isEnabled) {
                            // Ignore touches while a photo is saving
                        } else if (kotlin.math.abs(moved) > dp(24)) {
                            // Swipe left for the next tab, right for the previous one
                            onSwipe(if (moved < 0) 1 else -1)
                        } else {
                            view.performClick()
                        }
                    }
                }
                true
            }
        }
        strip.addView(tab, LinearLayout.LayoutParams(dp(width), dp(width)))
        return tab
    }

    private fun createSizeTabs(): LinearLayout {
        val strip = createStrip()
        sizeTabs = ratios.keys.map { name ->
            addTab(strip, name, 60, { selectSize(name) }, { stepSize(it) })
        }
        highlightSize()
        return strip
    }

    private fun stepSize(step: Int) {
        val names = ratios.keys.toList()
        val index = (names.indexOf(selectedRatio) + step).coerceIn(0, names.lastIndex)
        selectSize(names[index])
    }

    private fun selectSize(name: String) {
        if (name == selectedRatio) return
        selectedRatio = name
        getPreferences(MODE_PRIVATE).edit().putString("ratio", name).apply()
        highlightSize()
        shutter.isEnabled = false // Re-enabled once the camera matches the new shape
        applyRatio()
    }

    private fun highlightSize() = highlight(sizeTabs, selectedRatio)

    private fun highlight(tabs: List<TextView>, name: String) {
        tabs.forEach {
            val selected = it.text.toString() == name
            it.isSelected = selected
            it.setTextColor(if (selected) 0xFFFFD54F.toInt() else -1)
            it.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    // How far to turn labels so they read upright: 0, 90 or -90 degrees
    private var labelAngle = 0

    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                // Tell the camera which way up the photo should be saved
                val rotation = when (orientation) {
                    in 0..44, in 315..359 -> Surface.ROTATION_0
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> captureRotation // Phone is flat
                }
                if (rotation != captureRotation) {
                    captureRotation = rotation
                    imageCapture?.targetRotation = rotation
                }

                // Gaps between the ranges stop flickering near 45 degrees.
                // Flat and upside-down positions keep the previous angle.
                val angle = when (orientation) {
                    in 0..30, in 330..359 -> 0
                    in 60..120 -> -90
                    in 240..300 -> 90
                    else -> return
                }
                if (angle == labelAngle) return
                labelAngle = angle
                (sizeTabs + modeTabs).forEach {
                    it.animate().rotation(angle.toFloat()).setDuration(200).start()
                }
                placeStatus()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        orientationListener.enable()
    }

    override fun onPause() {
        orientationListener.disable()
        super.onPause()
    }

    // Move the message to whichever edge is currently at the top for the viewer
    private fun placeStatus() {
        val width = root.width - root.paddingLeft - root.paddingRight
        val height = root.height - root.paddingTop - root.paddingBottom
        status.rotation = labelAngle.toFloat()
        status.translationX = when (labelAngle) {
            -90 -> -(width - status.height) / 2f
            90 -> (width - status.height) / 2f
            else -> 0f
        }
        status.translationY =
            if (labelAngle == 0) -(height - status.height) / 2f else 0f
    }

    // The camera crops each photo to match the shape of the preview
    private fun applyRatio() {
        val maxWidth = root.width - root.paddingLeft - root.paddingRight
        val maxHeight = root.height - root.paddingTop - root.paddingBottom
        if (maxWidth <= 0 || maxHeight <= 0) return

        val ratio = ratios.getValue(selectedRatio)
        val widthOverHeight = if (maxHeight >= maxWidth) 1f / ratio else ratio
        var width = maxWidth
        var height = (width / widthOverHeight).toInt()
        if (height > maxHeight) {
            height = maxHeight
            width = (height * widthOverHeight).toInt()
        }
        preview.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val extensionsFuture = ExtensionsManager.getInstanceAsync(this, provider)
                extensionsFuture.addListener({
                    // If extensions can't load, the other modes still work
                    extensions = try { extensionsFuture.get() } catch (e: Exception) { null }
                    cameraProvider = provider
                    findModes(provider)
                    bindCamera()
                }, ContextCompat.getMainExecutor(this))
            } catch (e: Exception) {
                status.text = "Couldn't start the camera: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // Only offer the modes this phone reports that it supports
    private fun findModes(provider: ProcessCameraProvider) {
        val found = mutableListOf(standardMode)
        try {
            val info = provider.getCameraInfo(backCamera)
            if (Build.VERSION.SDK_INT >= 34 &&
                ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR in
                ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats
            ) found.add(Mode("Ultra HDR", ultraHdr = true))
        } catch (e: Exception) {
            // Treat Ultra HDR as unsupported
        }
        listOf(
            Mode("HDR", ExtensionMode.HDR),
            Mode("Night", ExtensionMode.NIGHT),
            Mode("Auto", ExtensionMode.AUTO)
        ).forEach {
            try {
                if (extensions?.isExtensionAvailable(backCamera, it.extension) == true) {
                    found.add(it)
                }
            } catch (e: Exception) {
                // Treat this mode as unsupported
            }
        }
        modes = found
        val saved = getPreferences(MODE_PRIVATE).getString("mode", null)
        selectedMode = modes.find { it.name == saved } ?: standardMode

        modeStrip.removeAllViews()
        modeTabs = modes.map { mode ->
            addTab(modeStrip, mode.name, 68, { selectMode(mode) }, { stepMode(it) }).apply {
                rotation = labelAngle.toFloat()
            }
        }
        // No need for a row if there's nothing to choose between
        modeStrip.visibility = if (modes.size > 1) View.VISIBLE else View.GONE
        highlight(modeTabs, selectedMode.name)
    }

    private fun stepMode(step: Int) {
        val index = (modes.indexOf(selectedMode) + step).coerceIn(0, modes.lastIndex)
        selectMode(modes[index])
    }

    private fun selectMode(mode: Mode) {
        if (saving || mode == selectedMode) return
        selectedMode = mode
        getPreferences(MODE_PRIVATE).edit().putString("mode", mode.name).apply()
        highlight(modeTabs, mode.name)
        status.text = "${mode.name} mode"
        bindCamera()
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        // Null until the preview is on screen; its layout listener will call us again
        val viewPort = preview.viewPort ?: return
        shutter.isEnabled = false
        try {
            val previewUseCase = Preview.Builder().build()
            previewUseCase.setSurfaceProvider(preview.surfaceProvider)

            // Favour quality over speed, at the highest standard resolution available
            val builder = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(97)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                        .build()
                )
                .setTargetRotation(captureRotation)
            if (selectedMode.ultraHdr) {
                builder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
            }
            val capture = builder.build()

            val selector = if (selectedMode.extension == ExtensionMode.NONE) backCamera
            else extensions!!.getExtensionEnabledCameraSelector(
                backCamera, selectedMode.extension)

            val group = UseCaseGroup.Builder()
                .setViewPort(viewPort) // Crops photos to match the preview
                .addUseCase(previewUseCase)
                .addUseCase(capture)
                .build()
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, selector, group)
            imageCapture = capture
            shutter.isEnabled = !saving
        } catch (e: Exception) {
            imageCapture = null
            if (selectedMode != standardMode) {
                val failed = selectedMode.name
                selectedMode = standardMode
                highlight(modeTabs, standardMode.name)
                bindCamera()
                status.text = "$failed isn't working, switched to Photo"
            } else {
                status.text = "Couldn't start the camera: ${e.message}"
            }
        }
    }

    // Pinch to zoom and tap to focus
    @SuppressLint("ClickableViewAccessibility")
    private fun setUpZoomAndFocus() {
        val pinch = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val current = camera ?: return true
                    val zoom = current.cameraInfo.zoomState.value ?: return true
                    current.cameraControl.setZoomRatio(
                        (zoom.zoomRatio * detector.scaleFactor)
                            .coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio))
                    return true
                }
            })
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var isTap = false
        preview.setOnTouchListener { _, event ->
            pinch.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    isTap = true
                }
                MotionEvent.ACTION_POINTER_DOWN -> isTap = false
                MotionEvent.ACTION_MOVE ->
                    if (kotlin.math.hypot(event.x - downX, event.y - downY) > slop) {
                        isTap = false
                    }
                MotionEvent.ACTION_UP -> if (isTap) {
                    val point = preview.meteringPointFactory.createPoint(event.x, event.y)
                    camera?.cameraControl?.startFocusAndMetering(
                        FocusMeteringAction.Builder(point).build())
                }
            }
            true
        }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        saving = true
        shutter.isEnabled = false
        (sizeTabs + modeTabs).forEach { it.isEnabled = false }
        status.text = "Saving…"
        val stamp = SimpleDateFormat("yyMMdd_HHmm", Locale.US).format(Date())

        // Save under a temporary name first, so the photo is safe even if labelling fails
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "photo_$stamp.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SnapName")
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()

        capture.takePicture(options, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    saving = false
                    shutter.isEnabled = true
                    (sizeTabs + modeTabs).forEach { it.isEnabled = true }
                    val uri = result.savedUri
                    if (uri == null) status.text = "Saved as photo_$stamp.jpg"
                    else describeAndRename(uri, stamp)
                }

                override fun onError(e: ImageCaptureException) {
                    saving = false
                    shutter.isEnabled = true
                    (sizeTabs + modeTabs).forEach { it.isEnabled = true }
                    status.text = "Capture failed: ${e.message}"
                }
            })
    }

    private fun describeAndRename(uri: Uri, stamp: String) {
        status.text = "Describing…"
        val image = try {
            InputImage.fromFilePath(this, uri)
        } catch (e: Exception) {
            status.text = "Saved as photo_$stamp.jpg (couldn't read it)"
            return
        }
        labeler.process(image)
            .addOnSuccessListener { labels ->
                val description = labels.sortedByDescending { it.confidence }
                    .take(3)
                    .joinToString("-") { slug(it.text) }
                    .ifEmpty { "unknown" }
                rename(uri, "${description}_$stamp", stamp)
            }
            .addOnFailureListener {
                status.text = "Saved as photo_$stamp.jpg (labelling failed)"
            }
    }

    private fun slug(text: String) =
        text.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-')

    // No extra permission needed: an app may rename media it created itself
    private fun rename(uri: Uri, base: String, stamp: String) {
        // If the name is taken, try base-2, base-3 and so on
        for (number in 1..20) {
            val name = if (number == 1) "$base.jpg" else "$base-$number.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
            }
            val renamed = try {
                contentResolver.update(uri, values, null, null) > 0
            } catch (e: Exception) {
                false
            }
            if (renamed) {
                // Android may adjust the name itself, so show what it actually saved
                status.text = "Saved as ${savedName(uri) ?: name}"
                return
            }
        }
        status.text = "Saved as ${savedName(uri) ?: "photo_$stamp.jpg"} (rename failed)"
    }

    private fun savedName(uri: Uri): String? =
        contentResolver.query(
            uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
}

/** Round shutter button with an aperture symbol. Dims when pressed or disabled. */
private class ShutterDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var dimmed = false

    override fun draw(canvas: Canvas) {
        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY()
        val r = minOf(bounds.width(), bounds.height()) / 2f
        val alpha = if (dimmed) 120 else 255

        // Outer white ring
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.09f
        paint.setARGB(alpha, 255, 255, 255)
        canvas.drawCircle(x, y, r * 0.94f, paint)

        // White centre
        paint.style = Paint.Style.FILL
        canvas.drawCircle(x, y, r * 0.78f, paint)

        // Six aperture blade edges around a hexagonal opening
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.055f
        paint.setARGB(alpha, 40, 40, 40)
        val inner = r * 0.30f
        val outer = r * 0.78f
        // Each edge extends one side of the hexagon out to the rim
        val length = (-inner / 2 +
            kotlin.math.sqrt(outer * outer - 0.75f * inner * inner))
        canvas.save()
        repeat(6) {
            // From the corner at 0 degrees, continuing the side that ends there
            canvas.drawLine(x + inner, y, x + inner + length / 2,
                y + length * 0.866f, paint)
            canvas.rotate(60f, x, y)
        }
        canvas.restore()
        val side = FloatArray(24)
        for (i in 0 until 6) {
            for (end in 0..1) {
                val angle = Math.toRadians((i + end) * 60.0)
                side[i * 4 + end * 2] = x + inner * kotlin.math.cos(angle).toFloat()
                side[i * 4 + end * 2 + 1] = y + inner * kotlin.math.sin(angle).toFloat()
            }
        }
        canvas.drawLines(side, paint)
    }

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val now = android.R.attr.state_pressed in state ||
            android.R.attr.state_enabled !in state
        if (now == dimmed) return false
        dimmed = now
        invalidateSelf()
        return true
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
