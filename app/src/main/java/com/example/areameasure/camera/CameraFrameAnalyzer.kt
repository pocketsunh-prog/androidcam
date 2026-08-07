package com.example.areameasure.camera

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.areameasure.processing.ImageProcessor
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * CameraX [ImageAnalysis.Analyzer] that converts each YUV_420_888 frame to an
 * RGBA [Mat] and feeds it to [ImageProcessor]. Results are delivered via callback.
 */
class CameraFrameAnalyzer(
    private val imageProcessor: ImageProcessor,
    private val onResult: (ImageProcessor.ProcessingResult) -> Unit
) : ImageAnalysis.Analyzer {

    /** User-selected target contour index (-1 = auto-select largest). */
    @Volatile
    private var targetContourIndex: Int = -1

    fun setTargetContourIndex(index: Int) {
        targetContourIndex = index
    }

    override fun analyze(image: ImageProxy) {
        try {
            val mat = image.toRgbaMat()
            val result = imageProcessor.processFrame(
                inputMat = mat,
                selectedTargetIndex = targetContourIndex
            )
            onResult(result)
        } catch (e: Exception) {
            Log.e(TAG, "Frame analysis failed", e)
        } finally {
            image.close()
        }
    }

    /**
     * Convert a YUV_420_888 [ImageProxy] to an RGBA [Mat].
     */
    private fun ImageProxy.toRgbaMat(): Mat {
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        // Y plane
        yBuffer.get(nv21, 0, ySize)
        // VU interleaved (NV21 expects V then U)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvMat = Mat(height + height / 2, width, CvType.CV_8UC1)
        yuvMat.put(0, 0, nv21)

        val rgbaMat = Mat()
        Imgproc.cvtColor(yuvMat, rgbaMat, Imgproc.COLOR_YUV2RGBA_NV21)

        yuvMat.release()

        return rgbaMat
    }

    companion object {
        private const val TAG = "CameraFrameAnalyzer"
    }
}
