# APK 签名

长按选择一个普通 `.apk` 文件，在底部“更多”菜单选择“APK 签名”。支持读取原始签名、使用已有密钥重新签名、创建签名密钥、配置签名方案、验证并保存独立输出。同时提供基础“制作共存版”，支持范围见 [APK 共存说明](apk-coexistence.md)。本应用最低支持 Android 11（API 30）。

## 使用方式与范围

1. 对话框直接使用文件列表选中的 APK。蓝色信息卡以文件名为主标题（最多两行），完整路径用小字号单行显示、中间省略，包名单行显示；文件大小与原签名状态并排排列。状态区分“读取中 / 未验证 / 读取失败 / 签名验证通过 / 未通过”，通过状态使用绿色背景和白字，状态框随文字收紧；操作中保留信息卡。底部按“详情 / 关闭 / 签名”顺序等宽、等距排列。“详情”支持选择复制完整路径、原始与输出 APK 信息、精确字节数、所选密钥库路径、版本、SDK 声明、验签结果、错误、证书和 SHA-256 指纹；读取中或取消后也可打开。界面不显示风险提示和验签警告。
2. 密钥区提供“选择已有”（已选择时为“更换密钥”）和“新建密钥”（新建时为“取消新建”）两个入口；未选择时只显示入口与简短提示。密钥来源为“系统文件选择器”和“已创建的密钥”；前者导入 PKCS12/JKS，后者列表显示修改时间与“当前使用”，空列表可直接新建。选择后显示文件名、单行小字号路径、库密码和别名，读取/刷新按钮与别名同行。格式默认自动识别，格式选择及独立私钥密码收在“格式与私钥密码”中；未启用独立密码或留空时复用库密码。
3. 新建使用独立的别名草稿，复用密钥库密码框并增加确认密码，密码须至少 8 个字符且两次一致。创建成功后自动选中新 PKCS12，可沿用当前窗口中的密码继续签名；取消或失败保留原先选中的密钥与别名。生成 RSA 3072 / SHA-256 自签名证书，有效期 25 年；新建 PKCS12 使用同一个密码保护密钥库和私钥。证书的 CN 使用所填别名。
4. 选择 v1/v2/v3 签名方案，最低签名 API 自动使用待处理 APK 的声明值，不再提供手动输入项。API 23 及以下要求 v1；API 24–27 至少要求 v1 或 v2。**不会修改 APK Manifest 中的最低 API**，报告单独显示实际验签范围。
5. 设置新的 `.apk` 文件名，输出默认保存到所选 APK 所在的当前目录，不显示输出目录及其选择按钮。签名与原文件始终分离，已存在的同名文件会被拒绝。完成后信息卡显示输出文件与输出签名状态，并提供查看 APK 内容、安装、分享和进入输出目录。

重新签名不会保留原开发者的数字签名身份。使用不同密钥一般不能更新已安装的原版，还可能影响签名权限、证书自检、第三方登录或服务端登记。签名验证通过不能证明应用已经安装或能够正常运行。

首期只处理自包含的单体 APK。读取二进制 Manifest 检查 `split`、`configForSplit`、`isFeatureSplit`、`isSplitRequired`、`requiredSplitTypes`、`uses-split` 及 Play 的 splits-required 元数据，拒绝需要其他拆分包的输入；APKS/XAPK/APKM 等容器不是单体 APK。Android 无法解析其应用信息、损坏的 ZIP、异常路径、重复/重叠条目、符号链接、ZIP64 和分卷包也会在发布前失败。这里不验证 DEX/JNI 的业务逻辑。

APK 使用流式复制与摘要；文件受 ZIP32 的约 4 GiB 上限约束，Manifest 上限 16 MiB，ZIP 元数据上限 32 MiB，展开条目总量上限 8 GiB。密钥库上限 16 MiB / 1024 项，JKS 证书链和口令派生参数另有上限；不支持的格式会有错误信息。输入需提供已知文件大小，本地符号链接不能直接作为输入。

## 依赖与实现

