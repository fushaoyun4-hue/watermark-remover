package com.watermarkremover.inference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 视频处理器：FFmpegKit 抽帧 → OpenCV Inpaint → FFmpeg 合成
 *
 * 技术方案：
 * 1. FFmpegKit 抽帧：将视频解码为帧图片
 * 2. OpenCV Inpaint：对每帧应用 Telea 算法去除水印
 * 3. FFmpegKit 合成：将修复后的帧重新合成为视频
 *
 * 轻量化设计：无 AI 模型，纯 OpenCV + FFmpeg
 */
@Singleton
class VideoProcessor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "VideoProcessor"
        private const val FPS = 30
        private const val FRAME_QUALITY = 95
    }

    /**
     * 处理进度状态
     */
    sealed class ProcessState {
        data class Progress(val current: Int, val total: Int, val phase: String) : ProcessState()
        data class Success(val outputUri: Uri) : ProcessState()
        data class Error(val message: String) : ProcessState()
    }

    /**
     * 处理图片（单帧）
     * @param bitmap 原图
     * @param masks 用户框选的水印区域（归一化坐标 0~1）
     * @return 修复后的图片
     */
    suspend fun processImage(bitmap: Bitmap, masks: List<RectF>): Bitmap = withContext(Dispatchers.Default) {
        val width = bitmap.width
        val height = bitmap.height

        // 将 Bitmap 转换为 OpenCV Mat
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)

        // 创建蒙版
        val mask = Mat(height, width, CvType.CV_8UC1, Scalar(0.0))

        for (rect in masks) {
            val left = (rect.left * width).toInt().coerceIn(0, width - 1)
            val top = (rect.top * height).toInt().coerceIn(0, height - 1)
            val right = (rect.right * width).toInt().coerceIn(left + 1, width)
            val bottom = (rect.bottom * height).toInt().coerceIn(top + 1, height)

            // 填充蒙版区域（白色 = 需要修复）
            for (y in top until bottom) {
                for (x in left until right) {
                    mask.put(y, x, 255.0)
                }
            }
        }

        // OpenCV Inpaint - Telea 算法（快速、轻量、无需模型）
        val dst = Mat()
        Imgproc.inpaint(src, mask, dst, 3.0, Imgproc.INPAINT_TELEA)

        // 转换回 Bitmap
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(dst, result)

        // 释放内存
        src.release()
        dst.release()
        mask.release()

        result
    }

    /**
     * 处理视频（逐帧处理 + 合成）
     * @param videoUri 视频文件 URI
     * @param masks 用户框选的水印区域
     * @param onProgress 进度回调
     * @return 修复后的视频 URI
     */
    fun processVideo(
        videoUri: Uri,
        masks: List<RectF>
    ): Flow<ProcessState> = flow {
        try {
            val tempDir = File(context.cacheDir, "video_process_${System.currentTimeMillis()}")
            tempDir.mkdirs()

            val framesDir = File(tempDir, "frames")
            framesDir.mkdirs()

            val outputVideo = File(tempDir, "output.mp4")

            // ========== 阶段1：抽帧 ==========
            emit(ProcessState.Progress(0, 100, "正在提取视频帧..."))

            val extractResult = FFmpegExtractor.extractFrames(
                context = context,
                videoUri = videoUri,
                outputDir = framesDir
            )

            if (extractResult.isFailure) {
                emit(ProcessState.Error("抽帧失败: ${extractResult.exceptionOrNull()?.message}"))
                return@flow
            }

            val frameFiles = extractResult.getOrNull()!!.sortedBy { it.name }
            val totalFrames = frameFiles.size

            if (totalFrames == 0) {
                emit(ProcessState.Error("视频无有效帧"))
                return@flow
            }

            // ========== 阶段2：逐帧修复 ==========
            emit(ProcessState.Progress(10, 100, "正在去除水印（${totalFrames}帧）..."))

            val repairedDir = File(tempDir, "repaired")
            repairedDir.mkdirs()

            var processed = 0
            for (frameFile in frameFiles) {
                val bitmap = android.graphics.BitmapFactory.decodeFile(frameFile.absolutePath)
                if (bitmap != null) {
                    val repaired = processImage(bitmap, masks)
                    val outputFile = File(repairedDir, frameFile.name)
                    FileOutputStream(outputFile).use { fos ->
                        repaired.compress(Bitmap.CompressFormat.JPEG, FRAME_QUALITY, fos)
                    }
                    bitmap.recycle()
                    repaired.recycle()
                }

                processed++
                val progress = 10 + (processed * 80 / totalFrames)
                emit(ProcessState.Progress(progress.toInt(), 100, "处理中 $processed/$totalFrames"))
            }

            // ========== 阶段3：合成视频 ==========
            emit(ProcessState.Progress(90, 100, "正在合成视频..."))

            val合成Result = FFmpegExtractor.mergeFrames(
                framesDir = repairedDir,
                outputFile = outputVideo,
                originalVideoUri = videoUri
            )

            if (合成Result.isFailure) {
                emit(ProcessState.Error("视频合成失败: ${合成Result.exceptionOrNull()?.message}"))
                return@flow
            }

            // 复制到应用私有目录
            val finalDir = File(context.filesDir, "results")
            finalDir.mkdirs()
            val finalFile = File(finalDir, "watermark_removed_${System.currentTimeMillis()}.mp4")
            outputVideo.copyTo(finalFile, overwrite = true)

            // 清理临时文件
            tempDir.deleteRecursively()

            emit(ProcessState.Progress(100, 100, "完成"))
            emit(ProcessState.Success(Uri.fromFile(finalFile)))

        } catch (e: OutOfMemoryError) {
            emit(ProcessState.Error("内存不足，请选择更短的视频"))
        } catch (e: Exception) {
            emit(ProcessState.Error("处理失败: ${e.message}"))
        }
    }.flowOn(Dispatchers.IO)
}

