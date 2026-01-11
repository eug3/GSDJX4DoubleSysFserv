#!/bin/bash

# PP-OCRv5 模型下载脚本
# 下载移动端检测和识别模型，并放置到正确的资产目录

set -e

TARGET_DIR="app/src/main/assets/models"
TEMP_DIR="/tmp/ppocr_v5_models"

echo "=== PP-OCRv5 模型下载工具 ==="
echo "目标目录: $TARGET_DIR"

# 创建目录
mkdir -p "$TARGET_DIR"
mkdir -p "$TEMP_DIR"
cd "$TEMP_DIR"

echo ""
echo "开始下载 PP-OCRv5 移动端模型..."

# 尝试从多个源下载
# 方案1: 从 PaddleOCR GitHub release 下载
# 方案2: 从 BOS 下载
# 方案3: 从 HuggingFace 下载

echo ""
echo "[1/2] 下载检测模型 (PP-OCRv5_mobile_det)..."

# 尝试 BOS 源
if curl -L -o ch_PP-OCRv5_det_slim_opt.nb \
    "https://paddleocr.bj.bcebos.com/PP-OCRv5/chinese/ch_PP-OCRv5_det_slim_opt.nb" 2>/dev/null; then
    echo "✓ 检测模型下载成功 (BOS)"
elif curl -L -o ch_PP-OCRv5_det_slim_opt.nb \
    "https://github.com/PaddlePaddle/PaddleOCR/releases/download/v5.0/ch_PP-OCRv5_det_slim_opt.nb" 2>/dev/null; then
    echo "✓ 检测模型下载成功 (GitHub)"
else
    echo "⚠ 检测模型下载失败，尝试使用 v3 替代"
    echo "如需手动下载，请访问: https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det"
fi

echo ""
echo "[2/2] 下载识别模型 (PP-OCRv5_mobile_rec)..."

# 尝试 BOS 源
if curl -L -o ch_PP-OCRv5_rec_slim_opt.nb \
    "https://paddleocr.bj.bcebos.com/PP-OCRv5/chinese/ch_PP-OCRv5_rec_slim_opt.nb" 2>/dev/null; then
    echo "✓ 识别模型下载成功 (BOS)"
elif curl -L -o ch_PP-OCRv5_rec_slim_opt.nb \
    "https://github.com/PaddlePaddle/PaddleOCR/releases/download/v5.0/ch_PP-OCRv5_rec_slim_opt.nb" 2>/dev/null; then
    echo "✓ 识别模型下载成功 (GitHub)"
else
    echo "⚠ 识别模型下载失败"
    echo "如需手动下载，请访问: https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_rec"
fi

echo ""
echo "检查下载结果..."
ls -lh *.nb 2>/dev/null || echo "未找到 .nb 文件"

echo ""
echo "验证文件大小..."
for file in *.nb; do
    if [ -f "$file" ]; then
        size=$(stat -f%z "$file" 2>/dev/null || stat -c%s "$file" 2>/dev/null)
        if [ "$size" -lt 100000 ]; then
            echo "⚠ $file 文件过小 ($size bytes)，可能下载失败"
            rm -f "$file"
        else
            echo "✓ $file: $(numfmt --to=iec-i --suffix=B $size 2>/dev/null || echo "$size bytes")"
        fi
    fi
done

echo ""
echo "复制模型到项目资产目录..."
cd -
cp -v "$TEMP_DIR"/*.nb "$TARGET_DIR/" 2>/dev/null || echo "没有有效的 .nb 文件可复制"

echo ""
echo "清理临时文件..."
# rm -rf "$TEMP_DIR"

echo ""
echo "=== 下载完成 ==="
echo ""
echo "已下载的模型："
ls -lh "$TARGET_DIR"/*.nb 2>/dev/null || echo "(无)"

echo ""
echo "如果下载失败，请手动下载："
echo "1. 访问 HuggingFace:"
echo "   检测: https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det"
echo "   识别: https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_rec"
echo ""
echo "2. 下载 .nb 文件后，重命名并放置到："
echo "   $TARGET_DIR/"
echo ""
echo "   文件名应包含关键字："
echo "   - 检测模型: 包含 'v5', 'det', '.nb'"
echo "   - 识别模型: 包含 'v5', 'rec', '.nb'"
echo ""
echo "3. 重新构建并安装应用："
echo "   ./gradlew :app:assembleDebug"
echo "   ./gradlew :app:installDebug"
