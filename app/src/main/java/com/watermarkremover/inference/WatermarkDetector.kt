package com.watermarkremover.inference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import ai.onnxruntime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * 水印自动检测器
 * 使用轻量级目标检测模型自动识别水印、字幕位置
 */
class WatermarkDetector(private val context: Context) {
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var hasModel = false
    
    companion object {
        const val MODEL_PATH = "yolov5n_watermark.onnx"
        const val INPUT_SIZE = 320
        const val CONFIDENCE_THRESHOLD = 0.3f
        const val IOU_THRESHOLD = 0.4f
        
        // 检测类别
        const val CLASS_WATERMARK = 0
        const val CLASS_SUBTITLE = 1
        const val CLASS_LOGO = 2
    }
    
    /**
     * 初始化检测器
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            
            // 检查模型文件是否存在
            val modelExists = try {
                context.assets.open(MODEL_PATH).use { true }
            } catch (e: Exception) {
                false
            }
            
            if (!modelExists) {
                hasModel = false
                return@withContext false
            }
            
            // 加载模型
            val modelStream: InputStream = context.assets.open(MODEL_PATH)
            val modelBytes = modelStream.readBytes()
            modelStream.close()
            
            val sessionOptions = OrtSession.SessionOptions()
            sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            sessionOptions.addCPU()
            
            ortSession = ortEnv!!.createSession(modelBytes, sessionOptions)
            hasModel = true
            true
        } catch (e: Exception) {
            e.printStackTrace()
            hasModel = false
            false
        }
    }
    
    /**
     * 检测单帧中的水印/字幕
     */
    suspend fun detect(frame: Mat): List<Detection> = withContext(Dispatchers.IO) {
        if (!hasModel || ortSession == null) {
            return@withContext emptyList()
        }
        
        try {
            // 预处理：调整大小并归一化
            val inputMat = preprocessFrame(frame)
            
            // 转换为 ONNX 输入格式
            val inputTensor = prepareInputTensor(inputMat)
            
            // 推理
            val outputs = ortSession!!.run(mapOf(ortSession!!.inputNames[0] to inputTensor))
            
            // 后处理：解析检测结果
            val detections = postprocessOutputs(outputs, frame.size())
            
            inputTensor.close()
            outputs.close()
            
            detections
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
    
    /**
     * 预处理帧：调整大小、归一化、转换颜色空间
     */
    private fun preprocessFrame(frame: Mat): Mat {
        val resized = Mat()
        Imgproc.resize(frame, resized, Size(INPUT_SIZE.toDouble(), INPUT_SIZE.toDouble()))
        
        // 转换为 RGB（如果需要）
        val rgb = Mat()
        when (frame.channels()) {
            1 -> Imgproc.cvtColor(resized, rgb, Imgproc.COLOR_GRAY2RGB)
            3 -> Imgproc.cvtColor(resized, rgb, Imgproc.COLOR_BGR2RGB)
            4 -> Imgproc.cvtColor(resized, rgb, Imgproc.COLOR_RGBA2RGB)
            else -> resized.copyTo(rgb)
        }
        
        resized.release()
        return rgb
    }
    
    /**
     * 准备 ONNX 输入张量
     */
    private fun prepareInputTensor(inputMat: Mat): OnnxTensor {
        val inputArray = FloatArray(INPUT_SIZE * INPUT_SIZE * 3)
        val data = inputMat.get(0, 0)
        
        var index = 0
        for (i in 0 until INPUT_SIZE) {
            for (j in 0 until INPUT_SIZE) {
                val pixel = inputMat.get(i, j)
                inputArray[index++] = (pixel[0] / 255.0).toFloat()  // R
                inputArray[index++] = (pixel[1] / 255.0).toFloat()  // G
                inputArray[index++] = (pixel[2] / 255.0).toFloat()  // B
            }
        }
        
        inputMat.release()
        
        return OnnxTensor.createTensor(
            ortEnv!!,
            inputArray,
            longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        )
    }
    
    /**
     * 后处理：解析 YOLO 输出
     */
    private fun postprocessOutputs(outputs: Map<String, OnnxTensor>, originalSize: Size): List<Detection> {
        val detections = mutableListOf<Detection>()
        
        try {
            val outputTensor = outputs[ortSession!!.outputNames[0]]
            val outputArray = outputTensor?.value as Array<Array<FloatArray>>?
            
            outputArray?.get(0)?.forEach { detection ->
                if (detection.size >= 6) {
                    val confidence = detection[4]
                    if (confidence >= CONFIDENCE_THRESHOLD) {
                        val classId = detection[5].toInt()
                        
                        // 转换坐标为原始图像尺寸
                        val x = (detection[0] * originalSize.width / INPUT_SIZE).toInt()
                        val y = (detection[1] * originalSize.height / INPUT_SIZE).toInt()
                        val width = (detection[2] * originalSize.width / INPUT_SIZE).toInt()
                        val height = (detection[3] * originalSize.height / INPUT_SIZE).toInt()
                        
                        val rect = RectF(
                            (x - width / 2).toFloat(),
                            (y - height / 2).toFloat(),
                            (x + width / 2).toFloat(),
                            (y + height / 2).toFloat()
                        )
                        
                        detections.add(Detection(rect, confidence, classId))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // 应用非极大值抑制
        return applyNMS(detections)
    }
    
    /**
     * 应用非极大值抑制
     */
    private fun applyNMS(detections: List<Detection>): List<Detection> {
        if (detections.isEmpty()) return emptyList()
        
        val sorted = detections.sortedByDescending { it.confidence }
        val selected = mutableListOf<Detection>()
        
        while (sorted.isNotEmpty()) {
            val current = sorted.first()
            selected.add(current)
            
            val remaining = sorted.drop(1).filter { detection ->
                val iou = calculateIoU(current.bbox, detection.bbox)
                iou < IOU_THRESHOLD
            }
            
            if (remaining.isEmpty()) break
        }
        
        return selected
    }
    
    /**
     * 计算 IoU（交并比）
     */
    private fun calculateIoU(rect1: RectF, rect2: RectF): Float {
        val intersection = RectF()
        intersection.set(
            maxOf(rect1.left, rect2.left),
            maxOf(rect1.top, rect2.top),
            minOf(rect1.right, rect2.right),
            minOf(rect1.bottom, rect2.bottom)
        )
        
        if (intersection.width() <= 0 || intersection.height() <= 0) {
            return 0f
        }
        
        val intersectionArea = intersection.width() * intersection.height()
        val unionArea = rect1.width() * rect1.height() + rect2.width() * rect2.height() - intersectionArea
        
        return intersectionArea / unionArea
    }
    
    /**
     * 生成修复蒙版
     */
    fun generateMaskFromDetections(detections: List<Detection>, frameSize: Size): Mat {
        val mask = Mat.zeros(frameSize, CvType.CV_8UC1)
        
        detections.forEach { detection ->
            val rect = detection.bbox
            val pt1 = Point(rect.left.toDouble(), rect.top.toDouble())
            val pt2 = Point(rect.right.toDouble(), rect.bottom.toDouble())
            
            // 在蒙版上绘制矩形
            Imgproc.rectangle(mask, pt1, pt2, Scalar(255.0), -1)
        }
        
        return mask
    }
    
    /**
     * 是否已加载模型
     */
    fun hasModel(): Boolean = hasModel
    
    /**
     * 释放资源
     */
    fun release() {
        ortSession?.close()
        ortEnv?.close()
    }
}

/**
 * 检测结果数据类
 */
data class Detection(
    val bbox: RectF,           // 边界框
    val confidence: Float,     // 置信度
    val classId: Int           // 类别ID
)