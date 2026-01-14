# GeckoView 网络请求拦截 API - 文档索引

> 📌 **快速导航**: 使用此索引快速找到您需要的文档和代码示例

---

## 📑 文档清单

### 🎯 根据需求选择文档

#### 我需要快速上手
→ 阅读: **[GECKOVIEW_QUICK_REFERENCE.md](GECKOVIEW_QUICK_REFERENCE.md)** (5-10 分钟)

**内容**:
- 3 分钟快速开始代码
- 常见操作代码片段
- 常见错误和修复
- FAQ

---

#### 我需要完整的 API 信息
→ 阅读: **[GECKOVIEW_NETWORK_INTERCEPT_API.md](GECKOVIEW_NETWORK_INTERCEPT_API.md)** (30 分钟)

**内容**:
- 完整接口文档
- 所有方法签名
- 详细使用示例
- 与其他 API 的对比
- 注意事项和最佳实践

---

#### 我需要具体的代码实现
→ 查看: **[NetworkInterceptorExample.kt](NetworkInterceptorExample.kt)**

**内容**:
- 3 种完整实现方案
  1. 基础拦截器 (简洁版)
  2. 高级拦截器 (功能丰富版)
  3. Activity 集成示例 (生产版)
- 每种方案都有详细注释

---

#### 我只需要查询研究结果
→ 查看: **[GECKOVIEW_API_RESEARCH_SUMMARY.md](GECKOVIEW_API_RESEARCH_SUMMARY.md)** (5 分钟)

**内容**:
- 问题的直接答案
- 核心答案和接口信息
- 使用示例代码
- 最佳替代方案

---

