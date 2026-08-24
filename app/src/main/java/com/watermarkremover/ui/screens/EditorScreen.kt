package com.watermarkremover.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.watermarkremover.inference.VideoProcessor
import com.watermarkremover.inference.VideoProcessor.MaskArea
import com.watermarkremover.ui.theme.WatermarkRemoverTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

/**
 * 编辑页：多选框 + 拖拽新建 + 点击删除已确认框 + 视频时间轴
 *
 * 修复记录：
 * - 问题1（全局蒙版）：蒙版从按帧存储改为全局单一列表，所有帧共享同一份蒙版，
 *   解决"蒙版只在当前帧生效"导致的处理跳帧问题。
 * - 问题2（进度显示）：ProcessingDialog 显示"正在处理 X/Y 帧..."真实进度，
 *   处理完成后显示成功提示，不允许关闭弹窗直到完成。
 * - 问题3（UI改进）：右上角添加关闭按钮，点击弹出确认对话框。
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    private val videoProcessor: VideoProcessor
) : ViewModel() {

    companion object {
        private const val MAX_FRAME_CACHE = 10
    }

    data class MaskRect(
        val id: Int,
        var left: Float,
        var top: Float,
        var right: Float,
        var bottom: Float,
        /** 手绘轨迹的归一化坐标点（可选，用于手绘蒙版） */
        var freehandPoints: List<Pair<Float, Float>>? = null,
        /** 累计平移量（归一化坐标），仅用于记录用户拖动历史 */
        var offsetX: Float = 0f,
        var offsetY: Float = 0f
    ) {
        val width  get() = kotlin.math.abs(right - left)
        val height get() = kotlin.math.abs(bottom - top)
        fun toRectF() = RectF(
            minOf(left, right), minOf(top, bottom),
            maxOf(left, right), maxOf(top, bottom)
        )

        /** 是否为手绘多边形蒙版 */
        val isFreehand: Boolean get() = (freehandPoints?.size ?: 0) >= 3

        /**
         * 整体平移蒙版（归一化 dx/dy），同时平移包围盒与手绘轨迹点。
         * 会自动夹紧到 [0,1] 画面范围内（保持形状不变形）。
         */
        fun moveBy(dx: Float, dy: Float) {
            val l = minOf(left, right); val r = maxOf(left, right)
            val t = minOf(top, bottom); val b = maxOf(top, bottom)
            // 夹紧：不允许整体拖出画面
            val ddx = dx.coerceIn(-l, 1f - r)
            val ddy = dy.coerceIn(-t, 1f - b)
            if (ddx == 0f && ddy == 0f) return
            left += ddx; right += ddx
            top += ddy; bottom += ddy
            freehandPoints = freehandPoints?.map { (px, py) -> Pair(px + ddx, py + ddy) }
            offsetX += ddx
            offsetY += ddy
        }

        /** 命中测试：手绘用 ray-casting，矩形用包围盒 */
        fun contains(nx: Float, ny: Float): Boolean {
            val pts = freehandPoints
            if (pts != null && pts.size >= 3) return pointInPolygon(nx, ny, pts)
            val l = minOf(left, right); val r = maxOf(left, right)
            val t = minOf(top, bottom); val b = maxOf(top, bottom)
            return nx in l..r && ny in t..b
        }

        companion object {
            /** Ray-casting：判断点是否在多边形内 */
            fun pointInPolygon(nx: Float, ny: Float, polygon: List<Pair<Float, Float>>): Boolean {
                if (polygon.size < 3) return false
                var inside = false
                var j = polygon.lastIndex
                for (i in polygon.indices) {
                    val (xi, yi) = polygon[i]
                    val (xj, yj) = polygon[j]
                    if (((yi > ny) != (yj > ny)) &&
                        (nx < (xj - xi) * (ny - yi) / ((yj - yi).let { if (it == 0f) 0.0001f else it }) + xi)
                    ) {
                        inside = !inside
                    }
                    j = i
                }
                return inside
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    //  视频帧浏览状态
    // ──────────────────────────────────────────────────────────

    /** 当前选中的帧索引（从 0 开始） */
    var currentFrameIndex by mutableIntStateOf(0)
        private set

    /** 视频总帧数（从 FFmpegKit 抽帧后可知） */
    var totalFrames by mutableIntStateOf(0)
        private set

    /** 已加载帧的 Bitmap 缓存（LRU，最多 MAX_FRAME_CACHE 帧） */
    private val _frameBitmaps = MutableStateFlow<Map<Int, Bitmap>>(emptyMap())
    val frameBitmaps: Map<Int, Bitmap> get() = _frameBitmaps.value

    // ──────────────────────────────────────────────────────────
    //  ✅ 全局蒙版（问题1核心修复）
    //  所有帧共享同一份蒙版列表，不再按帧存储。
    // ──────────────────────────────────────────────────────────

    /** 全局蒙版列表（所有帧共用） */
    private val _globalMasks = MutableStateFlow<List<MaskRect>>(emptyList())
    val globalMasks: List<MaskRect> get() = _globalMasks.value

    /**
     * 兼容旧 API：`masks` 用于 UI 渲染，值始终等于全局列表。
     * 改用 `globalMasks` 读取，`addMask / removeMask / moveMaskBy` 写操作。
     */
    var masks by mutableStateOf(listOf<MaskRect>())
        private set

    // ──────────────────────────────────────────────────────────
    //  AI 自动检测模式状态
    // ──────────────────────────────────────────────────────────

    /** AI 自动检测处理状态 */
    data class AutoDetectState(
        val videoBitmap: Bitmap? = null,
        val progress: Int = 0,
        val statusText: String = "",
        val resultUri: Uri? = null
    )
    
    private val _autoDetectState = mutableStateOf(AutoDetectState())
    val state: AutoDetectState get() = _autoDetectState.value
    
    /**
     * 重置 AI 自动检测状态
     */
    fun resetAutoDetectState() {
        _autoDetectState.value = AutoDetectState()
    }
    
    /**
     * AI 自动检测去水印（无需手动框选）
     *
     * @param videoUri 视频 URI
     * @param context 应用上下文
     */
    fun processVideo(videoUri: Uri, context: Context) {
        viewModelScope.launch {
            try {
                // 加载视频第一帧作为预览
                val bitmap = withContext(Dispatchers.IO) {
                    loadVideoFirstFrame(context, videoUri)
                }
                
                if (bitmap != null) {
                    _autoDetectState.value = _autoDetectState.value.copy(
                        videoBitmap = bitmap,
                        progress = 10,
                        statusText = "AI 模型正在加载..."
                    )
                } else {
                    _autoDetectState.value = _autoDetectState.value.copy(
                        statusText = "无法加载视频"
                    )
                    return@launch
                }
                
                // 调用 VideoProcessor 的自动检测模式（传入空列表）
                emitAll(videoProcessor.processVideo(videoUri, emptyList()).collectLatest { processState ->
                    when (processState) {
                        is VideoProcessor.ProcessState.Progress -> {
                            _autoDetectState.value = _autoDetectState.value.copy(
                                progress = processState.current,
                                statusText = processState.phase
                            )
                        }
                        is VideoProcessor.ProcessState.Success -> {
                            _autoDetectState.value = _autoDetectState.value.copy(
                                progress = 100,
                                statusText = "处理完成",
                                resultUri = processState.outputUri
                            )
                        }
                        is VideoProcessor.ProcessState.Error -> {
                            _autoDetectState.value = _autoDetectState.value.copy(
                                statusText = "错误：${processState.message}"
                            )
                        }
                    }
                })
            } catch (e: Exception) {
                _autoDetectState.value = _autoDetectState.value.copy(
                    statusText = "处理失败：${e.message}"
                )
            }
        }
    }
    
    /**
     * 从 URI 加载视频的第一帧
     */
    private fun loadVideoFirstFrame(context: Context, uri: Uri): Bitmap? {
        return try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, uri)
                retriever.frameAtTime
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 处理完成回调
     */
    private fun onComplete(outputPath: String, inputPath: String) {
        // TODO: 通过 LaunchedEffect 或返回 Result 通知 UI 层
    }

    // ──────────────────────────────────────────────────────────
    //  待确认框（仍在当前帧）
    // ──────────────────────────────────────────────────────────

    /** 正在拖拽的临时框（null = 没有在画新框） */
    var pendingMask by mutableStateOf<MaskRect?>(null)
        private set

    /** pendingMask 对应的手绘轨迹点（归一化坐标），用于 Canvas 渲染 */
    var pendingFreehandPoints by mutableStateOf<List<Pair<Float, Float>>>(emptyList())
        private set

    // ──────────────────────────────────────────────────────────
    //  处理状态
    // ──────────────────────────────────────────────────────────

    /** 是否正在处理中 */
    var isProcessing by mutableStateOf(false)
        private set

    /** 处理进度 0-100 */
    var progress by mutableIntStateOf(0)
        private set

    /** 处理帧计数（已处理帧数） */
    var processedFrames by mutableIntStateOf(0)
        private set

    /** 处理总帧数 */
    var totalFramesToProcess by mutableIntStateOf(0)
        private set

    /** 处理阶段描述（含帧索引） */
    var progressPhase by mutableStateOf("")
        private set

    /** 处理是否完成（用于显示成功提示） */
    var processingComplete by mutableStateOf(false)
        private set

    /** 错误信息 */
    var errorMessage by mutableStateOf<String?>(null)
        private set

    // ──────────────────────────────────────────────────────────
    //  媒体信息
    // ──────────────────────────────────────────────────────────

    /** 媒体原始宽高比（宽/高），用于画面自适应 */
    var mediaAspectRatio by mutableStateOf<Float?>(null)
        private set

    /** Canvas 尺寸（像素），由 UI 层设置 */
    var canvasSize by mutableStateOf<Size?>(null)
        private set

    /** 视频在 Canvas 中的实际显示区域（像素坐标，相对于 Canvas 左上角） */
    var videoDisplayRect by mutableStateOf<Rect?>(null)
        private set

    /** 视频原始像素尺寸（宽x高） */
    var videoPixelSize by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    fun setMediaAspectRatio(ratio: Float) {
        mediaAspectRatio = ratio
    }

    fun setVideoDisplayRectAndCanvasSize(canvasW: Float, canvasH: Float, displayRect: Rect) {
        canvasSize = Size(canvasW, canvasH)
        videoDisplayRect = displayRect
    }

    fun setVideoPixelSize(width: Int, height: Int) {
        videoPixelSize = width to height
    }

    // ──────────────────────────────────────────────────────────
    //  帧导航方法
    // ──────────────────────────────────────────────────────────

    fun initTotalFrames(n: Int) {
        totalFrames = n
    }

    /**
     * 切换到指定帧（会更新 currentFrameIndex）
     * 注意：masks 不再随帧切换而变化（全局共享）
     */
    fun seekToFrame(idx: Int) {
        if (idx < 0 || totalFrames <= 0) return
        currentFrameIndex = idx.coerceIn(0, totalFrames - 1)
        // masks 始终等于全局蒙版，无需更新
    }

    /**
     * 缓存帧 Bitmap（LRU，超过 MAX_FRAME_CACHE 时移除最旧的）
     */
    fun cacheFrameBitmap(idx: Int, bitmap: Bitmap) {
        _frameBitmaps.update { current ->
            val mutable = current.toMutableMap()
            if (mutable.size >= MAX_FRAME_CACHE) {
                val oldest = mutable.keys.minOrNull() ?: idx
                mutable[oldest]?.recycle()
                mutable.remove(oldest)
            }
            mutable[idx] = bitmap
            mutable.toMap()
        }
    }

    /**
     * 获取缓存的帧 Bitmap
     */
    fun getCachedFrameBitmap(idx: Int): Bitmap? = _frameBitmaps.value[idx]

    // ──────────────────────────────────────────────────────────
    //  ✅ 蒙版操作（全局列表）
    // ──────────────────────────────────────────────────────────

    /**
     * 添加蒙版到全局列表
     */
    fun addMask(mask: MaskRect) {
        _globalMasks.update { it + mask }
        masks = _globalMasks.value
    }

    /**
     * 整体拖动全局蒙版列表中的某个蒙版（归一化 dx/dy）。
     */
    fun moveMaskBy(id: Int, dx: Float, dy: Float) {
        _globalMasks.update { current ->
            current.map { m ->
                if (m.id != id) m
                else m.copy(freehandPoints = m.freehandPoints?.toList()).apply { moveBy(dx, dy) }
            }
        }
        masks = _globalMasks.value
    }

    /** 命中测试：返回最上层被点中的蒙版（手绘用多边形精确判断） */
    fun findMaskAt(nx: Float, ny: Float): MaskRect? = masks.findLast { it.contains(nx, ny) }

    /**
     * 从全局列表删除指定 id 的蒙版
     */
    fun removeMask(id: Int) {
        _globalMasks.update { list -> list.filter { it.id != id } }
        masks = _globalMasks.value
    }

    /**
     * ✅ 核心修复：返回所有帧到同一份全局蒙版列表。
     * 用于 VideoProcessor.processVideo，每帧都用相同的全局蒙版。
     */
    fun getAllFrameMasks(totalFrameCount: Int): Map<Int, List<MaskArea>> {
        val globalAreas = _globalMasks.value.map { mask ->
            MaskArea(
                rect = mask.toRectF(),
                freehandPoints = mask.freehandPoints
            )
        }
        // 所有帧都返回同一份全局蒙版
        return (0 until totalFrameCount).associateWith { globalAreas }
    }

    /**
     * 清空全局蒙版列表
     */
    fun clearAllMasks() {
        _globalMasks.value = emptyList()
        masks = emptyList()
        pendingMask = null
        pendingFreehandPoints = emptyList()
    }

    fun clearFrameBitmapCache() {
        _frameBitmaps.value.values.forEach { it.recycle() }
        _frameBitmaps.value = emptyMap()
    }

    // ──────────────────────────────────────────────────────────
    //  待确认框操作
    // ──────────────────────────────────────────────────────────

    private var nextId = 0

    /**
     * 松手时将临时框转为待确认状态（不自动加入 masks）
     */
    fun confirmPendingMask() {
        val p = pendingMask ?: return
        if (p.width > 0.02f && p.height > 0.02f) {
            pendingMask = MaskRect(
                id = nextId++,
                left   = minOf(p.left, p.right),
                top    = minOf(p.top,  p.bottom),
                right  = maxOf(p.left, p.right),
                bottom = maxOf(p.top,  p.bottom),
                freehandPoints = p.freehandPoints
            )
        } else {
            pendingMask = null
        }
    }

    /** 用户点击勾选 → 正式加入全局蒙版，关闭待确认 */
    fun acceptPendingMask() {
        val p = pendingMask ?: return
        val accepted = MaskRect(
            id = nextId++,
            left = p.left, top = p.top, right = p.right, bottom = p.bottom,
            freehandPoints = p.freehandPoints
        )
        addMask(accepted)
        pendingMask = null
        pendingFreehandPoints = emptyList()
    }

    /** 用户点击叉号 → 取消待确认的框 */
    fun rejectPendingMask() {
        pendingMask = null
    }

    fun cancelPendingMask() {
        pendingMask = null
    }

    fun updatePendingMask(rect: MaskRect?) {
        pendingMask = rect
    }

    /** Ray-casting 算法：判断归一化坐标 (nx, ny) 是否在多边形内部 */
    fun isInsidePolygon(nx: Float, ny: Float, polygon: List<Pair<Float, Float>>): Boolean =
        MaskRect.pointInPolygon(nx, ny, polygon)

    /** 清除手绘轨迹 */
    fun clearPendingFreehandPoints() {
        pendingFreehandPoints = emptyList()
    }

    // ──────────────────────────────────────────────────────────
    //  坐标归一化：将 Canvas 蒙版转为视频像素坐标系
    // ──────────────────────────────────────────────────────────

    private fun MaskRect.toNormedMaskArea(mediaType: String): MaskArea {
        val raw = this.toRectF()
        val normed = if (mediaType == "video") {
            val displayRect = videoDisplayRect
            val pixelSize = videoPixelSize
            val cs = canvasSize
            if (displayRect != null && pixelSize != null && cs != null) {
                val canvasL = raw.left * cs.width
                val canvasT = raw.top * cs.height
                val canvasR = raw.right * cs.width
                val canvasB = raw.bottom * cs.height
                val vidL = (canvasL - displayRect.left).coerceIn(0f, displayRect.width)
                val vidT = (canvasT - displayRect.top).coerceIn(0f, displayRect.height)
                val vidR = (canvasR - displayRect.left).coerceIn(0f, displayRect.width)
                val vidB = (canvasB - displayRect.top).coerceIn(0f, displayRect.height)
                RectF(
                    vidL / displayRect.width,
                    vidT / displayRect.height,
                    vidR / displayRect.width,
                    vidB / displayRect.height
                )
            } else raw
        } else raw
        return MaskArea(
            rect = normed,
            freehandPoints = this.freehandPoints
        )
    }

    // ──────────────────────────────────────────────────────────
    //  开始处理
    // ──────────────────────────────────────────────────────────

    fun startProcessing(
        context: android.content.Context,
        mediaUri: Uri,
        mediaType: String,
        onComplete: (String, String) -> Unit
    ) {
        if (mediaType == "image" && masks.isEmpty()) {
            errorMessage = "请先框选水印区域"
            return
        }
        if (mediaType == "video" && _globalMasks.value.isEmpty()) {
            errorMessage = "请先在视频帧上框选水印区域"
            return
        }

        isProcessing = true
        errorMessage = null
        progress = 0
        processedFrames = 0
        totalFramesToProcess = totalFrames
        progressPhase = ""
        processingComplete = false

        viewModelScope.launch {
            try {
                if (mediaType == "image") {
                    val androidMasks = masks.map { it.toNormedMaskArea("image") }

                    val bitmap = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(mediaUri)?.use { inputStream ->
                            BitmapFactory.decodeStream(inputStream)
                        }
                    }

                    if (bitmap == null) {
                        errorMessage = "无法读取图片"
                        isProcessing = false
                        return@launch
                    }

                    progressPhase = "正在处理..."
                    progress = 30

                    val result = withContext(Dispatchers.Default) {
                        videoProcessor.processImage(bitmap, androidMasks)
                    }
                    bitmap.recycle()

                    progress = 80
                    val outputFile = withContext(Dispatchers.IO) {
                        File(context.cacheDir, "result_${System.currentTimeMillis()}.jpg").apply {
                            FileOutputStream(this).use { fos ->
                                result.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                            }
                        }
                    }
                    result.recycle()

                    progress = 100
                    processingComplete = true
                    progressPhase = "✅ 处理完成，已保存"
                    // 延迟关闭，让用户看到成功提示
                    kotlinx.coroutines.delay(1500)
                    isProcessing = false
                    onComplete(mediaUri.toString(), Uri.fromFile(outputFile).toString())

                } else {
                    // ✅ 视频：使用全局蒙版（所有帧共享同一份）
                    val totalCount = totalFrames.coerceAtLeast(1)
                    val allFrameMasks = getAllFrameMasks(totalCount)
                    videoProcessor.processVideo(mediaUri, allFrameMasks).collectLatest { state ->
                        when (state) {
                            is VideoProcessor.ProcessState.Progress -> {
                                progress = state.current
                                // ✅ 解析帧计数："处理中 15/120"
                                val match = Regex("(\\d+)/(\\d+)").find(state.phase)
                                if (match != null) {
                                    processedFrames = match.groupValues[1].toIntOrNull() ?: 0
                                    totalFramesToProcess = match.groupValues[2].toIntOrNull() ?: totalCount
                                }
                                progressPhase = state.phase
                            }
                            is VideoProcessor.ProcessState.Success -> {
                                progress = 100
                                processingComplete = true
                                progressPhase = "✅ 处理完成，已保存"
                                // 延迟关闭，让用户看到成功提示
                                kotlinx.coroutines.delay(1500)
                                isProcessing = false
                                onComplete(mediaUri.toString(), state.outputUri.toString())
                            }
                            is VideoProcessor.ProcessState.Error -> {
                                errorMessage = state.message
                                isProcessing = false
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                errorMessage = "处理失败: ${e.message}"
                isProcessing = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        clearFrameBitmapCache()
    }
}

// ──────────────────────────────────────────────────────────────
//  UI 层
// ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    mediaUri: String,
    mediaType: String,
    onBack: () -> Unit,
    onComplete: (originalUri: String, processedUri: String) -> Unit
) {
    val context = LocalContext.current
    val viewModel: EditorViewModel = androidx.hilt.navigation.compose.hiltViewModel()

    var canvasSize by remember { mutableStateOf(Size.Zero) }

    /** 正在绘制的手绘轨迹点（屏幕坐标） */
    var currentFreehandPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }

    /** 是否正在画自由曲线 */
    var isDrawingFreehand by remember { mutableStateOf(false) }
    var lastDragPos by remember { mutableStateOf(Offset.Zero) }

    /** 正在整体拖动的已确认蒙版 id（null = 不在拖动蒙版） */
    var draggingMaskId by remember { mutableStateOf<Int?>(null) }

    /** 视频预览（首帧 or 当前帧 Bitmap） */
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }

    /** 视频原始宽高（用于计算比例） */
    var videoWidth by remember { mutableIntStateOf(0) }
    var videoHeight by remember { mutableIntStateOf(0) }

    /** 总帧数（从 retriever 获取） */
    var videoDurationMs by remember { mutableLongStateOf(0L) }
    var videoFrameRate by remember { mutableFloatStateOf(30f) }

    /** 图片原始宽高 */
    var imageWidth by remember { mutableIntStateOf(0) }
    var imageHeight by remember { mutableIntStateOf(0) }

    /** 防抖：用于 Slider 的延迟帧加载 */
    var pendingSeekFrame by remember { mutableIntStateOf(0) }
    var lastSeekTime by remember { mutableLongStateOf(0L) }

    /** ✅ 退出确认对话框 */
    var showExitDialog by remember { mutableStateOf(false) }

    val retriever = remember {
        android.media.MediaMetadataRetriever()
    }

    // 获取媒体尺寸
    LaunchedEffect(mediaUri) {
        if (mediaType == "video") {
            try {
                retriever.setDataSource(context, Uri.parse(mediaUri))

                val wStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val hStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val durStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                val fpsStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)

                videoWidth  = wStr?.toIntOrNull() ?: 1920
                videoHeight = hStr?.toIntOrNull() ?: 1080
                videoDurationMs = durStr?.toLongOrNull() ?: 0L
                videoFrameRate = fpsStr?.toFloatOrNull() ?: 30f

                val frameCount = if (videoDurationMs > 0 && videoFrameRate > 0) {
                    ((videoDurationMs / 1000.0) * videoFrameRate).toInt()
                } else 0

                previewBitmap = retriever.getFrameAtTime(0)

                if (videoWidth > 0 && videoHeight > 0) {
                    viewModel.setMediaAspectRatio(videoWidth.toFloat() / videoHeight.toFloat())
                    viewModel.setVideoPixelSize(videoWidth, videoHeight)
                }

                if (frameCount > 0) {
                    viewModel.initTotalFrames(frameCount)
                    previewBitmap?.let { bmp ->
                        viewModel.cacheFrameBitmap(0, bmp.copy(Bitmap.Config.ARGB_8888, false))
                    }
                }
            } catch (e: Exception) {
                viewModel.setMediaAspectRatio(16f / 9f)
            }
        } else {
            viewModel.setMediaAspectRatio(4f / 3f)
        }
    }

    // 加载指定帧（防抖）
    LaunchedEffect(pendingSeekFrame, mediaUri) {
        if (mediaType != "video") return@LaunchedEffect
        val now = System.currentTimeMillis()
        if (now - lastSeekTime < 100) return@LaunchedEffect
        lastSeekTime = now

        val idx = pendingSeekFrame
        viewModel.getCachedFrameBitmap(idx)?.let { cached ->
            previewBitmap = cached
            viewModel.seekToFrame(idx)
            return@LaunchedEffect
        }

        withContext(Dispatchers.IO) {
            try {
                val msPerFrame = if (videoFrameRate > 0) (1000000.0 / videoFrameRate).toLong() else 33333L
                val timeUs = idx * msPerFrame * 1000L
                val bmp = retriever.getFrameAtTime(timeUs.coerceAtLeast(0L))
                if (bmp != null) {
                    previewBitmap = bmp
                    viewModel.cacheFrameBitmap(idx, bmp.copy(Bitmap.Config.ARGB_8888, false))
                    viewModel.seekToFrame(idx)
                }
            } catch (_: Exception) { /* ignore */ }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            retriever.release()
            viewModel.clearFrameBitmapCache()
        }
    }

    // 图片加载完成后获取实际尺寸
    LaunchedEffect(mediaUri) {
        if (mediaType == "image") {
            withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(Uri.parse(mediaUri))?.use { input ->
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeStream(input, null, opts)
                        imageWidth = opts.outWidth
                        imageHeight = opts.outHeight
                        if (imageWidth > 0 && imageHeight > 0) {
                            viewModel.setMediaAspectRatio(imageWidth.toFloat() / imageHeight.toFloat())
                            viewModel.setVideoPixelSize(imageWidth, imageHeight)
                        }
                    }
                } catch (_: Exception) { /* ignore */ }
            }
        }
    }

    // ✅ 退出确认对话框（问题3）
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("确定退出编辑？") },
            text = { Text("未保存的蒙版将丢失。") },
            confirmButton = {
                TextButton(onClick = {
                    showExitDialog = false
                    onBack()
                }) {
                    Text("确定退出")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    WatermarkRemoverTheme {
        // ✅ 处理中进度弹窗（问题2：真实进度 + 完成后显示成功提示）
        if (viewModel.isProcessing) {
            ProcessingDialog(
                progress = viewModel.progress,
                phase = viewModel.progressPhase,
                processedFrames = viewModel.processedFrames,
                totalFrames = viewModel.totalFramesToProcess,
                isComplete = viewModel.processingComplete
            )
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (mediaType == "video") "编辑视频" else "编辑图片") },
                    navigationIcon = {
                        IconButton(onClick = { showExitDialog = true }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        if (mediaType == "video") {
                            // 跳转到上一帧
                            IconButton(
                                onClick = {
                                    val prev = (viewModel.currentFrameIndex - 1).coerceAtLeast(0)
                                    pendingSeekFrame = prev
                                    lastSeekTime = 0L
                                },
                                enabled = viewModel.currentFrameIndex > 0
                            ) {
                                Text("◀", fontSize = 18.sp)
                            }
                            // 跳转到下一帧
                            IconButton(
                                onClick = {
                                    val next = (viewModel.currentFrameIndex + 1).coerceAtMost((viewModel.totalFrames - 1).coerceAtLeast(0))
                                    pendingSeekFrame = next
                                    lastSeekTime = 0L
                                },
                                enabled = viewModel.currentFrameIndex < viewModel.totalFrames - 1
                            ) {
                                Text("▶", fontSize = 18.sp)
                            }
                        }
                        IconButton(
                            onClick = {
                                if (viewModel.masks.isNotEmpty()) {
                                    viewModel.removeMask(viewModel.masks.last().id)
                                }
                            },
                            enabled = viewModel.masks.isNotEmpty()
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "撤销最后框")
                        }
                        IconButton(
                            onClick = { viewModel.clearAllMasks() },
                            enabled = viewModel.masks.isNotEmpty()
                        ) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = "清空全部")
                        }
                        // ✅ 右上角关闭按钮（问题3）
                        IconButton(onClick = { showExitDialog = true }) {
                            Icon(Icons.Filled.Close, contentDescription = "关闭")
                        }
                    }
                )
            },
            bottomBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .navigationBarsPadding()
                ) {
                    viewModel.errorMessage?.let { msg ->
                        Text(
                            text = "⚠️ $msg",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    when {
                        viewModel.masks.isEmpty() && viewModel.pendingMask == null -> {
                            Text(
                                text = if (mediaType == "video")
                                    "✍️ 在下方时间轴选择帧，然后在画面上手指画圈圈住水印"
                                else
                                    "✍️ 手指画圈圈住水印；已确认的区域可直接拖动移位，点击区域内删除",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        viewModel.pendingMask != null -> {
                            Text(
                                text = "✅ 请点击右上角 ✓ 确认此区域，或点击 ✕ 重新圈选",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        else -> {
                            Text(
                                text = "✅ 已圈选 ${viewModel.masks.size} 个区域（所有帧共享）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                    }

                    Button(
                        onClick = {
                            viewModel.startProcessing(
                                context = context,
                                mediaUri = Uri.parse(mediaUri),
                                mediaType = mediaType,
                                onComplete = onComplete
                            )
                        },
                        enabled = viewModel.masks.isNotEmpty() && !viewModel.isProcessing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("开始去除水印", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(Color.Black)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.Top
            ) {
                // ─── 媒体预览区（占 60%） ───
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.60f)
                        .padding(8.dp)
                        .then(
                            if (viewModel.mediaAspectRatio != null && viewModel.mediaAspectRatio!! > 0) {
                                Modifier.aspectRatio(viewModel.mediaAspectRatio!!)
                            } else {
                                Modifier
                            }
                        )
                        .onSizeChanged { size ->
                            val canvasW = size.width.toFloat()
                            val canvasH = size.height.toFloat()
                            canvasSize = Size(canvasW, canvasH)
                            val ratio = viewModel.mediaAspectRatio ?: (16f / 9f)
                            val boxRatio = canvasW / canvasH
                            val (vidW, vidH) = if (boxRatio > ratio) {
                                val h = canvasH
                                val w = h * ratio
                                w.toFloat() to h.toFloat()
                            } else {
                                val w = canvasW
                                val h = w / ratio
                                w.toFloat() to h.toFloat()
                            }
                            val offsetX = (canvasW - vidW) / 2
                            val offsetY = (canvasH - vidH) / 2
                            viewModel.setVideoDisplayRectAndCanvasSize(
                                canvasW, canvasH,
                                Rect(offsetX, offsetY, offsetX + vidW, offsetY + vidH)
                            )
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { tapPos ->
                                    val nx = tapPos.x / canvasSize.width
                                    val ny = tapPos.y / canvasSize.height
                                    if (viewModel.pendingMask != null) {
                                        val p = viewModel.pendingMask!!
                                        val pl = minOf(p.left, p.right); val pr = maxOf(p.left, p.right)
                                        val pt = minOf(p.top, p.bottom); val pb = maxOf(p.top, p.bottom)
                                        if (nx !in pl..pr || ny !in pt..pb) {
                                            viewModel.rejectPendingMask()
                                        }
                                        return@detectTapGestures
                                    }
                                    val tapped = viewModel.findMaskAt(nx, ny)
                                    if (tapped != null) viewModel.removeMask(tapped.id)
                                }
                            )
                        }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { startPos ->
                                    if (viewModel.pendingMask != null) return@detectDragGestures
                                    lastDragPos = startPos
                                    val nx = startPos.x / canvasSize.width
                                    val ny = startPos.y / canvasSize.height
                                    val hit = viewModel.findMaskAt(nx, ny)
                                    if (hit != null) {
                                        draggingMaskId = hit.id
                                        isDrawingFreehand = false
                                        currentFreehandPoints = emptyList()
                                        return@detectDragGestures
                                    }
                                    draggingMaskId = null
                                    currentFreehandPoints = listOf(startPos)
                                    isDrawingFreehand = true
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val movingId = draggingMaskId
                                    if (movingId != null) {
                                        if (canvasSize.width > 0f && canvasSize.height > 0f) {
                                            viewModel.moveMaskBy(
                                                movingId,
                                                dragAmount.x / canvasSize.width,
                                                dragAmount.y / canvasSize.height
                                            )
                                        }
                                        lastDragPos = change.position
                                        return@detectDragGestures
                                    }
                                    lastDragPos = change.position
                                    val pts = currentFreehandPoints.toMutableList()
                                    pts.add(change.position)
                                    currentFreehandPoints = pts
                                },
                                onDragCancel = {
                                    draggingMaskId = null
                                    isDrawingFreehand = false
                                    currentFreehandPoints = emptyList()
                                },
                                onDragEnd = {
                                    isDrawingFreehand = false
                                    if (draggingMaskId != null) {
                                        draggingMaskId = null
                                        return@detectDragGestures
                                    }
                                    val pts = currentFreehandPoints
                                    currentFreehandPoints = emptyList()
                                    if (pts.size < 3) return@detectDragGestures
                                    val freehandNormPts = pts.map {
                                        Pair(it.x / canvasSize.width, it.y / canvasSize.height)
                                    }
                                    val xs = freehandNormPts.map { it.first }
                                    val ys = freehandNormPts.map { it.second }
                                    val nl = xs.minOrNull() ?: return@detectDragGestures
                                    val nr = xs.maxOrNull() ?: return@detectDragGestures
                                    val nt = ys.minOrNull() ?: return@detectDragGestures
                                    val nb = ys.maxOrNull() ?: return@detectDragGestures
                                    if ((nr - nl) > 0.02f && (nb - nt) > 0.02f) {
                                        viewModel.updatePendingMask(EditorViewModel.MaskRect(
                                            id = -1,
                                            left = nl, top = nt, right = nr, bottom = nb,
                                            freehandPoints = freehandNormPts
                                        ))
                                        viewModel.confirmPendingMask()
                                    }
                                }
                            )
                        }
                ) {
                    // 媒体预览层
                    if (mediaType == "image") {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(Uri.parse(mediaUri))
                                .crossfade(true)
                                .build(),
                            contentDescription = "原图",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        previewBitmap?.let { bitmap ->
                            androidx.compose.foundation.Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "视频帧 ${viewModel.currentFrameIndex + 1}",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        } ?: Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("视频帧加载中...", color = Color.White)
                        }
                    }

                    // ✅ 全局蒙版绘制层（所有帧都显示相同的蒙版）
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val borderPx = 2.dp.toPx()

                        // ---- 已确认的框 ----
                        viewModel.masks.forEach { mask ->
                            val isFreehand = mask.isFreehand
                            val isDragging = draggingMaskId == mask.id
                            val color = if (isDragging) Color(0xFFFF9800) else Color(0xFF00C853)

                            if (isFreehand) {
                                val pts = mask.freehandPoints!!
                                if (pts.size >= 3) {
                                    val path = Path()
                                    path.moveTo(pts[0].first * size.width, pts[0].second * size.height)
                                    for (i in 1 until pts.size) {
                                        path.lineTo(pts[i].first * size.width, pts[i].second * size.height)
                                    }
                                    path.close()
                                    drawPath(path, color.copy(alpha = 0.22f), style = Fill)
                                    drawPath(path, color, style = Stroke(width = borderPx))
                                }
                            } else {
                                val l = minOf(mask.left,  mask.right)  * size.width
                                val t = minOf(mask.top,   mask.bottom) * size.height
                                val w = kotlin.math.abs(mask.right - mask.left) * size.width
                                val h = kotlin.math.abs(mask.bottom - mask.top) * size.height
                                drawRect(color.copy(alpha = 0.22f), topLeft = Offset(l, t), size = Size(w, h))
                                drawRect(color, topLeft = Offset(l, t), size = Size(w, h), style = Stroke(width = borderPx))
                            }

                            // ---- 拖动手柄（框内右下角，橙色圆形 24dp） ----
                            val bl = minOf(mask.left,  mask.right)  * size.width
                            val bt = minOf(mask.top,   mask.bottom) * size.height
                            val br = maxOf(mask.left,  mask.right)  * size.width
                            val bb = maxOf(mask.top,   mask.bottom) * size.height
                            val handleR = 12.dp.toPx()
                            val handleCenter = Offset(
                                (br - handleR - borderPx).coerceAtLeast(bl + handleR),
                                (bb - handleR - borderPx).coerceAtLeast(bt + handleR)
                            )
                            drawCircle(Color(0xFFFF9800).copy(alpha = 0.92f), radius = handleR, center = handleCenter)
                            drawCircle(Color.White, radius = handleR, center = handleCenter, style = Stroke(width = borderPx * 0.8f))
                            val armLen = handleR * 0.5f
                            drawLine(Color.White, Offset(handleCenter.x - armLen, handleCenter.y), Offset(handleCenter.x + armLen, handleCenter.y), strokeWidth = borderPx * 0.9f)
                            drawLine(Color.White, Offset(handleCenter.x, handleCenter.y - armLen), Offset(handleCenter.x, handleCenter.y + armLen), strokeWidth = borderPx * 0.9f)
                        }

                        // ---- 正在画的自由曲线 ----
                        if (currentFreehandPoints.isNotEmpty()) {
                            val path = Path()
                            path.moveTo(currentFreehandPoints[0].x, currentFreehandPoints[0].y)
                            for (i in 1 until currentFreehandPoints.size) {
                                path.lineTo(currentFreehandPoints[i].x, currentFreehandPoints[i].y)
                            }
                            drawPath(path = path, color = Color(0xFF2196F3).copy(alpha = 0.3f), style = Fill)
                            drawPath(path = path, color = Color(0xFF2196F3), style = Stroke(width = borderPx * 1.5f))
                        }

                        // ---- 待确认的框 ----
                        viewModel.pendingMask?.let { p ->
                            val pts = p.freehandPoints
                            if (pts != null && pts.size >= 3) {
                                val path = Path()
                                path.moveTo(pts[0].first * size.width, pts[0].second * size.height)
                                for (i in 1 until pts.size) {
                                    path.lineTo(pts[i].first * size.width, pts[i].second * size.height)
                                }
                                path.close()
                                drawPath(path, Color(0xFF2196F3).copy(alpha = 0.25f), style = Fill)
                                drawPath(
                                    path, Color(0xFF2196F3),
                                    style = Stroke(width = borderPx, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 6f)))
                                )
                            } else {
                                val l = minOf(p.left,  p.right) * size.width
                                val t = minOf(p.top,   p.bottom) * size.height
                                val w = kotlin.math.abs(p.right - p.left) * size.width
                                val h = kotlin.math.abs(p.bottom - p.top) * size.height
                                drawRect(color = Color(0xFF2196F3).copy(alpha = 0.25f), topLeft = Offset(l, t), size = Size(w, h))
                                drawRect(
                                    color = Color(0xFF2196F3),
                                    topLeft = Offset(l, t),
                                    size = Size(w, h),
                                    style = Stroke(width = borderPx, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 6f)))
                                )
                            }
                        }
                    }

                    // ---- 待确认框顶部勾叉按钮 ----
                    viewModel.pendingMask?.let { p ->
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            val boxW = constraints.maxWidth
                            val boxH = constraints.maxHeight
                            val l = (minOf(p.left, p.right) * boxW).toInt()
                            val t = (minOf(p.top, p.bottom) * boxH).toInt()
                            val w = (kotlin.math.abs(p.right - p.left) * boxW).toInt()

                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .offset { IntOffset(l + w - 36, t - 40) }
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF00C853))
                                    .clickable { viewModel.acceptPendingMask() },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = "✓", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            }

                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .offset { IntOffset(l, t - 40) }
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFFFF5252))
                                    .clickable { viewModel.rejectPendingMask() },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = "✕", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // ─── ✅ 视频时间轴（问题1：所有帧都显示蒙版绿点，不再只标记有蒙版的帧） ───
                if (mediaType == "video" && viewModel.totalFrames > 0) {
                    VideoTimeline(
                        currentFrame = viewModel.currentFrameIndex,
                        totalFrames = viewModel.totalFrames,
                        hasMasks = viewModel.masks.isNotEmpty(),
                        onSeek = { idx ->
                            pendingSeekFrame = idx
                            lastSeekTime = 0L
                        }
                    )
                } else if (mediaType == "video") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("正在检测视频帧数...", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────
//  ✅ 视频时间轴组件（问题1修复：所有帧显示蒙版标记）
// ──────────────────────────────────────────────────────────────

@Composable
private fun VideoTimeline(
    currentFrame: Int,
    totalFrames: Int,
    hasMasks: Boolean,
    onSeek: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // 帧计数行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "第 ${currentFrame + 1} 帧",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )
            Text(
                text = "/ $totalFrames 帧",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 时间轴行（含帧标记点 + Slider）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            // ✅ 帧标记点：只要有蒙版，所有帧都显示绿点（不再是 frameMasks.keys）
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val yCenter = size.height / 2

                if (hasMasks) {
                    // 所有帧都显示绿点（蒙版是全局的，每帧都有）
                    val step = (totalFrames - 1).coerceAtLeast(1)
                    for (idx in 0 until totalFrames step (totalFrames / 10).coerceAtLeast(1)) {
                        val x = (idx.toFloat() / step) * w.coerceAtLeast(1f)
                        drawCircle(
                            color = Color(0xFF4CAF50),
                            radius = 4.dp.toPx(),
                            center = Offset(x, yCenter)
                        )
                    }
                }

                // 橙色当前位置竖线
                val thumbX = if (totalFrames > 1) {
                    (currentFrame.toFloat() / (totalFrames - 1)) * w
                } else w / 2
                drawLine(
                    color = Color(0xFFFF9800),
                    start = Offset(thumbX, 0f),
                    end = Offset(thumbX, size.height),
                    strokeWidth = 2.dp.toPx()
                )
            }

            // 透明 Slider（覆盖整个时间轴区域用于拖动）
            Slider(
                value = currentFrame.toFloat(),
                onValueChange = { newVal ->
                    onSeek(newVal.toInt())
                },
                valueRange = 0f..(totalFrames - 1).coerceAtLeast(1).toFloat(),
                steps = 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFFFF9800),
                    activeTrackColor = Color(0xFFFF9800),
                    inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                )
            )
        }
    }
}

