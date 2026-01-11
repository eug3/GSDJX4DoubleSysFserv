# PP-OCR 模型手动下载指南

由于 PaddleOCR 官方 BOS 源在当前网络环境不可用，请使用以下方法获取模型文件：

## 方案 1: 从 GitHub Release 下载（推荐）

1. **v2 识别模型** (推荐，稳定可用)
   - 仓库: https://github.com/PaddlePaddle/PaddleOCR
   - Releases: https://github.com/PaddlePaddle/PaddleOCR/releases
   - 找到 PP-OCRv2 相关 release，下载 `ch_ppocr_mobile_v2.0_rec_slim_opt.nb`
   - 预期大小: ~4-5 MB

2. **v2 检测模型** (可选，当前使用 v3 检测)
   - 文件: `ch_ppocr_mobile_v2.0_det_slim_opt.nb`
   - 预期大小: ~1-2 MB

## 方案 2: 从 HuggingFace 下载

访问以下页面，点击 "Files and versions"，下载 `.nb` 文件：

- **PP-OCRv5 检测**: https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det/tree/main
- **PP-OCRv5 识别**: https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_rec/tree/main
- **PP-OCRv2 识别**: https://huggingface.co/PaddlePaddle/ch_ppocr_mobile_v2.0_rec_slim/tree/main

注意：HuggingFace 上可能只有 `.pdmodel` 和 `.pdiparams` 文件，需要使用 `paddle_lite_opt` 工具转换。

## 方案 3: 使用本地已有模型转换

如果你本地有 PaddleOCR 训练的模型，可以使用以下命令转换：

```bash
# 安装 Paddle-Lite opt 工具
pip install paddlelite

# 转换模型
paddle_lite_opt \
    --model_file=inference.pdmodel \
    --param_file=inference.pdiparams \
    --optimize_out=ch_PP-OCRv2_rec_slim_opt \
    --valid_targets=arm \
    --optimize_out_type=naive_buffer
```

## 方案 4: 使用国内镜像源（如果可用）

有时可以尝试清华/阿里云镜像：
```bash
# 尝试不同的镜像源
curl -L -o ch_ppocr_mobile_v2.0_rec_slim_opt.nb \
  https://mirrors.tuna.tsinghua.edu.cn/paddlepaddle/PaddleOCR/...
```

## 下载后的操作

1. 将下载的 `.nb` 文件放到项目目录：
   ```
   app/src/main/assets/models/
   ```

2. 确保文件名符合自动识别规则：
   - v5 识别: 文件名包含 "v5"、"rec"、".nb"
   - v5 检测: 文件名包含 "v5"、"det"、".nb"
   - v2 识别: `ch_ppocr_mobile_v2.0_rec_slim_opt.nb`

3. 验证文件大小：
   ```bash
   ls -lh app/src/main/assets/models/*.nb
   ```
   
   正常大小应该是：
   - 检测模型: ~1-2 MB
   - 识别模型: ~4-5 MB
   - 分类模型: ~400-500 KB

4. 重新构建并安装：
   ```bash
   ./gradlew :app:assembleDebug
   ./gradlew :app:installDebug
   ```

5. 检查日志确认模型已加载：
   ```bash
   adb logcat | grep -E "检测到 v2|检测到 v5|识别模型"
   ```

## 临时解决方案

如果暂时无法获取模型文件，建议：
1. 保持当前的检测-only模式（已稳定运行）
2. 等待网络环境改善后重试下载
3. 或从其他已部署的 PaddleOCR 项目复制 `.nb` 文件

## 需要帮助？

如果以上方法都不可行，可以：
1. 在项目 issue 中请求文件分享
2. 使用 VPN/代理访问官方源
3. 联系有 PaddleOCR 环境的同事获取文件
