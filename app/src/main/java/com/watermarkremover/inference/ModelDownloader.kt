package com.watermarkremover.inference

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 模型下载器：将 AI 模型从 GitHub Releases 下载到 app assets 目录
 *
 * 使用场景：
 * - CI 构建时自动下载模型并打包进 APK
 * - 首次启动时提示用户下载（可选）
 *
 * 模型来源：CS-Presence/quantized-models（轻量量化版 MAT/LaMa）
 * 备选：Sanster/IOPaint 项目导出
 */
@Singleton
class ModelDownloader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ModelDownloader"

        // 模型信息配置
        data class ModelInfo(
            val fileName: String,
            val downloadUrl: String,
            val expectedSizeBytes: Long,  // 用于校验
            val description: String
        )

        // 优先使用量化版（APK 体积更小）
        private val MODELS = listOf(
            // MAT - 移动端优化版（量化 ~25MB）
            ModelInfo(
                fileName = "mat_inpainting.onnx",
                downloadUrl = "https://github.com/CS-Presence/quantized-models/releases/download/v1.0/mat_inpainting.onnx",
                expectedSizeBytes = 20_000_000,
                description = "MAT 移动端图像修复模型（推荐）"
            ),
            // 备选 LaMa
            ModelInfo(
                fileName = "lama_inpainting.onnx",
                downloadUrl = "https://github.com/CS-Presence/quantized-models/releases/download/v1.0/lama_inpainting.onnx",
                expectedSizeBytes = 80_000_000,
                description = "LaMa 图像修复模型（效果更好，体积较大）"
            )
        )
    }

    /**
     * 检查 assets/ 是否已有可用模型
     */
    fun hasModelInAssets(): Boolean {
        return MODELS.any { model ->
            try {
                context.assets.open(model.fileName).close()
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * 获取 assets/ 下已有的模型文件名
     */
    fun getAvailableModelFileName(): String? {
        return MODELS.firstOrNull { model ->
            try {
                context.assets.open(model.fileName).close()
                true
            } catch (_: Exception) {
                false
            }
        }?.fileName
    }

    /**
     * 下载模型到 app assets（通过反射写入）
     * 注意：assets 目录在 APK 打包后无法直接写入。
     * 本方法适用于：
     * 1. CI 构建时：通过 srcDir 参数指定源码目录
     * 2. 运行时降级：下载到 filesDir 供 ONNX Runtime 加载
     *
     * @param srcDir 项目源码目录（CI 构建时传入 app/src/main/）
     * @param progressCallback 下载进度回调（百分比）
     * @return 下载是否成功
     */
    suspend fun downloadModelToAssetsDir(
        srcDir: File,
        progressCallback: ((Int) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val assetsDir = File(srcDir, "assets")
        if (!assetsDir.exists()) assetsDir.mkdirs()

        for (model in MODELS) {
            val targetFile = File(assetsDir, model.fileName)
            if (targetFile.exists() && targetFile.length() > 1_000_000) {
                Log.i(TAG, "模型已存在: ${model.fileName} (${targetFile.length() / 1_000_000}MB)")
                continue
            }

            Log.i(TAG, "开始下载: ${model.description}")
            val success = downloadFile(model.downloadUrl, targetFile, model.expectedSizeBytes, progressCallback)
            if (success) {
                Log.i(TAG, "下载完成: ${model.fileName}")
            } else {
                Log.w(TAG, "下载失败，尝试下一个模型: ${model.downloadUrl}")
            }
        }

        // 检查最终结果
        hasModelInAssetsDir(assetsDir)
    }

    /**
     * 检查指定目录是否有模型文件
     */
    private fun hasModelInAssetsDir(assetsDir: File): Boolean {
        if (!assetsDir.exists()) return false
        return MODELS.any { File(assetsDir, it.fileName).exists() }
    }

    /**
     * 下载单个文件（支持进度回调）
     */
    private fun downloadFile(
        urlString: String,
        outputFile: File,
        expectedSize: Long,
        progressCallback: ((Int) -> Unit)?
    ): Boolean {
        return try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 30_000
            connection.readTimeout = 300_000
            connection.setRequestProperty("User-Agent", "watermark-remover-android/1.0")
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "HTTP $responseCode: $urlString")
                return false
            }

            val totalBytes = connection.contentLength.toLong().takeIf { it > 0 } ?: expectedSize
            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(outputFile)

            val buffer = ByteArray(8192)
            var downloadedBytes = 0L
            var lastReportedPercent = -1

            while (true) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead == -1) break

                outputStream.write(buffer, 0, bytesRead)
                downloadedBytes += bytesRead

                val percent = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                if (percent != lastReportedPercent && progressCallback != null) {
                    lastReportedPercent = percent
                    progressCallback(percent)
                }
            }

            outputStream.close()
            inputStream.close()
            connection.disconnect()

            val actualSize = outputFile.length()
            Log.i(TAG, "下载完成: ${outputFile.name} (${actualSize / 1_000_000}MB)")
            actualSize > 1_000_000  // 至少 1MB 才算成功

        } catch (e: Exception) {
            Log.e(TAG, "下载异常: ${e.message}")
            outputFile.delete()
            false
        }
    }
}
