# OCR 模型配置说明

## 概述

本项目使用 ONNX Runtime Mobile 进行 OCR 识别（PP-OCRv5）。

## 模型文件位置

```
app/src/main/assets/
├── dict/
│   └── ppocr_keys_v1.txt    # 中文字典
└── models/
    ├── det_model.onnx       # 文本检测模型
    ├── rec_model.onnx       # 文本识别模型
    └── cls_model.onnx       # 文本方向分类模型
```

## ONNX 模型获取

参考 [ONNX OCR 模型获取指南](https://github.com/PaddlePaddle/PaddleOCR/blob/release/2.8/docs/zh_CN/inference/faq.md)。
