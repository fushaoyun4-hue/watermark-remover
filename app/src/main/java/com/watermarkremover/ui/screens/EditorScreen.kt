package com.watermarkremover.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.watermarkremover.inference.VideoProcessor
import com.watermarkremover.ui.theme.WatermarkRemoverTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

/**
 * 编辑页：多选框 + 拖拽新建 + 点击删除已确认框
 * 优化：
 * - 自动适应画面比例（不再硬编码 16:9/4:3）
 * - 多区域框选后需勾选确认才生效，否则可重选
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    private val videoProcessor: VideoProcessor
) : ViewModel() {

    data class MaskRect(
        val id: Int,
        var left: Float,
        var top: Float,
        var right: Float,
        var bottom: Float,
        /** 手绘轨迹的归一化坐标点（可选，用于手绘蒙版） */
        var freehandPoints: List<Pair<Float, Float>>? = null
    ) {
        val width  get() = kotlin.math.abs(right - left)
        val height get() = kotlin.math.abs(bottom - top)
        fun toRectF() = android.graphics.RectF(
            minOf(left, right), minOf(top, bottom),
            maxOf(left, right), maxOf(top, bottom)
        )
    }

    /** 已确认的水印区域列表 */
    var masks by mutableStateOf(listOf<MaskRect>())
        private set

    /** UI 拖拽缩放时更新整个区域列表，触发重组 */
    fun updateMasks(list: List<MaskRect>) {
        masks = list
    }

    /** 正在拖拽的临时框（null = 没有在画新框） */
    var pendingMask by mutableStateOf<MaskRect?>(null)
        private set

    /** pendingMask 对应的手绘轨迹点（归一化坐标），用于 Canvas 渲染 */
    var pendingFreehandPoints by mutableStateOf<List<Pair<Float, Float>>>(emptyList())
        private set

    /** 是否正在处理中 */
    var isProcessing by mutableStateOf(false)
        private set

    /** 处理进度 0-100 */
    var progress by mutableStateOf(0)
        private set

    /** 处理阶段描述 */
    var progressPhase by mutableStateOf("")
        private set

    /** 错误信息 */
    var errorMessage by mutableStateOf<String?>(null)
        private set

    fun setMediaAspectRatio(ratio: Float) {
        mediaAspectRatio = ratio
    }

    /** 媒体原始宽高比（宽/高），用于画面自适应 */
    var mediaAspectRatio by mutableStateOf<Float?>(null)
        private set

    /** Canvas 尺寸（像素），由 UI 层设置 */
    var canvasSize by mutableStateOf<androidx.compose.ui.geometry.Size?>(null)
        private set

    /** 视频在 Canvas 中的实际显示区域（像素坐标，相对于 Canvas 左上角） */
    var videoDisplayRect by mutableStateOf<Rect?>(null)
        private set

    /** 视频原始像素尺寸（宽x高） */
    var videoPixelSize by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    fun setVideoDisplayRectAndCanvasSize(canvasW: Float, canvasH: Float, displayRect: Rect) {
        canvasSize = androidx.compose.ui.geometry.Size(canvasW, canvasH)
        videoDisplayRect = displayRect
    }

    fun setVideoPixelSize(width: Int, height: Int) {
        videoPixelSize = width to height
    }

    private var nextId = 0

    /** 松手时将临时框转为待确认状态（不自动加入 masks） */
    fun confirmPendingMask() {
        val p = pendingMask ?: return
        if (p.width > 0.02f && p.height > 0.02f) {
            // 不直接加入 masks，保持在 pendingMask，等待用户勾选确认
            pendingMask = MaskRect(
                id = nextId++,
                left   = minOf(p.left, p.right),
                top    = minOf(p.top,  p.bottom),
                right  = maxOf(p.left, p.right),
                bottom = maxOf(p.top,  p.bottom)
            )
        } else {
            pendingMask = null
        }
    }

    /** 用户点击勾选 → 正式加入 masks，关闭待确认 */
    fun acceptPendingMask() {
        val p = pendingMask ?: return
        val accepted = MaskRect(
            id = nextId++,
            left = p.left, top = p.top, right = p.right, bottom = p.bottom,
            freehandPoints = p.freehandPoints
        )
        masks = masks + accepted
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
    fun isInsidePolygon(nx: Float, ny: Float, polygon: List<Pair<Float, Float>>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val (xi, yi) = polygon[i]
            val (xj, yj) = polygon[j]
            if (((yi > ny) != (yj > ny)) &&
                (nx < (xj - xi) * (ny - yi) / ((yj - yi).coerceAtLeast(0.0001f)) + xi)) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /** 清除手绘轨迹 */
    fun clearPendingFreehandPoints() {
        pendingFreehandPoints = emptyList()
    }

    fun removeMask(id: Int) {
        masks = masks.filter { it.id != id }
    }

    fun clearMasks() {
        masks = emptyList()
        pendingMask = null
        pendingFreehandPoints = emptyList()
    }

    fun startProcessing(
        context: android.content.Context,
        mediaUri: Uri,
        mediaType: String,
        onComplete: (String, String) -> Unit
    ) {
        if (masks.isEmpty()) {
            errorMessage = "请先框选水印区域"
            return
        }

        isProcessing = true
        errorMessage = null
        progress = 0
        progressPhase = ""

        // 将 Canvas 坐标系(0~1) 的蒙版转换为视频像素坐标系(0~1)
        val displayRect = videoDisplayRect
        val pixelSize = videoPixelSize
        val cs = canvasSize
        val androidMasks = masks.map { mask ->
            val raw = mask.toRectF()
            val normed = if (displayRect != null && pixelSize != null && cs != null && mediaType == "video") {
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
            VideoProcessor.MaskArea(
                rect = normed,
                freehandPoints = mask.freehandPoints
            )
        }

        viewModelScope.launch {
            try {
                if (mediaType == "image") {
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
                    onComplete(mediaUri.toString(), Uri.fromFile(outputFile).toString())
                    isProcessing = false

                } else {
                    videoProcessor.processVideo(mediaUri, androidMasks).collectLatest { state ->
                        when (state) {
                            is VideoProcessor.ProcessState.Progress -> {
                                progress = state.current
                                progressPhase = state.phase
                            }
                            is VideoProcessor.ProcessState.Success -> {
                                progress = 100
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

    // 视频预览（首帧）
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // 视频原始宽高（用于计算比例）
    var videoWidth by remember { mutableIntStateOf(0) }
    var videoHeight by remember { mutableIntStateOf(0) }

    // 图片原始宽高
    var imageWidth by remember { mutableIntStateOf(0) }
    var imageHeight by remember { mutableIntStateOf(0) }

    // 获取媒体尺寸
    LaunchedEffect(mediaUri) {
        if (mediaType == "video") {
            try {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(context, Uri.parse(mediaUri))
                val bitmap = retriever.getFrameAtTime(0)
                previewBitmap = bitmap

                // ✅ 修复：先取元数据，再 release（之前顺序反了，release 后取值永远 null）
                val wStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val hStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                retriever.release()  // 现在才 release

                // 回退逻辑：优先用元数据，否则用首帧尺寸
                videoWidth  = wStr?.toIntOrNull() ?: (bitmap?.width  ?: 1920)
                videoHeight = hStr?.toIntOrNull() ?: (bitmap?.height ?: 1080)

                if (videoWidth > 0 && videoHeight > 0) {
                    viewModel.setMediaAspectRatio(videoWidth.toFloat() / videoHeight.toFloat())
                    // ✅ 修复：通知 ViewModel 视频像素尺寸，用于蒙版坐标归一化
                    viewModel.setVideoPixelSize(videoWidth, videoHeight)
                }
            } catch (e: Exception) {
                // fallback：16:9
                viewModel.setMediaAspectRatio(16f / 9f)
            }
        } else {
            // 图片：从 AsyncImage 加载时无法直接获取尺寸，用 PlaceHolder 方案
            // 先用 4:3 作为 fallback，图片加载后更新
            viewModel.setMediaAspectRatio(4f / 3f)
        }
    }

    // 图片加载完成后获取实际尺寸
    var loadedBitmap by remember { mutableStateOf<Bitmap?>(null) }

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
                            // ✅ 修复：图片模式下也通知 ViewModel 像素尺寸
                            viewModel.setVideoPixelSize(imageWidth, imageHeight)
                        }
                    }
                } catch (_: Exception) { /* ignore */ }
            }
        }
    }

    WatermarkRemoverTheme {
        // ---- 处理中进度弹窗 ----
        if (viewModel.isProcessing) {
            ProcessingDialog(
                progress = viewModel.progress,
                phase = viewModel.progressPhase
            )
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (mediaType == "video") "编辑视频" else "编辑图片") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
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
                            onClick = { viewModel.clearMasks() },
                            enabled = viewModel.masks.isNotEmpty()
                        ) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = "清空全部")
                        }
                    }
                )
            },
            bottomBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .navigationBarsPadding()  // 避免被系统导航栏遮挡
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
                                text = "✍️ 在画面上手指画圈圈住水印/字幕，松手自动填充，点击框内可删除",
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
                                text = "✅ 已圈选 ${viewModel.masks.size} 个区域：空白处画圈新增，点击框内删除",
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                // 限制媒体区域高度，留出操作区空间
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.75f)  // 最多占 75% 高度，留空间给底部按钮
                        .then(
                            // 如果已有实际比例，用实际比例；否则填满
                            if (viewModel.mediaAspectRatio != null && viewModel.mediaAspectRatio!! > 0) {
                                Modifier.aspectRatio(viewModel.mediaAspectRatio!!)
                            } else {
                                Modifier
                            }
                        )
                        .padding(8.dp)
                        .onSizeChanged { size ->
                            val canvasW = size.width.toFloat()
                            val canvasH = size.height.toFloat()
                            canvasSize = Size(canvasW, canvasH)
                            // 计算视频在 Canvas 中的实际显示区域（考虑 letterbox / pillarbox）
                            val ratio = viewModel.mediaAspectRatio ?: (16f / 9f)
                            val boxRatio = canvasW / canvasH
                            val (vidW, vidH) = if (boxRatio > ratio) {
                                // 左右有黑边（视频比 Canvas 更瘦长）
                                val h = canvasH
                                val w = h * ratio
                                w.toFloat() to h.toFloat()
                            } else {
                                // 上下有黑边（视频比 Canvas 更扁宽）
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
                                    val tapped = viewModel.masks.findLast { m ->
                                        val l = minOf(m.left, m.right); val r = maxOf(m.left, m.right)
                                        val t = minOf(m.top, m.bottom);  val b = maxOf(m.top, m.bottom)
                                        nx in l..r && ny in t..b
                                    }
                                    if (tapped != null) viewModel.removeMask(tapped.id)
                                }
                            )
                        }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { startPos ->
                                    if (viewModel.pendingMask != null) return@detectDragGestures
                                    lastDragPos = startPos
                                    currentFreehandPoints = listOf(startPos)
                                    isDrawingFreehand = true
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    lastDragPos = change.position
                                    val pts = currentFreehandPoints.toMutableList()
                                    pts.add(change.position)
                                    currentFreehandPoints = pts
                                },
                                onDragEnd = {
                                    isDrawingFreehand = false
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
                                contentDescription = "视频预览",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        } ?: Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("视频预览加载中...", color = Color.White)
                        }
                    }

                    // 蒙版绘制层
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val borderPx = 2.dp.toPx()

                                        // ---- 已确认的框：绿色边框（手绘显示为路径，矩形显示为矩形）----
                        viewModel.masks.forEach { mask ->
                            val isFreehand = !mask.freehandPoints.isNullOrEmpty()
                            val color = Color(0xFF00C853)

                            if (isFreehand) {
                                // 手绘：绘制封闭 Path
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
                                // 矩形
                                val l = minOf(mask.left,  mask.right)  * size.width
                                val t = minOf(mask.top,   mask.bottom) * size.height
                                val w = kotlin.math.abs(mask.right - mask.left) * size.width
                                val h = kotlin.math.abs(mask.bottom - mask.top) * size.height
                                drawRect(color.copy(alpha = 0.22f), topLeft = Offset(l, t), size = Size(w, h))
                                drawRect(color, topLeft = Offset(l, t), size = Size(w, h), style = Stroke(width = borderPx))
                            }
                        }

                        // ---- 正在画的自由曲线：蓝色半透明填充 + 虚线边框 ----
                        if (currentFreehandPoints.isNotEmpty()) {
                            val path = Path()
                            path.moveTo(currentFreehandPoints[0].x, currentFreehandPoints[0].y)
                            for (i in 1 until currentFreehandPoints.size) {
                                path.lineTo(currentFreehandPoints[i].x, currentFreehandPoints[i].y)
                            }
                            // 填充已围成区域
                            drawPath(
                                path = path,
                                color = Color(0xFF2196F3).copy(alpha = 0.3f),
                                style = Fill
                            )
                            // 蓝色实线描边
                            drawPath(
                                path = path,
                                color = Color(0xFF2196F3),
                                style = Stroke(width = borderPx * 1.5f)
                            )
                        }

                        // ---- 待确认的框：蓝色虚线 ----
                        viewModel.pendingMask?.let { p ->
                            val l = minOf(p.left,  p.right) * size.width
                            val t = minOf(p.top,   p.bottom) * size.height
                            val w = kotlin.math.abs(p.right - p.left) * size.width
                            val h = kotlin.math.abs(p.bottom - p.top) * size.height

                            // 半透明蓝色填充
                            drawRect(
                                color = Color(0xFF2196F3).copy(alpha = 0.25f),
                                topLeft = Offset(l, t),
                                size = Size(w, h)
                            )
                            // 蓝色虚线边框
                            drawRect(
                                color = Color(0xFF2196F3),
                                topLeft = Offset(l, t),
                                size = Size(w, h),
                                style = Stroke(
                                    width = borderPx,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 6f))
                                )
                            )
                        }
                    }

                    // ---- 待确认框顶部左右角的勾叉按钮 ----
                    viewModel.pendingMask?.let { p ->
                        BoxWithConstraints(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            val boxW = constraints.maxWidth
                            val boxH = constraints.maxHeight
                            val l = (minOf(p.left, p.right) * boxW).toInt()
                            val t = (minOf(p.top, p.bottom) * boxH).toInt()
                            val w = (kotlin.math.abs(p.right - p.left) * boxW).toInt()

                            // 绿色勾选按钮（框外顶部右侧）
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .offset { IntOffset(l + w - 36, t - 40) }  // 框外顶部右侧
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF00C853))
                                    .clickable { viewModel.acceptPendingMask() },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "✓",
                                    color = Color.White,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // 红色叉号按钮（框外顶部左侧）
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .offset { IntOffset(l, t - 40) }  // 框外顶部左侧
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFFFF5252))
                                    .clickable { viewModel.rejectPendingMask() },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "✕",
                                    color = Color.White,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 动态进度弹窗
 */
@Composable
fun ProcessingDialog(
    progress: Int,
    phase: String
) {
    Dialog(
        onDismissRequest = { /* 不允许关闭 */ },
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
                    text = "正在处理",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "$progress%",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(16.dp))

                @Suppress("DEPRECATION")
                LinearProgressIndicator(
                    progress = (progress / 100f).coerceIn(0f, 1f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = phase.ifEmpty { "准备中..." },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
