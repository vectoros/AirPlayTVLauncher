# 中文与英文显示 / Chinese and English

Launcher 默认跟随系统语言：中文使用简体中文资源，英文使用英文资源，其他未翻译语言回退到英文。没有独立语言开关。系统语言变化会重建 Activity，无需清除数据；壁纸、照片及手动城市选择保持不变。日期采用当前地区格式，时钟遵循系统 12/24 小时设置。

The launcher follows the system language: Simplified Chinese for Chinese locales, English otherwise. Changing language does not clear wallpaper, photo, or city preferences. Dates follow the active locale; time follows the system’s 12/24-hour preference.

## 覆盖范围

- 首页、全部应用、HDMI 状态、设置和确认弹窗。
- 壁纸名称、轮播效果、照片数量单复数、相册按钮及权限/错误提示。
- 天气状态、温度范围、位置来源、更新时间及离线缓存提示。缓存来源使用稳定标识，不保存新翻译文案；旧版缓存来源也在显示时翻译。
- 城市搜索与 IP 定位按照当前中英文语言请求结果。用户已选择的城市名、离线缓存中的城市名、相册名和照片文件名不自动翻译；天气恢复联网定位或重新选择城市后更新服务提供的名称。
- 独立 AirPlay 接收端补充中文设置、状态、通知和播放器控件，英文保留上游资源。调试 API 常量、协议字段、外部设备名称和日志不强制翻译。

英文设置按钮使用内容宽度；天气面板增加空间并自动调节字号；相册操作行允许横向滚动。12 小时时钟使用单行自动字号，避免 AM/PM 被截断。

## 维护约定

Launcher 的默认英文资源位于 `app/src/main/res/values/strings.xml`，中文位于 `values-zh/strings.xml`。两套资源的名称和格式参数应一致。动态文案使用完整的 `%1$s` / `%1$d` 模板，照片数量使用 plurals；不要拼接翻译碎片或在 Java 中重新硬编码中文。

AirPlay 中文资源作为新增文件保存在 `patches/airplay-resident.patch`，通过 `scripts/build-airplay.sh` 应用。保留固定上游子模块不变；接收端修改继续遵循 GPL，不属于 Launcher 的 MIT 代码。

## 验证记录（2026-09-24）

- Launcher `assembleDebug assembleDebugAndroidTest lintDebug` 成功，Lint 0 errors / 11 warnings；未添加运行时依赖。
- 127 项 Launcher 资源（含 plurals）中英文键集合及字符串格式参数核对一致；AirPlay 148 项中文资源覆盖上游字符串和音频缓冲选项数组。
- 独立接收端构建成功；未在 ARM64 模拟器运行仅含 ARMv7 库的接收端。
- Android 15 / API 35、ARM64、1920×1080、density 320 模拟器上，29 项 instrumentation 检查通过：12 项照片、8 项轮播调度、9 项语言检查。
- 人工检查中英文首页，以及英文设置和相册空状态：天气在线语言切换、日期、AM/PM 和较长按钮正常显示。截图仅保存在忽略目录 `artifacts/localization/`。
- 模拟器是通用 Android 镜像，不是 Sony TV 固件；本轮未复测 Sony、HDMI、AirPlay 实播或其他 Android 版本，也不以模拟器速度作为电视性能数据。

在 Android 13+ 测试设备上可通过应用语言覆盖隔离验证，而不修改整机语言：

```sh
adb -s SERIAL shell cmd locale set-app-locales dev.aurora.tv --user 0 --locales en-US
adb -s SERIAL shell cmd locale set-app-locales dev.aurora.tv --user 0 --locales zh-CN
# 测试后清除覆盖，恢复跟随系统。
adb -s SERIAL shell cmd locale set-app-locales dev.aurora.tv --user 0 --locales ''
```

Android 8–12 使用系统语言设置切换。还应检查大字体、长城市名、离线错误信息、权限拒绝和遥控器焦点；第三方应用是否提供对应语言标签由其自身资源决定。
