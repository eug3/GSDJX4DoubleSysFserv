#!/bin/bash

echo "============================================"
echo "PaddleOCR 模型诊断和修复工具"
echo "============================================"
echo ""

PROJECT_DIR="/Users/beijihu/Github/GSDJX4DoubleSysFserv/app/src/main/assets/models"
BACKUP_DIR="$PROJECT_DIR/backup_$(date +%Y%m%d_%H%M%S)"

echo "项目模型目录: $PROJECT_DIR"
echo ""

if [ ! -d "$PROJECT_DIR" ]; then
    echo "❌ 模型目录不存在!"
    exit 1
fi

echo "当前模型文件:"
ls -lh "$PROJECT_DIR"/*.nb 2>/dev/null || echo "⚠️  未找到 .nb 模型文件"

echo ""
echo "============================================"
echo "问题分析"
echo "============================================"
echo ""
echo "错误: Check failed: (k_ == w_dims[0]): 4800!==120"
echo ""
echo "原因可能包括:"
echo "1️⃣  模型文件损坏或不完整"
echo "2️⃣  模型版本与 PaddleLite 库不兼容"
echo "3️⃣  模型被错误转换或压缩"
echo ""
echo "解决方案:"
echo ""
echo "【方案 A - 重新下载官方模型 (推荐)】"
echo "备份当前模型..."
mkdir -p "$BACKUP_DIR"
cp "$PROJECT_DIR"/*.nb "$BACKUP_DIR/" 2>/dev/null && echo "✓ 备份完成: $BACKUP_DIR"
echo ""
echo "下载官方 PP-OCRv3 模型..."
echo ""

# 官方下载链接 (PP-OCRv3 精简版)
echo "下载 PP-OCRv3 文本检测模型..."
curl -L -o "$PROJECT_DIR/ch_PP-OCRv3_det_slim_opt.nb" \
  "https://paddleocr.bj.bcebos.com/pp_lite_models/ocr_v3/chinese_ppocr_mobile_v2_slim_opt.nb" \
  2>/dev/null && echo "✓ 检测模型下载完成" || echo "⚠️  检测模型下载失败 (可能需要科学上网)"

echo ""
echo "下载 PP-OCRv3 文本识别模型..."
curl -L -o "$PROJECT_DIR/ch_PP-OCRv3_rec_slim_opt.nb" \
  "https://paddleocr.bj.bcebos.com/pp_lite_models/ocr_v3/chinese_ppocr_mobile_v2_rec_slim_opt.nb" \
  2>/dev/null && echo "✓ 识别模型下载完成" || echo "⚠️  识别模型下载失败 (可能需要科学上网)"

echo ""
echo "下载 PP-OCRv3 文本分类模型..."
curl -L -o "$PROJECT_DIR/ch_ppocr_mobile_v2.0_cls_slim_opt.nb" \
  "https://paddleocr.bj.bcebos.com/pp_lite_models/ocr_v3/ch_ppocr_mobile_v2_cls_slim_opt.nb" \
  2>/dev/null && echo "✓ 分类模型下载完成" || echo "⚠️  分类模型下载失败 (可能需要科学上网)"

echo ""
echo "============================================"
echo "【方案 B - 使用离线模型 (如果网络下载失败)】"
echo "============================================"
echo ""
echo "从官网获取最新模型:"
echo "  https://github.com/PaddlePaddle/PaddleOCR/blob/release/2.8/doc/doc_en/models_en.md"
echo ""
echo "官方 PaddleOCR 模型下载列表 (直接下载):"
echo "  检测: https://paddleocr.bj.bcebos.com/pp_lite_models/ocr_v3/chinese_ppocr_mobile_v2_slim_opt.nb"
echo "  识别: https://paddleocr.bj.bcebos.com/pp_lite_models/ocr_v3/chinese_ppocr_mobile_v2_rec_slim_opt.nb"
echo "  分类: https://paddleocr.bj.bcebos.com/pp_lite_models/ocr_v3/ch_ppocr_mobile_v2_cls_slim_opt.nb"
echo ""
echo "下载后放到: $PROJECT_DIR"
echo ""
echo "============================================"
echo "修复后的下一步"
echo "============================================"
echo ""
echo "1. 确保模型文件完整 (大小应该 >100MB)"
echo "2. 运行: ./gradlew clean assembleDebug"
echo "3. 重新测试应用"
echo ""
