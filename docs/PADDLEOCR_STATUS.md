# PaddleOCR 替换 ML Kit - 当前状态总结

## ✅ 已完成的工作

### 1. Paddle-Lite 库集成
- ✅ 下载并配置 Paddle-Lite v2.10 预测库
  - `app/libs/PaddlePredictor.jar` (9.0K)
  - `app/src/main/jniLibs/arm64-v8a/libpaddle_lite_jni.so` (2.9M)
  - `app/src/main/jniLibs/arm64-v8a/libpaddle_light_api_shared.so` (2.9M)

### 2. 资源文件准备
- ✅ 下载中文字典: `app/src/main/assets/dict/ppocr_keys_v1.txt` (26K, 6623个字符)
- ✅ 下载文本方向分类模型: `app/src/main/assets/models/ch_ppocr_mobile_v2.0_cls_slim_opt.nb` (436K)

### 3. 代码更新
- ✅ 更新 `app/build.gradle.kts`:
  - 移除 ML Kit 依赖 (`com.google.mlkit:text-recognition:16.0.0`)
  - 添加 PaddlePredictor.jar 依赖
  
- ✅ 重写 `OcrHelper.kt`:
  - 移除 ML Kit 相关导入
  - 添加 PaddleOCR 框架代码
  - 保持相同的API接口（`recognizeText`等）
  - 标记了需要实现的TODO部分

### 4. 文档
- ✅ 创建 `docs/PADDLEOCR_SETUP.md` - 详细的配置说明

## ⚠️ 需要完成的工作

### 1. 获取模型文件（必需）
需要获取以下两个模型文件并放到 `app/src/main/assets/models/`:
- ⚠️ `ch_PP-OCRv3_det_slim_opt.nb` - 文本检测模型
- ⚠️ `ch_PP-OCRv3_rec_slim_opt.nb` - 文本识别模型

**获取方式**：参考 `docs/PADDLEOCR_SETUP.md` 中的两个方案：
1. 从 Paddle-Lite-Demo 仓库复制
2. 自己下载原始模型并转换

### 2. 实现完整的 OCR 逻辑

由于 Paddle-Lite 的 Java API 比较底层，需要手动实现：

#### 方案 A: 移植 C++ JNI 代码（推荐，功能完整）
从 PaddleOCR Android demo 移植以下文件：
- `native.cpp` - JNI 桥接
- `ocr_ppredictor.cpp/h` - OCR 预测逻辑
- `db_post_process.cpp` - DB检测后处理
- `crnn_process.cpp` - CRNN识别处理
- `cls_process.cpp` - 分类处理
- `preprocess.cpp` - 图像预处理

参考：[PaddleOCR Android Demo](https://github.com/PaddlePaddle/PaddleOCR/tree/main/deploy/android_demo/app/src/main/cpp)

#### 方案 B: 使用 Paddle-Lite Java API（工作量大，但无需C++）
需要在 Kotlin 中实现：
1. **图像预处理**:
   - 检测：缩放、归一化 (mean=[0.485, 0.456, 0.406], scale=[1/0.229, 1/0.224, 1/0.225])
   - 识别：CRNN resize、归一化 (mean=[0.5, 0.5, 0.5], scale=[1/0.5, 1/0.5, 1/0.5])
   
2. **模型推理**:
   ```kotlin
   // 伪代码示例
   val config = MobileConfig()
   config.setModelFromFile(modelPath)
   config.setThreads(4)
   val predictor = createPaddlePredictor(config)
   
   val inputTensor = predictor.getInput(0)
   inputTensor.reshape(intArrayOf(1, 3, height, width))
   inputTensor.setData(preprocessedData)
   
   predictor.run()
   
   val outputTensor = predictor.getOutput(0)
   val result = outputTensor.getData()
   ```

3. **后处理**:
   - DB检测：阈值化、轮廓提取、多边形拟合
   - CRNN识别：CTC解码、字典映射
   - 方向分类：softmax、角度判断

#### 方案 C: 直接使用 PaddleOCR Android Demo（最快）
克隆完整的 Android demo 项目作为参考或直接集成：
```bash
git clone https://github.com/PaddlePaddle/PaddleOCR.git
# 参考 PaddleOCR/deploy/android_demo/
```

## 📋 当前项目状态

### 代码可以编译 ✅
- Gradle 配置正确
- 依赖已更新
- 代码没有语法错误

### OCR 功能暂不可用 ⚠️
- `OcrHelper.recognizeText()` 返回占位数据
- 需要实现完整的推理逻辑
- 需要添加缺失的模型文件

### 对现有功能的影响 ⚠️
- `GeckoActivity.kt` 中的 OCR 同步功能会返回错误信息
- 不影响其他功能（BLE、浏览器等）

## 🚀 后续步骤建议

1. **获取模型文件**（5-10分钟）
   ```bash
   # 最快的方式
   git clone --depth=1 https://github.com/PaddlePaddle/Paddle-Lite-Demo.git
   cp Paddle-Lite-Demo/ocr/assets/models/*.nb app/src/main/assets/models/
   ```

2. **选择实现方案**:
   - **如果需要快速可用**: 直接参考 PaddleOCR Android demo，移植 JNI 代码
   - **如果避免 C++**: 使用 Paddle-Lite Java API，但需要实现大量预处理/后处理逻辑
   - **如果只是测试**: 先用简单的识别逻辑（仅识别，不检测区域）

3. **在 OcrHelper.kt 中实现 TODO 部分**

## 📚 参考资料

- [PaddleOCR 官方仓库](https://github.com/PaddlePaddle/PaddleOCR)
- [Paddle-Lite 端侧部署](https://github.com/PaddlePaddle/PaddleOCR/tree/main/deploy/lite)
- [PaddleOCR Android Demo](https://github.com/PaddlePaddle/PaddleOCR/tree/main/deploy/android_demo)
- [Paddle-Lite-Demo](https://github.com/PaddlePaddle/Paddle-Lite-Demo)
- [Paddle-Lite API 文档](https://paddle-lite.readthedocs.io/)

## 问题排查

如果遇到问题：
1. 检查模型文件是否存在且不是错误响应（117字节）
2. 检查 JNI 库是否正确加载
3. 查看 logcat 日志中的 "OcrHelper" tag
4. 确认设备是 ARM64 架构（因为只打包了 arm64-v8a 库）
