# OCR 模型说明

## 当前使用的模型

本项目使用 ONNX Runtime Mobile 进行 OCR 识别（PP-OCRv5）。

## 模型文件

```
app/src/main/assets/models/
├── det_model.onnx    # 文本检测模型
├── rec_model.onnx    # 文本识别模型
└── cls_model.onnx    # 文本方向分类模型
```

## 获取 ONNX 模型

1. 使用 PaddleOCR 的模型转换工具将 PP-OCRv5 模型转换为 ONNX 格式
2. 或从官方发布页面下载预转换的 ONNX 模型

## 字典文件

- `dict/ppocr_keys_v1.txt` - 中文字典文件
