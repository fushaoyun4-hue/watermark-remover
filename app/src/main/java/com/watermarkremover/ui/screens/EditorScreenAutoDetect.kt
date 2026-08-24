package com.watermarkremover.ui.screens

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.elevatedCard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.watermarkremover.inference.VideoProcessor
import com.watermarkremover.ui.theme.WatermarkRemoverTheme
import kotlinx.coroutines.flow.collectLatest

/**
 * 自动检测水印的编辑屏幕
 * 支持自动识别水印/字幕，无需手动框选
 */
@Composable
fun EditorScreenAutoDetect(
    videoUri: Uri,
    onProcessed: (Uri) -> Unit,
    viewModel: EditorViewModel = viewModel()
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(viewModel.state) }
    
    // 监听状态变化
    LaunchedEffect(viewModel.state) {
        state = viewModel.state
    }
    
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
                            Icon(Icons.Default.ArrowBack, "返回")
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
                if (state.videoBitmap != null) {
                    Image(
                        bitmap = state.videoBitmap!!,
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
                        progress = state.progress / 100f,
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
                Button(
                    onClick = { viewModel.processVideo(videoUri, context) },
                    enabled = state.statusText != "处理中...",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(16.dp)
                ) {
                    Text(if (state.statusText == "处理中...") "处理中..." else "开始自动去除水印")
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