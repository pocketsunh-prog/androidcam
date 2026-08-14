package com.example.areameasure.camera

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.ArrayDeque
import kotlin.math.abs

/**
 * CameraX analyzer for FAN mode.
 *
 * It downsamples each frame to grayscale, picks the image block with the
 * strongest frame-to-frame brightness change (the spinning blades), records
 * that block's motion energy over time, and estimates the blade-pass frequency
 * via autocorrelation. The caller converts that frequency to RPM using the
 * chosen blade count.
 *
 * Accuracy is limited by the camera frame rate: blade-pass frequencies above
 * roughly half the frame rate alias, so this works for slow-spinning fans.
 */
class FanSpeedAnalyzer(
    private val onResult: (FanSpeedResult) -> Unit
) : ImageAnalysis.Analyzer {

    data class FanSpeedResult(
        val detected: Boolean,
        /** Blade pass frequency in Hz (blades passing the region per second). */
        val passFrequencyHz: Double?,
        /** Normalised motion energy of the selected region (0..1). */
        val energy: Double,
        /** Number of samples accumulated so far. */
        val sampleCount: Int
    )

    private data class Sample(val timeMs: Long, val energy: Double)

    private var prevSmall: Mat? = null
    private val samples = ArrayDeque<Sample>()

    /** Drop accumulated state so a fresh session starts clean. */
    fun reset() {
        prevSmall?.release()
        prevSmall = null
        samples.clear()
    }

    override fun analyze(image: ImageProxy) {
        try {
            val gray = image.toGrayMat()
            val small = Mat()
            Imgproc.resize(gray, small, Size(120.0, 90.0), 0.0, 0.0, Imgproc.INTER_AREA)
            gray.release()

            var energy = 0.0
            val prev = prevSmall
            if (prev != null && prev.cols() == small.cols() && prev.rows() == small.rows()) {
                energy = strongestBlockDifference(prev, small)
            }
            prevSmall?.release()
            prevSmall = small

            val nowMs = image.imageInfo.timestamp / 1_000_000L
            samples.addLast(Sample(nowMs, energy))
            while (samples.size > MAX_SAMPLES) samples.removeFirst()

            val freq = estimateFrequency(samples.toList())
            onResult(
                FanSpeedResult(
                    detected = freq != null,
                    passFrequencyHz = freq,
                    energy = energy,
                    sampleCount = samples.size
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Fan analysis failed", e)
        } finally {
            image.close()
        }
    }

    /** Mean absolute difference per block; returns the strongest block (0..1). */
    private fun strongestBlockDifference(a: Mat, b: Mat): Double {
        val cols = a.cols()
        val rows = a.rows()
        val blockCols = 4
        val blockRows = 3
        val bw = cols / blockCols
        val bh = rows / blockRows

        val aData = ByteArray(cols * rows)
        val bData = ByteArray(cols * rows)
        a.get(0, 0, aData)
        b.get(0, 0, bData)

        var best = 0.0
        for (by in 0 until blockRows) {
            for (bx in 0 until blockCols) {
                val x0 = bx * bw
                val y0 = by * bh
                var sum = 0L
                var count = 0
                for (y in y0 until y0 + bh) {
                    val rowOffset = y * cols
                    for (x in x0 until x0 + bw) {
                        val idx = rowOffset + x
                        sum += abs(aData[idx].toInt() - bData[idx].toInt())
                        count++
                    }
                }
                val mean = sum.toDouble() / count
                if (mean > best) best = mean
            }
        }
        return best / 255.0
    }

    /** Autocorrelation-based period estimate of the energy signal; null if no clear period. */
    private fun estimateFrequency(list: List<Sample>): Double? {
        val n = list.size
        if (n < MIN_SAMPLES) return null

        val energies = DoubleArray(n) { list[it].energy }
        val mean = energies.average()
        val x = DoubleArray(n) { energies[it] - mean }

        val maxLag = n / 2
        val ac = DoubleArray(maxLag + 1)
        for (k in 0..maxLag) {
            var s = 0.0
            for (i in 0 until n - k) s += x[i] * x[i + k]
            ac[k] = s / (n - k)
        }
        if (ac[0] <= 1e-9) return null

        var bestLag = -1
        var bestPeak = 0.0
        for (k in 2..maxLag) {
            val v = ac[k] / ac[0]
            if (v > bestPeak && v > MIN_PEAK_RATIO) {
                bestPeak = v
                bestLag = k
            }
        }
        if (bestLag < 0) return null

        val dtAvg = (list.last().timeMs - list.first().timeMs).toDouble() / (n - 1) / 1000.0
        if (dtAvg <= 0.0) return null
        val periodSeconds = bestLag * dtAvg
        if (periodSeconds <= 0.0) return null
        return 1.0 / periodSeconds
    }

    private fun ImageProxy.toGrayMat(): Mat {
        val yBuffer = planes[0].buffer
        val rowStride = planes[0].rowStride
        val width = this.width
        val height = this.height
        val ySize = yBuffer.remaining()
        val y = ByteArray(ySize)
        yBuffer.get(y)

        val mat = Mat(height, width, CvType.CV_8UC1)
        val row = ByteArray(width)
        for (r in 0 until height) {
            System.arraycopy(y, r * rowStride, row, 0, width)
            mat.put(r, 0, row)
        }
        return mat
    }

    companion object {
        private const val TAG = "FanSpeedAnalyzer"
        private const val MAX_SAMPLES = 128
        private const val MIN_SAMPLES = 32
        private const val MIN_PEAK_RATIO = 0.25
    }
}
