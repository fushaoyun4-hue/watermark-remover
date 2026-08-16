package com.watermarkremover

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.opencv.core.Core
import android.util.Log

@HiltAndroidApp
class WatermarkApp : Application() {
    companion object {
        private const val TAG = "WatermarkApp"
        private var opencvLoaded = false
        
        fun isOpenCVLoaded(): Boolean = opencvLoaded
    }
    
    override fun onCreate() {
        super.onCreate()
        // 静态初始化 OpenCV（org.opencv:opencv 4.x 的 Java 绑定）
        try {
            System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
            opencvLoaded = true
            Log.d(TAG, "OpenCV native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load OpenCV native library: ${e.message}")
            opencvLoaded = false
        }
        // FFmpegKit 6.0+ 自动初始化，无需手动调用
    }
}
