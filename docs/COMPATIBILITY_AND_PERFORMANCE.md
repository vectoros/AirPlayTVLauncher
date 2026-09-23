# AOSP 兼容性与 TV 性能

审查日期：2026-09-24。版本支持分为代码覆盖、构建检查和实机验证；三者不能互相替代。

## 系统兼容列表

Launcher 当前 `minSdk=26`、`targetSdk=35`、`compileSdk=35`，使用 Java 17 编译，无 JNI / 原生库依赖。下表面向带 TV 功能的 AOSP 衍生系统，不代表所有手机、平板或厂商电视均验证通过。

| AOSP / Android | API | Launcher 支持边界 | 验证状态 |
| --- | --- | --- | --- |
| 7.1 及以下 | ≤25 | 不支持安装 | 低于 minSdk |
| 8.0 / 8.1 | 26 / 27 | 代码支持；半透明玻璃降级；BitmapFactory + Exif 照片路径 | 未实测 |
| 9 | 28 | 代码支持；ImageDecoder 解码；半透明玻璃降级 | 未实测 |
| 10 | 29 | 代码支持；MediaStore / 文档 URI 选图 | 未实测 |
| 11 | 30 | 代码支持；Manifest 已声明应用发现 queries；半透明玻璃降级 | 未实测 |
| 12 | 31 | RenderEffect 模糊及液态玻璃完整绘制路径 | Sony BRAVIA VH21 历史实测；不是纯 AOSP 认证 |
| 12L | 32 | 同 API 31 绘制路径 | 未实测 |
| 13 | 33 | 使用 READ_MEDIA_IMAGES 照片权限 | 未实测 |
| 14 | 34 | 处理 READ_MEDIA_VISUAL_USER_SELECTED 部分照片访问 | 未实测；授权重选及前后台撤权仍需回归 |
| 15 | 35 | 当前编译和目标 API；基础分支已覆盖 | 构建/Lint 检查，未实机验证 |
| 16 | 36 | 没有 maxSdk 安装上限，但新系统行为待验证 | 不承诺完整兼容 |
| 17 | 37 | 超出当前编译目标；须独立验证新系统行为 | 不承诺完整兼容 |