/**
 * FFmpegKit 抽帧和合成工具
 */
object FFmpegExtractor {

    /**
     * 从视频提取所有帧
     */
    fun extractFrames(
        context: Context,
        videoUri: Uri,
        outputDir: File
    ): Result<List<File>> = runCatching {
        val inputPath = getPathFromUri(context, videoUri)
            ?: throw IllegalArgumentException("无法获取视频路径")

        val outputPattern = File(outputDir, "frame_%04d.jpg").absolutePath

        val command = arrayOf(
            "-y",                          // 覆盖输出
            "-i", inputPath,                // 输入视频
            "-vf", "fps=30",                // 固定30fps
            "-q:v", "2",                   // 质量
            outputPattern                   // 输出帧
        )

        val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)

        if (ReturnCode.isSuccess(session.returnCode)) {
            outputDir.listFiles()?.toList() ?: emptyList()
        } else {
            throw Exception("FFmpeg 抽帧失败: ${session.failStackTrace}")
        }
    }

    /**
     * 将帧合成为视频
     */
    fun mergeFrames(
        framesDir: File,
        outputFile: File,
        originalVideoUri: Uri
    ): Result<File> = runCatching {
        // 获取原视频信息（尺寸、时长等）
        val mediaInfo = getVideoInfo(originalVideoUri)
        val width = mediaInfo["width"] ?: 1920
        val height = mediaInfo["height"] ?: 1080
        val duration = mediaInfo["duration"] ?: 15

        val inputPattern = File(framesDir, "frame_%04d.jpg").absolutePath

        val command = arrayOf(
            "-y",
            "-framerate", "30",
            "-i", inputPattern,
            "-c:v", "libx264",
            "-preset", "fast",              // 快速编码
            "-crf", "23",                   // 质量
            "-pix_fmt", "yuv420p",
            "-s", "${width}x${height}",
            "-t", duration.toString(),
            outputFile.absolutePath
        )

        val session = com.arthenica.ffmpegkit.FFmpegKit.execute(command)

        if (ReturnCode.isSuccess(session.returnCode)) {
            outputFile
        } else {
            throw Exception("FFmpeg 合成失败: ${session.failStackTrace}")
        }
    }

    /**
     * 获取视频信息
     */
    private fun getVideoInfo(uri: Uri): Map<String, Double> {
        val session = com.arthenica.ffmpegkit.FFmpegKit.execute("-i ${uri.path}")
        val output = session.output

        var width = 1920.0
        var height = 1080.0
        var duration = 15.0

        // 解析输出获取尺寸
        val sizeRegex = """(\d+)x(\d+)""".toRegex()
        sizeRegex.find(output)?.let {
            width = it.groupValues[1].toDouble()
            height = it.groupValues[2].toDouble()
        }

        // 解析输出获取时长
        val durationRegex = """Duration: (\d+):(\d+):(\d+)\.(\d+)""".toRegex()
        durationRegex.find(output)?.let {
            val h = it.groupValues[1].toDouble()
            val m = it.groupValues[2].toDouble()
            val s = it.groupValues[3].toDouble()
            val ms = it.groupValues[4].toDouble()
            duration = h * 3600 + m * 60 + s + ms / 100
        }

        return mapOf("width" to width, "height" to height, "duration" to duration)
    }

    /**
     * 从 Content URI 获取真实文件路径
     */
    private fun getPathFromUri(context: Context, uri: Uri): String? {
        return try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex("_data")
                    if (index >= 0) it.getString(index) else uri.path
                } else uri.path
            } ?: uri.path
        } catch (e: Exception) {
            uri.path
        }
    }
}
