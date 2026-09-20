# 真机验证记录

设备：Sony BRAVIA 4K VH21，Android 12 / API 31；系统配置为 1920×1080 输出，density 320。日期：2026-09-21。

## 已通过

- `./gradlew assembleDebug lintDebug`：成功，Lint 0 errors、5 warnings（TV 横屏、banner 尺寸、备份配置与中文字符串国际化建议）。
- ADB 安装及启动，首页首个焦点为哔哩哔哩 TV。
- 通过遥控器确认键打开 `com.xiaodianshi.tv.yst/.ui.main.MainActivity`。
- 设置默认 HOME 后，从 B站及 Sony 输入界面按 Home 返回 Aurora TV。
- 从首页遥控器导航逐一打开 HDMI 1–4；`dumpsys tv_input` 验证实际会话分别绑定 Sony HW2–HW5。
- 杭州天气在线加载、温度及当日高低温展示；本机天气缓存已写入。
- 在电视内搜索 `Hangzhou` 返回带行政区的城市选择列表。
- 菜单键打开设置；切换全部三套壁纸，重启应用后保留选择。
- 安装更新后保留天气城市和壁纸配置。
- ADB 重启整台电视后，不发送 Home 或启动命令，系统自动进入 Aurora TV；`sys.boot_completed=1`、默认 HOME 和前台 Activity 均验证成功，天气及配色保留。

## 性能采样

背景最高 25fps。玻璃面板采用实际 RenderEffect 背景模糊，缓动壁纸的模糊采样每秒更新一次，避免重复计算全部面板。优化后的真机静置采样为 404 帧，中位绘制耗时 12ms，P90 13ms，P95 14ms，系统统计 janky frames 4.70%。这是一段短时采样，不等同于稳定性或所有电视的性能保证。

## 验证边界

HDMI 端口当前均未连接信号源，因此仅验证入口、系统输入会话和无信号界面，没有验证外接设备的视频、音频或 HDMI-CEC。未断开电视网络测试离线模式，避免切断无线 ADB；离线缓存和并发请求路径经代码审查。Android 8–11 的透明降级未在真机测试。GitHub CI 尚未远端执行。

安装包、屏幕截图、性能原始输出和原桌面组件记录位于被 Git 忽略的 `artifacts/`，私人设备地址不写入公共代码或部署脚本。
