#!/bin/bash
# 快速获取 PaddleOCR 模型文件
# 使用方法: ./get_models.sh

set -e

echo "=== 下载 PaddleOCR 模型文件 ==="

# 创建临时目录
TEMP_DIR=$(mktemp -d)
cd "$TEMP_DIR"

echo "[1/3] 克隆 Paddle-Lite-Demo (仅下载 OCR 资源)..."
git clone --depth=1 --filter=blob:none --sparse https://github.com/PaddlePaddle/Paddle-Lite-Demo.git
cd Paddle-Lite-Demo
git sparse-checkout set ocr/assets/models

# 返回项目目录
PROJECT_DIR="$OLDPWD"

echo "[2/3] 复制模型文件到项目..."
cp -v ocr/assets/models/ch_PP-OCRv3_det_slim_opt.nb "$PROJECT_DIR/app/src/main/assets/models/" || {
    echo "警告：检测模型未找到，尝试其他名称..."
}

cp -v ocr/assets/models/ch_PP-OCRv3_rec_slim_opt.nb "$PROJECT_DIR/app/src/main/assets/models/" || {
    echo "警告：识别模型未找到，尝试其他名称..."
}

echo "[3/3] 清理临时文件..."
cd "$PROJECT_DIR"
rm -rf "$TEMP_DIR"

echo ""
echo "=== 检查模型文件 ==="
ls -lh app/src/main/assets/models/

echo ""
echo "✅ 完成！"
echo ""
echo "注意：如果某些模型未找到，请手动从以下位置获取："
echo "1. Paddle-Lite-Demo: https://github.com/PaddlePaddle/Paddle-Lite-Demo"
echo "2. 或参考 docs/PADDLEOCR_SETUP.md 自己转换模型"
