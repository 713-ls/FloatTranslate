# FloatTranslate — Android 全局悬浮窗英汉互译小插件

支持 **Android 8.0+ (API 26+)** 的轻量级悬浮窗翻译工具，专为阅读英文网页、技术文档、代码块、PDF 与各类 App 设计。

## 核心功能
1. **双向快速切换**：悬浮窗顶部一键切换 `EN → ZH`（英译中）与 `ZH → EN`（中译英）。
2. **双取词模式切换**：
   - **节点读词模式**（基于 `AccessibilityService`）：直接读取浏览器网页、代码块、微信或其它 App 页面中的文本节点，零截屏、响应快。
   - **框选识图模式**（基于 `MediaProjection` + `ML Kit` 端侧中英 OCR）：遇到图片、扫描版 PDF 或禁止复制的页面时，拖动框选屏幕任意区域即可识别并翻译。
3. **代码分词清洗**：自动识别并拆分 `camelCase`（如 `getUserProfile`）和 `snake_case`（如 `motion_planning`）标识符。
4. **双翻译引擎**：
   - **免费接口（默认）**：使用 MyMemory 免费翻译 + Free Dictionary API（英文单词附带音标与词性），无需配置即可使用。
   - **自定义 API（复杂文献兜底）**：支持填入任意 OpenAI 兼容接口（如 DeepSeek、Gemini OpenAI 兼容端、OpenAI 等）的 `Base URL`、`API Key` 与 `Model`，遇到专业文献时一键切换。
5. **本地 SQLite 缓存**：查过的词条自动缓存在本地，重复查询秒出结果。

## 导入与打包 APK 步骤
1. 使用 **Android Studio**（建议 Ladybug / 2024.2 及以上）选择 **Open**，打开本文件夹 `FloatTranslate`。
2. 等待 Gradle Sync 完成（首次需联网下载依赖，包括 Google ML Kit 中文/拉丁文识别库与 OkHttp）。
3. 用数据线连接 Android 手机（开启 USB 调试），点击顶部 **Run** 直接安装，或选择 **Build → Build Bundle(s) / APK(s) → Build APK(s)** 生成安装包。

## 手机端权限开启顺序
1. **悬浮窗权限**：在首页点击「授予悬浮窗权限」并允许在其他应用上层显示。
2. **无障碍服务**（节点读词模式需要）：点击「开启无障碍读词服务」，在系统无障碍列表中找到 **FloatTranslate 节点取词** 并开启。
3. **屏幕录制权限**（框选识图模式需要）：点击「授权截屏识图」，在系统弹窗中确认（仅在点击框选时单帧截取，不会后台持续录屏）。

## GitHub 云端自动编译 APK（无需安装 Android Studio）
1. 解压 `FloatTranslate.zip`，确保能看到 `.github/workflows/build.yml`、`app/`、`build.gradle.kts` 等文件。
2. 在 GitHub 新建一个仓库（Public 或 Private 均可），将解压后 `FloatTranslate/` 文件夹内的所有文件上传到仓库根目录。
3. 打开仓库顶部的 **Actions** 标签页，等待 `Build Android Debug APK` 运行完成（约 2~4 分钟变成绿色对勾）。
4. 点进该次构建记录，在页面最下方的 **Artifacts** 区域下载 `FloatTranslate-debug-apk`，解压出 `app-debug.apk` 即可直接安装到 Android 手机。
