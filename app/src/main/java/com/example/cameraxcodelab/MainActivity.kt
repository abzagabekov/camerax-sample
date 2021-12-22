package com.example.cameraxcodelab

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.example.cameraxcodelab.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity(R.layout.activity_main), LuminosityAnalyzer.ObjectDetectionListener {

    private val binding by viewBinding(ActivityMainBinding::bind)

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val Context.mainLoopExecutor: Executor
        get() = ContextCompat.getMainExecutor(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        if (allPermissionsGranted()) {
            bindUseCases()
        } else {
            permissionsRequest.launch(REQUIRED_PERMISSIONS)
        }

        initAndDrawOverlay()
    }

    private fun initAndDrawOverlay() {
        lifecycleScope.launch {
            val surfaceHolder = binding.overlay.initHolder()
            drawOverlay(
                surfaceHolder,
                DESIRED_HEIGHT_CROP_PERCENT,
                DESIRED_WIDTH_CROP_PERCENT,
                false
            )
        }
    }

    private suspend fun Context.getCameraProvider(): ProcessCameraProvider {
        return suspendCoroutine { continuation ->
            ProcessCameraProvider.getInstance(this).apply {
                addListener(Runnable {
                    continuation.resume(get())
                }, mainLoopExecutor)
            }
        }
    }

    private suspend fun ImageCapture.takePicture(
        executor: Executor, outputFileOptions: ImageCapture.OutputFileOptions): ImageCapture.OutputFileResults {
        return suspendCoroutine { continuation ->
            takePicture(outputFileOptions, executor, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    continuation.resume(outputFileResults)
                }

                override fun onError(exception: ImageCaptureException) {
                    continuation.resumeWithException(exception)
                }
            })
        }
    }

    private suspend fun SurfaceView.initHolder(): SurfaceHolder {
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSPARENT)
        return suspendCoroutine { continuation ->
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    continuation.resume(holder)
                }

                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int
                ) {}

                override fun surfaceDestroyed(holder: SurfaceHolder) {}
            })
        }
    }

    private fun bindUseCases() {
        lifecycleScope.launch {
            bindUseCases(getCameraProvider())
        }
    }

    private fun bindUseCases(cameraProvider: ProcessCameraProvider) {
        val metrics = DisplayMetrics().also { binding.viewFinder.display.getRealMetrics(it) }
        val screenAspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)
        val rotation = binding.viewFinder.display.rotation

        val preview = getPreview(screenAspectRatio, rotation)
        val imageAnalyzer = getImageAnalyzer(screenAspectRatio, rotation)
        val cameraSelector = getCameraSelector()

        val imageCapture = getImageCapture()

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture, imageAnalyzer)
        } catch (ex: Exception) {
            Log.e(TAG, "Use case binding failed", ex)
        }

        binding.cameraCaptureButton.setOnClickListener {
            takePicture(imageCapture)
        }
    }

    private fun takePicture(imageCapture: ImageCapture) {
        lifecycleScope.launch {
            val outputOptions = getOutputFileOptions()

            val imageOutput = imageCapture.takePicture(mainLoopExecutor, outputOptions)

            val msg = "Photo capture succeeded: ${imageOutput.savedUri}"
            Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
            Log.d(TAG, msg)
        }
    }

    private fun getOutputFileOptions(): ImageCapture.OutputFileOptions {
        val photoFile = File(
            getOutputDirectory(),
            SimpleDateFormat(FILENAME_FORMAT, Locale.US
            ).format(System.currentTimeMillis()) + ".jpg")
        return ImageCapture.OutputFileOptions.Builder(photoFile).build()
    }


    private fun getImageCapture() = ImageCapture.Builder()
        .build()

    private fun getCameraSelector() =
        CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

    private fun getImageAnalyzer(screenAspectRatio: Int, rotation: Int) =
        ImageAnalysis.Builder()
            .setTargetAspectRatio(screenAspectRatio)
            .setTargetRotation(rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(
                    cameraExecutor, LuminosityAnalyzer(
                        Pair(DESIRED_HEIGHT_CROP_PERCENT, DESIRED_WIDTH_CROP_PERCENT),
                        this
                    )
                )
            }


    private fun getPreview(screenAspectRatio: Int, rotation: Int) =
        Preview.Builder()
            .setTargetAspectRatio(screenAspectRatio)
            .setTargetRotation(rotation)
            .build()
            .also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }


    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() } }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private val permissionsRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) {
            if (allPermissionsGranted()) {
                bindUseCases()
            } else {
                Toast.makeText(this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }

    companion object {
        private const val TAG = "CameraXBasic"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

        const val DESIRED_WIDTH_CROP_PERCENT = 8
        const val DESIRED_HEIGHT_CROP_PERCENT = 54

        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0
    }


    private fun drawOverlay(
        holder: SurfaceHolder,
        heightCropPercent: Int,
        widthCropPercent: Int,
        isObjectDetected: Boolean
    ) {
        val canvas = holder.lockCanvas()
        val bgPaint = Paint().apply {
            alpha = 140
        }
        canvas.drawPaint(bgPaint)
        val rectPaint = Paint()
        rectPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        rectPaint.style = Paint.Style.FILL
        rectPaint.color = Color.WHITE
        val outlinePaint = Paint()
        outlinePaint.style = Paint.Style.STROKE
        outlinePaint.color = if (isObjectDetected) Color.GREEN else Color.WHITE
        outlinePaint.strokeWidth = 4f
        val surfaceWidth = holder.surfaceFrame.width()
        val surfaceHeight = holder.surfaceFrame.height()

        val cornerRadius = 25f
        // Set rect centered in frame
        val rectTop = surfaceHeight * heightCropPercent / 2 / 100f
        val rectLeft = surfaceWidth * widthCropPercent / 2 / 100f
        val rectRight = surfaceWidth * (1 - widthCropPercent / 2 / 100f)
        val rectBottom = surfaceHeight * (1 - heightCropPercent / 2 / 100f)
        val rect = RectF(rectLeft, rectTop, rectRight, rectBottom)
        canvas.drawRoundRect(
            rect, cornerRadius, cornerRadius, rectPaint
        )
        canvas.drawRoundRect(
            rect, cornerRadius, cornerRadius, outlinePaint
        )
        val textPaint = Paint()
        textPaint.color = Color.WHITE
        textPaint.textSize = 50F

        val overlayText = "Overlay text"
        val textBounds = Rect()
        textPaint.getTextBounds(overlayText, 0, overlayText.length, textBounds)
        val textX = (surfaceWidth - textBounds.width()) / 2f
        val textY = rectBottom + textBounds.height() + 15f // put text below rect and 15f padding
        canvas.drawText(overlayText, textX, textY, textPaint)
        holder.unlockCanvasAndPost(canvas)
    }

    /**
     *  [androidx.camera.core.ImageAnalysisConfig] requires enum value of
     *  [androidx.camera.core.AspectRatio]. Currently it has values of 4:3 & 16:9.
     *
     *  Detecting the most suitable ratio for dimensions provided in @params by comparing absolute
     *  of preview ratio to one of the provided values.
     *
     *  @param width - preview width
     *  @param height - preview height
     *  @return suitable aspect ratio
     */
    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = ln(max(width, height).toDouble() / min(width, height))
        if (abs(previewRatio - ln(RATIO_4_3_VALUE))
            <= abs(previewRatio - ln(RATIO_16_9_VALUE))
        ) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    data class BoxWithText(val box: Rect, val text: String)

    override fun onObjectDetected() {
        drawOverlay(binding.overlay.holder,
            DESIRED_HEIGHT_CROP_PERCENT,
            DESIRED_WIDTH_CROP_PERCENT, true)
    }

    override fun onObjectUndetected() {
        drawOverlay(binding.overlay.holder,
            DESIRED_HEIGHT_CROP_PERCENT,
            DESIRED_WIDTH_CROP_PERCENT, false)
    }
}
