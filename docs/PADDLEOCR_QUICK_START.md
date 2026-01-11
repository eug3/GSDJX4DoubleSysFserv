# PaddleOCR 快速参考

## 核心 API

```kotlin
// 初始化
OcrHelper.init(context)

// 识别
val result = OcrHelper.recognizeText(bitmap)

// 获取结果
println(result.text)              // ✅ 排版好的完整文本
result.blocks.forEach {           // 逐块遍历
    println("${it.text} (${it.confidence})")
}

// 清理
OcrHelper.close()
```

---

## 集成到 GeckoActivity

### 方案 1：直接调用（同步）
```kotlin
// ❌ 不要这样做 - 会卡顿
val result = runBlocking {
    ocrHelper.recognizeText(bitmap)
}
```

### 方案 2：使用 ViewModel（推荐）
```kotlin
// ✅ 正确做法
viewModel.recognizeImage(bitmap)
observeResult { result ->
    selectedText.value = result.text
}
```

### 方案 3：Coroutine（推荐）
```kotlin
// ✅ 使用协程
lifecycleScope.launch {
    val result = ocrHelper.recognizeText(bitmap)
    selectedText.value = result.text
}
```

---

## 数据结构

```kotlin
// 完整结果
OcrResult(
    text = "排版好的\n完整文本",
    blocks = listOf(
        TextBlock("排版好的", 0.85, 0),
        TextBlock("完整文本", 0.87, 1)
    ),
    rawText = "排版好的\n完整文本"
)

// 单个文本块
TextBlock(
    text = "排版好的",        // 识别的文字
    confidence = 0.85,       // 置信度 0.0-1.0
    blockIndex = 0           // 块索引
)
```

---

## 常见操作

### 1. 显示识别结果
```kotlin
textView.text = result.text
```

### 2. 过滤低置信度
```kotlin
val filtered = result.blocks.filter { it.confidence > 0.8 }
textView.text = filtered.joinToString("\n") { it.text }
```

### 3. 统计识别信息
```kotlin
Log.d("OCR", "总文本块: ${result.blocks.size}")
Log.d("OCR", "平均置信度: ${result.blocks.map { it.confidence }.average()}")
```

### 4. 导出为 CSV
```kotlin
result.blocks.forEach { block ->
    csv += "${block.blockIndex},${block.text},${block.confidence}\n"
}
```

---

## 错误处理

```kotlin
try {
    val result = ocrHelper.recognizeText(bitmap)
    // 处理结果
} catch (e: IllegalStateException) {
    // OcrHelper 未初始化
    ocrHelper.init(context)
} catch (e: Exception) {
    Log.e("OCR", "识别失败", e)
}
```

---

## 性能建议

| 操作 | 建议 |
|------|------|
| 初始化 | 应用启动时执行一次 |
| 识别 | 使用后台线程（Coroutine） |
| 图像大小 | 建议 < 2560x1920 |
| 批量识别 | 使用线程池，避免阻塞 |
| 释放资源 | Activity 销毁时调用 close() |

---

## 故障排除

### 问题：模型加载失败
```
E/OcrHelper: 初始化 PaddleOCR 失败
E/OcrHelper: java.io.FileNotFoundException
```
**解决：** 检查 assets/models/ 下的 3 个 .nb 文件是否存在

### 问题：JNI 库加载失败
```
E/SimplePaddleOcr: 模型加载失败
E/SimplePaddleOcr: java.lang.UnsatisfiedLinkError
```
**解决：** 检查 jniLibs/arm64-v8a/ 下的 .so 文件

### 问题：超时或内存不足
```
java.lang.OutOfMemoryError
```
**解决：** 缩小输入图像尺寸，或提高 maxSideLen 参数

---

## 配置选项

```kotlin
// 自定义线程数
ocrPredictor = PaddleOcrPredictor(
    detModelPath = ...,
    recModelPath = ...,
    clsModelPath = ...,
    cpuThreadNum = 2  // 低端设备用 2，高端用 4
)

// 自定义识别参数
val result = ocrPredictor.runImage(
    bitmap,
    maxSideLen = 960,   // 检测输入大小
    runDet = 1,         // 是否检测
    runCls = 1,         // 是否分类
    runRec = 1          // 是否识别
)
```

---

## 文件位置

```
📂 app/src/main/
├── 📂 assets/
│   ├── 📂 dict/
│   │   └── ppocr_keys_v1.txt
│   └── 📂 models/
│       ├── ch_PP-OCRv3_det_slim_opt.nb
│       ├── ch_PP-OCRv3_rec_slim_opt.nb
│       └── ch_ppocr_mobile_v2.0_cls_slim_opt.nb
├── 📂 java/.../ocr/
│   ├── OcrHelper.kt
│   ├── PaddleOcrPredictor.kt
│   ├── SimplePaddleOcrPredictor.kt
│   ├── OcrResult.kt
│   └── TextBlock.kt
└── 📂 jniLibs/arm64-v8a/
    ├── libpaddle_lite_jni.so
    └── libpaddle_light_api_shared.so
```

---

## 日志调试

```kotlin
// 启用详细日志
// 在 logcat 中搜索这些标签：
// - OcrHelper
// - PaddleOcrPredictor
// - SimplePaddleOcr

// 例如：
adb logcat | grep "OcrHelper\|PaddleOcr"
```

---

## 版本信息

- **PaddleOCR 版本**: v3.0
- **PP-OCRv3 模型**: 轻量级版本
- **Paddle-Lite 版本**: v2.10
- **最低 SDK**: Android 26
- **目标 SDK**: Android 36
- **支持架构**: ARM64-v8a

---

## 许可证

- PaddleOCR: Apache 2.0
- Paddle-Lite: Apache 2.0

---

*完整文档请参考 PADDLEOCR_USAGE.md*
