# BMP 二维码传输完整文档索引

## 📚 文档导航

本目录包含关于 BMP 二维码传输的完整分析和追踪文档。选择适合您的文档开始阅读：

---

## 🎯 按用途分类

### 🔴 我想要...

#### 1. **快速诊断设备问题** 
   → 使用 [BMP_QUICK_CHECKLIST.md](BMP_QUICK_CHECKLIST.md)
   - ⏱️ 阅读时间: 5-10 分钟
   - 📋 内容: 日志检查表、问题速查、常见错误
   - 💡 适合: 设备没有显示二维码，需要排查问题

#### 2. **了解完整的传输过程**
   → 使用 [BMP_TRANSMISSION_TRACE.md](BMP_TRANSMISSION_TRACE.md)
   - ⏱️ 阅读时间: 15-20 分钟
   - 📋 内容: 6 个阶段详细追踪、每步验证、协议合规性
   - 💡 适合: 想要深入理解整个流程

#### 3. **查看代码执行细节**
   → 使用 [BMP_CODE_TRACE.md](BMP_CODE_TRACE.md)
   - ⏱️ 阅读时间: 15-20 分钟
   - 📋 内容: 代码调用栈、执行时间轴、每行代码注释
   - 💡 适合: 开发者、需要修改代码

#### 4. **可视化学习**
   → 使用 [BMP_TRANSMISSION_VISUAL.md](BMP_TRANSMISSION_VISUAL.md)
   - ⏱️ 阅读时间: 10-15 分钟
   - 📋 内容: 流程图、帧头结构图、数据流示意
   - 💡 适合: 视觉学习者、想要全貌理解

#### 5. **查看修复总结**
   → 使用 [PROTOCOL_FIX_SUMMARY.md](PROTOCOL_FIX_SUMMARY.md)
   - ⏱️ 阅读时间: 10-15 分钟
   - 📋 内容: 修复内容、新增方法、协议说明
   - 💡 适合: 了解代码改动、新增的图片方法

#### 6. **完整总结**
   → 使用 [BMP_COMPLETE_SUMMARY.md](BMP_COMPLETE_SUMMARY.md)
   - ⏱️ 阅读时间: 10 分钟
   - 📋 内容: 核心结论、关键检查点、最终验证
   - 💡 适合: 快速回顾、确认传输正确性

---

## 📖 按阅读顺序

### 首次学习路径

1. **第一步** (5 分钟): 读 [BMP_COMPLETE_SUMMARY.md](BMP_COMPLETE_SUMMARY.md)
   - 了解核心结论
   - 看关键检查点

2. **第二步** (10 分钟): 看 [BMP_TRANSMISSION_VISUAL.md](BMP_TRANSMISSION_VISUAL.md)
   - 理解整体流程
   - 看可视化图表

3. **第三步** (15 分钟): 详读 [BMP_TRANSMISSION_TRACE.md](BMP_TRANSMISSION_TRACE.md)
   - 逐步详细追踪
   - 协议验证

4. **第四步** (10 分钟): 对照 [BMP_CODE_TRACE.md](BMP_CODE_TRACE.md)
   - 查看代码细节
   - 理解实现

5. **第五步** (按需): 使用 [BMP_QUICK_CHECKLIST.md](BMP_QUICK_CHECKLIST.md)
   - 问题诊断
   - 快速排查

---

### 快速排查路径

1. **问题出现**: 设备没有显示二维码
   ↓
2. **打开** [BMP_QUICK_CHECKLIST.md](BMP_QUICK_CHECKLIST.md) 的"日志检查表"
   ↓
3. **逐个检查**:
   - 第 1-2 步: 数据获取和发送命令日志
   - 第 3 步: 分片传输日志
   - 检查 type 和 flags 字段值
   ↓
4. **找到问题** → 转到对应的问题快速索引
   ↓
5. **参考** [BMP_CODE_TRACE.md](BMP_CODE_TRACE.md) 的代码位置修复

---

## 🗂️ 文件说明表

