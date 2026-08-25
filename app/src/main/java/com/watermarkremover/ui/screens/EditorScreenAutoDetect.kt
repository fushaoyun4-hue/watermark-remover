package com.watermarkremover.ui.screens

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.watermarkremover.ui.theme.WatermarkRemoverTheme

/**
 * 自动检测水印的编辑屏幕
 * 支持自动识别水印/字幕，无需手动框选
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreenAutoDetect(
    videoUri: Uri,
    onProcessed: (Uri) -> Unit,
    viewModel: EditorViewModel = viewModel()
) {
    val context = LocalContext.current
    // 订阅 State 变化（Compose 会自动重组）
    val state = viewModel.state.value

    // 当处理完成且有结果 URI 时，调用回调
    LaunchedEffect(state.resultUri) {
        if (state.resultUri != null) {
            onProcessed(state.resultUri!!)
            // 重置状态，允许再次处理
            viewModel.resetAutoDetectState()
        }
    }

    WatermarkRemoverTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("AI 自动去水印") },
                    navigationIcon = {
                        IconButton(onClick = { /* 返回 */ }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 视频预览
                val previewBitmap = state.videoBitmap
                if (previewBitmap != null) {
                    Image(
                        bitmap = previewBitmap.asImageBitmap(),
                        contentDescription = "视频预览",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                    )
                }

                // 进度显示
                if (state.progress > 0) {
                    Text(
                        text = "处理进度: ${state.progress}%",
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.primary
                    )

                    LinearProgressIndicator(
                        progress = (state.progress / 100f).coerceIn(0f, 1f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .padding(top = 8.dp)
                    )
                }

                // 状态信息
                if (state.statusText.isNotEmpty()) {
                    Text(
                        text = state.statusText,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }

                // 处理按钮
                val isProcessing = state.statusText == "处理中..."
                Button(
                    onClick = { viewModel.processVideo(videoUri, context) },
                    enabled = !isProcessing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(16.dp)
                ) {
                    Text(if (isProcessing) "处理中..." else "开始自动去除水印")
                }

                // 提示信息
                if (state.statusText.isEmpty()) {
                    Text(
                        text = "AI 模型将自动识别视频中的水印和字幕\n无需手动框选区域",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}