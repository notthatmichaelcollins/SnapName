package com.example.snapname

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.widget.ImageButton
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var camera: LifecycleCameraController
    private lateinit var status: TextView
    private lateinit var shutter: ImageButton

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
        val root = FrameLayout(this).apply {
            fitsSystemWindows = true
            addView(preview, FrameLayout.LayoutParams(-1, -1))
            addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(shutter, FrameLayout.LayoutParams(dp(80), dp(80),
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 64 })
        }
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) startCamera()
        else if (savedInstanceState == null) permission.launch(Manifest.permission.CAMERA)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun startCamera() {
        camera = LifecycleCameraController(this)
        camera.bindToLifecycle(this)
        preview.controller = camera
        camera.initializationFuture.addListener(
            { shutter.isEnabled = true }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        shutter.isEnabled = false
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

        camera.takePicture(options, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    shutter.isEnabled = true
                    val uri = result.savedUri
                    if (uri == null) status.text = "Saved as photo_$stamp.jpg"
                    else describeAndRename(uri, stamp)
                }

                override fun onError(e: ImageCaptureException) {
                    shutter.isEnabled = true
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