| 文件 | 长度 | 深度 | 重点 | 适合场景 |
|------|------|------|------|---------|
| **BMP_COMPLETE_SUMMARY.md** | 短 | 中 | ✅ 结论和验证 | 快速了解 |
| **BMP_TRANSMISSION_VISUAL.md** | 中 | 浅 | 📊 流程图表 | 视觉学习 |
| **BMP_TRANSMISSION_TRACE.md** | 长 | 深 | 🔍 详细追踪 | 深入理解 |
| **BMP_CODE_TRACE.md** | 长 | 深 | 💾 代码细节 | 代码分析 |
| **BMP_QUICK_CHECKLIST.md** | 长 | 中 | ⚡ 快速检查 | 问题诊断 |
| **PROTOCOL_FIX_SUMMARY.md** | 中 | 中 | 🛠️ 修复内容 | 了解改动 |

---

## 🔑 核心知识点

### 最重要的三个数值

```
1. Type 字段: 0x01 (BMP)    ← 不能是 0x10 (TXT)
2. Flags 字段: 0x0020       ← 不能是 0x0004
3. MTU 大小: 512 字节       ← 首片 32+480，后续 512
```

### 验证清单

```
✅ 参数：flags = FLAG_TYPE_BMP (0x0020)
✅ 帧头：header[5] = 0x01 (TYPE_BMP)
✅ 分片：第一片 = 512 字节 (32 头 + 480 数据)
✅ 总大小：5032 字节 (32 头 + 5000 数据)
✅ 无 EOF：不追加结束标记
✅ SHOW_PAGE：发送显示命令
```

---

## 🚀 快速代码定位

### 我想找到...

#### BMP 数据获取
```
文件: Views/WeReadPage.xaml.cs
行号: L240-330
方法: TrySendLoginQrAsync()
关键: 从网页获取 Base64，转换为字节数组
```

#### 发送入口验证
```
文件: Services/ShinyBleService.cs
行号: L1077-L1127
方法: SendImageToDeviceAsync()
关键: 检查连接、数据、类型
```

#### 帧头创建
```
文件: Services/ShinyBleService.cs
行号: L1130-L1175
方法: CreateX4IMv2Header()
关键: 构造 32 字节帧头，type=0x01
```

#### 类型转换
```
文件: Services/ShinyBleService.cs
行号: L1176-L1189
方法: FlagsToType()
关键: flags=0x0020 → type=0x01
```

#### 分片传输
```
文件: Services/ShinyBleService.cs
行号: L1185-L1272
方法: SendFrameAsync()
关键: MTU=512，512B 分片
```

#### 显示命令
```
文件: Services/ShinyBleService.cs
行号: L1273-L1300
方法: SendCommandAsync()
关键: 发送 SHOW_PAGE (0x80) 命令
```

#### 常量定义
```
文件: Services/X4IMProtocol.cs
行号: L30-55
说明: TYPE_BMP=0x01, FLAG_TYPE_BMP=0x0020
```

---

## 📊 日志样本

### 正常情况（一切都对）

```
[信息] WeRead: BMP 大小 5000 字节 (4.9 KB)
[信息] BLE: 发送图片 file="page_0.bmp" size=5000 字节 type=0x01 flags=0x0020
[调试] BLE: 已发送第一包 (512 = 32 头 + 480 数据 字节)
[信息] BLE: 帧传输完成，共 10 个分片，总 5032 字节
[信息] WeRead: 已发送登录二维码到设备 (5000 字节, 类型=BMP)

✅ 设备应该显示二维码
```

### 异常情况（需要修复）

```
[信息] BLE: 发送图片 file="page_0.bmp" size=5000 字节 type=0x10 flags=0x0004
        ❌ type 应该是 0x01，不是 0x10！
        ❌ flags 应该是 0x0020，不是 0x0004！
        → 检查 FlagsToType() 或调用参数
```

---

## 🎯 问题速查

