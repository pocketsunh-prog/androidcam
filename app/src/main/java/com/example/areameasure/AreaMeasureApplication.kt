package com.example.areameasure

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import org.opencv.android.OpenCVLoader

@HiltAndroidApp
class AreaMeasureApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        if (OpenCVLoader.initLocal()) {
            Log.d(TAG, "OpenCV loaded successfully")
        } else {
            Log.e(TAG, "OpenCV initialization failed")
        }
    }

    companion object {
        private const val TAG = "AreaMeasureApp"
    }
}
