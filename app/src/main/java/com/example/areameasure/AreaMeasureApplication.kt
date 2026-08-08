package com.example.areameasure

import android.app.Application
import android.util.Log
import com.example.areameasure.processing.FaceDetector
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
        // Materialize the Haar cascade asset to internal storage and load the
        // classifier. The :opencv module does not bundle the cascade XML in the
        // APK, so it must be shipped as an app asset and materialized before
        // CascadeClassifier (which needs a real filesystem path) can load it.
        FaceDetector.init(this)
    }

    companion object {
        private const val TAG = "AreaMeasureApp"
    }
}
