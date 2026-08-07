package com.example.areameasure.domain

/**
 * The measurement mode for the unified measure screen.
 *
 * [SIZE] measures an object's real-world 3D dimensions (tap-to-select, calibrate, capture).
 * [SPEED] tracks a moving object and measures its speed (auto-detect, zoom, track).
 */
enum class MeasureMode {
    SIZE,
    SPEED
}
