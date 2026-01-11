# PaddleOCR 集成说明

## 已完成的准备工作

1. ✅ 下载并配置 Paddle-Lite v2.10 库
   - `app/libs/PaddlePredictor.jar` - Java API
   - `app/src/main/jniLibs/arm64-v8a/libpaddle_lite_jni.so` - JNI 库
   - `app/src/main/jniLibs/arm64-v8a/libpaddle_light_api_shared.so` - C++ 库

2. ✅ 下载字典文件
   - `app/src/main/assets/dict/ppocr_keys_v1.txt` (6623个中文字符)

3. ⚠️ 模型文件需要手动获取

## 如何获取 PaddleOCR 模型文件

由于官方下载链接暂时失效，请通过以下方式之一获取模型：

### 方案 1: 从 Paddle-Lite-Demo 获取（推荐）

```bash
# 克隆 PaddleOCR Paddle-Lite-Demo
git clone https://github.com/PaddlePaddle/Paddle-Lite-Demo.git
cd Paddle-Lite-Demo/ocr/assets/models

# 复制模型文件到你的项目
cp ch_PP-OCRv3_det_slim_opt.nb /path/to/GSDJX4DoubleSysFserv/app/src/main/assets/models/
cp ch_PP-OCRv3_rec_slim_opt.nb /path/to/GSDJX4DoubleSysFserv/app/src/main/assets/models/
cp ch_ppocr_mobile_v2.0_cls_slim_opt.nb /path/to/GSDJX4DoubleSysFserv/app/src/main/assets/models/
```

### 方案 2: 自己转换模型

```bash
# 安装 paddlelite
pip install paddlelite==2.10

# 下载原始推理模型
wget https://paddleocr.bj.bcebos.com/PP-OCRv3/chinese/ch_PP-OCRv3_det_slim_infer.tar
wget https://paddleocr.bj.bcebos.com/PP-OCRv3/chinese/ch_PP-OCRv3_rec_slim_infer.tar  
wget https://paddleocr.bj.bcebos.com/dygraph_v2.0/slim/ch_ppocr_mobile_v2.0_cls_slim_infer.tar

# 解压
tar xf ch_PP-OCRv3_det_slim_infer.tar
tar xf ch_PP-OCRv3_rec_slim_infer.tar
tar xf ch_ppocr_mobile_v2.0_cls_slim_infer.tar

# 转换为 .nb 格式
paddle_lite_opt \
  --model_file=./ch_PP-OCRv3_det_slim_infer/inference.pdmodel \
  --param_file=./ch_PP-OCRv3_det_slim_infer/inference.pdiparams \
  --optimize_out=./ch_PP-OCRv3_det_slim_opt \
  --valid_targets=arm \
  --optimize_out_type=naive_buffer

paddle_lite_opt \
  --model_file=./ch_PP-OCRv3_rec_slim_infer/inference.pdmodel \
  --param_file=./ch_PP-OCRv3_rec_slim_infer/inference.pdiparams \
  --optimize_out=./ch_PP-OCRv3_rec_slim_opt \
  --valid_targets=arm \
  --optimize_out_type=naive_buffer

paddle_lite_opt \
  --model_file=./ch_ppocr_mobile_v2.0_cls_slim_infer/inference.pdmodel \
  --param_file=./ch_ppocr_mobile_v2.0_cls_slim_infer/inference.pdiparams \
  --optimize_out=./ch_ppocr_mobile_v2.0_cls_slim_opt \
  --valid_targets=arm \
  --optimize_out_type=naive_buffer

# 复制 .nb 文件到项目
cp ch_PP-OCRv3_det_slim_opt.nb /path/to/GSDJX4DoubleSysFserv/app/src/main/assets/models/
cp ch_PP-OCRv3_rec_slim_opt.nb /path/to/GSDJX4DoubleSysFserv/app/src/main/assets/models/
cp ch_ppocr_mobile_v2.0_cls_slim_opt.nb /path/to/GSDJX4DoubleSysFserv/app/src/main/assets/models/
```

## 最终目录结构

```
app/src/main/
├── assets/
│   ├── dict/
│   │   └── ppocr_keys_v1.txt  ✅
│   └── models/
│       ├── ch_PP-OCRv3_det_slim_opt.nb  ⚠️ 需要获取
│       ├── ch_PP-OCRv3_rec_slim_opt.nb  ⚠️ 需要获取
│       └── ch_ppocr_mobile_v2.0_cls_slim_opt.nb  ✅
├── jniLibs/
│   └── arm64-v8a/
│       ├── libpaddle_lite_jni.so  ✅
│       └── libpaddle_light_api_shared.so  ✅
└── java/com/guaishoudejia/x4doublesysfserv/ocr/
    └── OcrHelper.kt  (即将更新为使用 PaddleOCR)
```

## 模型说明

- **ch_PP-OCRv3_det_slim_opt.nb**: 文本检测模型，检测图像中的文本区域
- **ch_PP-OCRv3_rec_slim_opt.nb**: 文本识别模型，识别检测到的文本内容  
- **ch_ppocr_mobile_v2.0_cls_slim_opt.nb**: 文本方向分类模型，判断文本是否需要旋转

## 参考资料

- [PaddleOCR 官方文档](https://github.com/PaddlePaddle/PaddleOCR)
- [Paddle-Lite 端侧部署文档](https://github.com/PaddlePaddle/PaddleOCR/tree/main/deploy/lite)
- [Paddle-Lite-Demo](https://github.com/PaddlePaddle/Paddle-Lite-Demo/tree/develop/ocr)
