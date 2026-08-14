package com.example.areameasure.domain

/**
 * The running session type chosen in RUNNING mode.
 *
 * [LONG]  long-distance / steady run.
 * [SHORT] sprint / short run.
 */
enum class RunType(val label: String) {
    LONG("Long distance"),
    SHORT("Sprint");

    companion object {
        fun fromName(name: String?): RunType =
            entries.firstOrNull { it.name == name } ?: LONG
    }
}
