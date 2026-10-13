# 卡布达 Android MVP

卡布达是一个基于 **Decart 官方 Android SDK** 和 **Lucy 实时模型**实现的 Android AI 虚拟试穿 MVP。

用户可以从手机本地选择衣服图片，打开前置摄像头后实时查看 AI 试穿效果。项目不依赖 AnyWear 的专有代码、素材、模型权重或服务端。

## 功能

- 应用名称：卡布达
- 使用系统 Photo Picker 从手机选择本地衣服图片
- 自动修正图片 EXIF 方向，并缩放、压缩为适合传输的 JPEG
- 使用 Decart 官方 Android SDK `0.8.2`
- 前置摄像头实时预览
- 支持 `lucy-vton-latest` 等官方实时模型
- 实时 AI 试穿结果渲染
- 连接状态、断线和错误提示
- AI 输出出现后，本地预览自动切换为画中画
- 可保存商品图、元数据和本地/输出帧组成的会话 ZIP
- API Key 由用户在 App 设置页配置，不写入源码或构建文件

## 技术方案

项目通过 JitPack 引入 Decart 官方 SDK：

```kotlin
implementation("com.github.DecartAI:decart-android:0.8.2")
```

主要调用流程：

1. 在设置页配置 Decart API Key、实时地址和模型；
2. 从手机选择衣服图片并转换为 `data:image/jpeg;base64,...`；
3. 使用 `RealTimeClient.createLocalVideoStream()` 创建摄像头预览；
4. 调用 `RealTimeClient.connect()`，传入衣服图片和本地摄像头流；
5. 使用 LiveKit `TextureViewRenderer` 渲染本地预览与远端试穿结果；
6. 断开连接后销毁 Room，并重新创建干净的摄像头预览。

## 环境要求

- JDK 17
- Android SDK 35
- Android Studio 或命令行构建工具
- 支持相机的 Android 真机
- Android 7.0（API 24）及以上
- 可访问 Decart 服务的网络

## 快速开始

### 1. 克隆项目

```bash
git clone https://github.com/cdcRyan/kabuda-vton-android.git
cd kabuda-vton-android
```

### 2. 配置本机构建参数

复制配置模板：

```bash
cp local.properties.example local.properties
```

在 `local.properties` 中设置本机 Android SDK 路径：

```properties
sdk.dir=/your/android/sdk/path
```

`local.properties` 已被 `.gitignore` 排除，不能提交到 Git。

### 3. 构建 Debug APK

```bash
./gradlew assembleDebug
```

APK 输出路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

也可以直接用 Android Studio 打开项目，然后运行到 Android 真机。

## App 内配置

进入首页的“设置”页面：

- **Decart API Key**：必填，只保存在本机 SharedPreferences；
- **实时地址**：默认 `wss://api.decart.ai`；
- **模型名称**：默认 `lucy-vton-latest`；
- **可选后端 API 地址**：可留空；
- **遥测和反馈开关**：可选。

当前支持的模型包括：

```text
lucy-2.1
lucy-2.5
lucy-vton-3.5
lucy-vton-3.6
lucy-restyle-2
lucy-latest
lucy-vton-latest
lucy-restyle-latest
```

## 使用步骤

1. 安装并打开卡布达，首页会显示三步引导清单；
2. 点右上角「调节」图标进入设置，填写 Decart API Key（清单第一步自动打勾）；
3. 点底部左侧「衣服」从相册选择衣服图片；
4. 点底部右侧「打开相机」并授予相机权限；
5. 按下中间的快门键开始试穿，快门会变成红色停止键；
6. 等待远端 AI 输出，本地预览会自动缩小为右上角画中画；
7. 点右上角「⋯」打开会话面板，可保存/分享会话 ZIP、反馈或查看计费状态。

## 界面设计

- 暗色「暖调暗房」主题：近黑暖底 `#0D0C0B`、粉笔白文字 `#F3EEE6`、唯一强调色信号橙 `#FF5A36`（仅用于直播/停止/错误）；
- 衬线标题 + 无衬线正文 + 等宽技术字段（API Key、URL、模型名）；
- 首页为全屏相机，顶部状态胶囊（灰=待操作、绿=就绪、琥珀=连接中、橙=直播中/错误）；
- 底部为相机式 Dock：衣服缩略图 · 快门 · 相机开关；次要操作收进「会话」底部面板；
- 设置页按「Decart 实时服务 / 可选后端 / 隐私」分组，模型改为下拉选择，保存按钮吸底，恢复默认需二次确认；
- 全面沉浸式（edge-to-edge），提示统一使用锚定在 Dock 上方的 Snackbar。

## 常见问题

### Missing API key

- 确认设置页已填写 API Key 并保存；
- 重新进入设置页确认数据仍然存在；
- 确认 Key 没有被撤销，并拥有对应模型权限；
- 不要把 Key、`Bearer` 或引号以外的多余内容一起复制。

### Signaling error

- 使用 Android 真机，不要使用不支持 Realtime 相机功能的模拟器；
- 授予相机权限；
- 关闭 VPN、代理或会拦截 WebRTC 的网络工具；
- 尝试切换 Wi-Fi 或手机热点；
- 确保网络允许 HTTPS/WSS 443 和 UDP WebRTC 流量；
- 保持默认实时地址与官方模型，不要随意修改视频分辨率。

官方资料：

- [Decart Android SDK](https://docs.platform.decart.ai/sdks/android)
- [Android Realtime](https://docs.platform.decart.ai/sdks/android-realtime)
- [Realtime 最佳实践](https://docs.platform.decart.ai/models/realtime/streaming-best-practices)
- [网络要求](https://docs.platform.decart.ai/integrations/network-requirements)
- [Decart 状态页](https://status.decart.ai)

## 安全说明

- 不要把永久 API Key 写入源码、`gradle.properties`、`local.properties` 示例或提交记录；
- 不要提交 `local.properties`、签名文件、APK、AAB、日志和构建产物；
- 正式发布建议由自己的后端签发短期 Client Token；
- 正式上架前需要补充隐私政策，明确说明衣服图片和摄像头帧会发送给 Decart 实时服务。

## 当前限制

- MVP 不包含登录、应用商店计费、服务端余额页面或自有推理后端；
- 反馈、日志和计费需要可选后端；
- “保存会话”不录制视频；
- 尚未发布签名 Release 版本；
- 不包含 AnyWear 的商标、专有素材、模型权重或服务端代码。

## 项目结构

```text
app/src/main/java/ai/anywear/mvp/
├── MainActivity.kt              # 主界面和试穿流程
├── SettingsActivity.kt          # API Key 与服务配置
├── data/                        # 偏好设置、后端接口和数据层
├── realtime/DecartRealtimeSession.kt
└── recording/SessionArchive.kt  # 会话 ZIP 保存
```

## License

本仓库暂未指定开源 License。未经明确许可，请勿将项目用于商业再发布。
