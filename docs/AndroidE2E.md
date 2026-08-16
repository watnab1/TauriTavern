# TauriTavern Android Debug/E2E 构建与宿主契约

本文档描述外部 runner 如何稳定地驱动 Android Debug/E2E APK，以及 CI 产物的契约。

## 1. 构建变体

| 变体 | applicationId | versionName | Rust profile | Kotlin/R8 | debuggable | JNI debuggable | WebView CDP | cleartext | 签名 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Debug | `com.tauritavern.client.debug` | `2.2.0-debug` | dev | 不 minify | true | true | 显式开启 | true | Android Debug key |
| E2E | `com.tauritavern.client.e2e` | `2.2.0-e2e` | release（`panic=abort`, `lto`, `opt-level=s`, strip） | R8 shrink + resource shrink；AGP 对 debuggable 变体禁用 obfuscation/optimization | true | false | 显式开启 | true | 每次构建本地生成的专用非生产 key（`CN=TauriTavern E2E`） |
| Production Release | `com.tauritavern.client` | `2.2.0` | release | R8 minify + shrink | false | false | 显式关闭 | false（本 workflow 的默认 Gradle 契约；移动端发布流程仍由仓库既有 canary/stable 策略管理） | 生产 keystore（仓库 secrets，不参与本 workflow） |

Debug、E2E、Production 的 applicationId 完全隔离，卸载 `com.tauritavern.client.debug` / `com.tauritavern.client.e2e` 即可清理对应数据目录，不影响正式包。

`WebView.setWebContentsDebuggingEnabled` 在 `MainActivity.onWebViewCreate()` 的最早位置显式执行：

```kotlin
WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG || BuildConfig.E2E_ENABLED)
```

- Debug：`BuildConfig.DEBUG == true`，`E2E_ENABLED == false`；
- E2E：`BuildConfig.DEBUG == true`（因为 debuggable），`E2E_ENABLED == true`；
- Production Release：两个字段均为 `false`，显式传入 `false` 关闭；
- 不依赖 WebView 113+ 的 `android:debuggable` 自动推断，也不在 Manifest 写死 debuggable。

## 2. 本地构建

```bash
pnpm install --frozen-lockfile

# Debug APK（会同时初始化 Android Gradle 工程）
pnpm android:build --debug --apk --split-per-abi --target aarch64

# Release-like E2E APK（依赖上一步生成的工程；脚本会自动先跑 Debug 前置构建）
pnpm android:build:e2e

# Android/Kotlin 行为测试
(cd src-tauri/crates/tauritavern/gen/android && ./gradlew :app:testArm64DebugUnitTest)

# 产物契约验证
node scripts/ci/verify-android-build.mjs \
  --apk src-tauri/crates/tauritavern/gen/android/app/build/outputs/apk/arm64/debug/app-arm64-debug.apk \
  --build-type debug --metadata-out dist/build-metadata.json
node scripts/ci/verify-android-build.mjs \
  --apk src-tauri/crates/tauritavern/gen/android/app/build/outputs/apk/arm64/e2e/app-arm64-e2e.apk \
  --build-type e2e --metadata-out dist/build-metadata-e2e.json
```

E2E Gradle 变体通过 `rustBuild*E2e` 直接调用 Cargo Release profile 并更新 `jniLibs` 软链，不依赖 Android Studio 的 Tauri CLI options server，因此可以用 `./gradlew :app:assembleArm64E2e` 独立驱动。

## 3. E2E 宿主契约

`scripts/android-webview-preflight.mjs` 是通用只读 preflight/inspection 脚本，命令入口为：

```bash
pnpm android:preflight \
  --apk dist/app-arm64-debug.apk \
  --package com.tauritavern.client.debug \
  --build-metadata dist/build-metadata.json \
  --smoke --json
```

脚本按以下顺序工作：

1. 按 package name 启动 launcher Activity；
2. `pidof <package>` 必须返回唯一 PID，zero/multiple 均失败；
3. 读取 `/proc/net/unix` 与 `/proc/<pid>/fd`，只选择 inode 同时属于该 PID 的 `webview_devtools_remote_*` socket；
4. zero/multiple candidate 均失败，绝不默认选取第一个 WebView socket，也绝不触碰其他应用的 socket；
5. 用临时 `adb forward tcp:<port> localabstract:<socket>` 连接 `/json/list` 与 `/json/version`；
6. 只接受唯一 `type=page` target，并用其 `webSocketDebuggerUrl` 建立 CDP；
7. 读取 User-Agent、WebView 版本、页面 URL、viewport、display、Android release/SDK、Activity resumed/focused、system UI visibility；
8. 支持 `--navigate`、`--eval`、`--tap`、`--screenshot`、`--screenrecord`；
9. `--smoke` 会启动本地 `127.0.0.1` fixture + `adb reverse`，导航到不含 `#sheld` 的外部页面，验证：
   - `document.getElementById('sheld')` 为 `null`；
   - `window.__TAURITAVERN_INSETS__` 不存在；
   - `--tt-inset-top` 未注入；
