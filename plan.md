## Plan: 悬浮窗漫画辅助阅读器 (Floating VLM Assistant)

这是一个为 Mihon 提供“极低打断感”的日语辅读工具。核心方案为：开启前台服务驻留悬浮窗 -> 遇到生词点击悬浮窗 -> 屏幕短暂冻结并允许框选 -> 直接调用大视觉模型（VLM）进行 OCR+翻译+语法解析 -> 底部弹窗展示结果并快速关闭。

**Steps**
1. **基础架构与权限配置**
   - 创建 Android 核心工程 (推荐 Kotlin + Jetpack Compose)。
   - 申请并在设置页引导获取核心权限：`SYSTEM_ALERT_WINDOW` (悬浮窗)、`FOREGROUND_SERVICE_MEDIA_PROJECTION` (屏幕录制，用于截图)。
   - 实现管理悬浮窗生命周期的 Foreground Service。
   - **[已实现细节]**：使用 Jetpack Compose 构建了 `MainActivity` 的设置页，通过 `SettingsManager` 提供本地 SharedPreferences 存储，允许用户独立配置 VLM 和 LLM 双模型的 API Key、Base URL 和 模型名称参数。在应用启动时动态请求所有必要权限。

2. **悬浮窗与截图机制 (平行开发)**
   - 渲染常驻悬浮按钮。可以拖拽贴边隐藏，降低视觉干扰。
   - 点击悬浮窗申请 `MediaProjection` 捕获当前屏幕的一帧 `Bitmap`，随后立即隐藏悬浮窗以免阻挡。
   - **[已实现细节]**：在 `FloatingService` 中通过 `WindowManager` 注入可拖拽的“译”按钮。引入了专门应对 Android 14+ 严格安全限制的 `ScreenCaptureManager` 方案，通过保持 `VirtualDisplay` 存活和动态切换 `Surface` 的方式，彻底解决了多次截图引发的 `SecurityException` 崩溃问题。

3. **框选交互层**
   - 截图完成后，在顶层拉起一个全屏的透明 `TYPE_APPLICATION_OVERLAY` 窗口，将刚才获取的截图作为背景（产生“冻结”视觉效果）。
   - 使用 Compose Canvas 或自定义 View 实现手势监听，允许用户画出一个矩形框（高亮框选区，周围加半透明遮罩）。
   - 抬手后，根据框选坐标对原截图进行局部 Crop。
   - **[已实现细节]**：通过 `OverlayScreenManager` 中的 Compose Canvas 实现。利用了 `BlendMode.Clear` 擦除暗色遮罩展示底层清晰截图；兼容了四个方向（如从右下往左上）反拉画框的绝对值坐标计算；加入了 `80px` 的 `minCropSize` 防手滑机制，画框不足尺寸时仅显示调暗的白边且不执行 OCR。

4. **大视觉模型 API 接入**
   - 将裁剪出的气泡图像转为 `Base64`。
   - 配置 OpenAI 格式接口调用（使用 Retrofit / Ktor），传入 System Prompt 预设行为（例如：“提取图片里的日文并翻译为中文，对重点单词/语法给出简明解析”）。
   - 在框选区或屏幕底部显示 Loading 态。
   - **[已实现细节]**：使用 OkHttp 原生构建网络层 `OpenAIApiClient`。为了最高保真地传输带注音（Ruby）假名的细小日文气泡，将截取的极小图像强制使用原生 `PNG 무损编码`。启用了 OpenAI 兼容的 `stream=true` 模式，逐行解析 Server-Sent Events (SSE) 数据，并使用 Kotlin `Flow` 向外发射流式字符。将请求超时时间提高至 3 分钟以应对长文本推流响应。

5. **结果展示与体验闭环**
   - 在底部的沉浸式面板（Bottom Sheet）展示流式（Streaming）或非流式 API 返回内容。
   - 面板提供关闭按钮，或者点击面板外区域立即销毁遮挡层，恢复 Mihon 的直接触控操作。
   - **[已实现细节]**：在 `FloatingService` 构造了动态生成的深色半透明 Bottom Sheet。除了输出最终纯文本，还通过解析大模型流里面的 `reasoning_content`，将例如 DeepSeek-R1 这类模型的内化思考链路过程，实时“打字机式”滚动渲染到底部带有 ScrollView 的面板上；并在面板结束时自动带上包含 `stream_options` 回传的 Token 开销统计模块。

**Relevant files**
- `app/src/main/AndroidManifest.xml` — 声明悬浮窗与前台服务权限。
- `app/src/main/java/../FloatingService.kt` — 管理悬浮按钮生命周期与屏幕录像 Token。
- `app/src/main/java/../OverlayScreen.kt` — 全屏冻结、框选交互 Compose UI 以及截图裁剪逻辑。
- `app/src/main/java/../LLMClient.kt` — 处理 OpenAI Compatible 视觉多模态 API 请求。

