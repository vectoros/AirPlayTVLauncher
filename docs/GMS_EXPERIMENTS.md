# Sony Android TV：GMS 与 Shizuku 实验记录

实验日期：2026-09-22。公开记录整理日期：2026-09-23。

这是一次未成功完成 GMS 集成的实验记录，不是通用安装教程。目标为 Play 商店下载安装、YouTube 登录播放；截至最后一次真机测试，两项目标均未达成。此次整理没有重新操作电视。

原始设备地址、电脑路径、日志和下载文件只在本地保留，不随 Git 发布。以下命令使用环境变量，不包含具体设备地址。Google APK 和研究用二进制不纳入本项目发布物。

## 环境与前提

测试设备为 Sony BRAVIA VH21 国行 Android 12，API 31，内核 4.19.260，厂商构建 681621，声明安全补丁级别 2025-11-01。Android 应用 ABI 为 armeabi-v7a / armeabi；内核配置为 ARM64 且启用 COMPAT，不能把应用 ABI 等同于内核架构。

系统为 user / release-keys，ro.debuggable=0，启动加载器锁定，AVB green，SELinux Enforcing。ADB 为 uid=2000(shell)，没有发现可用 su。没有解锁、刷写分区或安装 Magisk。

## 安装文件与来源验证

| 组件 | 包名 | 安装版本 | 结果 |
| --- | --- | --- | --- |
| Google Services Framework | com.google.android.gsf | 12 / versionCode 31 | 安装成功，运行被权限阻塞 |
| Google Play services（Android TV） | com.google.android.gms | 25.28.34 (180306-787197649) / 252834118 | 安装成功，后台进程崩溃 |
| Google Play Store（Android TV） | com.android.vending | 51.8.09-31 [8] [PR] 927598563 / 85180918 | 安装成功，未进入可用下载流程 |
| Shizuku | moe.shizuku.privileged.api | 13.6.0.r1086.2650830c / 1086 | 安装、ADB 启动成功 |

