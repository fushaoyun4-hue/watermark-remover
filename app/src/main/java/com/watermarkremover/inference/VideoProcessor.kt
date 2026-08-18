package com.watermarkremover.inference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
 * 视频处理器：FFmpegKit 抽帧 → AI 图像修复 → FFmpeg 合成
 *
 * 技术方案（优先级顺序）：
 * 1. ONNX 轻量 AI 模型（MAT/LaMa）：模型随 APK 打包在 assets/，推理完全离线，
 *    消除闪烁、语义级水印去除，效果最接近"开拍app"
 * 2. OpenCV Telea inpaint：无模型 fallback，实时性好，但存在闪烁问题
 *
 * 蒙版 → 图像修复 → 合成的完整流程：
 * FFmpegKit 抽帧 → processImage（AI/OpenCV）→ 修复帧 → FFmpegKit 合成
 */
@Singleton
class VideoProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val onnxInpainter: OnnxInpainter   // AI 模型推理器（无模型时 hasModel=false）
) {
    companion object {
        private const val TAG = "VideoProcessor"

        // OpenCV Telea 修复半径
        private const val INPAINT_RADIUS_TELEA = 5.0
        private const val INPAINT_RADIUS_NS = 5.0

        // JPEG 压缩质量：帧质量，影响输出清晰度和文件大小
        private const val FRAME_QUALITY = 90

        // 蒙版扩大像素数（边缘羽化，让 AI/OpenCV 有更多上下文，边界更自然）
        private const val MASK_EXPAND_PX = 6
    }

    sealed class ProcessState {
        data class Progress(val current: Int, val total: Int, val phase: String) : ProcessState()
        data class Success(val outputUri: Uri) : ProcessState()
        data class Error(val message: String) : ProcessState()
    }

    /**
     * 水印蒙版区域（矩形或手绘多边形）
     * - rect: 包围盒（归一化坐标 0~1），用于时序平滑 blendMasks
     * - freehandPoints: 手绘轨迹点（归一化视频坐标 [0,1]），null=矩形模式
     */
    data class MaskArea(
        val rect: RectF,
        val freehandPoints: List<Pair<Float, Float>>? = null
    ) {
        val isFreehand: Boolean get() = !freehandPoints.isNullOrEmpty()
    }

    /**
     * 处理单帧图片
     *
     * @param bitmap 原图（ARGB_8888）
     * @param masks  水印区域
     * @return 修复后图片
     */
    suspend fun processImage(
        bitmap: Bitmap,
        masks: List<MaskArea>
    ): Bitmap = withContext(Dispatchers.Default) {
        if (masks.isEmpty()) return@withContext bitmap

        val width  = bitmap.width
        val height = bitmap.height

        // ─── 优先：尝试 ONNX AI 模型推理 ───
        if (onnxInpainter.hasModel) {
            try {
                val maskBitmap = buildMaskBitmap(width, height, masks)
                val result = onnxInpainter.inpaint(bitmap, maskBitmap)
                maskBitmap.recycle()
                if (result !== bitmap) {
                    return@withContext result
                }
                // ONNX 返回原图表示推理失败，降级到 OpenCV
            } catch (_: Exception) {
                // ONNX 推理出错，降级到 OpenCV
            }
        }

        // ─── Fallback：OpenCV Telea inpaint ───
        fallbackOpenCvInpaint(bitmap, masks)
    }

    /**
     * 构建蒙版 Bitmap（白色=修复区域，黑色=保留区域）
     * 矩形：用 Paint.drawRect 扩大绘制
     * 手绘多边形：用 Path + fill 填充 + 扩大（通过在蒙版上 dilate 实现）
     * 用于 ONNX 模型输入
     */
    private fun buildMaskBitmap(width: Int, height: Int, masks: List<MaskArea>): Bitmap {
        val maskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(maskBitmap)
        canvas.drawColor(Color.BLACK)

        val paint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            isAntiAlias = false
        }

        for (area in masks) {
            val rect = area.rect
            if (area.isFreehand && area.freehandPoints != null) {
                // 手绘多边形 → 用 Path 绘制封闭多边形
                val pts = area.freehandPoints
                if (pts.size >= 3) {
                    val path = Path()
                    path.moveTo(pts[0].first * width, pts[0].second * height)
                    for (i in 1 until pts.size) {
                        path.lineTo(pts[i].first * width, pts[i].second * height)
                    }
                    path.close()
                    canvas.drawPath(path, paint)
                }
            } else {
                // 矩形：扩大绘制
                val left   = (rect.left   * width - MASK_EXPAND_PX).coerceIn(0f, width.toFloat())
                val top    = (rect.top    * height - MASK_EXPAND_PX).coerceIn(0f, height.toFloat())
                val right  = (rect.right  * width + MASK_EXPAND_PX).coerceIn(0f, width.toFloat())
                val bottom = (rect.bottom * height + MASK_EXPAND_PX).coerceIn(0f, height.toFloat())
                canvas.drawRect(left, top, right, bottom, paint)
            }
        }
        return maskBitmap
    }

    /**
     * OpenCV Telea inpaint（无 AI 模型时的降级路径）
     */
    private fun fallbackOpenCvInpaint(bitmap: Bitmap, masks: List<MaskArea>): Bitmap {
        val width  = bitmap.width
        val height = bitmap.height

        // Bitmap → OpenCV Mat（ARGB → RGBA → BGR）
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)

        val srcBgr = Mat()
        Imgproc.cvtColor(src, srcBgr, Imgproc.COLOR_RGBA2BGR)
        src.release()

        // 构建蒙版 Mat
        val mask = Mat(height, width, CvType.CV_8UC1, Scalar(0.0))
        for (area in masks) {
            val rect = area.rect
            if (area.isFreehand && area.freehandPoints != null) {
                // 手绘多边形：先在蒙版上画多边形，再 dilate 扩大
                val pts = area.freehandPoints
                if (pts.size >= 3) {
                    val intPts = pts.map {
                        org.opencv.core.Point(
                            (it.first  * width).toDouble().coerceIn(0.0, width.toDouble()),
                            (it.second * height).toDouble().coerceIn(0.0, height.toDouble())
                        )
                    }
                    val hull = org.opencv.core.MatOfPoint()
                    hull.fromList(intPts)
                    Imgproc.fillPoly(mask, listOf(hull), Scalar(255.0))
                    hull.release()
                }
            } else {
                // 矩形蒙版（扩大 MASK_EXPAND_PX）
                val left   = (rect.left   * width - MASK_EXPAND_PX).toInt().coerceIn(0, width - 1)
                val top    = (rect.top    * height - MASK_EXPAND_PX).toInt().coerceIn(0, height - 1)
                val right  = (rect.right  * width + MASK_EXPAND_PX).toInt().coerceIn(left + 1, width)
                val bottom = (rect.bottom * height + MASK_EXPAND_PX).toInt().coerceIn(top + 1, height)
                Imgproc.rectangle(
                    mask,
                    org.opencv.core.Point(left.toDouble(), top.toDouble()),
                    org.opencv.core.Point(right.toDouble(), bottom.toDouble()),
                    Scalar(255.0), -1
                )
            }
        }

        // 适度膨胀（5x5 核），让边缘羽化过渡更自然
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, org.opencv.core.Size(5.0, 5.0))
        Imgproc.dilate(mask, mask, kernel)
        kernel.release()

        // Telea inpaint
        val dst = Mat()
        Photo.inpaint(srcBgr, mask, dst, INPAINT_RADIUS_TELEA, Photo.INPAINT_TELEA)

        // BGR → RGBA → Bitmap
        val dstRgba = Mat()
        Imgproc.cvtColor(dst, dstRgba, Imgproc.COLOR_BGR2RGBA)
        dst.release()

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(dstRgba, result)
        dstRgba.release()

        srcBgr.release()
        mask.release()

        return result
    }

    /**
     * 处理视频（逐帧处理 + 合成）
     *
     * @param frameMasks 按帧索引存储的蒙版 Map。若某帧无蒙版，沿用首帧蒙版。
     */
    fun processVideo(
        videoUri: Uri,
        frameMasks: Map<Int, List<MaskArea>>
    ): Flow<ProcessState> = flow {
        try {
            val timestamp = System.currentTimeMillis()
            val tempDir   = File(context.cacheDir, "vp_$timestamp").apply { mkdirs() }
            val framesDir = File(tempDir, "frames").apply { mkdirs() }
            val repairedDir = File(tempDir, "repaired").apply { mkdirs() }
            val outputVideo = File(tempDir, "output.mp4")

            // 阶段0：提取音频
            emit(ProcessState.Progress(0, 100, "正在提取音频..."))
            val audioFile = FFmpegExtractor.extractAudio(context, videoUri, tempDir)

            // 阶段1：抽帧
            emit(ProcessState.Progress(2, 100, "正在提取视频帧..."))
            val extractResult = FFmpegExtractor.extractFrames(context, videoUri, framesDir)
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

            // 获取全局默认蒙版（首帧蒙版；若首帧无蒙版取第一份蒙版）
            val defaultMasks: List<MaskArea> = frameMasks[0]
                ?: frameMasks.values.firstOrNull()
                ?: emptyList()

            // 阶段2：逐帧 AI 修复（带时序平滑）
            val modelDesc = if (onnxInpainter.hasModel) "（AI 模型）" else "（OpenCV）"
            emit(ProcessState.Progress(5, 100, "正在去除水印$modelDesc（$totalFrames 帧）..."))

            var prevMasks: List<MaskArea>? = null  // 上一帧融合后的蒙版

            for ((idx, frameFile) in frameFiles.withIndex()) {
                val bitmap = BitmapFactory.decodeFile(frameFile.absolutePath,
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })

                if (bitmap != null) {
                    // 获取当前帧蒙版（无则用默认蒙版）
                    val rawMasks = frameMasks[idx] ?: defaultMasks

                    // 时序平滑处理（prevMasks 会在内部自动融合并传递）
                    val (repaired, blendedMasks) = processFrameWithSmoothing(bitmap, rawMasks, prevMasks)
                    prevMasks = blendedMasks  // 保留给下一帧

                    val outputFile = File(repairedDir, frameFile.name)
                    FileOutputStream(outputFile).use { fos ->
                        repaired.compress(Bitmap.CompressFormat.JPEG, FRAME_QUALITY, fos)
                    }
                    if (repaired !== bitmap) repaired.recycle()
                    bitmap.recycle()
                }

                val progress = 5 + ((idx + 1) * 85 / totalFrames)
                emit(ProcessState.Progress(progress.coerceIn(5, 90), 100, "处理中 ${idx + 1}/$totalFrames"))
            }

            // 阶段3：合成视频
            emit(ProcessState.Progress(92, 100, "正在合成视频..."))
            val mergeResult = FFmpegExtractor.mergeFrames(
                framesDir   = repairedDir,
                outputFile  = outputVideo,
                originalVideoUri = videoUri,
                audioFile   = audioFile
            )
            if (mergeResult.isFailure) {
                emit(ProcessState.Error("视频合成失败: ${mergeResult.exceptionOrNull()?.message}"))
                return@flow
            }

            // 复制到应用私有目录
            val finalDir  = File(context.filesDir, "results").apply { mkdirs() }
            val finalFile = File(finalDir, "watermark_removed_$timestamp.mp4")
            outputVideo.copyTo(finalFile, overwrite = true)

            // 清理临时文件
            tempDir.deleteRecursively()

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
     * 带时序平滑的单帧处理
     * prevMasks: 上一帧的蒙版区域（MaskArea 列表）
     * alpha: 平滑强度 0.0~1.0，值越大蒙版越稳定（闪烁越小但响应越慢）
     * 返回：融合了历史信息的当前帧蒙版
     */
    private fun blendMasks(
        currentMasks: List<MaskArea>,
        prevMasks: List<MaskArea>?,
        alpha: Float = 0.7f
    ): List<MaskArea> {
        if (prevMasks == null || prevMasks.isEmpty()) return currentMasks
        if (currentMasks.size != prevMasks.size) return currentMasks

        // 时序平滑：对包围盒进行 alpha 混合，保留 freehandPoints（形状不变）
        // 效果：字幕移动时蒙版缓慢跟随 → 消除动态水印的拉丝闪烁
        return currentMasks.zip(prevMasks) { cur, prev ->
            val curRect = cur.rect; val prevRect = prev.rect
            MaskArea(
                rect = RectF(
                    (curRect.left   * (1 - alpha) + prevRect.left   * alpha),
                    (curRect.top    * (1 - alpha) + prevRect.top    * alpha),
                    (curRect.right  * (1 - alpha) + prevRect.right  * alpha),
                    (curRect.bottom * (1 - alpha) + prevRect.bottom * alpha)
                ),
                freehandPoints = cur.freehandPoints  // 保留手绘形状
            )
        }
    }

    /**
     * 逐帧处理（带时序平滑）
     * rawMasks: 当前帧的蒙版（可能来自 frameMasks Map 或 defaultMasks）
     * prevMasks: 上一帧融合后的蒙版，用于下一帧平滑
     * 返回：处理后图片 + 融合后的蒙版（供下一帧使用）
     */
    private suspend fun processFrameWithSmoothing(
        bitmap: Bitmap,
        rawMasks: List<MaskArea>,
        prevMasks: List<MaskArea>?
    ): Pair<Bitmap, List<MaskArea>> = withContext(Dispatchers.Default) {
        // 1. 时序融合蒙版
        val blendedMasks = blendMasks(rawMasks, prevMasks, alpha = 0.65f)

        // 2. 处理当前帧
        val repaired = processImage(bitmap, blendedMasks)

        // 3. 返回结果和当前蒙版（供下一帧参考）
        repaired to blendedMasks
    }
}

