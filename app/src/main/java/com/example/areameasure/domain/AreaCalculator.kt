package com.example.areameasure.domain

import com.example.areameasure.data.model.UnitOfMeasure

/**
 * Converts pixel-space 3D measurements into real-world dimensions
 * using a calibration factor (pixels per millimeter).
 */
object AreaCalculator {

    /**
     * Convert pixel dimensions to real-world dimensions.
     *
     * @param pixelX      Width in pixels
     * @param pixelY      Height in pixels
     * @param pixelZ      Depth in pixels
     * @param pixelsPerMm Calibration: how many pixels equal one millimeter
     * @param unit        Target output unit
     * @return Triple of (X, Y, Z) in the requested unit
     */
    fun calculateDimensions(
        pixelX: Double,
        pixelY: Double,
        pixelZ: Double,
        pixelsPerMm: Double,
        unit: UnitOfMeasure
    ): Triple<Double, Double, Double> {
        val mmX = pixelX / pixelsPerMm
        val mmY = pixelY / pixelsPerMm
        val mmZ = pixelZ / pixelsPerMm
        return Triple(
            mmX * unit.mmFactor,
            mmY * unit.mmFactor,
            mmZ * unit.mmFactor
        )
    }
}
