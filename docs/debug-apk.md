# GitHub Actions Debug APK

推送 `master` 或手动运行 [Debug APK](https://github.com/giturass/MaterialFiles/actions/workflows/debug.yml)
即可在 GitHub Actions 构建 debug APK，无需在手机上构建，也不需要发布签名密钥。

工作流使用项目指定的 JDK 21、SDK 37.2、Build Tools 37.0.0 和 NDK
30.0.16248370，运行 `:app:assembleDebug :app:lintDebug`，并限制为两个 Gradle worker。
随后检查 APK 签名、ZIP 完整性、新文件列表代码、各 ABI 的原生库和跟踪 SDK 缺失状态。

成功后的 `materialfiles-debug-<提交 SHA>` artifact 包含 debug APK、SHA-256、签名检查结果
和校验报告；Lint 报告单独上传。APK 使用 debug 签名，原有签名 release 工作流继续保留。
