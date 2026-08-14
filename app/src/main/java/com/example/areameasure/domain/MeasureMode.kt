package com.example.areameasure.domain

/**
 * The measurement mode for the unified measure screen.
 *
 * [SIZE] measures an object's real-world 3D dimensions (tap-to-select, calibrate, capture).
 * [SPEED] tracks a moving object and measures its speed (auto-detect, zoom, track).
 * [PEOPLE] counts the faces visible right now via on-device face detection (live count, save snapshot).
 * [RUNNING] analyses running posture (long-distance vs sprint) and flags good/bad form.
 * [FAN] automatically measures a spinning fan's blade speed (RPM).
 * [RACE] times a race until the runner crosses the finish line (auto photo).
 */
enum class MeasureMode {
    SIZE,
    SPEED,
    PEOPLE,
    RUNNING,
    FAN,
    RACE
}
