package com.watermarkremover.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
 * 编辑页：视频预览 + 可拖拽矩形框（Mask）选择
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    private val videoProcessor: VideoProcessor
) : ViewModel() {

    data class MaskRect(
        var left: Float,
        var top: Float,
        var right: Float,
        var bottom: Float
    )

    var masks by mutableStateOf(listOf<MaskRect>())
        private set

    var currentMask by mutableStateOf<MaskRect?>(null)
        private set

    var isProcessing by mutableStateOf(false)
        private set

    var progress by mutableStateOf(0)
        private set

    var progressPhase by mutableStateOf("")
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    fun addMask(left: Float, top: Float, right: Float, bottom: Float) {
        masks = masks + MaskRect(
            left = minOf(left, right),
            top = minOf(top, bottom),
            right = maxOf(left, right),
            bottom = maxOf(top, bottom)
        )
    }

    fun removeLastMask() {
        if (masks.isNotEmpty()) {
            masks = masks.dropLast(1)
        }
    }

    fun clearMasks() {
        masks = emptyList()
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

        val androidRects = masks.map {
            android.graphics.RectF(it.left, it.top, it.right, it.bottom)
        }

        viewModelScope.launch {
            try {
                if (mediaType == "image") {
                    // 图片处理
                    val inputStream = context.contentResolver.openInputStream(mediaUri)
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream?.close()

                    if (bitmap == null) {
                        errorMessage = "无法读取图片"
                        isProcessing = false
                        return@launch
                    }

                    val result = videoProcessor.processImage(bitmap, androidRects)

                    // 保存结果
                    val outputFile = File(context.cacheDir, "result_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(outputFile).use { fos ->
                        result.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                    }

                    onComplete(mediaUri.toString(), Uri.fromFile(outputFile).toString())
                    isProcessing = false

                } else {
                    // 视频处理
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

    var imageSize by remember { mutableStateOf(IntSize.Zero) }
    var canvasOffset by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    // 拖拽状态
    var isDragging by remember { mutableStateOf(false) }
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var dragEnd by remember { mutableStateOf(Offset.Zero) }

    // 显示视频预览（第一帧）
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(mediaUri) {
        if (mediaType == "video") {
            // 提取视频第一帧作为预览
            try {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(context, Uri.parse(mediaUri))
                val bitmap = retriever.getFrameAtTime(0)
                retriever.release()
                previewBitmap = bitmap
            } catch (e: Exception) {
                // ignore
            }
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
                        TextButton(onClick = { viewModel.clearMasks() }) {
                            Text("清除")
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
                    // 提示
                    if (viewModel.masks.isEmpty()) {
                        Text(
                            text = "👆 在图片上拖动，框选水印区域（可框选多个）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    } else {
                        Text(
                            text = "已选择 ${viewModel.masks.size} 个区域，点击开始去除",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
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
                                color = Color.White
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
                        .aspectRatio(
                            if (mediaType == "video") "16:9"
                            else "4:3"
                        )
                        .padding(8.dp)
                        .onSizeChanged { size ->
                            imageSize = size
                            canvasSize = Size(size.width.toFloat(), size.height.toFloat())
                        }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    isDragging = true
                                    dragStart = offset
                                    dragEnd = offset
                                },
                                onDrag = { change, _ ->
                                    dragEnd = change.position
                                },
                                onDragEnd = {
                                    isDragging = false
                                    if (dragEnd.x > dragStart.x + 20 && dragEnd.y > dragStart.y + 20) {
                                        val left = minOf(dragStart.x, dragEnd.x) / canvasSize.width
                                        val top = minOf(dragStart.y, dragEnd.y) / canvasSize.height
                                        val right = maxOf(dragStart.x, dragEnd.x) / canvasSize.width
                                        val bottom = maxOf(dragStart.y, dragEnd.y) / canvasSize.height
                                        viewModel.addMask(left, top, right, bottom)
                                    }
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
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = "视频",
                                tint = Color.White,
                                modifier = Modifier.size(64.dp)
                            )
                        }
                    }

                    // 蒙版区域（红色半透明矩形）
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        // 已添加的蒙版
                        viewModel.masks.forEach { mask ->
                            drawRect(
                                color = Color.Red.copy(alpha = 0.3f),
                                topLeft = Offset(
                                    mask.left * canvasSize.width,
                                    mask.top * canvasSize.height
                                ),
                                size = Size(
                                    (mask.right - mask.left) * canvasSize.width,
                                    (mask.bottom - mask.top) * canvasSize.height
                                )
                            )
                            drawRect(
                                color = Color.Red,
                                topLeft = Offset(
                                    mask.left * canvasSize.width,
                                    mask.top * canvasSize.height
                                ),
                                size = Size(
                                    (mask.right - mask.left) * canvasSize.width,
                                    (mask.bottom - mask.top) * canvasSize.height
                                ),
                                style = Stroke(width = 2.dp.toPx())
                            )
                        }

                        // 当前拖拽的蒙版
                        if (isDragging) {
                            val left = minOf(dragStart.x, dragEnd.x)
                            val top = minOf(dragStart.y, dragEnd.y)
                            val width = kotlin.math.abs(dragEnd.x - dragStart.x)
                            val height = kotlin.math.abs(dragEnd.y - dragStart.y)

                            drawRect(
                                color = Color.Blue.copy(alpha = 0.3f),
                                topLeft = Offset(left, top),
                                size = Size(width, height)
                            )
                            drawRect(
                                color = Color.Blue,
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