#### 我需要参考现实的项目实现
→ 查看: **[GeckoActivity.kt](app/src/main/java/com/guaishoudejia/x4doublesysfserv/GeckoActivity.kt#L370)**

**内容**:
- 实际生产环境使用
- 代理服务器集成
- 完整工作流程

---

## 🗂️ 按文件类型组织

### 📄 Markdown 文档

| 文件 | 大小 | 用途 | 时间 |
|------|------|------|------|
| [GECKOVIEW_QUICK_REFERENCE.md](GECKOVIEW_QUICK_REFERENCE.md) | 📋 Medium | 快速上手 | 5-10 min |
| [GECKOVIEW_NETWORK_INTERCEPT_API.md](GECKOVIEW_NETWORK_INTERCEPT_API.md) | 📚 Large | 完整参考 | 30 min |
| [GECKOVIEW_API_RESEARCH_SUMMARY.md](GECKOVIEW_API_RESEARCH_SUMMARY.md) | 📊 Medium | 查询结果 | 5 min |

### 💻 Kotlin 代码

| 文件 | 行数 | 内容 | 难度 |
|------|------|------|------|
| [NetworkInterceptorExample.kt](NetworkInterceptorExample.kt) | ~400 | 3 个完整方案 | ⭐⭐⭐ |
| [GeckoActivity.kt (第 370 行)](app/src/main/java/com/guaishoudejia/x4doublesysfserv/GeckoActivity.kt#L370) | ~40 | 生产实现 | ⭐⭐ |

---

## 🎯 常见问题对应文档

### Q: GeckoView 如何拦截网络请求？
**A**: 
- 快速答案 → [GECKOVIEW_API_RESEARCH_SUMMARY.md](GECKOVIEW_API_RESEARCH_SUMMARY.md#核心答案)
- 完整说明 → [GECKOVIEW_NETWORK_INTERCEPT_API.md](GECKOVIEW_NETWORK_INTERCEPT_API.md#正式-api-接口)

### Q: 有代码示例吗？
**A**:
- 最小示例 → [GECKOVIEW_QUICK_REFERENCE.md](GECKOVIEW_QUICK_REFERENCE.md#-3-分钟快速开始)
- 完整示例 → [NetworkInterceptorExample.kt](NetworkInterceptorExample.kt)
- 生产代码 → [GeckoActivity.kt](app/src/main/java/com/guaishoudejia/x4doublesysfserv/GeckoActivity.kt)

### Q: 如何修改请求内容？
**A**:
- 快速参考 → [GECKOVIEW_QUICK_REFERENCE.md](GECKOVIEW_QUICK_REFERENCE.md#-常见操作)
- 完整教程 → [GECKOVIEW_NETWORK_INTERCEPT_API.md](GECKOVIEW_NETWORK_INTERCEPT_API.md#💻-实现示例)

### Q: 与其他拦截方式有什么区别？
**A**: [GECKOVIEW_NETWORK_INTERCEPT_API.md](GECKOVIEW_NETWORK_INTERCEPT_API.md#📊-与-navigationdelegate-的区别)

### Q: 实际项目如何使用？
**A**: 
- 项目实现 → [GeckoActivity.kt](app/src/main/java/com/guaishoudejia/x4doublesysfserv/GeckoActivity.kt)
- 集成示例 → [NetworkInterceptorExample.kt](NetworkInterceptorExample.kt#geckonetwokinterceptoractivity)

---

## 📊 学习路径建议

### 路径 1: 快速学习（15 分钟）
```
1. 查看 GECKOVIEW_API_RESEARCH_SUMMARY.md (5 min)
   ↓
2. 阅读 GECKOVIEW_QUICK_REFERENCE.md (5 min)
   ↓
3. 复制 NetworkInterceptorExample.kt 的代码 (5 min)
```

### 路径 2: 深入理解（1 小时）
```
1. GECKOVIEW_API_RESEARCH_SUMMARY.md (5 min)
   ↓
2. GECKOVIEW_QUICK_REFERENCE.md (10 min)
   ↓
3. GECKOVIEW_NETWORK_INTERCEPT_API.md (30 min)
   ↓
4. NetworkInterceptorExample.kt 代码解析 (15 min)
```

### 路径 3: 实战应用（2 小时）
```
1. 阅读 GECKOVIEW_API_RESEARCH_SUMMARY.md
   ↓
2. 学习 GECKOVIEW_QUICK_REFERENCE.md
   ↓
3. 研究 GeckoActivity.kt 的实现
   ↓
4. 参考 NetworkInterceptorExample.kt 的三种方案
   ↓
5. 根据需求修改实现
```

---

## 🔍 快速查找

### 我想要...

| 需求 | 查看文件 | 具体位置 |
|------|---------|---------|
| 接口名称 | GECKOVIEW_API_RESEARCH_SUMMARY.md | 返回的接口名称和方法签名 |
| 方法签名 | GECKOVIEW_NETWORK_INTERCEPT_API.md | 核心方法签名 |
| 完整示例 | NetworkInterceptorExample.kt | 方案 1-3 |
| 修改 URI | GECKOVIEW_QUICK_REFERENCE.md | 2️⃣ 修改请求 URI |
| 修改请求头 | GECKOVIEW_QUICK_REFERENCE.md | 3️⃣ 修改请求头 |
| 拦截广告 | GECKOVIEW_QUICK_REFERENCE.md | 调试技巧 |
| 性能优化 | GECKOVIEW_NETWORK_INTERCEPT_API.md | 注意事项 |
| 常见错误 | GECKOVIEW_QUICK_REFERENCE.md | 🔴 常见错误 |
| 项目实现 | GeckoActivity.kt | setupRequestInterceptor 方法 |

---

## 💡 核心概念速览

### 什么是 WebRequestDelegate？
```
GeckoView 提供的网络请求拦截接口
↓
可以拦截所有 HTTP/HTTPS 请求
↓
包括子资源（图片、脚本、样式）
↓
支持修改请求内容
↓
是最强大的拦截方式
```

### 基本流程
```
用户在 GeckoView 中浏览网页
        ↓
页面加载任何资源（包括子资源）
        ↓
触发 WebRequestDelegate.onLoadRequest()
        ↓
你的拦截逻辑运行
        ↓
返回修改后的请求或 null
        ↓
GeckoView 加载资源
```

### 最常见的用法
```
原始请求: https://example.com/page
        ↓
拦截并转换为: http://proxy:8080/forward?url=https://example.com/page
        ↓
代理服务器处理请求
        ↓
返回内容给 GeckoView
```

---

## 📈 文档统计

| 项目 | 数量 |
|------|------|
| Markdown 文档 | 4 个 |
| Kotlin 代码示例 | 3 个完整方案 |
| 代码行数 | ~400 行 |
| API 接口 | 1 个（WebRequestDelegate） |
| 核心方法 | 1 个（onLoadRequest） |
| 使用示例 | 10+ 个 |

---

## 🔗 相关资源

### 官方资源
- [GeckoView 官方网站](https://mozilla.github.io/geckoview/)
- [WebRequest JavaDoc](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/WebRequest.html)
- [GeckoSession 源码](https://searchfox.org/mozilla-central/source/mobile/android/geckoview)

### 项目中的相关文件
- [GeckoActivity.kt](app/src/main/java/com/guaishoudejia/x4doublesysfserv/GeckoActivity.kt) - 主 Activity
- [WeReadProxyClient.kt](app/src/main/java/com/guaishoudejia/x4doublesysfserv/WeReadProxyClient.kt) - 代理客户端
- [RemoteServe/proxy_handler.go](../BleReadBook/RemoteServe/handler/proxy_handler.go) - 代理服务器

---

## ✅ 检查清单

在开始使用 GeckoView WebRequestDelegate 前，确保：

- [ ] 已经阅读了 GECKOVIEW_API_RESEARCH_SUMMARY.md
- [ ] 了解 WebRequestDelegate 接口的签名
- [ ] 有完整的代码示例（参考 NetworkInterceptorExample.kt）
- [ ] 理解了返回 null vs LoadRequestReturn 的区别
- [ ] 知道如何复制请求头
- [ ] 了解了需要跳过的特殊 URI
- [ ] 准备好进行实际集成

---

## 🎓 推荐阅读顺序

### 对于时间有限的开发者 ⏱️
1. GECKOVIEW_QUICK_REFERENCE.md (5 min)
2. NetworkInterceptorExample.kt - 方案 1 (5 min)
3. 开始编码！

### 对于想深入理解的开发者 📚
1. GECKOVIEW_API_RESEARCH_SUMMARY.md (5 min)
2. GECKOVIEW_NETWORK_INTERCEPT_API.md (30 min)
3. NetworkInterceptorExample.kt 所有三个方案 (30 min)
4. GeckoActivity.kt 实现 (10 min)
5. 参考官方文档

### 对于项目维护者 🔧
1. GECKOVIEW_API_RESEARCH_SUMMARY.md
2. GeckoActivity.kt 实现
3. NetworkInterceptorExample.kt - 方案 2（高级）
4. 定期检查官方更新

---

## 📞 获取帮助

如果您在使用过程中遇到问题：

1. **检查常见错误** → GECKOVIEW_QUICK_REFERENCE.md - 🔴 常见错误
2. **查看 FAQ** → GECKOVIEW_QUICK_REFERENCE.md - 🤔 FAQ
3. **查询调试技巧** → GECKOVIEW_QUICK_REFERENCE.md - 🔧 调试技巧
4. **参考完整文档** → GECKOVIEW_NETWORK_INTERCEPT_API.md
5. **查看项目实现** → GeckoActivity.kt

---

## 📝 文档版本

- **版本**: 1.0
- **更新时间**: 2026-01-14
- **GeckoView 版本**: 120+
- **Android 最低版本**: API 21
- **作者**: 自动化 API 文档生成系统

---

**祝您使用愉快！** 🚀
