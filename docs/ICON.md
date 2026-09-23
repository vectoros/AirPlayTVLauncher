# Aurora TV 图标

图标沿用 A 形山峰标识，以深蓝底色、青紫极光、玻璃面板和浅青高光呼应桌面的玻璃风格。玻璃质感为静态矢量渐变，不依赖实时模糊、图片下载或额外库。

![Aurora TV 图标](images/ic_launcher.svg)

## 资源与维护

- `app/src/main/res/drawable/ic_launcher.xml`：108 × 108 viewport 的应用图标，由 Manifest 的 `android:icon` 引用。
- `app/src/main/res/drawable/banner.xml`：320 × 180 的 Android TV 横幅，由 `android:banner` 引用。
- `docs/images/ic_launcher.svg`、`docs/images/banner.svg`：对应的可编辑预览；修改图形时同步更新 Android 路径、渐变和 SVG。

两项 Android 资源均为原生 VectorDrawable，适用于项目最低 API 26。当前使用普通图标资源，未新增自适应或单色主题图标。图形为项目原创，沿用 Launcher 的 MIT 许可。

## 验证

已检查 SVG 预览，并通过 `./gradlew assembleDebug lintDebug`。APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

本次尝试连接目标电视返回 `No route to host`，未安装新 APK；电视应用列表中的实际显示及缓存刷新仍待真机验证。设备可达后使用 `./scripts/deploy.sh SERIAL` 安装。
