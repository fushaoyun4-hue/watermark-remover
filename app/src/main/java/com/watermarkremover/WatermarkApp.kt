package com.watermarkremover

import android.app.Application
import com.arthenica.ffmpegkit.FFmpegKitConfig
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class WatermarkApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // FFmpegKit 初始化
        FFmpegKitConfig.init()
    }
}
