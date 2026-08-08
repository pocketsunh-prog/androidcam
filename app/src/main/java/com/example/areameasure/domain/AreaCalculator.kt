package com.example.areameasure.domain

import com.example.areameasure.data.model.UnitOfMeasure

/**
 * Converts real-world measurements on the calibrated plane into the user's
 * chosen unit. Pixel-to-millimetre conversion is no longer done here: it happens
 * in the homography step ([PerspectiveCalibration]), which also corrects for
 * perspective tilt and in-plane rotation.
 */
object AreaCalculator {

    /**
     * Express a plane measurement (already in millimetres) in [unit].
     *
     * X and Y are the two measurable side lengths of the object on the plane.
     * Z (depth, out of the plane) cannot be recovered from a single 2D frame, so
     * we anchor it to X and assume roughly cubic proportions (Z ≈ X).
     *
     * @param widthMm  longer side length in millimetres (X)
     * @param heightMm shorter side length in millimetres (Y)
     * @param unit     target output unit
     * @return Triple of (X, Y, Z) in the requested unit
     */
    fun fromPlaneMm(
        widthMm: Double,
        heightMm: Double,
        unit: UnitOfMeasure
    ): Triple<Double, Double, Double> {
        val mmZ = widthMm
        return Triple(
            widthMm * unit.mmFactor,
            heightMm * unit.mmFactor,
            mmZ * unit.mmFactor
        )
    }
}
