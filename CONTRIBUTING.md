# 参与贡献

感谢你愿意改进翻翻。项目仍处于早期阶段，提交改动前请先阅读下面的约定。

## 先讨论，再动手

- 修复明确的小问题可以直接提交 Pull Request。
- 新功能、交互重做、数据库结构变化或权限调整，请先创建 Issue 说明场景和方案，避免重复工作。
- 安全或隐私问题不要附带真实照片、设备日志中的个人路径或其他敏感信息；请选择合适的私密报告渠道，待仓库维护者公布后再提交细节。

## 开发环境

- JDK 25
- Android SDK Platform 37
- Android Studio，或 Android SDK Command-line Tools

在仓库根目录验证环境和项目（Android 工程位于 `android/`）：

```bash
cd android && ./gradlew lintDebug testDebugUnitTest assembleDebug assembleRelease
```

如果改动涉及权限、播放、动态照片或系统回收站，还需在模拟器或专用测试设备上验证。请勿使用唯一副本或私人媒体测试删除流程。

## 代码约定

- 遵循现有 Kotlin 与 Compose 风格，保持格式和命名一致。
- 优先让 `flip` 中的状态逻辑保持为可在 JVM 上测试的纯 Kotlin 代码。
- MediaStore 和 Room 操作不得阻塞主线程；媒体列表加载应优先查询系统索引，避免启动时逐文件扫描。
- 新增用户可见文案时使用简洁中文，并补充有意义的无障碍描述。
- 注释重点解释设计原因、平台限制和容易踩坑的行为，不要复述代码。
- 不做与当前改动无关的批量格式化或依赖升级。

## 提交建议

建议每个提交只表达一个清晰意图。提交标题可使用以下前缀，但不强制：

```text
feat: 新功能
fix: 问题修复
perf: 性能优化
refactor: 不改变行为的重构
test: 测试
docs: 文档
build: 构建与依赖
```

## Pull Request 检查清单

- [ ] 改动范围单一，未包含本地配置、签名文件、构建产物或私人媒体。
- [ ] `cd android && ./gradlew lintDebug testDebugUnitTest assembleDebug assembleRelease` 通过。
- [ ] 新增或变化的状态逻辑有相应单元测试。
- [ ] 界面改动附有前后截图，并检查浅色/深色媒体背景下的可读性。
- [ ] 权限或删除行为说明了测试的 Android 版本、授权范围与系统确认结果。
- [ ] 用户可见行为、构建方式或目录变化已同步更新 README。

## 许可证

项目采用 [Apache License 2.0](LICENSE)。提交贡献即表示你同意按同一许可证提供相关代码与文档。