GSF 来自 APKPure，Play services 和 Play Store 为用户从 APKMirror 下载的文件。Shizuku 来自[官方 v13.6.0 release](https://github.com/RikkaApps/Shizuku/releases/tag/v13.6.0)。使用 Android SDK 的 apksigner 验证 APK 签名，并记录摘要；安装成功不代表设备通过 Google 认证。

| 文件 | SHA-256 |
| --- | --- |
| GSF | `fbd942ebe4bc2a701e335dcc6fb919c2aed989d5ce6b5c609b53c63cbb485d0d` |
| Play services | `ef5cc6e20dce811d3bbd59b53d513768300df152b74a546189478466affe7fd9` |
| Play Store | `3f8e1f2afbf1a79a733f76ab65d6cdb7965523bc5e0a0a494c27d98e96ffb95e` |
| Shizuku | `6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f` |

三个 Google APK 的签名证书 SHA-256 均为 `7ce83c1b71f3d572fed04c8d40c5cb10ff75e6d87d9df6fbd53f0468c2905053`。Shizuku 证书 SHA-256 为 `268b5590e868fb08bae7e0ac413564cd1ff88f5ccff74af9dbd0dc918e30db30`。这些是公开文件的摘要，不是私钥。

## GMS 安装与错误

安装步骤为核对 SDK / ABI、验证签名，然后通过 ADB 安装上述三个 Google 包。命令模板如下；执行前由操作者设置 DEVICE_SERIAL：

```sh
adb -s "$DEVICE_SERIAL" shell getprop ro.build.version.sdk
adb -s "$DEVICE_SERIAL" shell getprop ro.product.cpu.abilist
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs artifacts/gms/gsf.apk
adb -s "$DEVICE_SERIAL" install -r artifacts/gms/gsf.apk
adb -s "$DEVICE_SERIAL" install -r artifacts/gms/gms.apk
adb -s "$DEVICE_SERIAL" install -r artifacts/gms/play-store.apk
```

文件名为模板，可按本地下载文件调整。三个包均返回安装成功，但启动商店后的崩溃日志显示：

- GSF 缺少 `android.permission.READ_DEVICE_CONFIG`，抛出 SecurityException。
- 商店及 `com.google.android.gms.persistent` 遇到 `MANAGE_USERS` / `CREATE_USERS` 权限拒绝。
- ADB 的 `pm grant` 返回 `is not a changeable permission type`，不能像普通运行时权限一样补授这些权限。

目前证据仅证明上述版本在该固件上受到权限阻塞，没有验证其他版本能解决问题，也没有验证系统集成后的完整行为。

## root 路线：明确区分执行与研究

| 路线 | 实际操作 | 结论 |
| --- | --- | --- |
| adb root | 已执行 | production build 不允许 adbd 以 root 运行 |
| BRAVIA CVE-2019-2215 PoC | 仅检查源码，未编译执行 | 公开代码针对旧内核，包含固定布局与偏移，未验证适配本机 |
| mtk-su 32 位 | 已运行，带超时限制 | `Failed critical init step 1` / `Incompatible platform`，退出码 1，仍为 shell |
| mtk-su 64 位 | 检查 ELF，未执行 | 缺少所需动态链接器 |
| CVE-2022-20421 | 资料调查，未执行 | 未验证本机是否可利用 |
| 其他 Binder / Dirty Pipe / run-as 方案 | 资料调查，未执行 | 没有完成本机适配或有效性验证 |

mtk-su 文件取自 [Mtk Easy Su](https://github.com/JunioJsv/mtk-easy-su)，下载摘要与 Git LFS 指针核对一致；这不是完整代码审计。32 位文件 SHA-256 为 `b69e0ee37ba9cef064758ba36a9e0c0246c12f318b39da8a0abcfa8db98dcac4`。没有修改工具跳过兼容性检查，执行后已删除设备上的测试文件。SELinux 仍为 Enforcing，未获得 root。

安全补丁日期是厂商声明，不足以独立证明某漏洞已修复或仍存在。本记录不把“Android 12”当作通用提权适用条件，也不将未执行的路线记为实测失败。

## Shizuku 实测

1. 安装官方 APK，打开 Shizuku，进入“通过连接电脑启动 → 查看指令”。
2. 旧版 `/sdcard/Android/data/moe.shizuku.privileged.api/start.sh` 路径不存在，改用应用显示的本次安装路径下 `lib/arm/libshizuku.so` 启动命令。该路径会随安装改变，必须重新读取，不能照抄其他设备的随机路径。
3. 启动返回成功，界面显示“Shizuku 正在运行”“版本 13.5，adb”。这是服务界面文本，APK 发布版本仍为 13.6.0。进程身份为 shell。
4. 从同一 APK 提取官方 rish 及 dex，临时放入设备 `/data/local/tmp`，通过 Shizuku 通道执行 `id`，得到 uid=2000(shell)，SELinux 上下文为 `u:r:shell:s0`。
5. 通过 rish 再次尝试为 GSF 授予 READ_DEVICE_CONFIG、为商店授予 MANAGE_USERS，均返回 SecurityException / `is not a changeable permission type`。MANAGE_USERS 测试退出码为 255。
6. 重新启用三个 Google 包并启动商店，新日志仍出现 GSF 的 READ_DEVICE_CONFIG 及 GMS 的 MANAGE_USERS / CREATE_USERS 错误。未进入 Google 登录或下载流程。

Shizuku 的 ADB 模式提供 shell 身份，实测没有获得额外的系统特权，未解决此固件上的 GMS 阻塞。启动机制参见[官方说明](https://shizuku.rikka.app/guide/setup/)。

## 收尾状态与验收边界

- 三个 Google 包已 force-stop 并设为 disabled-user，保留安装文件用于后续研究。
- Shizuku 保留安装，最后测试时服务运行；没有配置自动启动，设备重启后需重新通过 ADB 启动。
- 已清理设备上的临时 rish、dex、UI XML 与提权测试程序。
- 默认桌面仍为 Aurora，未因本次实验修改 Launcher 代码。
- 没有安装 YouTube，没有执行 Google 账号登录，没有完成商店下载安装验证。

收尾命令模板（不是再次执行记录）：

```sh
for package in com.google.android.gsf com.google.android.gms com.android.vending; do
  adb -s "$DEVICE_SERIAL" shell am force-stop "$package"
  adb -s "$DEVICE_SERIAL" shell pm disable-user --user 0 "$package"
done
```

后续若取得匹配本机固件的系统集成途径，仍需依次验证权限、服务稳定性、重启行为、商店安装更新、YouTube 登录播放与设备认证状态。获得 root 或安装 Shizuku 本身均不等于完成这些验收。
