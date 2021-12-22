package com.example.cameraxcodelab

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.common.model.LocalModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.custom.CustomObjectDetectorOptions
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.android.synthetic.main.activity_main.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*

class LuminosityAnalyzer(
    private val imageCropPercentages: Pair<Int, Int>,
    private val listener: ObjectDetectionListener) : ImageAnalysis.Analyzer {

    companion object {
        private val localModel: LocalModel =
            LocalModel.Builder().setAssetFilePath("custom_models/object_labeler.tflite").build()

        private const val TAG = "LuminosityAnalyzer"
    }

    interface ObjectDetectionListener {
        fun onObjectDetected()
        fun onObjectUndetected()
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        rewind()    // Rewind the buffer to zero
        val data = ByteArray(remaining())
        get(data)   // Copy the buffer into a byte array
        return data // Return the byte array
    }

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(image: ImageProxy) {

        val mediaImage = image.image ?: return

        val rotationDegrees = image.imageInfo.rotationDegrees

        // We requested a setTargetAspectRatio, but it's not guaranteed that's what the camera
        // stack is able to support, so we calculate the actual ratio from the first frame to
        // know how to appropriately crop the image we want to analyze.
        val imageHeight = mediaImage.height
        val imageWidth = mediaImage.width

        val actualAspectRatio = imageWidth / imageHeight

        val convertImageToBitmap = ImageUtils.convertYuv420888ImageToBitmap(mediaImage)
        val cropRect = Rect(0, 0, imageWidth, imageHeight)

        // If the image is rotated by 90 (or 270) degrees, swap height and width when calculating
        // the crop.
        val cropPercentages = imageCropPercentages
        val heightCropPercent = cropPercentages.first
        val widthCropPercent = cropPercentages.second
        val (widthCrop, heightCrop) = when (rotationDegrees) {
            90, 270 -> Pair(heightCropPercent / 100f, widthCropPercent / 100f)
            else -> Pair(widthCropPercent / 100f, heightCropPercent / 100f)
        }

        cropRect.inset(
            (imageWidth * widthCrop / 2).toInt(),
            (imageHeight * heightCrop / 2).toInt()
        )
        val croppedBitmap =
            ImageUtils.rotateAndCrop(convertImageToBitmap, rotationDegrees, cropRect)

        //saveToFile(croppedBitmap)
        runObjectDetection(croppedBitmap, rotationDegrees)

        image.close()
    }

    private fun runObjectDetection(bitmap: Bitmap, rotation: Int) {
        val image = InputImage.fromBitmap(bitmap, rotation)

        val options = CustomObjectDetectorOptions.Builder(localModel)
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableClassification()
            .setClassificationConfidenceThreshold(0.8f)
            .build()
        val objectDetector = ObjectDetection.getClient(options)

        objectDetector.process(image).addOnSuccessListener { results ->
            debugPrint(results)

            // Parse ML Kit's DetectedObject and create corresponding visualization data
            val detectedObjects = results.map {
                var text = "Unknown"

                // We will show the top confident detection result if it exist
                if (it.labels.isNotEmpty()) {
                    val firstLabel = it.labels.first()
                    text = "${firstLabel.text}, ${firstLabel.confidence.times(100).toInt()}%"
                }
                MainActivity.BoxWithText(it.boundingBox, text)
            }

            if (detectedObjects.isNotEmpty()) {
                //drawOverlay(overlay.holder, DESIRED_HEIGHT_CROP_PERCENT, DESIRED_WIDTH_CROP_PERCENT, true)
            }
        }.addOnFailureListener {
            Log.e(TAG, it.message.toString())
        }
    }

//    private fun saveToFile(bitmap: Bitmap) {
//        val file = File(
//            getOutputDirectory(),
//            SimpleDateFormat(
//                MainActivity.FILENAME_FORMAT, Locale.US
//            ).format(System.currentTimeMillis()) + ".jpg")
//
//        file.createNewFile()
//        // convert bitmap to byte array
//        val bitmapData = ByteArrayOutputStream().use {
//            bitmap.compress(Bitmap.CompressFormat.JPEG, 50, it)
//            it.toByteArray()
//        }
//        // write bytes into file
//        FileOutputStream(file).use {
//            it.write(bitmapData)
//        }
//    }

    private fun debugPrint(detectedObjects: List<DetectedObject>) {
        detectedObjects.forEachIndexed { index, detectedObject ->
            val box = detectedObject.boundingBox

            Log.d(TAG, "Detected object: $index")
            Log.d(TAG, " trackingId: ${detectedObject.trackingId}")
            Log.d(TAG, " boundingBox: (${box.left}, ${box.top}) - (${box.right},${box.bottom})")
            if (detectedObject.labels.isNotEmpty()) {
                detectedObject.labels.forEach {
                    Log.d(TAG, " categories: ${it.text}")
                    Log.d(TAG, " category index: ${it.index}")
                    Log.d(TAG, " confidence: ${it.confidence}")

                    if (it.index in listOf(510))
                        listener.onObjectDetected()
                    else
                        listener.onObjectUndetected()
                }
            } else {
                listener.onObjectUndetected()
            }
        }
    }

}
