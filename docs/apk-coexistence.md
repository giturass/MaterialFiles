# APK 共存

应用支持 Android 11（API 30）及以上的 arm64-v8a 设备，处理单体 APK。基础流程为**修改安装包名 → 重建 Manifest → 对齐 → 重新签名 → 验证并另存**。原文件始终保留。

## 使用

1. 长按选择一个普通 `.apk` 文件，在底部“更多”中选择“制作共存版”。对话框直接使用所选文件，不再提供“选择 APK”按钮。
2. 输入目标包名，默认是原包名加 `.clone`，也可以自行修改。
3. 选择或创建签名密钥，设置签名方案和新的输出文件名。最低签名 API 自动使用原 APK 的声明值，输出默认保存到所选 APK 所在的当前目录；不再显示最低 API 输入项、输出目录和目录选择按钮。
4. 点击“确定”，执行改包名、重建和签名。完成后可以打开 APK、安装、分享或进入输出目录。

窗口的蓝色信息卡以文件名为主标题（最多两行），完整路径用小字号单行显示、中间省略，包名单行显示；大小与原签名状态并排排列。状态区分“读取中 / 未验证 / 读取失败 / 签名验证通过 / 未通过”，通过状态使用绿色背景和白字，状态框随文字收紧。操作中保留信息卡，完成后改为输出文件与输出签名状态。

密钥区提供“选择已有 / 更换密钥”和“新建密钥 / 取消新建”两个入口，未选择时只显示入口与简短提示。已有密钥来源为“系统文件选择器”和“已创建的密钥”。选择后显示文件摘要、库密码及同行的别名与读取/刷新按钮；格式与独立私钥密码在“格式与私钥密码”折叠选项中。私有密钥列表显示修改时间与“当前使用”，空列表可直接新建。新建使用独立别名草稿、库密码框和确认密码（至少 8 个字符且两次一致），成功自动选中并可沿用当前窗口密码；取消或失败保留旧密钥和别名。

底部按“详情 / 关闭 / 确定”顺序等宽、等距排列。“详情”可复制完整路径、原始与输出 APK 信息及精确字节数、所选密钥库路径、签名方案、证书指纹、验签结果和修改结果；读取中或取消后也可打开。界面不显示风险提示和验签警告，错误仍会显示。

## 修改范围

仅编辑 `AndroidManifest.xml`：

- 更改安装应用 ID。
- 将相对组件类名补为原来的完整类名，保留原 DEX 中的类。
- 遍历 `<application>` 内所有 `<provider>`，逐项改写 `android:authorities`（支持分号分隔）。原包名前缀替换为目标包名，其他固定 authority 加上目标包名前缀；`<queries><provider>` 的外部查询声明保留。
- 调整自定义权限的 Manifest 声明及引用，减少与原版安装时的名称冲突。
- 移除共享 UID 声明，使共存版获得独立 UID。
- 保留名称、图标、版本和 SDK 声明；没有显式应用标签时保留原显示名。

写入前检查 authorities：改写后的名称不能彼此冲突，也不能与原 APK 的任一 authority 重叠。若目标包名导致冲突，操作会报错并要求更换目标包名，避免生成与原版发生 `INSTALL_FAILED_CONFLICTING_PROVIDER` 的 APK。

DEX、`resources.arsc`、资源文件、assets 和原生库内容保持原样。不做 Smali 数据流分析、不迁移代码类名，也不扫描 JNI、反射或第三方 SDK 来预判兼容性。

这是一项基础改包名功能。代码中固定的包名、Provider URI、权限、资源包名或证书身份不会自动迁移；依赖这些信息、共享 UID、服务端登记、App Links 或加固校验的应用可能不能正常使用。输出页只报告实际完成的重建和验签，不能据此认定两个应用都能安装、启动和正常工作。

只接受自包含的单体 APK；Split APK、依赖其他 split 的 base APK 和 APKS/XAPK/APKM 容器不在范围内。输入上限 512 MiB，展开体积上限 1 GiB，资源表上限 16 MiB；文件与目录总数、路径层级及 XML 解析另有边界检查。无效 ZIP、越界路径、重复条目和符号链接会明确失败。

## Android 实现

