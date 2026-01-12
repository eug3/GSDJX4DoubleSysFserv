# PP-OCRv5 + ONNX Runtime Mobile 集成指南

本项目已集成 PP-OCRv5 Mobile 模型与 ONNX Runtime Mobile，用于在 Android 设备上进行高效的 OCR 文字识别。

## 概述

### 模型版本

| 模型 | 版本 | 格式 | 来源 |
|------|------|------|------|
| 检测模型 (Detection) | PP-OCRv5 Mobile | ONNX | [PaddlePaddle/PP-OCRv5_mobile_det](https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det) |
| 识别模型 (Recognition) | PP-OCRv5 Mobile | ONNX | [PaddlePaddle/PP-OCRv5_mobile_rec](https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_rec) |
| 分类模型 (Classification) | PP-OCRv5 Mobile | ONNX | 可选 |

### 推理引擎

- **ONNX Runtime Mobile 1.20.0** - 跨平台推理引擎
- **OpenCV 4.9.0** - 用于图像预处理和后处理

## 环境准备

### Android 项目配置

1. **添加依赖** (已在 `app/build.gradle.kts` 中配置):

```kotlin
dependencies {
    // ONNX Runtime Mobile for PP-OCRv5
    implementation("com.microsoft.onnxruntime:onnxruntime-mobile:1.20.0")

    // OpenCV for image processing
    implementation("org.opencv:opencv:4.9.0")
}
```

2. **支持的架构**:
- `arm64-v8a` (64 位 ARM)
- `armeabi-v7a` (32 位 ARM)

## 模型转换

### 步骤 1: 安装 Python 依赖

```bash
# 创建虚拟环境
python3 -m venv ocr_env
source ocr_env/bin/activate  # Linux/Mac
# 或 ocr_env\Scripts\activate  # Windows

# 安装依赖
pip install paddlepaddle paddle2onnx onnx==1.16.0 onnxruntime
```

### 步骤 2: 运行转换脚本

```bash
cd /path/to/GSDJX4DoubleSysFserv

# 转换所有模型（检测、识别、分类）
python scripts/convert_ppocrv5_to_onnx.py

# 或仅转换特定模型
python scripts/convert_ppocrv5_to_onnx.py --models det rec
```

**脚本参数**:
| 参数 | 默认值 | 说明 |
|------|--------|------|
| `--models-dir` | `./models` | Paddle 模型下载目录 |
| `--output-dir` | `./onnx_models` | ONNX 模型输出目录 |
| `--models` | `det rec cls` | 要转换的模型类型 |
| `--opset` | `14` | ONNX opset 版本 |
| `--skip-download` | `False` | 跳过模型下载 |

### 步骤 3: 复制 ONNX 模型到 Assets

```bash
# 将转换后的 ONNX 模型复制到 Android assets 目录
cp onnx_models/*.onnx app/src/main/assets/models_onnx/
```

## 模型文件结构

```
app/src/main/assets/
├── models_onnx/
│   ├── ppocrv5_mobile_det.onnx    # 检测模型 (~3MB)
│   ├── ppocrv5_mobile_rec.onnx    # 识别模型 (~8MB)
│   └── ppocrv5_mobile_cls.onnx    # 分类模型 (~2MB)
└── dict/
    └── ppocr_keys_v1.txt           # 中文字符字典
```

## 使用方法

### Kotlin 代码示例

```kotlin
import com.guaishoudejia.x4doublesysfserv.ocr.OnnxOcrHelper
import kotlinx.coroutines.runBlocking

class OcrManager {
    private val ocrHelper = OnnxOcrHelper()

    suspend fun initialize(context: Context) {
        ocrHelper.init(context)
    }

    suspend fun recognizeImage(bitmap: Bitmap): String {
        val result = ocrHelper.recognizeText(bitmap)
        return result.text
    }

    suspend fun drawDetectionBoxes(bitmap: Bitmap): Bitmap {
        return ocrHelper.drawDetectionBoxes(bitmap)
    }

    fun cleanup() {
        ocrHelper.close()
    }
}
```

### 在 Activity 中使用

