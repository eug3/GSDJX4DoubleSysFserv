#!/bin/bash
# 快速获取 OCR ONNX 模型文件
# 使用方法: ./get_models.sh

set -e

echo "=== 下载 OCR ONNX 模型文件 ==="

# 创建临时目录
TEMP_DIR=$(mktemp -d)
cd "$TEMP_DIR"

echo "[1/2] 下载 ONNX 模型..."

# PP-OCRv5 ONNX 模型下载示例
wget -c https://paddleocr.bj.bcebos.com/PP-OCRv5/chinese/onnx/det_model.onnx
wget -c https://paddleocr.bj.bcebos.com/PP-OCRv5/chinese/onnx/rec_model.onnx
wget -c https://paddleocr.bj.bcebos.com/PP-OCRv5/chinese/onnx/cls_model.onnx

# 返回项目目录
PROJECT_DIR="$OLDPWD"

echo "[2/2] 复制模型文件到项目..."
cp -v det_model.onnx "$PROJECT_DIR/app/src/main/assets/models/"
cp -v rec_model.onnx "$PROJECT_DIR/app/src/main/assets/models/"
cp -v cls_model.onnx "$PROJECT_DIR/app/src/main/assets/models/"

# 清理临时文件
cd "$PROJECT_DIR"
rm -rf "$TEMP_DIR"

echo ""
echo "=== 检查模型文件 ==="
ls -lh app/src/main/assets/models/

echo ""
echo "完成！"