| 问题 | 症状 | 检查文档 |
|------|------|---------|
| 类型错误 | type=0x10 | [BMP_QUICK_CHECKLIST.md#Q1](BMP_QUICK_CHECKLIST.md#q1-type0x10-txt-而不是-0x01-bmp) |
| 分片大小错误 | (514 = ...) | [BMP_QUICK_CHECKLIST.md#Q2](BMP_QUICK_CHECKLIST.md#q2-第一片大小不是-512) |
| 总大小错误 | 总 5037 字节 | [BMP_QUICK_CHECKLIST.md#Q3](BMP_QUICK_CHECKLIST.md#q3-总数据大小不是-5032) |
| 没有 SHOW_PAGE | 无显示命令 | [BMP_QUICK_CHECKLIST.md#Q4](BMP_QUICK_CHECKLIST.md#q4-没有-show_page-日志) |
| 设备显示错误 | 显示异常 | [BMP_TRANSMISSION_VISUAL.md#问题排查清单](BMP_TRANSMISSION_VISUAL.md#问题排查清单) |

---

## 📱 关键概念

### X4IM v2 协议帧头（32 字节）

```
[0-3]   Magic: "X4IM"
[4]     Version: 0x02
[5]     Type: 文件类型 (0x01=BMP, 0x10=TXT 等)
[6-7]   Flags: 文件标志位
[8-11]  PayloadSize: 数据大小（小端序）
[12-15] SD: 存储 ID
[16-31] Name: 文件名
```

### BLE 分片策略（MTU 512）

```
第一片：32 字节头 + 480 字节数据 = 512 字节
后续片：512 字节数据
最后片：不足 512 字节
```

### 类型识别链

```
参数: flags=0x0020
  → FlagsToType(0x0020)
  → if ((0x0020 & 0x0020) != 0) → true
  → return TYPE_BMP (0x01)
  → header[5] = 0x01
  → ✅ 正确识别为 BMP
```

---

## 💡 最常见问题

**Q: 为什么 BMP 被识别为 TXT？**
A: 检查 [BMP_QUICK_CHECKLIST.md#Q1](BMP_QUICK_CHECKLIST.md#q1-type0x10-txt-而不是-0x01-bmp) 的四个检查点

**Q: 分片大小不对怎么办？**
A: 参考 [BMP_CODE_TRACE.md#检查点-3-分片信息](BMP_CODE_TRACE.md#检查点-3-分片信息)

**Q: 如何确认传输正确？**
A: 按 [BMP_QUICK_CHECKLIST.md#完整性检查表](BMP_QUICK_CHECKLIST.md#完整性检查表) 检查所有项

**Q: 设备收不到怎么办？**
A: 按 [BMP_TRANSMISSION_VISUAL.md#最终排查流程](BMP_TRANSMISSION_VISUAL.md#最终排查流程) 一步步检查

---

## 🏆 总体评估

✅ **BMP 二维码传输完全正确**

**验证状态**:
- ✅ 类型字段: TYPE = 0x01 (BMP)
- ✅ 标志字段: FLAGS = 0x0020
- ✅ MTU 分片: 512 字节首片
- ✅ 总数据: 5032 字节完整
- ✅ 无 EOF: BMP 不需要结束标记
- ✅ SHOW_PAGE: 发送显示命令

**预期结果**:
设备应该能够正确接收、解析并显示二维码 🎉

---

## 📞 需要帮助？

1. **快速查找信息** → 使用本文件的目录和搜索功能
2. **诊断问题** → 打开 [BMP_QUICK_CHECKLIST.md](BMP_QUICK_CHECKLIST.md)
3. **理解细节** → 阅读 [BMP_CODE_TRACE.md](BMP_CODE_TRACE.md)
4. **看流程图** → 查看 [BMP_TRANSMISSION_VISUAL.md](BMP_TRANSMISSION_VISUAL.md)
5. **了解改动** → 查阅 [PROTOCOL_FIX_SUMMARY.md](PROTOCOL_FIX_SUMMARY.md)

---

**最后更新**: 2026-01-24  
**版本**: 1.0  
**状态**: ✅ 已验证，传输流程正确
