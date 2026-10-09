# GitHub Actions Release APK

推送 `master` 或手动运行 [Release APK](https://github.com/giturass/MaterialFiles/actions/workflows/android.yml)
工作流，会在 Ubuntu 上使用 JDK 21、SDK 37.2、Build Tools 37.0.0、NDK
30.0.16248370 和 CMake 3.22.1 执行 `:app:assembleRelease :app:lintRelease`。
Release 启用 R8 和资源压缩，JNI 使用项目的常规 NDK 配置构建。

## 签名

APK 使用与 [MDTerm 发布构建](https://github.com/giturass/MDTerm/actions/runs/37774449287)
相同的发布证书，SHA-256 为：

```text
4bea1b63b90233d46ed951d66bae2927440c595323234556da7d2038aec25e89
```

仓库 Actions Secrets 使用以下名称：

- `MDTERM_RELEASE_KEYSTORE_BASE64`：原发布 PKCS#12 密钥库的 Base64。
- `MDTERM_RELEASE_STORE_PASSWORD`：密钥库口令。
- `MDTERM_RELEASE_KEY_ALIAS`：签名条目别名。
- `MDTERM_RELEASE_KEY_PASSWORD`：私钥口令。

工作流在 runner 临时目录还原密钥库，通过既有 `signing.gradle` 的环境变量接口
提供签名参数，结束后清理密钥库。仓库和构建产物不包含签名私钥或口令。

## 产物及校验

成功运行会上传 `materialfiles-release-<完整提交 SHA>` artifact，保留 90 天：

- `MaterialFiles-<短提交 SHA>-release.apk`
- 对应的 `.sha256`、`.verification.json` 和 `.signature.txt`

[`tools/verify_release_apk.py`](../tools/verify_release_apk.py) 在上传前核验 APK 签名，
要求签名证书与上述指纹一致；同时检查包名、不可调试状态、Sora 编辑入口、
ARM64 原生库、全部语言资源及固定校验和、JCodings 运行时表。校验失败不会
上传 APK。Lint 和 R8 报告另存为 `materialfiles-build-reports-<提交 SHA>` artifact。

APK 内的语法资源检查不能替代混淆后代码的设备交互验收。编辑器的 Android
运行时测试和交互覆盖范围见 [Sora 编辑器说明](sora-editor.md)。
