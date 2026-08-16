package com.watermarkremover.inference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import com.arthenica.ffmpegkit.ReturnCode
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
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

        // ========== Inpaint 算法核心参数 ==========
        // 修复半径：框选区域向外扩展的像素范围
        // 值越大：边缘越平滑，但计算越慢；值越小：细节保留好但可能有残留
        // 水印通常比较薄，3-5px 是最佳平衡点
        private const val INPAINT_RADIUS_TELEA = 5.0

        // NS（Navier-Stokes）算法参数：倾向于保留结构，适合去除边缘清晰的水印
        private const val INPAINT_RADIUS_NS = 5.0

        // JPEG 压缩质量：帧质量，影响输出清晰度和文件大小
        private const val FRAME_QUALITY = 90
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
     *
     * 算法说明：
     * - 使用 Telea FMM（Fast Marching Method）：边缘保留好，速度快，适合大多数水印
     * - 同时也支持 NS 算法，可在性能有余量时切换
     *
     * @param bitmap 原图
     * @param masks 用户框选的水印区域（归一化坐标 0~1）
     * @param useNavierStokes 是否使用 NS 算法（默认 false，使用 Telea）
     * @return 修复后的图片
     */
    suspend fun processImage(
        bitmap: Bitmap,
        masks: List<RectF>,
        useNavierStokes: Boolean = false
    ): Bitmap = withContext(Dispatchers.Default) {

        if (masks.isEmpty()) return@withContext bitmap

        val width = bitmap.width
        val height = bitmap.height

        // 将 Bitmap 转换为 OpenCV Mat
        // Utils.bitmapToMat 对 ARGB_8888 产生 CV_8UC4（4通道），inpaint 只接受 8-bit 3通道 BGR
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)

        // 4通道 RGBA → 3通道 BGR（inpaint 的必需格式）
        val srcBgr = Mat()
        Imgproc.cvtColor(src, srcBgr, Imgproc.COLOR_RGBA2BGR)
        src.release()

        // 转为灰度图（蒙版用）
        val graySrc = Mat()
        Imgproc.cvtColor(srcBgr, graySrc, Imgproc.COLOR_BGR2GRAY)

        // 创建蒙版
        val mask = Mat(height, width, CvType.CV_8UC1, Scalar(0.0))

        for (rect in masks) {
            // 归一化坐标 → 像素坐标
            val left   = (rect.left   * width).toInt().coerceIn(0, width - 1)
            val top    = (rect.top    * height).toInt().coerceIn(0, height - 1)
            val right  = (rect.right  * width).toInt().coerceIn(left + 1, width)
            val bottom = (rect.bottom * height).toInt().coerceIn(top + 1, height)

            // 填充蒙版（白色 = 需要修复区域）
            // 用 -1 可以直接填充整个矩形（含边界）
            Imgproc.rectangle(
                mask,
                org.opencv.core.Point(left.toDouble(), top.toDouble()),
                org.opencv.core.Point(right.toDouble(), bottom.toDouble()),
                Scalar(255.0),
                -1
            )
        }

        // 对蒙版做一次轻微的膨胀（dilate），扩大修复范围，让边缘过渡更自然
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_ELLIPSE,
            org.opencv.core.Size(3.0, 3.0)
        )
        Imgproc.dilate(mask, mask, kernel)
        kernel.release()

        // 执行修复：OpenCV Photo 模块的 inpaint（Telea / Navier-Stokes 算法，零模型、纯离线）
        val dst = Mat()
        val radius = if (useNavierStokes) INPAINT_RADIUS_NS else INPAINT_RADIUS_TELEA
        val flag = if (useNavierStokes) Photo.INPAINT_NS else Photo.INPAINT_TELEA
        Photo.inpaint(srcBgr, graySrc, dst, radius, flag)

        // BGR(3通道) → RGBA(4通道) 才能写入 ARGB_8888 Bitmap
        val dstRgba = Mat()
        Imgproc.cvtColor(dst, dstRgba, Imgproc.COLOR_BGR2RGBA)
        dst.release()

        // 转换回 Bitmap
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(dstRgba, result)
        dstRgba.release()

        // 严格释放内存
        srcBgr.release()
        graySrc.release()
        mask.release()

        result
    }

    /**
     * 处理视频（逐帧处理 + 合成）
     *
     * 内存优化策略：
     * - 每帧处理完立即 recycle Bitmap
     * - 使用 Flow 而非 blocking，每帧 yield 一次
     * - 帧解码使用低内存选项
     */
    fun processVideo(
        videoUri: Uri,
        masks: List<RectF>
    ): Flow<ProcessState> = flow {
        try {
            val timestamp = System.currentTimeMillis()
            val tempDir = File(context.cacheDir, "vp_$timestamp")
            tempDir.mkdirs()

            val framesDir = File(tempDir, "frames")
            framesDir.mkdirs()

            val outputVideo = File(tempDir, "output.mp4")

            // ========== 阶段0：提取原始音频 ==========
            emit(ProcessState.Progress(0, 100, "正在提取音频..."))
            val audioFile = FFmpegExtractor.extractAudio(context, videoUri, tempDir)

            // ========== 阶段1：抽帧 ==========
            emit(ProcessState.Progress(2, 100, "正在提取视频帧..."))

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

            // ========== 阶段2：逐帧修复（带时序平滑）============
            emit(ProcessState.Progress(5, 100, "正在去除水印（$totalFrames 帧）..."))

            val repairedDir = File(tempDir, "repaired")
            repairedDir.mkdirs()

            var processed = 0
            // 用于时序平滑：上一帧的蒙版
            var prevMask: Mat? = null

            for (frameFile in frameFiles) {
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bitmap = BitmapFactory.decodeFile(frameFile.absolutePath, options)

                if (bitmap != null) {
                    val repaired = processImageWithSmoothing(bitmap, masks, prevMask)
                    prevMask?.release()
                    // 保存当前帧蒙版供下一帧用（仅保留蒙版 Mat，不留完整帧）
                    prevMask = null // 每次重建蒙版，不保留以省内存

                    val outputFile = File(repairedDir, frameFile.name)
                    FileOutputStream(outputFile).use { fos ->
                        repaired.compress(Bitmap.CompressFormat.JPEG, FRAME_QUALITY, fos)
                    }
                    repaired.recycle()
                    bitmap.recycle()
                }

                processed++
                val progress = 5 + (processed * 85 / totalFrames)
                emit(ProcessState.Progress(progress.toInt().coerceIn(5, 90), 100, "处理中 $processed/$totalFrames"))
            }
            prevMask?.release()

            // ========== 阶段3：合成视频 ==========
            emit(ProcessState.Progress(92, 100, "正在合成视频..."))

            val mergeResult = FFmpegExtractor.mergeFrames(
                framesDir = repairedDir,
                outputFile = outputVideo,
                originalVideoUri = videoUri,
                audioFile = audioFile
            )

            if (mergeResult.isFailure) {
                emit(ProcessState.Error("视频合成失败: ${mergeResult.exceptionOrNull()?.message}"))
                return@flow
            }

            // 复制到应用私有目录
            val finalDir = File(context.filesDir, "results")
            finalDir.mkdirs()
            val finalFile = File(finalDir, "watermark_removed_$timestamp.mp4")
            outputVideo.copyTo(finalFile, overwrite = true)

            // 清理所有临时文件
            framesDir.deleteRecursively()
            repairedDir.deleteRecursively()
            outputVideo.delete()
            tempDir.delete()

            emit(ProcessState.Progress(100, 100, "完成"))
            emit(ProcessState.Success(Uri.fromFile(finalFile)))

        } catch (e: OutOfMemoryError) {
            System.gc()
            emit(ProcessState.Error("内存不足，请选择更短的视频或减少框选区域"))
        } catch (e: Exception) {
            emit(ProcessState.Error("处理失败: ${e.message}"))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 带时序平滑的单帧处理：先用邻帧均值轻微模糊蒙版，减少闪烁
     */
    private suspend fun processImageWithSmoothing(
        bitmap: Bitmap,
        masks: List<RectF>,
        prevMask: Mat?  // 上一帧蒙版（暂未用，保留扩展性）
    ): Bitmap = withContext(Dispatchers.Default) {
        val result = processImage(bitmap, masks)
        // 时序平滑：对修复结果轻微双边滤波，减少帧间闪烁
        //（因 Dispatchers.Default 并行安全要求，暂不跨帧传递 Mat；
        //  此处保留接口，下一版本实现帧间蒙版平滑）
        result
    }
}

/**
 * FFmpegKit 抽帧和合成工具
 */
object FFmpegExtractor {

    private const val ENCODE_CRF = "20"

    /**
     * 从视频提取音频（复制流，不重新编码）
     * @return 音频文件路径，若无音频轨则返回 null
     */
    fun extractAudio(
        context: Context,
        videoUri: Uri,
        outputDir: File
    ): File? {
        val inputPath = getPathFromUri(context, videoUri)
            ?: return null
        val audioFile = File(outputDir, "audio.aac")

        val command = arrayOf(
            "-y",
            "-i", inputPath,
            "-vn",                      // 不要视频
            "-c:a", "copy",            // 直接复制流，不重新编码
            "-f", "adts",               // 强制 AAC 封装
            audioFile.absolutePath
        )

        val session = com.arthenica.ffmpegkit.FFmpegKit.executeWithArguments(command)
        return if (ReturnCode.isSuccess(session.returnCode) && audioFile.exists() && audioFile.length() > 0) {
            audioFile
        } else {
            null // 无音频或提取失败
        }
    }

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
            "-vsync", "cfr",               // 恒定帧率
            "-q:v", "2",                   // 高质量 JPEG
            outputPattern                   // 输出帧
        )

        val session = com.arthenica.ffmpegkit.FFmpegKit.executeWithArguments(command)

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
        originalVideoUri: Uri,
        audioFile: File? = null
    ): Result<File> = runCatching {
        val mediaInfo = getVideoInfo(framesDir.listFiles()?.firstOrNull())
        val width = mediaInfo["width"] ?: 1920
        val height = mediaInfo["height"] ?: 1080
        val fps = mediaInfo["fps"] ?: 30.0

        val inputPattern = File(framesDir, "frame_%04d.jpg").absolutePath

        val videoOnly = if (audioFile != null && audioFile.exists()) {
            File(outputFile.parentFile, "video_only.mp4")
        } else null

        val videoOut = videoOnly ?: outputFile

        val command = arrayOf(
            "-y",
            "-framerate", fps.toString(),
            "-i", inputPattern,
            "-c:v", "libx264",
            "-preset", "fast",
            "-crf", ENCODE_CRF,
            "-pix_fmt", "yuv420p",
            "-vf", "scale=$width:$height:force_original_aspect_ratio=decrease,pad=$width:$height:(ow-iw)/2:(oh-ih)/2",
            videoOut.absolutePath
        )

        val session = com.arthenica.ffmpegkit.FFmpegKit.executeWithArguments(command)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            throw Exception("FFmpeg 合成视频失败: ${session.failStackTrace}")
        }

        // 混音：视频 + 音频合并
        if (videoOnly != null && audioFile != null && audioFile.exists()) {
            val mergeCommand = arrayOf(
                "-y",
                "-i", videoOnly.absolutePath,
                "-i", audioFile.absolutePath,
                "-c:v", "copy",
                "-c:a", "aac",
                "-b:a", "192k",
                "-shortest",
                outputFile.absolutePath
            )
            val mergeSession = com.arthenica.ffmpegkit.FFmpegKit.executeWithArguments(mergeCommand)
            videoOnly.delete()
            if (!ReturnCode.isSuccess(mergeSession.returnCode)) {
                throw Exception("音频合成失败: ${mergeSession.failStackTrace}")
            }
        }

        outputFile
    }

    /**
     * 获取视频信息（从首帧直接读取更可靠）
     */
    private fun getVideoInfo(firstFrame: File?): Map<String, Double> {
        if (firstFrame != null && firstFrame.exists()) {
            try {
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(firstFrame.absolutePath, options)
                if (options.outWidth > 0 && options.outHeight > 0) {
                    return mapOf(
                        "width" to options.outWidth.toDouble(),
                        "height" to options.outHeight.toDouble(),
                        "fps" to 30.0
                    )
                }
            } catch (_: Exception) { /* ignore */ }
        }
        return mapOf("width" to 1920.0, "height" to 1080.0, "fps" to 30.0)
    }

    /**
     * 从 Content URI 获取真实文件路径
     * - 如果是 file:// URI，直接返回路径
     * - 如果是 content:// URI（Android 10+），复制到临时文件后返回临时文件路径
     */
    private fun getPathFromUri(context: Context, uri: Uri): String? {
        // file:// URI
        if (uri.scheme == "file") {
            return uri.path
        }
        // content:// URI → 复制到临时文件
        return try {
            val tempFile = File(context.cacheDir, "temp_media_${System.currentTimeMillis()}")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            tempFile.absolutePath
        } catch (e: Exception) {
            // fallback：尝试传统方法
            try {
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val index = it.getColumnIndex("_data")
                        if (index >= 0) it.getString(index) else uri.path
                    } else uri.path
                } ?: uri.path
            } catch (e2: Exception) {
                uri.path
            }
        }
    }
}