固定使用正式发布的 `com.android.tools.build:apksig:9.4.1`，与项目已有 AGP 同版，未升级其他已有依赖。已读取 Google Maven 的 [POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/apksig/9.4.1/apksig-9.4.1.pom)、[JAR](https://dl.google.com/dl/android/maven2/com/android/tools/build/apksig/9.4.1/apksig-9.4.1.jar) 和 [源码包](https://dl.google.com/dl/android/maven2/com/android/tools/build/apksig/9.4.1/apksig-9.4.1-sources.jar) 核实 API。JAR 的 SHA-256 为 `7ae2e5980c77d853e3513074ee7c822bbdcdcde1668889d17faa7fe8bc8aa821`，Apache-2.0 许可证已加入关于页。

签名模块已移除低版本 Android 的 `O_CLOEXEC` 常量回退、旧 ByteBuffer 调用适配、证书/私钥 API 绕行和旧系统安装 URI 分支。v1/v2/v3 选项与根据输入声明自动确定的最低签名 API 仍描述待签 APK 的签名规则，不属于本应用的旧系统适配，不会擅自修改输入的最低 SDK。

应用内直接调用 `ApkSigner.Builder` / `ApkVerifier.Builder`，不执行 SDK 的 `apksigner`，也不依赖设备上的 Termux、Root 或外部签名程序。Bouncy Castle 继续使用项目已有的 `bcprov/bcpkix-jdk15to18:1.86`；生成、解密时传入独立 Provider，不替换 Android 全局加密提供者。

- [ApkSigningService](../app/src/main/java/me/zhanghai/android/files/apk/core/ApkSigningService.java)：签名、ZIP 对齐、证书身份复核及失败清理。
- [ApkVerificationService](../app/src/main/java/me/zhanghai/android/files/apk/core/ApkVerificationService.java)：实际 APK 字节验签、方案、错误和警告。无效签名中可解析的证书仅用于检查，不能据此认定可信。
- [SigningKeyStoreService](../app/src/main/java/me/zhanghai/android/files/apk/core/SigningKeyStoreService.java)：生成和导入密钥、读取别名、私钥与证书配对检查。
- [ApkCertificateInfo](../app/src/main/java/me/zhanghai/android/files/apk/core/ApkCertificateInfo.java)：证书主体、颁发者、序列号、有效期、公钥算法与 SHA-256。
- [ApkOperations](../app/src/main/java/me/zhanghai/android/files/apk/ApkOperations.kt)：提供方输入暂存、元数据读取、任务隔离、输出保存与重启清理。

Android 和 BC 的标准 JKS 实现不能直接提供所需的旧 JKS 私钥导入。因此这里实现了有界的只读 JKS v1/v2 适配，验证库完整性、解密旧 KeyProtector、验证证书与私钥匹配，使用 `keytool` 创建的真实 JKS 交叉测试；不生成新的 JKS。PKCS12 使用 BC 的 SafeBag API，支持独立的密钥库和私钥密码，拒绝没有完整性保护的密钥库。

签名前规范化 ZIP 对齐：普通未压缩条目 4 字节，未压缩原生 `.so` 条目 16 KiB。保留应用 entry 的原压缩数据；签名元数据会被重建，空 `META-INF/` 目录可能被 apksig 移除。签名后检查对齐、通过 ApkVerifier 验签并核对选中证书，再对最终暂存副本验签和重新读取应用信息，保存后回读 SHA-256 确认字节未改变。ZIP 对齐不保证原生 ELF 本身满足 16 KiB 页设备的运行要求。

## 生命周期与敏感数据

UI 仅收集配置和展示状态，阻塞处理放在既有 `viewModelScope` 的 `runInterruptible(Dispatchers.IO)` 中。旋转保留正在运行的任务，表单和进度恢复；密码视图禁用状态保存和 Autofill，任务口令使用 `char[]`，所有出口清零，私钥在使用后尽力销毁并释放引用。输入框中的口令在开始签名、切换密钥库或关闭对话框时清空。密码不进入 Bundle、Intent、普通配置或日志。

新建加密密钥库位于 `noBackupFilesDir/apk-signing/keys`，不会进入 Android 自动备份；清除应用数据或卸载应用会丢失这些密钥。没有后台上传或默默导出密钥的行为。

APK 工作文件位于私有 `cacheDir/apk-signing`。线程锁及文件锁串行化并发操作，排队也可取消；每次操作独立工作目录，释放流、文件锁和临时文件。取消时等待底层工作停止后再清理。进程重启不重放包含密码的任务，用户需要重新开始。

保存仅允许本地目录：使用 Android `Os.open(O_CREAT | O_EXCL | O_NOFOLLOW)` 排他创建新文件，避免现有文档/网络提供方的“先检查、再创建”竞态；不以覆盖式 rename 发布结果。输出成功前执行同步落盘及摘要复核。发布日志只记录输出和私有工作路径、设备/inode、大小和摘要，不含密码或私钥。启动时在既有后台执行器上清理中断工作，后续操作也会重试；仅删除仍匹配原文件身份、且逐字节等于私有已验证副本前缀的不完整输出，保留已经写完且摘要匹配的产物。用户后来改写的内容、私有副本已被系统清理等无法核实的情况会保留文件与日志，需用户处理；权限撤销等失败也保留诊断。系统在极短的创建/记录窗口终止进程仍可能留下空文件；不会据此认定任务成功。

## 验证

主机验证命令：

```sh
python3 tools/verify_apk_signing.py
```

脚本将固定版本的官方 JAR 和固定 AOSP 提交的测试 APK 缓存于 `~/tmp/materialfiles-apk-tools`，检查依赖 SHA-256，用 JDK 编译实际生产 Java 核心并运行 [测试](../tests/ApkSigningCoreTest.java)。所有测试密钥都是临时生成的公开测试数据。样本来自 AOSP [固定提交](https://android.googlesource.com/platform/tools/apksig/+/184702d9d18877edf9e5296c4e191cf0aa2b5fbb/src/test/resources/com/android/apksig/)，不是为了通过测试伪造的 APK 容器。

主机套件包含签名核心 20 项、二进制 Manifest 22 项，并运行基础共存 Manifest 改写检查：覆盖未签名/已有签名 APK、损坏签名、证书指纹、各签名方案、ZIP payload 保留及对齐、JKS/PKCS12 与独立口令、错误密码/别名、已有输出及晚期重名保护、取消、中文和空格路径、64 MiB 输入及二进制 Manifest 边界。针对 API 23 和 36 的 ApkVerifier 检查只证明对应签名规则的有效性，不等同于在这些系统上运行应用。

Android ART 验证使用 [verify_apk_signing_android.py](../tools/verify_apk_signing_android.py)。先构建 Debug APK 并运行主机脚本，然后在 Android/Termux 中执行：

```sh
python3 -B tools/verify_apk_signing_android.py \
  --android-jar "$HOME/tmp/materialfiles-check/sdk/platforms/android-37.2/android.jar" \
  --java-home "$PREFIX/lib/jvm/java-21-openjdk"
```

SDK/JDK 路径按本机环境调整。完整套件还应传入 `--jks`，指向主机脚本在 `~/tmp/materialfiles-apk-tools/test-runs/ApkSigningCoreTest-*/run-*/external keytool.jks` 生成的临时测试文件，以覆盖来自 `keytool` 的独立密钥库/私钥密码；省略时不执行该项，检查总数相应减少。可用 `--apk` 指定待测构建。默认加载该 APK 中的实际生产 DEX、apksig 和 BC，只有测试入口由 D8 单独编译；`--standalone` 仅用于预先验证源码，不能替代实际 APK 验证。

[签名 ART 测试](../tests/ApkSigningAndroidTest.java) 覆盖真实签名、密钥库、中文/空格路径、取消、32 MiB 样本以及重新签名 Material Files 自身，并比对实际 APK 的资源 ID。[输出恢复 ART 测试](../tests/ApkOutputRecoveryAndroidTest.java) 调用实际 `OwnedOutput` 和 Android 文件系统 API，检查未完成输出清理、同 inode 改写/替换文件保护、缺失或变化的私有副本、符号链接、恢复取消及日志。脚本在 `~/tmp/materialfiles-apk-art-check/run-*` 保存命令、源码与依赖摘要、APK 摘要、系统版本和逐项结果。

完整项目的标准检查为 `:app:assembleDebug :app:lintDebug`。Termux 本轮使用隔离的 Gradle 9.6 / SDK 37.2 / Build Tools 37、Android 版 aapt2/AIDL 和既有 JNI；缺少的 syscall/hiddenapi 库从当前源码及 NDK 29 sysroot 为四个 ABI 编译。此环境通过临时初始化脚本复用 JNI，未升级项目已有依赖，也未修改项目 SDK/NDK 配置或 `local.properties`，属于调试验证。

标准检查为 `:app:assembleDebug :app:lintDebug`，另检查 `:app:minifyReleaseWithR8`。输出 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`；ART 脚本会记录本轮实际 APK 的 SHA-256，避免将旧产物的结果用作新代码的验证。

Android 11 真机、完整签名窗口/IME/旋转交互、真实磁盘耗尽和系统强杀后的端到端恢复仍需要设备验收。ART 恢复测试不等于强杀进程的端到端测试；签名通过也不等于安装成功、启动成功或应用功能正常。同时安装与功能验收的独立状态由共存测试报告记录。

## 密钥界面与图标验证（2026-10-16）

本轮调整密钥选择与创建布局，来源仅保留“系统文件选择器”和“已创建的密钥”；共存按钮改为“确定”，关于页使用 18dp 的 X 品牌矢量图标。

使用既有 Termux 工具链离线执行 `:app:assembleDebug :app:lintDebug`，构建成功，Lint 为 0 错误、609 警告。aapt2 核对新 APK 内的 X 图标样式为 18dp，来源文案与“确定”已打包，专用的“文件管理器”密钥来源资源已移除。本轮布局、文案及 X 图标没有 Lint 告警。

产物为 `app/build/outputs/apk/debug/app-debug.apk`，大小 **66,614,692 字节**。SHA-256：

```text
003b7bad1be19b6b2c21d191eea30371164272559043ae3e5a712d2914d1103e
```

本轮验证编译、静态检查及打包资源，未重跑 Java/ART 签名与共存功能套件；未进行实机窗口、深浅主题、大字号、键盘和旋转交互验收。

## 紧凑信息卡验证（2026-10-16）

使用既有 Termux 工具链离线执行 `:app:assembleDebug :app:lintDebug`，构建成功，Lint 为 0 错误、609 警告。用 aapt2 读取新 APK 的实际布局，确认文件名最多两行、路径与包名单行省略、大小与签名状态可换行，以及密钥库路径的小字号与限行属性已打包。

产物为 `app/build/outputs/apk/debug/app-debug.apk`，大小 **66,611,655 字节**。SHA-256：

```text
1b12f47e898b40cd4eb9156dcc310abbe37f02476c39525073c3c18aa4346aee
```

本轮调整布局和信息展示，未重跑签名/共存功能套件。当前无可用 adb 或界面渲染环境，未进行真机窗口、大字号、深浅主题、键盘和旋转交互验收；构建与资源检查不代表视觉验收。

## 界面精简验证（2026-10-16）

使用既有 Termux 工具链执行 `:app:assembleDebug :app:lintDebug`，构建成功，Lint 为 0 错误、617 警告。另用 aapt2 读取新 APK 内的 `res/layout/apk_signing_dialog.xml`，确认打包布局可解析。

本次产物为 `app/build/outputs/apk/debug/app-debug.apk`，最低 API 30，仅含 arm64-v8a，大小 **65,579,989 字节**。SHA-256：

```text
0e15156bf77a5c558a2729642f06d936e6efe547ec8969c27567c62d9e5d8f3e
```

本次未重跑 Java/ART 功能套件，也未实测完整窗口、键盘及旋转交互；下节功能验证对应界面精简前的 APK。

## 界面精简前的功能验证（2026-10-16）

以下为界面精简前的历史验证记录，仅对应下述 SHA-256 的 APK。该次 Debug APK 输出至 `app/build/outputs/apk/debug/app-debug.apk`，最低 API 30，仅含 arm64-v8a，大小 **65,579,989 字节**（约 65.6 MB）。SHA-256：

```text
f1ca625f7b6364e179fd6549175a46da896498e316d5b958586d866cd6379da8
```

该次构建、Lint（0 错误、616 警告）与 Release R8 检查通过。主机 68 项回归通过。在 Android 15 / API 35 上加载上述 APK，签名与资源检查 16/16、输出恢复 13/13 通过；传入了主机生成、密钥库密码与私钥密码不同的真实 keytool JKS。报告在 `~/tmp/materialfiles-apk-art-check/run-5kwp2l9i/report.json`。基础共存另有 11 项 ART 检查与 99 条生命周期断言通过，见 [界面精简前的共存验证](apk-coexistence.md#界面精简前的功能验证2026-10-16)。

该 Debug 没有启用代码裁剪，且 AGP 在 minSdk ≥ 28 时默认将 DEX 不压缩打包。该 APK 的 DEX 共 47,573,160 字节，是其主要体积来源；仅移除其他架构已将 APK 从 90,063,423 字节降至上述大小。可通过独立的 `packagingOptions.dex.useLegacyPackaging = true` 开启 DEX 压缩，不必降低最低 SDK；该次构建没有启用该选项。

Apktool 的约 4.5 MB 框架包只有系统 `resources.arsc` 和 `AndroidManifest.xml`，供 aapt2 编译 Manifest 时解析系统资源引用。该版本只调用 Manifest 处理链路，但 Debug 仍编入 Apktool 的完整 Java 模块与依赖，Smali 和完整资源解码功能尚未从该次构建中剥离。
