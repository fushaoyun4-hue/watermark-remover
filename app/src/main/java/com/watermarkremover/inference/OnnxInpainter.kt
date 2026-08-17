package com.watermarkremover.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ONNX 轻量 AI 图像修复推理器
 *
 * 模型要求（输入/输出格式）：
 * - 输入节点：图像 [batch=1, channel=3, height, width] float32, 范围 [-1, 1]
 * - 输入节点：蒙版 [batch=1, channel=1, height, width] float32, 范围 [0, 1]
 * - 输出节点：修复图 [batch=1, channel=3, height, width] float32, 范围 [-1, 1]
 *
 * 兼容模型：MAT（MIGAN）、LaMa（from IOPaint）
 * 模型文件放入 assets/mat_inpainting.onnx 或 assets/lama_inpainting.onnx
 * 无模型时自动降级到 OpenCV Telea inpaint
 */
@Singleton
class OnnxInpainter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "OnnxInpainter"

        private const val MODEL_FILE_PRIMARY  = "mat_inpainting.onnx"
        private const val MODEL_FILE_FALLBACK = "lama_inpainting.onnx"
    }

    // OrtSession 可全局复用（线程安全）
    private var session:        ai.onnxruntime.OrtSession?  = null
    private var imageInputName: String = ""
    private var maskInputName:  String = ""
    private var loadedModelName: String? = null

    val hasModel: Boolean get() = session != null

    private fun modelExists(fileName: String): Boolean {
        return try {
            context.assets.open(fileName).close(); true
        } catch (_: Exception) { false }
    }

    /**
     * 加载 ONNX 模型（线程安全，由 Dispatchers.IO 保护）
     */
    private suspend fun ensureSession(): Boolean = withContext(Dispatchers.IO) {
        session?.let { return@withContext true }

        val modelFileName = when {
            modelExists(MODEL_FILE_PRIMARY)  -> MODEL_FILE_PRIMARY
            modelExists(MODEL_FILE_FALLBACK) -> MODEL_FILE_FALLBACK
            else -> return@withContext false
        }

        try {
            // 读取 assets 模型到临时文件（ONNX Runtime 需要文件路径加载）
            val modelBytes = context.assets.open(modelFileName).use { it.readBytes() }
            val tempFile = File(context.cacheDir, modelFileName)
            FileOutputStream(tempFile).use { it.write(modelBytes) }

            // 获取 ONNX Runtime 单例环境（OrtEnvironment 是全局单例，线程安全）
            val ortEnv = ai.onnxruntime.OrtEnvironment.getEnvironment()

            val sessionOptions = ai.onnxruntime.OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setInterOpNumThreads(2)
            }

            session = ortEnv.createSession(tempFile.absolutePath, sessionOptions)
            loadedModelName = modelFileName

            // 动态获取输入节点名（适配不同模型）
            val inputNames = session!!.inputNames
            imageInputName = inputNames.find {
                it.lowercase().contains("image") || it == "input" || it == "x"
            } ?: inputNames.getOrElse(0) { "input" }
            maskInputName  = inputNames.getOrElse(1) { "mask" }

            tempFile.delete()

            Log.i(TAG, "✅ ONNX 模型加载成功: $modelFileName")
            Log.i(TAG, "   输入节点: image=$imageInputName mask=$maskInputName")
            true

        } catch (e: Exception) {
            Log.e(TAG, "❌ ONNX 模型加载失败: ${e.message}")
            session?.close()
            session = null
            false
        }
    }

    /**
     * 用 AI 模型修复图像
     *
     * @param bitmap 原图（ARGB_8888）
     * @param mask 蒙版（黑色=保留，白色=修复区域），尺寸须与 bitmap 一致
     * @return 修复后图像；失败时返回原图
     */
    suspend fun inpaint(bitmap: Bitmap, mask: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        if (!ensureSession()) {
            Log.w(TAG, "无 ONNX 模型，降级到 OpenCV Telea")
            return@withContext bitmap
        }

        val width  = bitmap.width
        val height = bitmap.height

        val sess: ai.onnxruntime.OrtSession
        val ortEnv: ai.onnxruntime.OrtEnvironment
        try {
            sess   = session!!
            ortEnv = ai.onnxruntime.OrtEnvironment.getEnvironment()
        } catch (_: Exception) {
            return@withContext bitmap
        }

        try {
            // ─── 预处理：Bitmap → Float32 NCHW ───────────────────────────────
            val pixels     = IntArray(width * height)
            val maskPixels = IntArray(width * height)
            bitmap.getPixels(pixels,     0, width, 0, 0, width, height)
            mask.getPixels(maskPixels,   0, width, 0, 0, width, height)

            // 图像: [0,255] ARGB → [-1,1] float32 RGB, NCHW 布局
            val imageData = FloatArray(3 * width * height)
            for (i in pixels.indices) {
                val pixel = pixels[i]
                imageData[i]                      = ((pixel shr 16) and 0xFF) / 255f * 2f - 1f  // R
                imageData[width * height + i]     = ((pixel shr  8) and 0xFF) / 255f * 2f - 1f  // G
                imageData[2 * width * height + i] = (pixel and 0xFF) / 255f * 2f - 1f           // B
            }

            // 蒙版: [0,255] → [0,1] float32
            val maskData = FloatArray(width * height)
            for (i in maskPixels.indices) {
                val gray = ((maskPixels[i] shr 16) and 0xFF) * 0.299f +
                           ((maskPixels[i] shr  8) and 0xFF) * 0.587f +
                           (maskPixels[i] and 0xFF) * 0.114f
                maskData[i] = if (gray > 127f) 1f else 0f
            }

            // ─── 构建 ONNX Tensor ────────────────────────────────────────────
            val imageShape = longArrayOf(1, 3, height.toLong(), width.toLong())
            val maskShape  = longArrayOf(1, 1, height.toLong(), width.toLong())

            val imageTensor = ai.onnxruntime.OnnxTensor.createTensor(ortEnv, imageData, imageShape)
            val maskTensor  = ai.onnxruntime.OnnxTensor.createTensor(ortEnv, maskData,  maskShape)

            // ─── 推理 ────────────────────────────────────────────────────────
            val inputs = mapOf(imageInputName to imageTensor, maskInputName to maskTensor)
            val results = sess.run(inputs)
            val outputTensor = results[0]

            // ─── 后处理：Float32 NCHW → ARGB_8888 Bitmap ───────────────────
            // outputTensor.value: float[1][3][H][W]
            val outputValue = outputTensor.value
            val channel0: Array<FloatArray>; val channel1: Array<FloatArray>; val channel2: Array<FloatArray>

            when (outputValue) {
                is Array<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val arr0 = outputValue[0] as Array<*>
                    @Suppress("UNCHECKED_CAST")
                    channel0 = arr0[0] as Array<FloatArray>
                    @Suppress("UNCHECKED_CAST")
                    channel1 = arr0[1] as Array<FloatArray>
                    @Suppress("UNCHECKED_CAST")
                    channel2 = arr0[2] as Array<FloatArray>
                }
                else -> throw IllegalStateException("Unexpected output type: ${outputValue::class.java}")
            }

            val outPixels = IntArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val idx = y * width + x
                    val r = ((channel0[y][x].coerceIn(-1f, 1f) + 1f) / 2f * 255f).toInt().coerceIn(0, 255)
                    val g = ((channel1[y][x].coerceIn(-1f, 1f) + 1f) / 2f * 255f).toInt().coerceIn(0, 255)
                    val b = ((channel2[y][x].coerceIn(-1f, 1f) + 1f) / 2f * 255f).toInt().coerceIn(0, 255)
                    outPixels[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }

            // 释放资源
            imageTensor.close()
            maskTensor.close()
            outputTensor.close()
            results.close()

            Bitmap.createBitmap(outPixels, width, height, Bitmap.Config.ARGB_8888)

        } catch (e: Exception) {
            Log.e(TAG, "ONNX 推理异常: ${e.message}")
            bitmap
        }
    }

    /**
     * 释放 ONNX Runtime 资源
     */
    fun close() {
        try {
            session?.close()
            session = null
            loadedModelName = null
        } catch (_: Exception) { /* ignore */ }
    }
}
