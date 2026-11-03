package com.jakaria.s7naturalcamera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var captureButton: Button
    private lateinit var modeButton: Button
    private lateinit var flipButton: Button
    private lateinit var flashButton: Button
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var camera: Camera? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var videoMode = false
    private var flashOn = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val cameraGranted = result[Manifest.permission.CAMERA] == true
        if (cameraGranted) startCamera() else {
            Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildInterface()
        if (hasPermissions()) startCamera() else requestPermissions()
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF000000.toInt())
        }
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        root.addView(previewView, LinearLayout.LayoutParams(-1, 0, 1f))

        status = TextView(this).apply {
            text = "S7 NATURAL CAMERA  •  AUTO"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(8, 12, 8, 12)
        }
        root.addView(status)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(6, 10, 6, 22)
        }
        modeButton = makeButton("PHOTO") { toggleMode() }
        flashButton = makeButton("FLASH OFF") { toggleFlash() }
        captureButton = makeButton("●") { captureOrRecord() }
        flipButton = makeButton("FLIP") { flipCamera() }
        controls.addView(modeButton, weighted())
        controls.addView(flashButton, weighted())
        controls.addView(captureButton, weighted())
        controls.addView(flipButton, weighted())
        root.addView(controls)
        setContentView(root)
    }

    private fun weighted() = LinearLayout.LayoutParams(0, 58, 1f).apply {
        setMargins(3, 0, 3, 0)
    }

    private fun makeButton(label: String, action: () -> Unit) =
        Button(this).apply {
            text = label
            textSize = 11f
            isAllCaps = false
            setOnClickListener { action() }
        }

    private fun hasPermissions(): Boolean {
        val needed = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= 32) needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        return needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestPermissions() {
        val needed = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= 32) needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        permissionLauncher.launch(needed.toTypedArray())
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(100)
                .build()
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, selector, preview, imageCapture, videoCapture)
                applyFlash()
                status.text = "S7 NATURAL CAMERA  •  ${if (lensFacing == CameraSelector.LENS_FACING_BACK) "REAR" else "FRONT"}"
            } catch (e: Exception) {
                Toast.makeText(this, "Camera could not start: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureOrRecord() {
        if (videoMode) {
            if (recording != null) {
                recording?.stop()
                recording = null
                captureButton.text = "●"
                status.text = "VIDEO SAVED"
            } else startRecording()
        } else takePhoto()
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val name = "S7CAM_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/S7NaturalCamera")
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()
        capture.takePicture(options, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Toast.makeText(this@MainActivity, "Photo saved to Gallery", Toast.LENGTH_SHORT).show()
                }
                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(this@MainActivity, "Photo error: ${exception.message}", Toast.LENGTH_LONG).show()
                }
            })
    }

    private fun startRecording() {
        val capture = videoCapture ?: return
        val name = "S7VIDEO_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/S7NaturalCamera")
        }
        val output = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values).build()
        var pending = capture.output.prepareRecording(this, output)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled()
        }
        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                if (event.hasError()) Toast.makeText(this, "Video recording failed", Toast.LENGTH_LONG).show()
                else Toast.makeText(this, "Video saved to Gallery", Toast.LENGTH_SHORT).show()
            }
        }
        captureButton.text = "■"
        status.text = "RECORDING VIDEO..."
    }

    private fun toggleMode() {
        if (recording != null) return
        videoMode = !videoMode
        modeButton.text = if (videoMode) "VIDEO" else "PHOTO"
        captureButton.text = if (videoMode) "●" else "●"
        status.text = if (videoMode) "VIDEO MODE • QUALITY: DEVICE HIGHEST" else "PHOTO MODE • MAX QUALITY"
    }

    private fun flipCamera() {
        if (recording != null) return
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        startCamera()
    }

    private fun toggleFlash() {
        flashOn = !flashOn
        flashButton.text = if (flashOn) "FLASH ON" else "FLASH OFF"
        applyFlash()
    }

    private fun applyFlash() {
        camera?.cameraControl?.enableTorch(flashOn && lensFacing == CameraSelector.LENS_FACING_BACK)
        imageCapture?.flashMode = if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
    }

    override fun onDestroy() {
        recording?.stop()
        super.onDestroy()
    }
}
