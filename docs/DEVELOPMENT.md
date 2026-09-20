# 开发与维护记录

## 需求与实现

项目面向 Sony Android TV，首页参考 Apple TV 的简约布局，支持遥控器操作、毛玻璃效果及动态壁纸。首版要求与实现对应如下：

| 需求 | 实现 |
| --- | --- |
| 保留 HDMI 入口 | 通过 `TvInputManager` 动态发现物理 HDMI，保留未连接端口，过滤重复的 CEC 子设备 |
| 第一个应用是哔哩哔哩 TV | B站固定首位并默认聚焦，未安装时保留提示入口 |
| 首页显示当天的天气 | 默认公网 IP 自动定位，支持手动城市、分模式缓存和更新时间 |
| 动态壁纸 | 原创 Canvas 渐变山峦及本地照片轮播，三种过渡效果 |
| 代码简洁、便于开源 | 六个 Java 文件，使用 Android 原生 API，无第三方运行时依赖 |

## 关键实现选择

- 使用原生 View 与 Canvas，避免引入额外 UI 框架；应用图标从电视已安装应用读取。
- `LauncherRepository` 负责应用和输入源发现。B站之后优先显示第三方应用，系统应用随后，其他桌面最后；首页最多显示六项，其余通过「所有应用」打开。
- Sony 输入源必须通过 passthrough URI 加 `vnd.android.cursor.item/channel` MIME 类型启动。实际验证的四个 HDMI 对应 HW2–HW5，但代码不硬编码这些 ID。
- 毛玻璃只模糊对应区域的壁纸，保持文字和图标清晰。Android 12 及以上使用 `RenderEffect`，旧版本显示半透明面板。
- 壁纸最高 25fps；玻璃背景每秒重新采样一次以降低渲染开销。离开桌面停止动画。
- 天气异步请求使用后台线程，结果回调主线程；请求序号阻止旧结果覆盖新城市，关闭服务时断开网络连接。
- IP 定位通过 IPwho.is 完成，位置在内存中缓存 30 分钟，强制刷新或检测到网络变化时重新定位；旧版固定杭州配置升级为自动模式，自动和手动模式的天气缓存分开。
- 照片选择页使用 MediaStore，兼容 Sony 仅有占位文件选择器的固件。PhotoStore 后台修正方向并缩放图片，全部成功才切换清单，失败保留原相册。最多选择 30 张，播放器持有当前及下一张图片；图片全部损坏时回退渐变背景。
- 照片过渡期间玻璃背景采样加快至 100ms，其他时刻每秒一次。已提交绘制的位图由 GC 与 RenderNode 管理释放，避免手动 recycle 破坏缓存绘制。
- 使用系统默认 HOME 机制进入桌面，无需禁用或卸载电视原有桌面。部署脚本和更改默认 HOME 的脚本分开，恢复脚本可恢复原组件。

## 本地 Git 工作流

主分支为 `main`。代码、构建配置、Gradle Wrapper、脚本和公共文档纳入 Git；构建产物、本机 SDK 路径、签名密钥及本地设备记录忽略。

建议按功能创建分支，并将相关文档与代码一起提交：

```sh
git switch -c feature/short-description
./gradlew assembleDebug lintDebug
git diff --check
git add <changed-files>
git commit -m "Describe the change"
```

提交前检查 `git diff --cached`，确认没有设备地址、凭据、本机路径或签名文件。测试与限制更新到 [VERIFICATION.md](VERIFICATION.md)，用户可见的功能和部署说明更新到 [README.md](../README.md)。未修改运行代码时，不必重复执行真机测试。

GitHub Actions 已配置构建、Lint 和调试 APK 上传。当前仅建立本地仓库，远端仓库与发布版本尚未创建；调试 APK 不应被描述为正式签名的发行版。

## 后续验证

实际连接 HDMI 外设后补测画面、音频与 CEC；补测待机唤醒、网络失败、Android 8–11 降级效果及长时间运行。首版已经通过的测试和性能采样见 [VERIFICATION.md](VERIFICATION.md)。

## 技术参考

- [Android RenderEffect](https://developer.android.com/reference/android/graphics/RenderEffect)
- [Android TvContract](https://developer.android.com/reference/android/media/tv/TvContract)
- [IPwho.is 定位 API](https://ipwhois.io/documentation)
- [Open-Meteo 天气 API](https://open-meteo.com/en/docs)
- [Open-Meteo 城市搜索 API](https://open-meteo.com/en/docs/geocoding-api)

## v0.3.0 液态玻璃

新增独立 LiquidGlassFrame，MainActivity 统一接入及保存开关。默认开启；详见 [LIQUID_GLASS.md](LIQUID_GLASS.md)。Git 分支 `feature/liquid-glass`。原生 API 31 背景模糊及 Canvas 折射近似，无新增库。
