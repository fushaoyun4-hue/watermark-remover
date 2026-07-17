# 一键去水印

Android 轻量化去水印工具，支持图片和视频。

## 技术方案

- **检测层**：用户手动矩形框选（无 AI 模型）
- **修复层**：OpenCV `Imgproc.inpaint`（Telea 算法）
- **视频处理**：FFmpegKit 抽帧 → OpenCV 逐帧修复 → FFmpeg 合成
- **依赖**：opencv-android + ffmpeg-kit-full-gpl

## 特点

- ✅ 离线运行，无需网络
- ✅ 处理快速，不卡顿
- ✅ 不发热，不耗电
- ✅ 无 AI 模型，包体积小

## 开发指南

### 环境要求

本项目设计为 **云端开发**（本地 8GB 核显无法编译）：

1. **GitHub Codespace**（推荐，2核4GB免费）
2. **Android Studio**（本地，IDEA 预装）
3. **本地 Git**（commit/push/pull 即可）

### 快速开始

```bash
# 克隆仓库
git clone git@github.com:fushaoyun4-hue/watermark-remover.git
cd watermark-remover

# 在 Codespace 或 Android Studio 中
./gradlew assembleDebug

# APK 输出在 app/build/outputs/apk/debug/
```

### 云端开发流程

```
本地修改代码 → git push
    ↓
GitHub Actions 自动构建
    ↓
下载 APK 或 Codespace 直接运行
```

## 项目结构

```
watermark-remover/
├── app/src/main/java/com/watermarkremover/
│   ├── inference/
│   │   └── VideoProcessor.kt   ← 核心处理（OpenCV + FFmpeg）
│   ├── ui/
│   │   ├── MainActivity.kt     ← 入口
│   │   ├── MainNavigation.kt   ← 导航
│   │   └── screens/
│   │       ├── HomeScreen.kt   ← 首页（导入媒体）
│   │       ├── EditorScreen.kt ← 编辑页（框选水印）
│   │       └── ResultScreen.kt ← 结果页（对比+保存）
│   └── WatermarkApp.kt         ← Hilt Application
└── .github/workflows/
    └── build.yml               ← 云端构建（Codespace）
```

## 界面预览

```
┌─────────────────────────────┐
│      一键去水印              │
│                             │
│   导入视频     导入图片       │
│                             │
│  ✓ 离线运行  ✓ 不发热  ✓ 快速  │
└─────────────────────────────┘
```

## License

MIT