```kotlin
class MainActivity : ComponentActivity() {
    private val ocrHelper = OnnxOcrHelper()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化 OCR
        lifecycleScope.launch {
            ocrHelper.init(this@MainActivity)
        }
    }

    private fun performOcr(bitmap: Bitmap) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = ocrHelper.recognizeText(bitmap)
            withContext(Dispatchers.Main) {
                // 显示结果
                textView.text = result.text
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ocrHelper.close()
    }
}
```

## API 参考

### OnnxOcrHelper

| 方法 | 说明 |
|------|------|
| `suspend fun init(context: Context)` | 初始化 OCR 引擎，加载模型 |
| `fun isReady(): Boolean` | 检查是否已初始化完成 |
| `suspend fun recognizeText(bitmap: Bitmap): OcrResult` | 识别图像中的文字 |
| `suspend fun drawDetectionBoxes(bitmap: Bitmap): Bitmap` | 在图像上绘制检测框 |
| `suspend fun binarizeBitmap(bitmap: Bitmap): Bitmap` | 图像二值化处理 |
| `fun close()` | 释放资源 |

### OcrResult

| 属性 | 类型 | 说明 |
|------|------|------|
| `text` | String | 完整识别文本（换行分隔） |
| `blocks` | List<TextBlock> | 文本块列表 |
| `rawText` | String | 原始识别文本 |

### TextBlock

| 属性 | 类型 | 说明 |
|------|------|------|
| `text` | String | 文本内容 |
| `confidence` | Float | 置信度 (0-1) |
| `blockIndex` | Int | 块索引 |

## 性能参考

基于 PP-OCRv5 Mobile 模型的性能数据：

| 操作 | 耗时 (参考) |
|------|------------|
| 模型加载 | ~500ms |
| 文本检测 | ~150ms |
| 文本识别 | ~80ms/块 |
| 总计 (单张图) | ~300-500ms |

## 常见问题

### 1. 内存不足

如果遇到 OOM 错误，可以尝试：

1. 减小 `DET_LIMIT_SIDE_LEN` 值（默认 960）
2. 降低图像分辨率
3. 关闭分类模型（修改代码禁用 CLS）

### 2. 识别结果不准确

1. 确保使用正确的字典文件 `ppocr_keys_v1.txt`
2. 检查图像预处理是否正确（二值化等）
3. 尝试调整 `DET_DB_THRESH` 和 `DET_DB_BOX_THRESH`

### 3. 模型加载失败

1. 确认 ONNX 模型已正确放入 `assets/models_onnx/` 目录
2. 检查模型文件完整性（文件大小）
3. 查看 Logcat 中的详细错误日志

## 从 PaddleOCR v3 迁移

如果你正在使用 PaddleOCR v3 + Paddle-Lite：

| 组件 | 旧方案 | 新方案 |
|------|--------|--------|
| 模型格式 | `.nb` (Paddle-Lite) | `.onnx` (ONNX) |
| 推理引擎 | Paddle-Lite | ONNX Runtime Mobile |
| OCR Helper | `OcrHelper` | `OnnxOcrHelper` |
| 依赖大小 | ~15MB | ~10MB |

**迁移步骤**:

```kotlin
// 旧代码
import com.guaishoudejia.x4doublesysfserv.ocr.OcrHelper
val ocr = OcrHelper
ocr.init(context)
val result = ocr.recognizeText(bitmap)

// 新代码
import com.guaishoudejia.x4doublesysfserv.ocr.OnnxOcrHelper
val ocr = OnnxOcrHelper()
ocr.init(context)
val result = ocr.recognizeText(bitmap)
```

## 资源链接

- **PP-OCRv5 官方文档**: [PaddleOCR GitHub](https://github.com/PaddlePaddle/PaddleOCR)
- **ONNX Runtime Mobile**: [Microsoft/onnxruntime](https://github.com/microsoft/onnxruntime)
- **模型转换参考**: [PaddleOCR Paddle2ONNX 指南](https://github.com/PaddlePaddle/PaddleOCR/blob/main/deploy/paddle2onnx/readme_ch.md)

## 许可证

- PP-OCRv5 模型遵循 [Apache 2.0 许可证](https://www.apache.org/licenses/LICENSE-2.0)
- ONNX Runtime Mobile 遵循 [MIT 许可证](https://github.com/microsoft/onnxruntime/blob/main/LICENSE)
