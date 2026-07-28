package com.watermarkremover

import android.app.Application
import com.arthenica.ffmpegkit.FFmpegKitConfig
import dagger.hilt.android.HiltAndroidApp
import org.opencv.android.OpenCVLoader

@HiltAndroidApp
class WatermarkApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 初始化 OpenCV（必须在使用前调用）
        if (!OpenCVLoader.initLocal()) {
            OpenCVLoader.initDebug()
        }
        // 初始化 FFmpegKit
        FFmpegKitConfig.init()
    }
}
