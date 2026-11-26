package com.example.snapname

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.widget.Button
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
    private lateinit var shutter: Button

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
        shutter = Button(this).apply {
            text = "Take photo"
            isEnabled = false
            setOnClickListener { takePhoto() }
        }
        val root = FrameLayout(this).apply {
            fitsSystemWindows = true
            addView(preview, FrameLayout.LayoutParams(-1, -1))
            addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(shutter, FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 64 })
        }
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) startCamera()
        else if (savedInstanceState == null) permission.launch(Manifest.permission.CAMERA)
    }

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
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

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
                rename(uri, "${description}_$stamp.jpg", stamp)
            }
            .addOnFailureListener {
                status.text = "Saved as photo_$stamp.jpg (labelling failed)"
            }
    }

    private fun slug(text: String) =
        text.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-')

    // No extra permission needed: an app may rename media it created itself
    private fun rename(uri: Uri, name: String, stamp: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
        }
        status.text = try {
            if (contentResolver.update(uri, values, null, null) > 0) "Saved as $name"
            else "Saved as photo_$stamp.jpg (rename failed)"
        } catch (e: Exception) {
            "Saved as photo_$stamp.jpg (rename failed)"
        }
    }
}
