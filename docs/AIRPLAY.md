# Aurora AirPlay 接收（Launcher 0.6.0）

Launcher 与 GPL 接收器分别安装、使用同一签名。默认启用 AirPlay：开机或进入桌面自动启动后台前台服务，无需打开接收器、点击 Start。iPhone、iPad、Mac 在同一局域网选择 **Aurora TV**。

## 使用与设置

首页 AirPlay 或「设置 → AirPlay 接收」直接打开 Launcher 内的中英文设置面板：

- **启用 AirPlay**：统一控制接收、开机启动与后台运行。关闭会结束当前投屏；返回首页、服务重建或重启电视都不会重新开启。重新开启后恢复广播。
- **设备名称**：1–40 个字符，不允许控制字符；更名会重新发布服务。
- **连接时要求 PIN**：开启后，配对码在电视投屏界面显示。更改名称或 PIN 选项前会提示当前会话将中断。
- **状态 / 刷新**：显示关闭、启动中、可接收、投屏中或启动失败。旧接收器需与 Launcher 一起更新。
- **允许自动显示投屏画面**：缺少悬浮显示权限时进入系统授权页面。ADB 配套安装脚本可授予该权限；普通安装需用户操作。
- **高级设置**：进入独立接收器调整编码、分辨率、帧率和音频参数。启动及后台选项统一由 Launcher 开关管理；高级界面的 Stop 同样关闭总开关，Start 可重新启用。

真实镜像尺寸、视频播放或 PIN 配对事件到来时进入全屏播放页，跳过接收器概览和设置。纯 TCP / RTSP 探测不会切走桌面。发送端断开后约 1.5 秒退出播放页，返回之前的界面；遥控器返回键结束本次投屏，接收服务继续等待下一台设备。自动投屏模式不自动进入画中画，电视播放保持横屏。纯音频在后台播放。

## 架构与恢复

- 接收器保存唯一持久化总开关；Launcher 不另存一份。`AirPlayCompanion` 在后台线程通过 `content://dev.aurora.airplay.control` 读取 / 更新配置，在主线程回调设置界面。
- Provider 和导出服务均由签名权限 `dev.aurora.tv.permission.CONTROL_AIRPLAY` 保护，Provider 的 `call` 额外显式检查权限。不返回配对码、发送端地址或媒体信息。两个 APK 必须同签名。
- Launcher `onResume` / `onNewIntent` 只在开关开启时确保服务运行；启动服务不打开 Activity。`BOOT_COMPLETED`、快速启动及包更新广播也遵守总开关。
- 服务及时提升为前台服务并返回 `START_STICKY`；关闭状态收到前台启动请求时也先满足时限，再立即释放并停止，避免仍被 Activity 绑定时触发系统超时。初始化失败 30 秒后重试。默认网络变化防抖 1.5 秒后重建原生服务及发现广播。关闭 / 销毁时取消重试，释放组播锁、唤醒锁、播放器和原生资源。关闭配置还主动释放仍被播放界面绑定的服务，避免仅 stopService 留下接收实例。
- 自动播放使用不导出的 `PlaybackActivity` 别名进入同一 Activity。普通应用不能通过外部 Intent 冒充自动播放入口。断开时清理会话状态，避免停留在空播放页。

“常驻”依赖 Android 前台服务及重建机制。厂商后台限制、强行停止和深度待机仍会影响运行；不是系统不可杀进程。网络恢复路径已实现，真实网络切换和长时间待机仍需逐机验证。

## DiPlay 参考范围

参考 [DiPlay](https://github.com/shihabal3amri/DiPlay) 固定提交 `6d3b8a8ce927cb4b115b8c3881f17619433e93b1` 中的 [BootReceiver](https://github.com/shihabal3amri/DiPlay/blob/6d3b8a8ce927cb4b115b8c3881f17619433e93b1/common/src/main/java/com/shilapi/xcertplay/BootReceiver.kt)、[DiPlaySessionService](https://github.com/shihabal3amri/DiPlay/blob/6d3b8a8ce927cb4b115b8c3881f17619433e93b1/common/src/main/java/com/shilapi/xcertplay/DiPlaySessionService.kt) 和 [DiPlayBootstrap](https://github.com/shihabal3amri/DiPlay/blob/6d3b8a8ce927cb4b115b8c3881f17619433e93b1/common/src/main/java/com/shilapi/xcertplay/DiPlayBootstrap.kt) 的服务生命周期与开机协调思路。

DiPlay 主要面向车机 CarPlay，不能直接替换电视 AirPlay 接收器。本项目未引入其源码、附件身份或认证材料；协议实现仍为既有 android-airplay-server / UxPlay，改造集中于电视上的自动接收流程。

## 构建和安装

```sh
# JDK 17+，接收器 SDK 36；Launcher SDK 35
./scripts/install-airplay.sh SERIAL
```

脚本构建并安装两包、授予接收器自动显示权限、停止可能冲突的旧上游接收器，最后启动 Launcher；不更改默认 HOME 或卸载其他应用。

接收器基于 [android-airplay-server v0.0.31](https://github.com/jqssun/android-airplay-server/tree/v0.0.31)，包名 `dev.aurora.airplay`、版本 `0.0.31-aurora.2`。固定上游子模块保持不变，修改保存于 `patches/airplay-resident.patch`，沿用 GPL-3.0-only；Launcher 保持 MIT。

本机脚本重编译 Kotlin / Java / 资源，复用经 SHA-256 校验的上游四个原生库，只打包 `armeabi-v7a`，不能用于纯 64 位系统。协议栈未重新编译；完全源码构建及发布义务见 [补丁说明](../patches/README.md) 和 [第三方来源](../third_party/README.md)。

## 检查与支持边界

```sh
dns-sd -B _airplay._tcp local.
dns-sd -B _raop._tcp local.
python3 scripts/check-airplay.py TV_IP

# Launcher 默认 29 项；加 airplay 参数执行跨包控制检查并恢复原设置
./gradlew assembleDebug assembleDebugAndroidTest lintDebug
adb -s SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s SERIAL shell am instrument -w -e airplay true \
  dev.aurora.tv.test/dev.aurora.tv.RegressionInstrumentation

# 接收器会话流程测试；运行前结束真实投屏，测试进程会重新启动
artifacts/airplay-resident-build/gradlew -p artifacts/airplay-resident-build \
  assembleDebugAndroidTest -PprebuiltNative=true
adb -s SERIAL install -r artifacts/airplay-resident-build/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s SERIAL shell am instrument -w \
  dev.aurora.airplay.test/io.github.jqssun.airplay.AuroraRegressionInstrumentation
```

跨包测试短暂更改开关、名称和 PIN 选项，最后恢复原配置；检测到投屏时跳过状态变更。接收器测试使用模拟协议回调验证抢屏、进出播放页和关闭后开机广播，不代替 Apple 设备或解码器验收。

上游采用 UxPlay legacy 协议，不支持完整 AirPlay 2 多房间同步及 Apple DRM 视频。流程以 Apple TV 的自动接收体验为目标，不承诺协议或全部应用兼容性等同 Apple TV。Bonjour / RTSP 只能证明发现和入口工作；真实音画、网络与待机验证范围见 [验证记录](VERIFICATION.md)。
