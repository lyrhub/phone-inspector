# 参数盒子 · Phone Inspector

一个**原生 Java、无第三方 SDK、默认离线**的 Android 手机参数查看应用。中文界面，支持分类、关键词搜索、刷新、折叠卡片、长按复制、保存 TXT 报告和系统分享。

- **最低版本**：Android 8.0 / API 26
- **编译 / 目标 SDK**：35
- **应用 ID**：`cn.minis.devicecheck`
- **构建组合**：JDK 17 + Gradle 8.9 + Android Gradle Plugin 8.7.3
- **运行依赖**：只有 Android SDK；不使用 AndroidX、广告或统计 SDK

> 此交付为源码项目。按要求没有在交付设备上安装 Android SDK 或构建 APK；未经本地编译或真机验证。GitHub Actions 是首次实际构建、lint 和发现机型兼容问题的验证入口。

## 1. 放到 GitHub 自动生成 APK

### 上传代码

解压源码压缩包，把 **`phone-inspector` 文件夹里面的内容**作为仓库根目录提交。根目录应直接包含 `settings.gradle`、`build.gradle`、`app/` 和 `.github/`，不要再套一层项目文件夹。

特别注意：`.github` 是隐藏文件夹，必须一起提交，否则不会自动编译。请在仓库的 **Code → .github → workflows → android.yml** 确认它存在。

使用 Git 上传示例（在解压后的项目目录执行）：

```sh
git init
git add .
git commit -m "Add Phone Inspector Android app"
git branch -M main
git remote add origin https://github.com/你的用户名/你的仓库.git
git push -u origin main
```

### 下载 APK

1. 打开 GitHub 仓库的 **Actions**。
2. 等待 **Build Android APK** 工作流完成。若 Actions 未启用，先在仓库启用。
3. 打开成功的那次运行，在 **Artifacts** 区域下载 APK 压缩包。
4. 解压后把 `app-debug.apk` 传到手机，允许该来源安装应用，再安装。

工作流在 `push`、`pull_request` 时触发，也可点击 **Run workflow** 手动触发。没有分支名限制。

**不需要配置任何签名密钥或 GitHub Secrets。** CI 输出的是调试版，自动使用 runner 的调试签名，仅适合试用；不同 runner 的调试签名可能不一致，重装时可能需要先卸载旧版。正式长期分发应另外配置持久 release 签名，并妥善保存密钥。Android 8.0 以下不能安装。

## 2. 可查看的项目

| 分类 | 内容及边界 |
| --- | --- |
| 设备 | 厂商、品牌、型号、产品、板卡、硬件和 ABI 等系统属性 |
| 系统 | Android/API 版本、安全补丁、内核、运行时长等 |
| CPU | 系统可见核心数、系统报告的 SoC 信息、允许读取时的频率；`/proc`、`/sys` 受限时明确提示 |
| 内存 | 系统报告的总内存/可用内存、低内存状态、应用堆；并非物理内存芯片鉴定 |
| 存储 | 应用内部数据所在分区的容量/可用空间，不冒充厂商标称容量 |
| 电池 | 电量、充电状态、电源、健康枚举、温度、电压和系统可提供的电池属性 |
| 屏幕 | 显示模式、分辨率、刷新率、DPI 等；显示模式尺寸、逻辑窗口尺寸分别标注 |
| 传感器 | 系统公开的传感器能力列表；不持续采集实时读数 |
| 网络 | 默认网络类型、连接验证/计量状态、IP、DNS 等；不扫描 Wi-Fi、不测速 |
| 硬件能力 | NFC、蓝牙、定位、指纹等系统特性支持状态；不代表当前开启或功能已通过测试 |
| 相机 | 系统允许读取的相机静态能力；不预览、不拍照，可能受厂商和系统限制 |

全部数值来自公开 Android API 或允许读取的只读系统节点。不支持、未提供和访问受限的项目会明确标注，不编造容量、温度、健康度或跑分。设备名称/硬件信息可被 ROM 修改，本应用不能保证识别真伪。

## 3. 使用方法

- 打开应用自动读取一次；右上角 **刷新**重新读取。电量、内存、网络等均是快照，不是持续监控。
- 分类标签筛选内容；搜索始终覆盖所有分类，匹配字段名或值。
- 点击分类标题折叠/展开；长按参数值复制到剪贴板（复制后其他具有剪贴板访问能力的应用可能读取该值）。
- **保存报告**通过 Android 系统文件选择器创建 UTF-8 TXT，不需要存储权限。
- **分享报告**使用系统分享面板；目标应用由你选择。
- 报告包含**所有分类的最近一次快照**，不受搜索、筛选或折叠影响。导出前会提醒隐私。

## 4. 权限与隐私

清单仅申请普通权限 `ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`，用于查看网络状态；无运行时权限弹窗。

- **没有 `INTERNET` 权限**，不发起网络请求、没有后台上传。
- 不读取 IMEI、硬件序列号、MAC、电话、定位或通讯录。
- 不申请相机、麦克风或存储权限，不采集照片或音频。
- 不在应用内持久化设备报告。只有你主动保存/分享时才对外导出。
- 报告可能包含设备型号、设备名称、IP 和 DNS；请只分享给可信对象。分享目标或云存储提供方仍会按其自身规则处理数据。
- Wi-Fi SSID/BSSID、手机号码等敏感字段不采集；不尝试绕过 Android 限制。

`BatteryManager` 的“健康”是系统枚举状态，不是准确的电池损耗百分比；电量计读数也不代表额定容量。未进行充放电测试、屏幕触摸测试、性能压力测试或硬件故障诊断。

## 5. 项目结构

```text
phone-inspector/
├── .github/workflows/android.yml   # GitHub 自动构建及 lint
├── settings.gradle
├── build.gradle
├── gradle.properties
├── app/
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/cn/minis/devicecheck/
│       │   ├── MainActivity.java    # 界面、搜索、刷新、导出
│       │   └── DeviceInspector.java # 参数采集与报告
│       └── res/
│           ├── drawable/ic_launcher.xml
│           ├── values/{strings,styles}.xml
│           └── values-v27/styles.xml # Android 8.1+ 导航栏主题
└── README.md
```

CI 使用 `gradle/actions/setup-gradle` 下载固定的 Gradle 8.9，**不依赖 Gradle Wrapper**，因此本项目不包含 `gradlew`。需要在电脑手动构建时先安装 JDK 17、Android SDK 35 和 Gradle 8.9，再执行：

```sh
gradle --no-daemon :app:assembleDebug :app:lintDebug
```

首次构建需要从 Google/Maven/Gradle 官方服务下载工具与依赖；这与安装后的应用离线运行不同。若失败，请在 Actions 中查看首个失败步骤的完整日志；lint 报告也会单独上传为 artifact。

## 6. 建议的验收清单

首次 GitHub 构建成功不等于所有机型已通过测试。安装后建议核对：

- Android 8、Android 15 及以上各至少一台；全面屏状态栏、导航栏、软键盘是否遮挡。
- 刷新、分类、搜索、折叠、旋转屏幕、前后台切换是否正常。
- 电池充/放电、Wi-Fi/蜂窝/无网络分别刷新，是否正确显示当前快照。
- 不支持或受限参数是否明确说明，而不是显示 0 或虚构值。
- 保存报告、取消保存、切换文件提供方、旋转保存页面、分享给文本应用。
- 确认系统应用权限页没有位置、相机、麦克风、电话等运行时权限请求。

源码没有将 lint 错误静默忽略；如果后续修改引入 SDK/API/权限错误，CI 应失败并提示修复。
