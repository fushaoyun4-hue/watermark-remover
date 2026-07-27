package com.watermarkremover.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.watermarkremover.inference.VideoProcessor
import com.watermarkremover.ui.theme.WatermarkRemoverTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

/**
 * 编辑页：支持多选框 + 拖拽调整 + 删除单个区域
 *
 * 交互设计：
 * - 拖拽空白区域 → 新增框选
 * - 点击已有框选 → 选中（蓝色高亮）
 * - 拖拽已有框选内部 → 移动位置
 * - 拖拽已有框选边缘 → 调整大小
 * - 点击删除按钮 / 再次点击已选中框 → 删除该框
 * - 清除按钮 → 清空所有框
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    private val videoProcessor: VideoProcessor
) : ViewModel() {

    data class MaskRect(
        val id: Int,
        var left: Float,   // 归一化 0~1
        var top: Float,
        var right: Float,
        var bottom: Float
    ) {
        val width get()  = kotlin.math.abs(right - left)
        val height get() = kotlin.math.abs(bottom - top)
        fun toRectF() = android.graphics.RectF(
            minOf(left, right),
            minOf(top, bottom),
            maxOf(left, right),
            maxOf(top, bottom)
        )
    }

    var masks by mutableStateOf(listOf<MaskRect>())
        private set

    var selectedMaskId by mutableStateOf<Int?>(null)
        private set

    var isProcessing by mutableStateOf(false)
        private set

    var progress by mutableStateOf(0)
        private set

    var progressPhase by mutableStateOf("")
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    private var nextId = 0

    /** 新增一个框选 */
    fun addMask(left: Float, top: Float, right: Float, bottom: Float) {
        masks = masks + MaskRect(
            id = nextId++,
            left = minOf(left, right),
            top = minOf(top, bottom),
            right = maxOf(left, right),
            bottom = maxOf(top, bottom)
        )
    }

    /** 删除指定 id 的框选 */
    fun removeMask(id: Int) {
        masks = masks.filter { it.id != id }
        if (selectedMaskId == id) selectedMaskId = null
    }

    /** 删除最后添加的框选 */
    fun removeLastMask() {
        if (masks.isNotEmpty()) {
            val last = masks.last()
            removeMask(last.id)
        }
    }

    /** 清空所有框选 */
    fun clearMasks() {
        masks = emptyList()
        selectedMaskId = null
    }

    /** 选中/取消选中某个框选 */
    fun toggleSelect(id: Int) {
        selectedMaskId = if (selectedMaskId == id) null else id
    }

    /** 移动指定框选（拖拽移动） */
    fun moveMask(id: Int, deltaX: Float, deltaY: Float) {
        masks = masks.map { m ->
            if (m.id == id) {
                m.copy(
                    left  = (m.left  + deltaX).coerceIn(0f, 1f),
                    right = (m.right + deltaX).coerceIn(0f, 1f),
                    top   = (m.top   + deltaY).coerceIn(0f, 1f),
                    bottom= (m.bottom+ deltaY).coerceIn(0f, 1f)
                )
            } else m
        }
    }

    /** 调整指定框选的大小（拖拽边角） */
    fun resizeMask(id: Int, corner: Corner, deltaX: Float, deltaY: Float) {
        masks = masks.map { m ->
            if (m.id == id) {
                when (corner) {
                    Corner.TOP_LEFT     -> m.copy(left = (m.left + deltaX).coerceIn(0f, m.right - 0.02f), top = (m.top + deltaY).coerceIn(0f, m.bottom - 0.02f))
                    Corner.TOP_RIGHT    -> m.copy(right = (m.right + deltaX).coerceIn(m.left + 0.02f, 1f), top = (m.top + deltaY).coerceIn(0f, m.bottom - 0.02f))
                    Corner.BOTTOM_LEFT  -> m.copy(left = (m.left + deltaX).coerceIn(0f, m.right - 0.02f), bottom = (m.bottom + deltaY).coerceIn(m.top + 0.02f, 1f))
                    Corner.BOTTOM_RIGHT -> m.copy(right = (m.right + deltaX).coerceIn(m.left + 0.02f, 1f), bottom = (m.bottom + deltaY).coerceIn(m.top + 0.02f, 1f))
                }
            } else m
        }
    }

    enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

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

        val androidRects = masks.map { it.toRectF() }

        viewModelScope.launch {
            try {
                if (mediaType == "image") {
                    val inputStream = context.contentResolver.openInputStream(mediaUri)
                    val options = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    val bitmap = BitmapFactory.decodeStream(inputStream, null, options)
                    inputStream?.close()

                    if (bitmap == null) {
                        errorMessage = "无法读取图片"
                        isProcessing = false
                        return@launch
                    }

                    val result = videoProcessor.processImage(bitmap, androidRects)
                    bitmap.recycle()

                    val outputFile = File(context.cacheDir, "result_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(outputFile).use { fos ->
                        result.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                    }
                    result.recycle()

                    onComplete(mediaUri.toString(), Uri.fromFile(outputFile).toString())
                    isProcessing = false

                } else {
                    videoProcessor.processVideo(mediaUri, androidRects).collectLatest { state ->
                        when (state) {
                            is VideoProcessor.ProcessState.Progress -> {
                                progress = state.current
                                progressPhase = state.phase
                            }
                            is VideoProcessor.ProcessState.Success -> {
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

    // 拖拽状态
    var isDraggingNew by remember { mutableStateOf(false) }
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var dragEnd by remember { mutableStateOf(Offset.Zero) }

    // 移动/调整已有框的状态
    var isDraggingExisting by remember { mutableStateOf(false) }
    var dragMaskId by remember { mutableStateOf<Int?>(null) }
    var dragMode by remember { mutableStateOf<DragMode?>(null) }

    // 视频预览（首帧）
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(mediaUri) {
        if (mediaType == "video") {
            try {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(context, Uri.parse(mediaUri))
                val bitmap = retriever.getFrameAtTime(0)
                retriever.release()
                previewBitmap = bitmap
            } catch (_: Exception) { /* ignore */ }
        }
    }

    WatermarkRemoverTheme {
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
                        // 删除最后一个
                        IconButton(
                            onClick = { viewModel.removeLastMask() },
                            enabled = viewModel.masks.isNotEmpty()
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "撤销")
                        }
                        // 清空全部
                        IconButton(
                            onClick = { viewModel.clearMasks() },
                            enabled = viewModel.masks.isNotEmpty()
                        ) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = "清空")
                        }
                    }
                )
            },
            bottomBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    // 选中提示
                    viewModel.selectedMaskId?.let { selectedId ->
                        viewModel.masks.find { it.id == selectedId }?.let {
                            Text(
                                text = "✅ 已选中区域 ${viewModel.masks.indexOf(it) + 1}，拖拽可移动，拖拽边缘可调整大小",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                    }

                    // 框选数量提示
                    if (viewModel.masks.isEmpty()) {
                        Text(
                            text = "👆 在图片上拖动框选水印区域（可多选）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    } else if (viewModel.selectedMaskId == null) {
                        Text(
                            text = "已框选 ${viewModel.masks.size} 个区域，点击区域可选中并移动/调整",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    // 进度/错误提示
                    if (!viewModel.errorMessage.isNullOrEmpty()) {
                        Text(
                            text = "⚠️ ${viewModel.errorMessage}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    // 开始按钮
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
                        if (viewModel.isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("${viewModel.progressPhase} ${viewModel.progress}%")
                        } else {
                            Text("开始去除水印", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(if (mediaType == "video") 16f / 9f else 4f / 3f)
                        .padding(8.dp)
                        .onSizeChanged { size ->
                            canvasSize = Size(size.width.toFloat(), size.height.toFloat())
                        }
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                // 点击已有框 → 选中/取消
                                val normalized = Offset(
                                    offset.x / canvasSize.width,
                                    offset.y / canvasSize.height
                                )
                                val hit = viewModel.masks.find { m ->
                                    normalized.x >= minOf(m.left, m.right) - 0.02f &&
                                    normalized.x <= maxOf(m.left, m.right) + 0.02f &&
                                    normalized.y >= minOf(m.top, m.bottom) - 0.02f &&
                                    normalized.y <= maxOf(m.top, m.bottom) + 0.02f
                                }
                                if (hit != null) {
                                    if (viewModel.selectedMaskId == hit.id) {
                                        // 再次点击已选中框 → 删除
                                        viewModel.removeMask(hit.id)
                                    } else {
                                        viewModel.toggleSelect(hit.id)
                                    }
                                } else {
                                    viewModel.selectedMaskId = null
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val normalized = Offset(
                                        offset.x / canvasSize.width,
                                        offset.y / canvasSize.height
                                    )
                                    // 检查是否点在某个已有框上（用于移动/调整）
                                    val hit = viewModel.masks.findLast { m ->
                                        normalized.x >= minOf(m.left, m.right) - 0.02f &&
                                        normalized.x <= maxOf(m.left, m.right) + 0.02f &&
                                        normalized.y >= minOf(m.top, m.bottom) - 0.02f &&
                                        normalized.y <= maxOf(m.top, m.bottom) + 0.02f
                                    }
                                    if (hit != null) {
                                        isDraggingExisting = true
                                        dragMaskId = hit.id
                                        viewModel.selectedMaskId = hit.id
                                        // 检测是否在边角（边长 * 0.15 内）
                                        val w = kotlin.math.abs(hit.right - hit.left)
                                        val h = kotlin.math.abs(hit.bottom - hit.top)
                                        val cornerZone = minOf(w, h) * 0.15f
                                        val lx = normalized.x
                                        val ly = normalized.y
                                        val l = minOf(hit.left, hit.right)
                                        val t = minOf(hit.top, hit.bottom)
                                        val r = maxOf(hit.left, hit.right)
                                        val b = maxOf(hit.top, hit.bottom)

                                        val nearLeft   = kotlin.math.abs(lx - l) < cornerZone
                                        val nearRight  = kotlin.math.abs(lx - r) < cornerZone
                                        val nearTop    = kotlin.math.abs(ly - t) < cornerZone
                                        val nearBottom = kotlin.math.abs(ly - b) < cornerZone

                                        dragMode = when {
                                            nearLeft  && nearTop    -> DragMode.Resize(EditorViewModel.Corner.TOP_LEFT)
                                            nearRight && nearTop    -> DragMode.Resize(EditorViewModel.Corner.TOP_RIGHT)
                                            nearLeft  && nearBottom -> DragMode.Resize(EditorViewModel.Corner.BOTTOM_LEFT)
                                            nearRight && nearBottom -> DragMode.Resize(EditorViewModel.Corner.BOTTOM_RIGHT)
                                            else                     -> DragMode.Move
                                        }
                                    } else {
                                        // 新建框
                                        isDraggingNew = true
                                        dragStart = offset
                                        dragEnd = offset
                                    }
                                },
                                onDrag = { change, _ ->
                                    if (isDraggingExisting && dragMaskId != null) {
                                        val deltaX = change.position.x - change.previousPosition().x
                                        val deltaY = change.position.y - change.previousPosition().y
                                        val normDX = deltaX / canvasSize.width
                                        val normDY = deltaY / canvasSize.height
                                        when (val mode = dragMode) {
                                            is DragMode.Move -> viewModel.moveMask(dragMaskId!!, normDX, normDY)
                                            is DragMode.Resize -> viewModel.resizeMask(dragMaskId!!, mode.corner, normDX, normDY)
                                            null -> {}
                                        }
                                    } else if (isDraggingNew) {
                                        dragEnd = change.position
                                    }
                                },
                                onDragEnd = {
                                    if (isDraggingNew) {
                                        val left   = minOf(dragStart.x, dragEnd.x) / canvasSize.width
                                        val top    = minOf(dragStart.y, dragEnd.y) / canvasSize.height
                                        val right  = maxOf(dragStart.x, dragEnd.x) / canvasSize.width
                                        val bottom = maxOf(dragStart.y, dragEnd.y) / canvasSize.height
                                        if (right - left > 0.02f && bottom - top > 0.02f) {
                                            viewModel.addMask(left, top, right, bottom)
                                        }
                                    }
                                    isDraggingNew = false
                                    isDraggingExisting = false
                                    dragMaskId = null
                                    dragMode = null
                                }
                            )
                        }
                ) {
                    // 图片/视频预览
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
                        val cornerHandlePx = 10.dp.toPx()
                        val borderPx = 2.dp.toPx()
                        val selectedBorderPx = 3.dp.toPx()

                        viewModel.masks.forEach { mask ->
                            val isSelected = mask.id == viewModel.selectedMaskId
                            val l = minOf(mask.left, mask.right) * size.width
                            val t = minOf(mask.top, mask.bottom) * size.height
                            val r = maxOf(mask.left, mask.right) * size.width
                            val b = maxOf(mask.top, mask.bottom) * size.height
                            val w = r - l
                            val h = b - t

                            // 半透明填充
                            drawRect(
                                color = if (isSelected) Color(0xFF2196F3).copy(alpha = 0.25f)
                                        else Color.Red.copy(alpha = 0.25f),
                                topLeft = Offset(l, t),
                                size = Size(w, h)
                            )

                            // 虚线边框（未选中）
                            if (!isSelected) {
                                drawRect(
                                    color = Color.Red,
                                    topLeft = Offset(l, t),
                                    size = Size(w, h),
                                    style = Stroke(
                                        width = borderPx,
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))
                                    )
                                )
                            }

                            // 实线边框（选中）
                            if (isSelected) {
                                drawRect(
                                    color = Color(0xFF2196F3),
                                    topLeft = Offset(l, t),
                                    size = Size(w, h),
                                    style = Stroke(width = selectedBorderPx)
                                )

                                // 四个角把手
                                val corners = listOf(
                                    Offset(l, t), Offset(r, t),
                                    Offset(l, b), Offset(r, b)
                                )
                                corners.forEach { corner ->
                                    drawCircle(
                                        color = Color(0xFF2196F3),
                                        radius = cornerHandlePx,
                                        center = corner
                                    )
                                    drawCircle(
                                        color = Color.White,
                                        radius = cornerHandlePx * 0.5f,
                                        center = corner
                                    )
                                }
                            }
                        }

                        // 当前正在拖拽的新框
                        if (isDraggingNew) {
                            val left   = minOf(dragStart.x, dragEnd.x)
                            val top    = minOf(dragStart.y, dragEnd.y)
                            val width  = kotlin.math.abs(dragEnd.x - dragStart.x)
                            val height = kotlin.math.abs(dragEnd.y - dragStart.y)

                            drawRect(
                                color = Color(0xFF2196F3).copy(alpha = 0.3f),
                                topLeft = Offset(left, top),
                                size = Size(width, height)
                            )
                            drawRect(
                                color = Color(0xFF2196F3),
                                topLeft = Offset(left, top),
                                size = Size(width, height),
                                style = Stroke(width = 2.dp.toPx())
                            )
                        }
                    }
                }
            }
        }
    }
}

sealed class DragMode {
    data object Move : DragMode()
    data class Resize(val corner: EditorViewModel.Corner) : DragMode()
}
