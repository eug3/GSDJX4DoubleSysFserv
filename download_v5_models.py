"""
PP-OCRv5 模型下载工具 (Python 版本)
从 HuggingFace 下载移动端模型到项目资产目录
"""

import os
import sys
from pathlib import Path
import subprocess

TARGET_DIR = Path("app/src/main/assets/models")
TEMP_DIR = Path("/tmp/ppocr_v5_download")

# HuggingFace 仓库和文件信息
MODELS = {
    "det": {
        "repo": "PaddlePaddle/PP-OCRv5_mobile_det",
        "files": ["inference.pdiparams", "inference.pdmodel"],
        "output": "ch_PP-OCRv5_det_slim_opt.nb"
    },
    "rec": {
        "repo": "PaddlePaddle/PP-OCRv5_mobile_rec", 
        "files": ["inference.pdiparams", "inference.pdmodel"],
        "output": "ch_PP-OCRv5_rec_slim_opt.nb"
    }
}

def download_from_huggingface(repo, filename, output_path):
    """从 HuggingFace 下载文件"""
    url = f"https://huggingface.co/{repo}/resolve/main/{filename}"
    print(f"  下载: {filename} ...")
    
    cmd = ["curl", "-L", "-o", str(output_path), url]
    result = subprocess.run(cmd, capture_output=True)
    
    if result.returncode == 0 and output_path.exists() and output_path.stat().st_size > 100000:
        size_mb = output_path.stat().st_size / 1024 / 1024
        print(f"  ✓ 成功 ({size_mb:.1f} MB)")
        return True
    else:
        print(f"  ✗ 失败")
        return False

def main():
    print("=== PP-OCRv5 模型下载工具 (HuggingFace) ===\n")
    
    # 创建目录
    TARGET_DIR.mkdir(parents=True, exist_ok=True)
    TEMP_DIR.mkdir(parents=True, exist_ok=True)
    
    print(f"目标目录: {TARGET_DIR}\n")
    print("注意: 该脚本仅下载源文件，还需要使用 Paddle2ONNX + opt 工具转换为 .nb\n")
    print("如果你只需要快速测试，建议:")
    print("1. 直接访问 HuggingFace 网页下载已转换的 .nb 文件")
    print("2. 或使用 PP-OCRv2 模型（更稳定）\n")
    
    response = input("继续下载源文件？(y/N): ")
    if response.lower() != 'y':
        print("\n已取消。")
        print("\n替代方案 - 使用 PP-OCRv2:")
        print("访问: https://paddleocr.bj.bcebos.com/PP-OCRv2/chinese/")
        print("下载: ch_ppocr_mobile_v2.0_rec_slim_opt.nb")
        print(f"放置到: {TARGET_DIR}/")
        return
    
    # 下载模型文件
    for model_type, info in MODELS.items():
        print(f"\n[{model_type.upper()}] {info['repo']}")
        model_dir = TEMP_DIR / model_type
        model_dir.mkdir(exist_ok=True)
        
        success = True
        for filename in info["files"]:
            output = model_dir / filename
            if not download_from_huggingface(info["repo"], filename, output):
                success = False
                break
        
        if not success:
            print(f"  ⚠ {model_type} 模型下载失败")
    
    print("\n" + "="*50)
    print("\n下载完成！但模型还需要转换为 .nb 格式。")
    print("\n转换步骤:")
    print("1. 安装 paddle2onnx 和 Paddle-Lite opt 工具")
    print("2. 将 .pdmodel + .pdiparams 转换为 .nb")
    print("\n或者，直接使用 v2 模型替代:")
    print("  curl -o app/src/main/assets/models/ch_ppocr_mobile_v2.0_rec_slim_opt.nb \\")
    print("    https://paddleocr.bj.bcebos.com/PP-OCRv2/chinese/ch_ppocr_mobile_v2.0_rec_slim_opt.nb")

if __name__ == "__main__":
    main()