RenderEffect 从 [API 31](https://developer.android.com/reference/android/graphics/RenderEffect) 提供；低版本没有真实背景模糊。照片权限参考 [Android 14 部分照片访问](https://developer.android.com/about/versions/14/changes/partial-photo-video-access)。新版系统即使没有提高 targetSdk 也可能改变行为，参见 [Android 16](https://developer.android.com/about/versions/16/behavior-changes-all) 与 [Android 17](https://developer.android.com/about/versions/17)。

## 系统能力与 AirPlay 边界

- Manifest 要求 `android.software.leanback`，面向电视遥控器操作；纯 AOSP 缺少 TV 服务的镜像不作为支持目标。HOME 选择、保活和后台启动仍可能受到厂商限制。
- HDMI 必须由厂商通过 TvInputManager 暴露输入源，并有可处理 passthrough URI 的播放器。无 HDMI 硬件的盒子或模拟器不能验证此能力；降级入口是系统输入设置。
- 照片需要 MediaStore 或可用的系统文档选择器；部分电视只有文件选择器占位程序。天气需要 HTTPS 网络，但 Launcher 不依赖 GMS，不要求 root。
- Launcher 没有自带原生库，不受本项目 JNI ABI 限制；这不等于每个 ABI 都已测试。
- 独立 AirPlay 接收端上游 `minSdk=24`、`targetSdk=36`，当前脚本的产物仅含 `armeabi-v7a` 原生库。与 Launcher 配套最低仍为 API 26；纯 64 位系统不能直接使用当前接收端产物。其他 ABI 必须另行构建并验证原生依赖，包括适用设备的 16KB 页兼容性。
- 接收端要求与 Launcher 匹配的签名，启动由前台 Launcher 触发。Android 13+ 通知、14+ 前台服务及厂商后台策略需要逐机测试。仅 Sony Android 12 已验证发现和服务入口，实际 Apple 音视频、长时投屏仍未完成验收；不是完整 AirPlay 2 实现。

## 性能排查结果

| 开销 | 证据与影响 | 处理状态 |
| --- | --- | --- |
| 静态照片反复绘制 | 淡入淡出/滑移的等待期原来仍 40ms invalidation，并触发玻璃采样 | 本次取消等待期绘制，计时最多 1Hz；临近过渡按剩余时间唤醒，过渡/推拉/程序化壁纸仍最高 25fps |
| 首次启动重复构建 | onCreate 与紧接的 onResume 均 populate，重复枚举应用、解码图标、创建卡片 | 本次保留 onResume 的一次构建 |
| 主线程包扫描 | 每次返回首页、HDMI 状态变化、打开抽屉都会查询应用，加载全部图标；首页只显示前六项 | 尚未迁移后台或加入包变更缓存；大量应用时可能产生卡顿 |
| 玻璃 GPU 负载 | 多卡片重绘壁纸，液态边缘额外采样；普通更新约 1Hz，轮播过渡约 10Hz | 已节流但仍有成本；不是一次全局共享模糊纹理 |
| 4K 与持续动画 | 程序化背景/推拉持续最高 25fps，多层渐变和混合；4K 相比 1080p 像素数为四倍 | 未测 4K；不能推断帧率也按四倍变化 |
| 图片内存 | 轮播保留当前与下一张，单张最大 1920×1080 ARGB 约 7.9MiB，双张约 15.8MiB | 后台采样解码；GPU 纹理、待回收旧图、解码临时对象额外占内存，不能把 15.8MiB 当进程上限 |
| 大相册 | GridView 复用可见卡片，异步缩略图跳过失效任务；仍全量读取照片元数据，无缩略图缓存 | 超大相册/快速滚动需压测，后续考虑分页与有界缓存 |
| 天气 | 两个后台线程，连接/读取超时，响应限制 512KiB，销毁时断开连接 | 每次回首页仍会触发刷新；快速往返时的请求合并可继续优化 |
| 离开首页 | onPause 停止壁纸计时和材质动画，销毁关闭解码器 | Launcher 绘制停止；独立 AirPlay 前台服务仍运行，须单独测量 |

液态玻璃关闭后使用旧毛玻璃，API 31+ 仍有 22dp 模糊，不能把此开关等同于“低功耗模式”。低配设备可先选单张照片和淡入淡出，避免持续推拉；目前没有完整的关闭模糊/静态程序化壁纸性能档位。

## 性能证据与验收方法

历史 Sony 1080p 短测：v0.1 静置 P50 12ms / P95 14ms、jank 4.70%；v0.3 连续焦点 P50 14ms / P95 34ms、jank 5.36%。这些是不同场景的旧版本数据，不能作为本次优化后的结果或稳定 60fps 保证，详见 [原始验证说明](VERIFICATION.md)。25fps 是背景调度上限，不是整机显示刷新率。

本次 `assembleDebug assembleDebugAndroidTest lintDebug` 成功，Lint 0 errors / 14 warnings；新增 instrumentation 仅完成编译，未执行。

本次目标电视返回 `No route to host`，未安装修改后的 APK，未获得新帧率、CPU、温度或 PSS 数据。新增调度回归检查覆盖等待截止点、完整过渡、结束后降频及推拉/失败降级，需连接设备后执行 instrumentation。

设备恢复后，使用相同输出分辨率、壁纸、应用数量分别比较旧/新 APK；每组预热 30 秒、采样至少 60 秒。分别测试静置、连续焦点、开关抽屉、照片过渡、后台静置与 AirPlay 投屏；至少跑一组 30 分钟内存/温升观察。不要在真实相册上做破坏性测试。

```sh
adb -s SERIAL shell dumpsys gfxinfo dev.aurora.tv reset
# 在电视上执行上述场景，再采集；原始输出留在忽略目录。
adb -s SERIAL shell dumpsys gfxinfo dev.aurora.tv framestats > artifacts/gfxinfo.txt
adb -s SERIAL shell dumpsys meminfo dev.aurora.tv > artifacts/meminfo.txt
adb -s SERIAL shell dumpsys cpuinfo > artifacts/cpuinfo.txt
adb -s SERIAL shell dumpsys meminfo dev.aurora.airplay > artifacts/airplay-memory.txt
```

同时记录系统/API、芯片、可用内存、输出分辨率及 AirPlay 状态。比较 P50/P95、jank、CPU 与 PSS 增长，不仅看平均帧率；系统采样中的壁纸静止帧数下降是预期现象。优先排查可复现的遥控器响应超过 100ms、主线程超过一帧预算、持续内存增长或后台仍重绘。
