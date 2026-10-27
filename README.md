# 局域互控 · LanPeer

同一个安卓 APK 同时包含主控端和被控端。手机和平板可任选角色；断开当前会话后，在两台设备上互换角色即可。当前每台设备同时参加一个单向会话。

## 使用

1. 两台设备安装相同 APK，安卓 8.0 或更高版本。
2. 一台开启热点，另一台加入；或连接同一路由器的 Wi-Fi。无需手机流量或外部服务器。部分厂商/路由器的设备隔离会阻止直连。
3. 被控端打开“允许其他设备控制”。需要操作屏幕时，先在系统无障碍设置中开启“局域互控 · 触控服务”。
4. 同意系统屏幕共享授权。Android 14+ 请求共享默认显示屏，保证触控坐标对应完整屏幕。
5. 主控端扫描被控端二维码，或手动输入其显示的地址、8 位连接码、16 位纯数字设备校验码。如果有多个地址，切换被控端地址后重新扫码。
6. 被控端确认本次连接。主控端可点击、滑动、长按；底部居中的经典三键可执行返回、主页和最近任务，不要求在远端画面上滑动系统手势。断开连接使用画面右侧独立的红色悬浮按钮。主控画面的“全屏”隐藏系统栏和上方工具；底部“工具”恢复常用工具。视频始终按比例完整显示，黑边不会响应触控。
7. 任一端断开后会话立即失效。被控端也可从常驻通知停止共享。重新共享会生成新连接码。

相机只用于主控端离线扫码。被控端可以只授权屏幕共享、不启用触控服务，此时只能观看。

连接成功的设备会出现在“历史设备”。可重命名或移除记录。记录只保存局域网地址与设备校验身份；8 位连接码及二维码令牌每次共享时更新，不会保存。再次连接需输入被控端当前显示的连接码，且仍需被控端确认。若触控服务未运行，应用“设置”页会显示状态与开启入口。

## 已实现与边界

- 同一 applicationId、同一安装包、同一代码路径提供双角色；无需另装控制插件。
- 本地 WSS 信令服务在被控端监听 TCP 8765；WebRTC 视频/控制走动态 UDP 端口，没有 STUN/TURN、公网信令、登录或遥测。
- 信令使用 Android Keystore 设备证书和 TLS；二维码携带完整 SHA-256 指纹和 128 位随机令牌。手动配对校验从指纹导出的 16 位纯数字设备码和 8 位连接码。每次均需被控端本机确认，失败尝试和确认等待有限制。
- 视频默认“均衡”档，上限 1600 长边 / 30 fps / 3.5 Mbps。主控端可切换“流畅”1280 / 45 fps / 2 Mbps，或“清晰”1920 / 30 fps / 6 Mbps。WebRTC 会按网络条件降到上限以下；静止屏幕实际 fps 可能接近零。主控页显示当前解码尺寸、帧率、码率与连接 RTT。
- 单指滑动和按住在触摸过程中分段发送并通过 Android 无障碍手势延续执行；短点击在抬起时发送。仍不支持多点触控、压感以及要求原生触摸事件的游戏。
- 主控页视频与工具区分离；全屏时仍按比例显示完整远端画面，不以裁边填充屏幕。首页按实际窗口宽度在手机单栏、平板双栏之间适配，底部提供连接、历史设备和设置分区。
- 根据实际视频画面计算触控坐标，忽略黑边；旋转期间不执行针对上一方向的手势。
- 不读取无障碍节点内容，不包含文字输入、声音传输、文件传输、多设备并发、后台无人值守、自动重连或同时双向控制。
- 锁屏、受保护视频、FLAG_SECURE 页面及系统安全界面受安卓系统限制。
- 初版局域网地址支持 RFC1918 IPv4；IPv6-only、非 RFC1918 企业网络和部分特殊热点网卡命名暂不支持。
- 屏幕采集使用前台服务；进程或系统停止共享后必须手动重新授权，不复用旧授权。
- 被控端从开启共享到断开期间保持屏幕点亮，包括等待连接、返回桌面或打开其他应用时；断开共享即释放屏幕唤醒锁。用户手动按电源键仍可锁屏。此功能会增加耗电。

## 与上传的 BilldDesk 的关系

本项目受到 [BilldDesk](https://github.com/galaxy-s10/billd-desk) 的 WebRTC 视频 + DataChannel 控制架构启发，针对安卓手机与平板局域网互控独立实现。没有复制 BilldDesk Pro 代码，也不兼容其服务端、账号或消息协议。

## 构建

工程使用 Java 17、Android Gradle Plugin 8.7.3、Gradle 8.9、Android SDK 35。可用 Android Studio 打开本目录，或执行：

```powershell
.\build.ps1
```

脚本优先使用项目 `.tools` 目录的 JDK、SDK 和 Gradle；缓存及构建工具不属于应用运行依赖。也可配置本机 `JAVA_HOME`、`ANDROID_HOME` 后使用 Gradle Wrapper：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

公开测试版 APK 见 [GitHub Releases](https://github.com/xiaoxiaomuyu321/LanPeer-Android/releases)。本地构建输出位于 `app/build/outputs/apk/debug/app-debug.apk`。该包是开发测试版。正式发布需要固定的发行签名，不应把签名私钥加入源码。

若 Google Maven 的 TLS 下载不稳定，可按需添加 `-PgoogleMirror=https://maven.aliyun.com/repository/google` 使用镜像；默认仍为官方仓库。

## 真机验证清单

编译通过不能替代以下真机测试：

- 手机开热点，平板扫码：确认、显示画面、点击、滑动、长按、导航键。
- 断开后互换角色，仍然使用同一个 APK 测试以上操作。
- 两种方向分别旋转主控和被控端，验证画面比例和触控落点。
- 未开启无障碍时只能观看；拒绝连接不采集屏幕、不执行手势。
- 错误连接码、错误指纹、确认超时、通知停止、网络断开均应结束或拒绝会话。
- Android 14+ 首次共享、停止后重新共享，均走新的系统授权流程。

单元测试覆盖邀请解析、版本与地址限制、蜂窝网卡过滤，以及完整画面的坐标映射。性能采样与真机限制见 [PERFORMANCE.md](PERFORMANCE.md)。

界面截图：[平板首页](docs/tablet-home-v3.png)、[手机权限设置](docs/phone-settings-v3.png)。

依赖参考：[WebRTC Android](https://github.com/webrtc-sdk/android)、[Java-WebSocket](https://github.com/TooTallNate/Java-WebSocket)、[ZXing](https://github.com/zxing/zxing)。平台参考：[屏幕共享](https://developer.android.com/media/grow/media-projection)、[无障碍手势接口](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)。