使用固定的 [apktool-android v1.0.0](https://github.com/anirudhmalik/apktool-android/tree/f0ed422f89f37276a55e464d208b0eeb91d1ca0b)，提交 `f0ed422f89f37276a55e464d208b0eeb91d1ca0b`。其 Java 基线为 `3.0.3-SNAPSHOT`，并非后来正式发布的官方 Apktool 3.0.3。源码、原生工具、依赖版本、补丁与许可证记录在 [third_party/apktool-android](../third_party/apktool-android/README.md)。

[ApkCoexistenceService](../app/src/main/java/me/zhanghai/android/files/apk/ApkCoexistenceService.java) 使用 `ONLY_MANIFEST` / `DecodeSources.NONE`。解析原资源表以还原 Manifest 中的资源符号，编译时将原 APK 作为资源依赖，只重建 Manifest，然后复制原资源和代码。集成修复了该模式未加载资源表、存在原 `resources.arsc` 时跳过 Manifest 重建的上游路径。

aapt2 随应用内置于 `nativeLibraryDir/libaapt2.so`，原生目标 API 30。构建仅打包 arm64-v8a，包括文件管理本地库和 aapt2；这不限制待处理 APK 内原生库的架构，因为不会加载或改写它们。运行功能不依赖 Termux、Root、外部桌面工具或在线下载。缺少工具时会在处理前报错。

最终通过官方 apksig Java API 完成对齐、签名与独立验签，复核目标包名和应用元数据，再保存到所选 APK 所在的当前本地目录。详情见 [APK 签名](apk-signing.md)。

后台处理沿用签名任务的串行锁、私有工作目录、可取消 IO 和输出日志；密码不保存。原生子进程有超时、取消与输出收集回收机制。取消或失败后等待工作停止再清理临时目录，进程重启不会重放带有密码的任务。

## 验证

```sh
python3 -B third_party/apktool-android/verify_vendor.py
python3 -B tools/verify_apk_signing.py
```

第一条核对固定的第三方文件和补丁摘要。第二条编译实际 Java 核心，检查 Manifest 改写、签名、密钥库、输入边界与取消行为。

构建 Debug APK 后，在 Android / Termux 中运行实际 APK 的 ART 联合验证：

```sh
python3 -B tools/verify_apk_coexistence_android.py \
  --android-jar "$HOME/tmp/materialfiles-check/sdk/platforms/android-37.2/android.jar" \
  --java-home "$PREFIX/lib/jvm/java-21-openjdk"
```

路径可按本机 SDK/JDK 调整。脚本只编译测试入口，生产代码、资源、加密库和 aapt2 均从待测 APK 加载。自有单体夹具包含 Activity、多个 Provider、自定义权限、资源与持久化操作；核对重建和签名后二进制 Manifest 中的全部 authorities（含分号列表和非包名前缀）、新旧 authority 集合无交集、Provider 查询声明保留，以及新包名、有效签名和未修改 payload，并检查取消、构建失败和子进程生命周期。命令、输入摘要、逐项日志和两个测试 APK 保存在 `~/tmp/materialfiles-apk-coexistence-check/run-*/`。

脚本默认将同时安装、冷启动和 Provider 功能测试分别记录为 `not-run`。有已连接设备的 adb 时可通过 `--adb /path/to/adb` 启用这些项目；脚本拒绝覆盖已存在的同名测试应用，只清理由本轮安装并验证身份的夹具。ART 中运行解压的 aapt2 不等于已验证安装后 `nativeLibraryDir` 的执行路径。

本轮密钥界面和底栏文案的 Debug 构建、Lint、产物摘要及验证范围见 [密钥界面与图标验证](apk-signing.md#密钥界面与图标验证2026-10-16)。之前的记录见 [紧凑信息卡验证](apk-signing.md#紧凑信息卡验证2026-10-16)及 [界面精简验证](apk-signing.md#界面精简验证2026-10-16)。

## Provider authorities 验证（2026-10-16）

- 主机回归 70 项通过：共存 Manifest 改写 28 项、二进制 Manifest 读取 22 项、签名与密钥库 20 项。新增用例覆盖多个 Provider、命名空间别名和新旧 authority 冲突；冲突检查不依赖声明顺序，失败时文件内容保持不变。
- Debug 构建通过；Android 15 / API 35 的实际 APK 共存 ART 检查 12/12 通过，验证 3 个 Provider、5 个 authority 在重建及签名后的二进制 Manifest 中均按预期改写，且与原版无交集。
- 原生进程与工作线程生命周期检查 106/106 通过。第三方 151 个文件的摘要校验通过。

本次待测 `app/build/outputs/apk/debug/app-debug.apk` 的 SHA-256 为 `b31bc863283e0e3ee5cd6477370bdfacefa93a5a1919436a7260adff461b734f`。报告保存在 `~/tmp/materialfiles-apk-coexistence-check/run-bril157o/report.json`，包含改写前后的完整 authority 集合。未启用 adb，同时安装、冷启动和 Provider 业务读写记录为 `not-run`。

## 界面精简前的功能验证（2026-10-16）

以下为界面精简前的历史验证记录，对应的 APK 摘要见下方链接。

- 主机回归：68 项通过，其中基础共存 Manifest 改写 26 项、二进制 Manifest 22 项、签名与密钥库 20 项。
- 该次 arm64 APK 在 Android 15 / API 35 的共存 ART 检查 11/11 通过：真实夹具完成改包名、Manifest 重建、独立密钥签名和 v2/v3 验证；所有非 Manifest/签名 payload 的名称与内容不变，图标/标签/资源 ID 保留，取消及构建失败没有输出成品。
- 原生进程与工作线程生命周期 99 条断言通过，包括超时、取消、回收、16 KiB 输出保留上限和临时目录隔离。
- 签名与输出恢复 ART 检查 29/29 通过，同样使用该次 APK 的生产代码。
- 该次 Debug 构建、Lint（0 错误、616 警告）和 Release R8 检查通过；APK 中仅含 arm64-v8a 本地库。第三方 151 个文件的摘要校验通过。

报告分别保存在 `~/tmp/materialfiles-apk-coexistence-check/run-l1zxil_a/report.json` 和 `~/tmp/materialfiles-apk-art-check/run-5kwp2l9i/report.json`，两者记录的待测 APK SHA-256 一致。对应的历史产物信息见 [界面精简前的签名验证](apk-signing.md#界面精简前的功能验证2026-10-16)。

该次验证时本机没有 adb；同时安装、冷启动、Provider 业务读写以及安装后 nativeLibraryDir 的执行均未实测。Android 11 真机、其他页大小设备和完整 UI 交互也未覆盖。以上 ART 成功仅证明当时已经执行的项目，不代表任意输入应用都能正常共存。