10. 然后 force-stop 并重新启动应用，重新发现 socket/CDP，等待自有页面 `#sheld` 与 insets bridge 恢复；
11. 正常退出、异常、SIGINT/SIGTERM 都清理本轮精确 forward/reverse；清理后校验 `adb forward --list` 不再包含本轮条目。

不需要 deep link。CDP `Page.navigate` 在 WebView target 上可直接工作，因此未增加 `tauritavern-e2e://open` custom scheme，也没有 Debug/E2E manifest overlay。

## 4. CI（`.github/workflows/android-debug-build.yml`）

- 保留 `workflow_dispatch`；
- `dev` push 与 PR 受 paths filter 限制自动触发（Android/Tauri host、frontend 输入、lockfile、workflow 自身）；
- `cancel-in-progress: true`；
- 构建 Debug 与 E2E APK，并生成 Production Release processed manifest 契约；
- 对每个 APK 执行 `apksigner verify --verbose --print-certs`、`aapt2 dump badging`、`aapt2 dump xmltree`、native library 与 ABI 检查；
- 验证 applicationId、versionName/versionCode、debuggable、cleartext、ABI、签名者；
- 从 APK dex 验证 `configureWebViewDebugging` 调用 `WebView.setWebContentsDebuggingEnabled(Z)`，并解析 `BuildConfig.DEBUG` 与 `E2E_ENABLED` 的实际字节码常量；
- 将 APK 内 `lib/arm64-v8a/libtauritavern_lib.so` 的 SHA-256 与 `target/aarch64-linux-android/{debug,release}/libtauritavern_lib.so` 比对：Debug 必须等于 dev profile 产物，E2E 必须等于 Release profile 产物；
- 生成 `SHA256SUMS`、`build-metadata.json`（Debug）与 `build-metadata-e2e.json`；
- 有 arm64 设备/模拟器时自动执行 Debug + E2E runtime smoke；GitHub `ubuntu-latest` 没有 arm64 Android 设备时，显式打印 `NO_DEVICE_BOUNDARY` 并跳过，不把静态 APK 检查描述成运行时验证；
- 仅 `workflow_dispatch` 发布 GitHub prerelease；push/PR 只上传 artifacts。

## 5. 安全边界

- Production package ID、签名、权限和数据目录不变；
- Production WebView debugging 显式关闭；
- Production cleartext 与 E2E deep link 契约在 processed manifest 中验证；
- 没有新增 `@JavascriptInterface`、没有 E2E deep link、没有扩大 Tauri permissions、没有增加 `tauri-plugin-pilot` capability；
- 外部主 frame 导航后，app-owned JS bridges 与本地 Wry `Ipc.kt` override 均 fail-closed，`window.ipc.postMessage` 不再进入 Tauri command surface；
- 不读取/清理正式用户数据，不清理全局 WebView cache，不关闭硬件加速、系统动画，不修改 renderer/GPU 策略，不做设备/WebView 品牌特判。

## 6. 已知边界

- `ubuntu-latest` runner 不能执行 arm64 Android runtime smoke；如需设备验证，使用仓库提供的 preflight 脚本在 arm64 真机/模拟器上运行；
- E2E 变体 `isDebuggable=true` 时 AGP 会禁用 R8 obfuscation 和 optimization，但仍执行 shrink（`isMinifyEnabled=true` + `isShrinkResources=true`）；因此 E2E 是 Release-like，不是 bit-identical Production Release；
- 主 frame 外部页面已被 app-owned bridges 和 Wry `Ipc` fail-closed；Android `addJavascriptInterface` 是 WebView 级注入，无法按 iframe origin 过滤。自有页面内嵌的第三方 iframe 仍共享同一 WebView 的 legacy bridge 面，这是 Wry/Android 的既有限制；彻底修复需要迁移到 `WebMessageListener` 的 allowed-origin 机制，不在本次范围；
- 性能对比必须固定使用同一 build variant、同一 WebView provider 与同一录屏配置。