**Verification**
1. 启动悬浮窗，验证是否能在 Mihon 顶层显示且能自由拖拽。
2. 点击按钮，验证屏幕是否瞬间截图冻结，画框交互是否顺滑。
3. 手指抬起后，验证图像裁剪、Base64 编码并联通外部 API 的过程，确认返回了准确的解析内容。
4. 全局注销体验，验证点击空白处是否能立刻退出辅助层，继续漫画翻页。

**Decisions**
- **触发与选择机制**：悬浮窗 + 手势框选局部。
- **解析核心**：视觉大模型 (VLM) 直接识图不跑本地传统 OCR。
- **结果展示**：使用支持动态高度延展的 Bottom Sheet 沉浸式底部面板。
- **项目包名**：`me.sgly.translator`。
- **项目构建**：使用 Android Studio 官方模板创建 Jetpack Compose 项目。
- **最小 SDK 版本 (Min SDK)**：以 Android 14/15/16 (API 34+) 为基准，放弃旧设备兼容，使用最简洁的现代 API，并原生支持最新的前台服务规范 (`FOREGROUND_SERVICE_MEDIA_PROJECTION`)。

**Critical Technical Insights (防坑记录与核心机制)**
为防止后续 AI 代理或开发者在重构时踩坑，请务必注意以下已验证的核心机制：
1. **Android 14 截图崩溃防范**：Android 14 对 `MediaProjection` 限制极严。每次点击截图必须**复用**同一个 `VirtualDisplay`，仅通过动态切换/挂载 `ImageReader` 的 `Surface` 来实现多次截图。如果每次都通过 `createVirtualDisplay` 新建，会在第二次触发时抛出 `SecurityException` 崩溃。
2. **为何坚持使用 PNG 无损压缩**：大多数 VLM (如 OpenAI, GLM-4v 等) 的 Token 计费和推理速度仅与“图像像素分辨率 (Tiles 切割块数)”相关，与 Base64 字符串长度/文件字节大小**无关**。由于本身就是屏幕局部 Crop，必须使用 `CompressFormat.PNG, 100` 无损压缩并配搭 `data:image/png;base64` 头传输。这能完美保留极小字体和漫画中假名注音（Ruby）的清晰度，绝不可重用导致模糊伪影的 JPEG 压缩去尝试“省流量”。
3. **双模型级联拦截**：整体架构为串联工作流 `VLM (只负责 OCR 出日语原文) -> LLM (负责润色与系统翻译)`。部分模型（如 deepseek-v3 / glm-5.1）严禁传入空 Prompt 且会抛出难以辨认的 `400 Bad Request` 甚至跨国 JSON 非正常报错。因此在 `FloatingService.kt` 中设计了强拦截：当 VLM 输出空值或失败时，立刻中止协程并抛出提示，严防向 LLM 传递空字符引发脏日志。
4. **URL 智能拼接防 404**：设置页中的 Base URL 常被用户随心填写。代码网络层中加入了智能后缀组装逻辑：通过检测 `endsWith("/chat/completions")` 修正末尾路径，防止出现直接追加导致最终请求 `.../v1/chat/completions/chat/completions` 造成的网络 404 异常。
5. **反向框选坐标的绝对纠偏**：在 Compose Canvas 的手势监听中，必须由 `minOf` 和 `maxOf` 兜底生成高亮 `Rect` 的左上和右下绝对坐标。如果漏写纠偏规则，当使用者从右下往左上“反向画框”时，长宽为负值将导致底层渲染的高亮遮罩失效并误命中小尺寸拦截逻辑。

**Development Workflow (开发流程规范)**
1. **测试与部署**：每次代码修改完成后，使用终端串联执行 `./gradlew installDebug && adb shell am start -n me.sgly.translator/me.sgly.translator.MainActivity && adb logcat -c`，实现编译、安装、拉起前台 Activity 以及清空旧日志的自动化流，确保直接在真机上验证纯净的输出。
2. **文件读写约束**：环境终端（如 msys2 / fish）对特殊符号重定向支持不佳，**严禁**使用 `cat` 或 `echo` 等命令行工具向文件写入代码。必须优先使用系统提供的工具 (`read_file`, `replace_string_in_file`) 进行安全的代码查阅与修改。
3. **进度同步**：每次解决重要 Bug 或取得实际业务进展（Milestone）时，都必须主动回头更新本 `plan.md` 文档中的 `[已实现细节]` 与计划状态，保持项目上下文和设计脉络的绝对一致。