/**
 * ✅ 动态进度弹窗（问题2修复：显示"正在处理 X/Y 帧..."真实进度）
 * - 处理中：显示帧计数 + LinearProgressIndicator
 * - 完成后：显示 ✅ 处理完成，已保存
 * - 禁止关闭（dismissOnBackPress=false, dismissOnClickOutside=false）
 */
@Composable
fun ProcessingDialog(
    progress: Int,
    phase: String,
    processedFrames: Int = 0,
    totalFrames: Int = 0,
    isComplete: Boolean = false
) {
    Dialog(
        onDismissRequest = { /* 禁止关闭 */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isComplete) "✅ 完成" else "正在处理",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (isComplete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = if (isComplete) "100%" else "$progress%",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isComplete) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(16.dp))

                @Suppress("DEPRECATION")
                LinearProgressIndicator(
                    progress = (progress / 100f).coerceIn(0f, 1f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp),
                    color = if (isComplete) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )

                Spacer(modifier = Modifier.height(12.dp))

                // ✅ 显示帧计数（问题2核心）
                val displayPhase = if (processedFrames > 0 && totalFrames > 0 && !isComplete) {
                    "正在处理 $processedFrames/$totalFrames 帧..."
                } else {
                    phase.ifEmpty { "准备中..." }
                }

                Text(
                    text = displayPhase,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isComplete) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
