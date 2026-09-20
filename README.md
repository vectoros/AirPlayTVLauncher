# Aurora TV

一个面向 Android TV / Sony 电视的轻量桌面，以 Apple TV 首页的简洁观感为设计参考：大尺寸应用卡片、遥控器焦点动画、玻璃面板和缓慢流动的渐变山峦。项目独立开发，与 Apple、Sony、哔哩哔哩无关联。

![Sony 电视真机首页](docs/images/home.png)

文档：[开发与维护记录](docs/DEVELOPMENT.md) · [真机验证记录](docs/VERIFICATION.md)

## 功能

- 首页首个应用固定为哔哩哔哩 TV 入口；已安装的电视应用自动发现。
- 保留 HDMI 输入卡片和系统输入源入口。具体输入源的直达能力取决于电视固件及系统公开接口。
- 显示所选城市的当前天气、当日高低温及更新时间；网络异常时显示缓存状态。
- 默认天气城市为杭州，可在「设置 → 天气城市」或天气卡片中搜索并切换；不申请定位权限。
- 三款原创内置动态壁纸，使用 Android Canvas 绘制，最高 25fps；离开桌面停止动画，主题选择保存在本机。
- Android 12 / API 31 及以上使用 RenderEffect 模糊壁纸形成玻璃效果；更低版本使用半透明面板降级。

方向键移动焦点，确认键打开应用或输入源，菜单键打开设置。壁纸可在设置中切换。

## 构建

需要 JDK 17、Android SDK 35 和 Android SDK Platform Tools。项目使用 AGP 8.8.0、Gradle 8.10.2，最低 Android 8.0 / API 26，`targetSdk 35`、`compileSdk 35`。

设置 `ANDROID_HOME`，或在本地 `local.properties` 中配置 `sdk.dir`，然后执行：

```sh
./gradlew assembleDebug lintDebug
```

APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。Lint 报告输出到 `app/build/reports/`。GitHub Actions 配置会运行相同的构建和 Lint，并上传调试 APK 与报告；远端运行结果以实际工作流为准。

## 部署与恢复桌面

先在电视开启开发者选项及 ADB 调试，用 `adb devices` 确认设备并完成电视端授权。下文的 `SERIAL` 替换为实际设备序列号或网络 ADB 地址；公共仓库不保存私人设备地址。

在更换默认桌面之前，记录原桌面的组件名：

```sh
adb -s SERIAL shell cmd package resolve-activity --brief \
  -a android.intent.action.MAIN -c android.intent.category.HOME
```

部署并启动桌面：

```sh
./scripts/deploy.sh SERIAL
```

确认遥控器、应用和输入源入口符合预期后，设置为默认 HOME：

```sh
./scripts/set-home.sh SERIAL
```

需要恢复时，将 `ORIGINAL_COMPONENT` 替换为先前记录的组件名，例如 `com.example.launcher/.HomeActivity`：

```sh
./scripts/restore-home.sh SERIAL ORIGINAL_COMPONENT
```

输入源切换、系统 HOME 行为和开机启动表现可能随电视固件不同。已在 Sony BRAVIA 4K VH21 / Android 12 验证系统重启自动进入桌面、Home 返回及四个 HDMI 输入会话，详情见[真机验证记录](docs/VERIFICATION.md)。待机唤醒及各 HDMI 外设仍应在目标设备上分别验证。

## 源码结构

应用只有四个 Java 源文件，使用原生 Android UI、网络及绘图 API，无第三方运行时依赖：

| 文件 | 职责 |
| --- | --- |
| `MainActivity.java` | 首页、遥控器焦点、玻璃面板、设置与生命周期 |
| `LauncherRepository.java` | 应用发现、哔哩哔哩优先、HDMI 与系统入口 |
| `WeatherService.java` | 城市搜索、天气请求、本机缓存与错误处理 |
| `WallpaperView.java` | 原创动态背景、配色持久化与动画调度 |

壁纸是项目自身的程序化绘图实现，随代码采用 MIT 许可；当前没有集成第三方壁纸引擎，也不需要下载壁纸素材。

## 数据、隐私与许可

天气由 [Open-Meteo](https://open-meteo.com/) 提供，天气数据采用 [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)；城市搜索数据来自 [GeoNames](https://www.geonames.org/)。界面与本文保留数据来源署名。

当前使用 Open-Meteo 的免费非商业 API，无需 API Key。数据许可与托管 API 的使用条款是两回事：商业部署应按照 [Open-Meteo 服务条款](https://open-meteo.com/en/terms) 和 [商业方案](https://open-meteo.com/en/pricing) 配置合适的服务端点。

应用将城市搜索词、所选城市坐标发送至 Open-Meteo，以获取搜索结果和天气；城市设置、天气缓存及壁纸选择存于本机。不包含广告、账户系统或分析 SDK。电视上已安装应用的名称和图标属于各自权利人，运行时从设备读取。

项目代码和原创壁纸采用 [MIT License](LICENSE)。仓库提供 GitHub 开源所需的基础文件；不代表已经创建远端仓库或发布版本。
