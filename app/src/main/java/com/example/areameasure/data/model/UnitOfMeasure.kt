package com.example.areameasure.data.model

/**
 * Supported length units for 3D measurement.
 *
 * @param label     Human-readable name
 * @param symbol    Short symbol shown in the UI (e.g. "mm")
 * @param mmFactor  Conversion factor: multiply a value in mm by this to get the unit value
 */
enum class UnitOfMeasure(val label: String, val symbol: String, val mmFactor: Double) {
    MM("Millimeters", "mm", 1.0),
    CM("Centimeters", "cm", 0.1),
    M("Meters", "m", 0.001),
    INCH("Inches", "in", 1.0 / 25.4);

    companion object {
        fun fromSymbol(symbol: String): UnitOfMeasure =
            entries.firstOrNull { it.symbol == symbol } ?: MM
    }
}
