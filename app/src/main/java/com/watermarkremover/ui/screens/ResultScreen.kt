package com.watermarkremover.ui.screens

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

import com.watermarkremover.ui.theme.WatermarkRemoverTheme



/**
 * 结果页：处理完成，提供保存按钮（不显示对比）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    originalUri: String,
    processedUri: String,
    mediaType: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isSaving by remember { mutableStateOf(false) }
    var savedPath by remember { mutableStateOf<String?>(null) }
    var saveProgress by remember { mutableStateOf(0) }  // 保存进度 0-100

    // 统一的保存逻辑（不再通过 callback，避免 rememberCoroutineScope 失效问题）
    fun doSave() {
        if (isSaving || savedPath != null) return
        isSaving = true
        saveProgress = 0

        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    val sourceUri = Uri.parse(processedUri)
                    val fileName = "watermark_removed_${System.currentTimeMillis()}"
                    saveProgress = 30
                    if (mediaType == "video") {
                        saveVideoToGallery(context, sourceUri, fileName)
                    } else {
                        saveImageToGallery(context, sourceUri, fileName)
                    }.also { saveProgress = 100 }
                }
                isSaving = false
                savedPath = saved
                if (saved != null) {
                    Toast.makeText(context, "已保存到相册", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                isSaving = false
                Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Android 9- 需要运行时权限
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            doSave()
        } else {
            Toast.makeText(context, "需要存储权限才能保存", Toast.LENGTH_SHORT).show()
        }
    }

    fun saveToGallery() {
        if (isSaving || savedPath != null) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 不需要权限
            doSave()
        } else {
            // Android 9- 检查权限
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                doSave()
            } else {
                permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }



    WatermarkRemoverTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("处理完成") }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // 成功图标
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(80.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "水印去除完成",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (savedPath != null) {
                    Text(
                        text = "✓ 已保存到相册",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                } else if (isSaving) {
                    // 保存进度指示
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "保存中... ${saveProgress}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        @Suppress("DEPRECATION")
                        LinearProgressIndicator(
                            progress = (saveProgress / 100f).coerceIn(0f, 1f),
                            modifier = Modifier.fillMaxWidth(0.8f).height(6.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    }
                } else {
                    Text(
                        text = "点击下方按钮保存到手机相册",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                // 保存按钮
                Button(
                    onClick = { saveToGallery() },
                    enabled = !isSaving && savedPath == null,
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("保存中 ${saveProgress}%...")
                    } else if (savedPath != null) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("已保存", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Filled.SaveAlt, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("保存到相册", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 继续处理按钮
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(0.8f)
                ) {
                    Text("继续处理其他文件")
                }
            }
        }
    }
}

/**
 * 保存图片到相册
 */
private fun saveImageToGallery(context: Context, sourceUri: Uri, fileName: String): String? {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 使用 MediaStore
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$fileName.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/WatermarkRemover")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ) ?: return null

            context.contentResolver.openOutputStream(uri)?.use { output ->
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    input.copyTo(output)
                }
            }

            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, contentValues, null, null)

            Environment.DIRECTORY_PICTURES + "/WatermarkRemover/$fileName.jpg"
        } else {
            // Android 9 及以下
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val appDir = File(picturesDir, "WatermarkRemover")
            appDir.mkdirs()

            val destFile = File(appDir, "$fileName.jpg")
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            // 通知相册
            val mediaScanIntent = android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            mediaScanIntent.data = Uri.fromFile(destFile)
            context.sendBroadcast(mediaScanIntent)

            destFile.absolutePath
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

/**
 * 保存视频到相册
 */
private fun saveVideoToGallery(context: Context, sourceUri: Uri, fileName: String): String? {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "$fileName.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/WatermarkRemover")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ) ?: return null

            context.contentResolver.openOutputStream(uri)?.use { output ->
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    input.copyTo(output)
                }
            }

            contentValues.clear()
            contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, contentValues, null, null)

            Environment.DIRECTORY_MOVIES + "/WatermarkRemover/$fileName.mp4"
        } else {
            val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val appDir = File(moviesDir, "WatermarkRemover")
            appDir.mkdirs()

            val destFile = File(appDir, "$fileName.mp4")
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            val mediaScanIntent = android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            mediaScanIntent.data = Uri.fromFile(destFile)
            context.sendBroadcast(mediaScanIntent)

            destFile.absolutePath
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
