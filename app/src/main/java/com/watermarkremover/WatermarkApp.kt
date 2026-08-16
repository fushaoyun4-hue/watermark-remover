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
            // 注意：OpenCV 4.9.0 AAR 里的 native lib 名称是 libopencv_java4.so，
            // Core.NATIVE_LIBRARY_NAME 在 4.9.0 返回 "opencv_java490"（版本号不匹配），
            // 因此直接写死正确的库名，避免 dlopen 找不到文件导致闪退。
            System.loadLibrary("opencv_java4")
            opencvLoaded = true
            Log.d(TAG, "OpenCV native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load OpenCV native library: ${e.message}")
            opencvLoaded = false
        }
        // FFmpegKit 6.0+ 自动初始化，无需手动调用
    }
}
