package com.watermarkremover.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
        var bottom: Float
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

    /** 正在拖拽的临时框（null = 没有在画新框） */
    var pendingMask by mutableStateOf<MaskRect?>(null)
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

    /** 媒体原始宽高比（宽/高），用于画面自适应 */
    var mediaAspectRatio by mutableStateOf<Float?>(null)
        private set

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
        masks = masks + p
        pendingMask = null
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

    fun removeMask(id: Int) {
        masks = masks.filter { it.id != id }
    }

    fun clearMasks() {
        masks = emptyList()
        pendingMask = null
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

        val androidRects = masks.map { it.toRectF() }

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
                        videoProcessor.processImage(bitmap, androidRects)
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
    var dragStart by remember { mutableStateOf(Offset.Zero) }

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
                retriever.release()
                previewBitmap = bitmap
                // 从视频元数据获取尺寸
                try {
                    val wStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    val hStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    videoWidth = wStr?.toIntOrNull() ?: (bitmap?.width ?: 1920)
                    videoHeight = hStr?.toIntOrNull() ?: (bitmap?.height ?: 1080)
                } catch (_: Exception) {
                    videoWidth = bitmap?.width ?: 1920
                    videoHeight = bitmap?.height ?: 1080
                }
                if (videoWidth > 0 && videoHeight > 0) {
                    viewModel.mediaAspectRatio = videoWidth.toFloat() / videoHeight.toFloat()
                }
            } catch (e: Exception) {
                // fallback：16:9
                viewModel.mediaAspectRatio = 16f / 9f
            }
        } else {
            // 图片：从 AsyncImage 加载时无法直接获取尺寸，用 PlaceHolder 方案
            // 先用 4:3 作为 fallback，图片加载后更新
            viewModel.mediaAspectRatio = 4f / 3f
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
                            viewModel.mediaAspectRatio = imageWidth.toFloat() / imageHeight.toFloat()
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
                        .padding(16.dp)
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
                                text = "👆 在画面上拖动框选水印区域，可选多个",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        viewModel.pendingMask != null -> {
                            // 有待确认的框，显示勾选提示
                            Text(
                                text = "✅ 请点击右上角 ✓ 确认此区域，或点击 ✕ 重新框选",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        else -> {
                            Text(
                                text = "✅ 已框选 ${viewModel.masks.size} 个区域，点击绿色区域可删除",
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
                // 根据实际比例显示，填满屏幕宽度，高度自适应
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            // 如果已有实际比例，用实际比例；否则填满可用高度
                            if (viewModel.mediaAspectRatio != null) {
                                Modifier.aspectRatio(viewModel.mediaAspectRatio!!)
                            } else {
                                Modifier.fillMaxHeight(0.7f)
                            }
                        )
                        .padding(8.dp)
                        .onSizeChanged { size ->
                            canvasSize = Size(size.width.toFloat(), size.height.toFloat())
                        }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitPointerEvent()
                                val startPos = down.changes.first().position
                                val nx0 = startPos.x / canvasSize.width
                                val ny0 = startPos.y / canvasSize.height

                                val hitMask = viewModel.masks.find { m ->
                                    nx0 >= minOf(m.left, m.right) - 0.02f &&
                                    nx0 <= maxOf(m.left, m.right) + 0.02f &&
                                    ny0 >= minOf(m.top,  m.bottom) - 0.02f &&
                                    ny0 <= maxOf(m.top,  m.bottom) + 0.02f
                                }

                                if (hitMask != null) {
                                    // 点击已确认框 → 删除
                                    viewModel.removeMask(hitMask.id)
                                } else {
                                    // 空白处开始拖拽新建框
                                    dragStart = startPos
                                    viewModel.cancelPendingMask()

                                    var dragEnded = false
                                    while (!dragEnded) {
                                        val event = awaitPointerEvent()
                                        event.changes.forEach { ch -> ch.consume() }
                                        val pos = event.changes.first().position
                                        viewModel.updatePendingMask(EditorViewModel.MaskRect(
                                            id = -1,
                                            left   = minOf(dragStart.x, pos.x) / canvasSize.width,
                                            top    = minOf(dragStart.y, pos.y) / canvasSize.height,
                                            right  = maxOf(dragStart.x, pos.x) / canvasSize.width,
                                            bottom = maxOf(dragStart.y, pos.y) / canvasSize.height
                                        ))
                                        if (!event.changes.any { ch -> ch.pressed }) {
                                            dragEnded = true
                                            // 不自动确认，等用户点勾选
                                            viewModel.confirmPendingMask()
                                        }
                                    }
                                }
                            }
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
                        val btnPx   = 16.dp.toPx()

                        // ---- 已确认的框：绿色边框 ----
                        viewModel.masks.forEach { mask ->
                            val l = minOf(mask.left,  mask.right)  * size.width
                            val t = minOf(mask.top,   mask.bottom) * size.height
                            val r = maxOf(mask.left,  mask.right)  * size.width
                            val b = maxOf(mask.top,   mask.bottom) * size.height
                            val w = r - l
                            val h = b - t

                            drawRect(
                                color = Color(0xFF00C853).copy(alpha = 0.22f),
                                topLeft = Offset(l, t),
                                size = Size(w, h)
                            )
                            drawRect(
                                color = Color(0xFF00C853),
                                topLeft = Offset(l, t),
                                size = Size(w, h),
                                style = Stroke(width = borderPx)
                            )
                        }

                        // ---- 待确认的框：蓝色虚线 + 右上角勾叉按钮 ----
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

                            // 右上角：勾（绿色）叉（红色）
                            val btnCenterX = l + w
                            val btnCenterY = t

                            // 绿色勾按钮
                            drawCircle(
                                color = Color(0xFF00C853),
                                radius = btnPx,
                                center = Offset(btnCenterX - btnPx * 2.2f, btnCenterY + btnPx * 1.2f)
                            )
                            // 红色叉按钮
                            drawCircle(
                                color = Color(0xFFFF5252),
                                radius = btnPx,
                                center = Offset(btnCenterX - btnPx * 0.4f, btnCenterY + btnPx * 1.2f)
                            )
                        }
                    }

                    // ---- 待确认框右上角的勾叉按钮（Overlay 绝对定位） ----
                    viewModel.pendingMask?.let { p ->
                        BoxWithConstraints(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            val boxW = constraints.maxWidth.toFloat()
                            val boxH = constraints.maxHeight.toFloat()
                            val l = minOf(p.left,  p.right) * boxW
                            val t = minOf(p.top,   p.bottom) * boxH
                            val w = kotlin.math.abs(p.right - p.left) * boxW

                            // 绿色勾选按钮（框右上角）
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .offset(x = (l + w - 42).dp, y = (t + 2).dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF00C853))
                                    .clickable { viewModel.acceptPendingMask() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "确认",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            // 红色叉号按钮（勾按钮右边）
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .offset(x = (l + w - 6).dp, y = (t + 2).dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFF5252))
                                    .clickable { viewModel.rejectPendingMask() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "取消",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
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
