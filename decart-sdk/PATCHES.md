# Decart Android SDK 本地补丁

此目录是 Decart 官方 Android SDK `0.8.2` 源码的本地副本，仅用于修复实时
信令协议兼容问题。

- 上游仓库：https://github.com/DecartAI/decart-android
- 上游版本：`0.8.2`
- 上游提交：`ce946a07c815d473e00c3889dd1e44a50e574b8b`
- 许可证：MIT，见同目录 `LICENSE`

## 补丁内容

`SessionIdMessage` 原先要求服务端必须返回 `server_ip` 和 `server_port`。
当前实时服务返回的 `session_id` 消息已经不再包含这两个旧字段，导致
`kotlinx.serialization.MissingFieldException`，最终被 SDK 归类为
`WEBRTC_SIGNALING_ERROR` / `Signaling error`。

两个字段对 LiveKit 传输没有实际用途，因此在本副本中改为可缺省：

```diff
-    @SerialName("server_ip") val serverIp: String,
-    @SerialName("server_port") val serverPort: Int
+    @SerialName("server_ip") val serverIp: String? = null,
+    @SerialName("server_port") val serverPort: Int? = null
```

补丁升级到新的上游版本时，请重新核对这两个字段是否已在上游修复。
