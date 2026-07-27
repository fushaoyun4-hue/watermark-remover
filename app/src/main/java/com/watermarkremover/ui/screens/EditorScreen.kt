package com.watermarkremover.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
 * 编辑页：支持多选框 + 拖拽调整 + 删除单个区域
 *
 * 交互设计：
 * - 拖拽空白区域 → 新增临时框（蓝色虚线）
 * - 松手 → 框固定下来并显示 ✅ 确认标记，同时可以继续拖拽新增更多
 * - 点击未确认的框（蓝色实线）→ 该框进入"已确认"状态，显示 ✅，锁定不可再拖
 * - 点击已确认的框 → 删除该框
 * - 清除按钮 → 清空所有框
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    private val videoProcessor: VideoProcessor
) : ViewModel() {

    /** 归一化坐标的矩形框 */
    data class MaskRect(
        val id: Int,
        var left: Float,   // 归一化 0~1
        var top: Float,
        var right: Float,
        var bottom: Float
    ) {
        val width  get() = kotlin.math.abs(right - left)
        val height get() = kotlin.math.abs(bottom - top)
        fun toRectF() = android.graphics.RectF(
            minOf(left, right), minOf(top, bottom),
            maxOf(left, right), maxOf(top, bottom)
        )
    }

    var masks by mutableStateOf(listOf<MaskRect>())
        private set

    /** 当前正在拖拽但尚未固定的临时框（null = 没有在画新框） */
    var pendingMask by mutableStateOf<MaskRect?>(null)
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

    /** 在临时框松开时，将其正式加入 masks */
    fun confirmPendingMask() {
        val p = pendingMask ?: return
        if (p.width > 0.02f && p.height > 0.02f) {
            masks = masks + MaskRect(
                id = nextId++,
                left = minOf(p.left, p.right),
                top  = minOf(p.top, p.bottom),
                right= maxOf(p.left, p.right),
                bottom=maxOf(p.top, p.bottom)
            )
        }
        pendingMask = null
    }

    /** 取消当前临时框 */
    fun cancelPendingMask() {
        pendingMask = null
    }

    /** 删除已确认的框 */
    fun removeMask(id: Int) {
        masks = masks.filter { it.id != id }
    }

    /** 清空所有已确认框 */
    fun clearMasks() {
        masks = emptyList()
        pendingMask = null
    }

    /** 开始处理 */
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

        val androidRects = masks.map { it.toRectF() }

        viewModelScope.launch {
            try {
                if (mediaType == "image") {
                    // ---- 图片：解码必须在 IO 线程 ----
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

                    // ---- inpaint 在 Default 线程 ----
                    progressPhase = "正在处理..."
                    progress = 30
                    val result = withContext(Dispatchers.Default) {
                        videoProcessor.processImage(bitmap, androidRects)
                    }
                    bitmap.recycle()

                    progress = 80
                    val outputFile = withContext(Dispatchers.IO) {
                        File(File(context.cacheDir, "result_${System.currentTimeMillis()}.jpg")).apply {
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
                    // ---- 视频：Flow collect ----
                    videoProcessor.processVideo(mediaUri, androidRects).collectLatest { state ->
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

// ────────────────────────────────────────────────────────────
//  UI 层
// ────────────────────────────────────────────────────────────

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

    // 拖拽新建框的状态
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var isDragging by remember { mutableStateOf(false) }

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
        // ---- 处理中进度弹窗 ----
        if (viewModel.isProcessing) {
            ProcessingDialog(
                progress = viewModel.progress,
                phase = viewModel.progressPhase,
                onCancel = { /* 暂不支持中途取消 */ }
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
                        // 撤销最后框
                        IconButton(
                            onClick = {
                                if (viewModel.masks.isNotEmpty()) {
                                    viewModel.removeMask(viewModel.masks.last().id)
                                }
                            },
                            enabled = viewModel.masks.isNotEmpty()
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "撤销")
                        }
                        // 清空全部
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
                        .padding(16.dp)
                ) {
                    // 错误提示
                    viewModel.errorMessage?.let { msg ->
                        Text(
                            text = "⚠️ $msg",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    // 状态提示
                    when {
                        viewModel.masks.isEmpty() && viewModel.pendingMask == null -> {
                            Text(
                                text = "👆 在图片上拖动框选水印区域，可选多个",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        viewModel.pendingMask != null -> {
                            Text(
                                text = "⏳ 框已画好 ✅ 请继续框选其他区域，或点击「开始去除」",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        else -> {
                            Text(
                                text = "✅ 已框选 ${viewModel.masks.size} 个区域，点击区域可删除",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
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
                                val normalized = Offset(
                                    offset.x / canvasSize.width,
                                    offset.y / canvasSize.height
                                )
                                // 点击已确认的框 → 删除
                                val hit = viewModel.masks.find { m ->
                                    normalized.x >= minOf(m.left, m.right) - 0.02f &&
                                    normalized.x <= maxOf(m.left, m.right) + 0.02f &&
                                    normalized.y >= minOf(m.top, m.bottom) - 0.02f &&
                                    normalized.y <= maxOf(m.top, m.bottom) + 0.02f
                                }
                                if (hit != null) {
                                    viewModel.removeMask(hit.id)
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    dragStart = offset
                                    isDragging = true
                                    // 取消之前的 pending
                                    viewModel.cancelPendingMask()
                                },
                                onDrag = { change, _ ->
                                    val end = change.position
                                    val left   = minOf(dragStart.x, end.x)
                                    val top    = minOf(dragStart.y, end.y)
                                    val right  = maxOf(dragStart.x, end.x)
                                    val bottom = maxOf(dragStart.y, end.y)
                                    viewModel.pendingMask = EditorViewModel.MaskRect(
                                        id = -1,
                                        left = left / canvasSize.width,
                                        top  = top  / canvasSize.height,
                                        right= right/ canvasSize.width,
                                        bottom=bottom/ canvasSize.height
                                    )
                                },
                                onDragEnd = {
                                    isDragging = false
                                    viewModel.confirmPendingMask()
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
                        val borderPx    = 2.dp.toPx()
                        val handlePx    = 8.dp.toPx()
                        val checkMarkPx = 18.dp.toPx()

                        // ---- 已确认的框：绿色边框 + 右上角勾 ----
                        viewModel.masks.forEach { mask ->
                            val l = minOf(mask.left,  mask.right)  * size.width
                            val t = minOf(mask.top,   mask.bottom) * size.height
                            val r = maxOf(mask.left,  mask.right)  * size.width
                            val b = maxOf(mask.top,   mask.bottom) * size.height
                            val w = r - l
                            val h = b - t

                            // 半透明绿色填充
                            drawRect(
                                color = Color(0xFF00C853).copy(alpha = 0.22f),
                                topLeft = Offset(l, t),
                                size = Size(w, h)
                            )
                            // 实线绿色边框
                            drawRect(
                                color = Color(0xFF00C853),
                                topLeft = Offset(l, t),
                                size = Size(w, h),
                                style = Stroke(width = borderPx)
                            )

                            // 右上角 ✅ 勾：中心在矩形右上角内侧
                            val cx = r - checkMarkPx * 0.9f
                            val cy = t + checkMarkPx * 0.9f
                            // 圆形背景
                            drawCircle(
                                color = Color(0xFF00C853),
                                radius = checkMarkPx,
                                center = Offset(cx, cy)
                            )
                            drawCircle(
                                color = Color.White,
                                radius = checkMarkPx * 0.7f,
                                center = Offset(cx, cy)
                            )
                            // 勾（简化为一条斜线+圆点表示）
                            drawCircle(
                                color = Color(0xFF00C853),
                                radius = checkMarkPx * 0.35f,
                                center = Offset(cx, cy)
                            )
                        }

                        // ---- 正在拖拽的临时框：蓝色虚线 ----
                        viewModel.pendingMask?.let { p ->
                            val l = minOf(p.left, p.right) * size.width
                            val t = minOf(p.top,  p.bottom) * size.height
                            val w = kotlin.math.abs(p.right - p.left) * size.width
                            val h = kotlin.math.abs(p.bottom - p.top) * size.height

                            drawRect(
                                color = Color(0xFF2196F3).copy(alpha = 0.25f),
                                topLeft = Offset(l, t),
                                size = Size(w, h)
                            )
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
                }
            }
        }
    }
}

/**
 * 动态进度弹窗（仿"开拍"风格）
 * - 卡片居中，圆角
 * - 标题 + 百分比数字
 * - LinearProgressIndicator 进度条
 * - 阶段文字
 */
@Composable
fun ProcessingDialog(
    progress: Int,
    phase: String,
    onCancel: () -> Unit
) {
    Dialog(
        onDismissRequest = { /* 不允许点击外部关闭 */ },
        properties = DialogProperties(
            dismissOnBackPress = true,
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
                // 标题
                Text(
                    text = "正在处理",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(24.dp))

                // 百分比数字（大字号）
                Text(
                    text = "$progress%",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 进度条
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 阶段描述
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
