# AGENTS.md — Pixiv-Shaft 工作区说明

PixShaft（代号 Shaft）是第三方 Pixiv Android 客户端：Kotlin 为主 + 遗留 Java，MVVM + Material 3，Retrofit / Room / Glide / Cronet（QUIC 直连）/ ONNX Runtime（端侧 AI）。主分支 `classic`。

## 常用命令

Flavor 维度 `channel`：`github`（完整版，GitHub 发布）和 `google`（Play 精简版 `IS_LITE=true`）。buildType debug 包名后缀 `.cshaft`，release 后缀 `.pshaft`。JDK 17，compileSdk 36 / minSdk 24。

```bash
./gradlew assembleGithubDebug              # 调试 APK
./gradlew assembleGithubRelease            # 发布 APK（签名读根目录 keystore.properties 或 SHAFT_KEYSTORE_* 环境变量，缺省回退 debug 签名）
./gradlew testGithubDebugUnitTest          # JVM 单测（app/src/test）
./gradlew testGithubDebugUnitTest --tests "ceui.pixiv.db.synonym.*"   # 聚焦单个包/类
./gradlew connectedGithubDebugAndroidTest  # 设备测试（Room 迁移测试从 app/schemas 读历史 schema）
./gradlew lintGithubDebug                  # lint：abortOnError，NewApi fatal，存量问题在 lint-baseline.xml
```

app 有 C++ 原生部分（CMake 3.22.1，C++20，HMAC 事件签名密钥构建期注入生成头文件）；密钥材料绝不进 git / BuildConfig。

## 模块与架构边界

- `:app` — 主应用。`ceui.lisa.*` 是历史包（大量 Java，如 `utils/Settings.java`）；**新代码一律放 `ceui.pixiv.*` 子包**（db / ui / http / feeds / snapshot 等）。
- `:feeds` — 全 app 列表页共用骨架（FeedSource / FeedRenderer / FeedViewModel / FeedFragment），**对 pixiv 零依赖**，宿主特有逻辑由 ShaftFeedHost 在 Shaft.onCreate 注入。改列表页先读 `docs/feeds-module.md`。
- `:actionqueue` — 收藏 / 关注等写操作的持久化限流队列（入队即返回、串行发送、429 整队冷却、进程被杀后续发）。写操作走队列，不要点一次发一次请求。见 `docs/action-queue.md`。
- `:models` — API 数据模型；`:progressmanager` / `:flowlayout-lib` / `:witstudio` 为独立小库。
- Room schema 变更必须把 `app/schemas` 下的 JSON 随版本一并提交（迁移测试与 code review 的输入）。
- `:app` 用 kapt（Glide 编译器只有 kapt 版），新模块一律用 KSP；kotlinter 已停用（kotlinter 与 Kotlin 2.1 KGP 冲突），不要重新启用。

## 约定

- 提交信息：conventional commits + 中文主题，如 `feat(synonym): 同义词命中改为置顶不再自动勾选`。
- `strings.xml` 改动必须同步全部 locale：默认（中文）+ en / ja / ko / ru / tr / zh-rTW。辅助脚本 `scripts/sort_locale_strings.py`、`scripts/find_missing_used_strings.py`。
- lint 的 `NewApi` 是 fatal：minSdk 24 但 compileSdk 36，跨版本 API 调用编译期无感、老设备运行时才炸（参见 #953）。
- 发版走 `/genupdate` skill（`.agents/skills/genupdate/SKILL.md`）：版本 commit 直接 push `classic` 不开 PR，tag 必须指向 `chore(release): X.Y.Z`，APK 固定在 `app/github/release/app-github-release.apk`，命名 `PixShaft_{version}_classic.apk`。

## 本机开发环境（仅这台 Windows 机器）

- SDK 在 `G:\Android\Sdk`；`GRADLE_USER_HOME=G:\Android\gradle-home`（全局 init.gradle 把阿里云镜像插到所有仓库最前，命令行构建依赖它，环境变量异常时需手动 export）。
- `gradle/wrapper/gradle-wrapper.properties` 的 distributionUrl 已本地改成腾讯镜像——**未提交的本地改动，勿"还原"也勿提交**。本机 `repo.maven.apache.org` TLS 被劫持、`services.gradle.org` 被 RST，依赖解析全靠镜像。
- 模拟器：`G:/Android/Sdk/emulator/emulator.exe -avd shaft_test -no-snapshot`（需 `ANDROID_AVD_HOME=G:\Android\avd`），AVD 里已登录测试账号，可实测收藏 / 标签类功能。
- 盲测 UI 用 `adb exec-out uiautomator dump /dev/tty`（Git Bash 里 adb shell 的路径参数会被 MSYS 改写，用 exec-out 规避）；`scripts/emulator_install_debug.sh` 可装 debug 包。

## 改动前先读

- `docs/feeds-module.md` / `docs/action-queue.md` — 列表框架 / 写操作队列的模块边界。
- `docs/direct-connect.md` / `docs/image-host.md` — Cronet 直连 / 图片源切换，两者强互斥，改网络层必读。
- `docs/ws-chat-integration.md` — 聊天 WebSocket 的服务端契约。
