# 部署和测试指南

## 前置检查清单

### 1. 环境准备
```bash
# 确认 Android SDK 已安装
which adb
# 输出: /Users/beijihu/Library/Android/sdk/platform-tools/adb

# 确认 JDK 已正确配置
java -version
# 输出: openjdk version "21.0.8"

# 确认 Gradle 构建成功
cd /Users/beijihu/Github/GSDJX4DoubleSysFserv
./gradlew clean assembleDebug
```

### 2. 设备连接
```bash
# 列出连接的设备
adb devices

# 输出示例:
# List of attached devices
# emulator-5554    device
# ZY22D51PWR       device
```

## 安装步骤

### 1. 构建 APK（已完成）
```bash
cd /Users/beijihu/Github/GSDJX4DoubleSysFserv

# 清理并构建
./gradlew clean assembleDebug

# 预期输出:
# BUILD SUCCESSFUL in 23s
```

### 2. 安装到设备
```bash
# 使用标准 adb install 命令
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 预期输出:
# Installing com.guaishoudejia.x4doublesysfserv...
# Package installed successfully.
```

### 3. 启动应用
```bash
# 方法 1: 使用 adb 启动
adb shell am start -n com.guaishoudejia.x4doublesysfserv/.MainActivity

# 方法 2: 从设备上点击应用图标启动
# 在设备主屏幕查找应用图标并点击
```

## 功能测试

### 1. 基础功能检查
```bash
# 监听应用日志
adb logcat -s "OcrHelper,PaddleOcrPredictor,X4Service" | grep -v "DEBUG"

# 执行测试操作：
# 1. 打开应用
# 2. 选择图像或拍照
# 3. 点击 OCR 识别按钮
# 4. 观察是否正确识别文本
```

### 2. 日志分析

#### 预期的成功日志
```
✓ 所有必需模型加载成功，字典大小: 6623
✓ 识别模型加载成功
【识别模型期望输入】: 320x32
✓ 识别输出形状: [1, 6625], 数据长度: 6625
✓ 推理完成成功
```

#### 错误日志示例（已修复）
```
❌ 推理执行失败! 错误: Check failed: (k_ == w_dims[0]): 4800!==120
↑ 这个错误在修复后应该不会再出现
```

### 3. 边界情况测试
- 测试各种图像大小（小、中、大）
- 测试不同的图像格式（PNG、JPEG）
- 测试中文、英文、数字识别
- 测试倾斜或旋转的文本

## 性能基准

### 模型加载时间
```
预期值: 1-3 秒（首次初始化）
```

### 推理速度
```
预期值: 200-500ms（每张图像）
取决于：
- 设备性能（CPU 类型）
- 图像尺寸
- Android 版本
```

### 内存占用
```
模型加载: ~50MB
运行时峰值: ~100MB
（使用优化模型后比推理模型更低）
```

## 故障排除

### 错误 1: "模型文件不存在"
```
症状: java.io.FileNotFoundException
原因: 模型文件未正确复制到 cache 目录
解决:
  1. 确认 assets 中的模型文件存在
  2. 清除应用缓存: adb shell pm clear com.guaishoudejia.x4doublesysfserv
  3. 重新启动应用
```

### 错误 2: "推理执行失败"
```
症状: PaddlePredictor.run() 抛出异常
原因: 模型格式不正确或库版本不兼容
解决:
  1. 验证 app/src/main/assets/models 中的文件
  2. 确保所有文件都是 *_opt.nb（优化格式）
  3. 检查 PaddlePredictor.jar 版本
```

### 错误 3: "维度错误 4800!==120"
```
症状: Check failed: (k_ == w_dims[0]): 4800!==120
原因: 使用了推理模型而不是优化模型 ✅ 已修复
解决:
  1. 检查模型文件是否正确
  2. 确保使用 *_opt.nb 而不是 *_infer.nb
  3. 重新构建并部署
```

### 错误 4: "应用崩溃"
```
症状: 应用退出并返回主屏幕
调试步骤:
  1. 检查 logcat 输出
  2. 查找 "FATAL EXCEPTION" 或 "crash"
  3. 检查堆栈跟踪
  4. 如果涉及 JNI，检查 native 日志
```

## 验证清单

完成以下检查以确保部署成功：

- [ ] APK 构建成功（BUILD SUCCESSFUL）
- [ ] APK 安装成功（Package installed successfully）
- [ ] 应用启动无崩溃
- [ ] OCR 初始化日志显示所有模型加载成功
- [ ] 能够选择图像或拍照
- [ ] 识别功能正常工作
- [ ] 没有维度错误 (`4800!==120`)
- [ ] 没有其他异常日志

## 下一步行动

1. **立即执行**：
   ```bash
   adb install -r /Users/beijihu/Github/GSDJX4DoubleSysFserv/app/build/outputs/apk/debug/app-debug.apk
   ```

2. **启动应用并观察日志**：
   ```bash
   adb logcat -s "OcrHelper" &
   adb shell am start -n com.guaishoudejia.x4doublesysfserv/.MainActivity
   ```

3. **测试 OCR 功能**：
   - 选择一张包含清晰文本的图像
   - 点击识别按钮
   - 验证识别结果是否正确

4. **性能测试**（可选）：
   - 使用 Android Profiler 监控 CPU、内存占用
   - 多次运行识别以测试稳定性
   - 记录平均推理时间

## 联系和支持

如果遇到问题：
1. 查看本文档的故障排除部分
2. 检查 `MODEL_VERIFICATION_SUMMARY.md` 中的说明
3. 查看应用日志（`adb logcat`）
4. 验证所有模型文件都在正确的位置

---

**最后更新**: 2025-01-11  
**版本**: 1.0.0