/**
 * FFmpegKit 抽帧和合成工具
 */
object FFmpegExtractor {

    private const val ENCODE_CRF = "20"

    fun extractAudio(
        context: Context,
        videoUri: Uri,
        outputDir: File
    ): File? {
        val inputPath = getPathFromUri(context, videoUri) ?: return null
        val audioFile = File(outputDir, "audio.aac")

        val command = arrayOf(
            "-y", "-i", inputPath,
            "-vn", "-c:a", "copy", "-f", "adts",
            audioFile.absolutePath
        )

        val session = com.arthenica.ffmpegkit.FFmpegKit.executeWithArguments(command)
        return if (ReturnCode.isSuccess(session.returnCode)
            && audioFile.exists() && audioFile.length() > 0
        ) audioFile else null
    }

    fun extractFrames(
        context: Context,
        videoUri: Uri,
        outputDir: File
    ): Result<List<File>> = runCatching {
        val inputPath = getPathFromUri(context, videoUri)
            ?: throw IllegalArgumentException("无法获取视频路径")

        val outputPattern = File(outputDir, "frame_%04d.jpg").absolutePath
        val command = arrayOf(
            "-y", "-i", inputPath,
            "-vsync", "cfr", "-q:v", "2",
            outputPattern
        )

        val session = com.arthenica.ffmpegkit.FFmpegKit.executeWithArguments(command)
        if (ReturnCode.isSuccess(session.returnCode)) {
            outputDir.listFiles()?.toList() ?: emptyList()
        } else {
            throw Exception("FFmpeg 抽帧失败: ${session.failStackTrace}")
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun mergeFrames(
        framesDir: File,
        outputFile: File,
        originalVideoUri: Uri,
        audioFile: File? = null
    ): Result<File> = runCatching {
        val mediaInfo = getVideoInfo(framesDir.listFiles()?.firstOrNull())
        val width  = mediaInfo["width"] ?: 1920.0
        val height = mediaInfo["height"] ?: 1080.0
        val fps    = mediaInfo["fps"] ?: 30.0

        val inputPattern = File(framesDir, "frame_%04d.jpg").absolutePath

        val videoOnly = if (audioFile != null && audioFile.exists()) {
            File(outputFile.parentFile, "video_only.mp4")
        } else null
        val videoOut = videoOnly ?: outputFile

        val command = arrayOf(
            "-y", "-framerate", fps.toString(), "-i", inputPattern,
            "-c:v", "libx264", "-preset", "fast", "-crf", ENCODE_CRF,
            "-pix_fmt", "yuv420p",
            "-vf", "scale=${width.toInt()}:${height.toInt()}:force_original_aspect_ratio=decrease,pad=${width.toInt()}:${height.toInt()}:(ow-iw)/2:(oh-ih)/2",
            videoOut.absolutePath
        )

        val session = com.arthenica.ffmpegkit.FFmpegKit.executeWithArguments(command)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            throw Exception("FFmpeg 合成失败: ${session.failStackTrace}")
        }

        // 混音
        if (videoOnly != null && audioFile != null && audioFile.exists()) {
            val mergeCommand = arrayOf(
                "-y", "-i", videoOnly.absolutePath, "-i", audioFile.absolutePath,
                "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-shortest",
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

    private fun getVideoInfo(firstFrame: File?): Map<String, Double> {
        if (firstFrame != null && firstFrame.exists()) {
            try {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(firstFrame.absolutePath, options)
                if (options.outWidth > 0 && options.outHeight > 0) {
                    return mapOf("width" to options.outWidth.toDouble(),
                                 "height" to options.outHeight.toDouble(),
                                 "fps" to 30.0)
                }
            } catch (_: Exception) { /* ignore */ }
        }
        return mapOf("width" to 1920.0, "height" to 1080.0, "fps" to 30.0)
    }

    private fun getPathFromUri(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        return try {
            val tempFile = File(context.cacheDir, "temp_media_${System.currentTimeMillis()}")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            tempFile.absolutePath
        } catch (e: Exception) {
            try {
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val index = it.getColumnIndex("_data")
                        if (index >= 0) it.getString(index) else uri.path
                    } else uri.path
                } ?: uri.path
            } catch (_: Exception) {
                uri.path
            }
        }
    }
}
