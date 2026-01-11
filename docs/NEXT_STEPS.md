## PaddleOCR 集成完成步骤

模型文件已就绪✅：
- ch_PP-OCRv3_det_slim_opt.nb (1.0M) - 检测模型
- ch_PP-OCRv3_rec_slim_opt.nb (4.9M) - 识别模型  
- ch_ppocr_mobile_v2.0_cls_slim_opt.nb (436K) - 分类模型

## 需要完成的 JNI 集成

PaddleOCR 的 Android 实现依赖 C++ JNI 代码。有两个选择：

### 选项 1: 复制完整的 C++ 代码（推荐-功能完整）

1. 从 `/tmp/PaddleOCR/deploy/android_demo/app/src/main/cpp/` 复制以下文件到项目：

```
app/src/main/cpp/
├── CMakeLists.txt
├── native.cpp (JNI 桥接)
├── ocr_ppredictor.cpp/h (OCR 预测逻辑)
├── ppredictor.cpp/h (Paddle-Lite 预测器封装)
├── common.h
├── db_post_process.cpp/h (DB检测后处理)
├── crnn_process.cpp/h (CRNN识别处理)
├── cls_process.cpp/h (分类处理)
├── preprocess.cpp/h (图像预处理)
└── ocr_db_post_process.h
```

2. 从 Java 代码复制：
```bash
cp /tmp/PaddleOCR/deploy/android_demo/app/src/main/java/com/baidu/paddle/lite/demo/ocr/OCRPredictorNative.java \
   app/src/main/java/com/guaishoudejia/x4doublesysfserv/ocr/

cp /tmp/PaddleOCR/deploy/android_demo/app/src/main/java/com/baidu/paddle/lite/demo/ocr/OcrResultModel.java \
   app/src/main/java/com/guaishoudejia/x4doublesysfserv/ocr/

cp /tmp/PaddleOCR/deploy/android_demo/app/src/main/java/com/baidu/paddle/lite/demo/ocr/Utils.java \
   app/src/main/java/com/guaishoudejia/x4doublesysfserv/ocr/
```

3. 在 `app/build.gradle.kts` 中启用 native 构建：
```kotlin
android {
    //...
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
```

4. 修改包名从 `com.baidu.paddle.lite.demo.ocr` 到 `com.guaishoudejia.x4doublesysfserv.ocr`

### 选项 2: 使用预编译的 SO 库（快速-需要手动提取）

从已编译的 PaddleOCR Android Demo APK 提取：

```bash
# 下载 APK
wget https://paddleocr.bj.bcebos.com/deploy/lite/ocr_v3_for_cpu.apk

# 解压 APK  
unzip ocr_v3_for_cpu.apk -d paddleocr_apk

# 复制 native 库
cp paddleocr_apk/lib/arm64-v8a/libNative.so app/src/main/jniLibs/arm64-v8a/
```

然后复制 Java 封装代码（同选项1的第2步）。

### 选项 3: 使用纯 Kotlin/Java 实现（工作量大-不推荐）

需要手动实现：
- 图像预处理（resize, normalize）
- DB 后处理（阈值化、轮廓检测）
- CRNN CTC 解码
- 文本方向分类

这需要几百行代码且易出错。

## 推荐的实施方案

**立即可用**: 使用选项 1

执行以下命令快速集成：

```bash
cd /Users/beijihu/Github/GSDJX4DoubleSysFserv

# 复制 C++ 代码
mkdir -p app/src/main/cpp
cp -r /tmp/PaddleOCR/deploy/android_demo/app/src/main/cpp/* app/src/main/cpp/

# 复制 Java 封装
cp /tmp/PaddleOCR/deploy/android_demo/app/src/main/java/com/baidu/paddle/lite/demo/ocr/OCRPredictorNative.java \
   app/src/main/java/com/guaishoudejia/x4doublesysfserv/ocr/
cp /tmp/PaddleOCR/deploy/android_demo/app/src/main/java/com/baidu/paddle/lite/demo/ocr/OcrResultModel.java \
   app/src/main/java/com/guaishoudejia/x4doublesysfserv/ocr/
cp /tmp/PaddleOCR/deploy/android_demo/app/src/main/java/com/baidu/paddle/lite/demo/ocr/Utils.java \
   app/src/main/java/com/guaishoudejia/x4doublesysfserv/ocr/

# 修改包名
sed -i '' 's/com\.baidu\.paddle\.lite\.demo\.ocr/com.guaishoudejia.x4doublesysfserv.ocr/g' \
   app/src/main/java/com/guaishoudejia/x4doublesysfserv/ocr/*.java
```

然后我会为你更新 OcrHelper.kt 和 build.gradle.kts。

要我继续执行这些步骤吗？
