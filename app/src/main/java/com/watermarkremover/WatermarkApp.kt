package com.watermarkremover

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class WatermarkApp : Application() {
    // FFmpegKit 6.0+ 自动初始化，无需手动调用
}
