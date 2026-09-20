# 照片轮播与自动天气（0.2.0）

## 自动 IP 天气

升级后默认按电视当前公网出口 IP 查询近似城市，再加载该城市天气，不再固定杭州。

点击首页天气卡片，或进入「设置 → 天气与定位」：

- **自动 IP 定位**：恢复自动模式，并立即定位。
- **立即刷新天气与位置**：跳过位置缓存重新查询。
- **手动选择城市**：在 IP 识别不准或出口经过代理时，指定需要的城市；以后可再切回自动。

自动位置在内存中保留约 30 分钟；回到首页时检查活动网络，网络变化会重新定位。代理、VPN 和运营商出口可能导致城市与实际所在地不一致。天气卡片标明「IP 近似定位」或「手动城市」。定位失败不会伪装成成功；如有同模式的历史天气，会显示「上次缓存」及其更新时间。

## 从本地相册选择照片

1. 将照片复制到电视的 `Pictures` 或 `DCIM` 目录，确保已被媒体库扫描。
2. 打开「设置 → 壁纸与照片轮播 → 选择本地照片」。
3. 首次使用允许照片访问；方向键移动，确认键选中或取消选中，可按相册筛选。
4. 选好后向上移到「使用所选照片」，确认导入；最多 30 张。新选择会替换上次选择，不是追加。

应用复制选中图片并按比例缩小至最大 1920×1080，自动修正照片方向；原图不变。所有图片成功导入后才替换相册；中途失败保留原来的照片集合。照片只在电视本机处理，卸载应用会删除这些副本。

如果复制后看不到照片，可刷新相册；还需要文件管理器或系统媒体扫描将其加入媒体库。ADB 开发调试可对指定照片执行：

```sh
adb -s SERIAL push photo.jpg /sdcard/Pictures/photo.jpg
adb -s SERIAL shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
  -d file:///sdcard/Pictures/photo.jpg
```

「文件 / USB 选择」需要电视提供可用的系统文件选择器。这台 Sony 的固件只有占位选择器，因此会显示说明；可先把 USB 照片复制进本地相册再选择。已被媒体库索引的外接存储图片也可出现在相册中。

## 轮播选项

| 设置 | 效果 |
| --- | --- |
| 背景来源 | 在渐变壁纸和已选照片之间切换，保留照片集合 |
| 切换渐变配色 | 切回程序化背景并更换颜色 |
| 轮播间隔 | 15、30、60 秒，默认 30 秒 |
| 过渡效果 | 柔和淡化、缓慢推拉、横向滑移，默认缓慢推拉 |
| 下一张照片 | 手动触发过渡；仅有一张时保持该图 |
| 清空已选照片 | 仅删除桌面副本，回到渐变背景，不删除相册原图 |

照片采用居中裁切以铺满电视，竖图顶部和底部会被裁切；背景加暗色遮罩保持文字可读。离开桌面暂停轮播，返回继续；配置和照片副本在重启应用后保留。无法读取的图片跳过，全部不可用时回退渐变背景。

## 回归测试

照片导入使用无第三方依赖的原生 instrumentation 测试，沙盒目录与偏好均隔离，不覆盖真实相册：

```sh
./gradlew assembleDebug assembleDebugAndroidTest lintDebug
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s SERIAL shell am instrument -w dev.aurora.tv.test/dev.aurora.tv.RegressionInstrumentation
```

输出应包含 `PASS: 12 photo regression checks`。覆盖多选去重、方向修正、尺寸限制、损坏文件回滚、原图保留和清空行为。该 runner 不依赖 JUnit，使用上面的 `am instrument` 命令执行；GitHub CI 只构建并执行 Lint，不代表已执行真机测试。
