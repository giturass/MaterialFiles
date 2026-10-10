# 本地定制说明

基于 [Material Files](https://github.com/zhanghai/MaterialFiles) 的 `c9b29cb3`（1.7.5）。

- 文件浏览仅保留列表，移除网格入口、布局和视图类型设置；原有全局和各文件夹的排序设置继续可用。
- 全应用固定使用 Material 3，移除旧版设计切换和仅适用于旧版设计的手动颜色选择；保留系统动态配色。夜间模式统一为“跟随系统、开启、关闭、A屏黑”，纯黑模式不再使用独立开关；迁移旧偏好，A屏黑强制使用深色纯黑主题。
- 应用名称及主界面标题统一为“文件管理”。应用语言固定为简体中文，移除语言切换入口及其他语言翻译；默认文案也使用简体中文，非中文系统、旧语言设置和后台通知均按简体中文处理。
- 列表缩略图使用圆形裁切；APK 文件图标单独从原始自适应图标图层生成圆形，避免系统的圆角矩形蒙版已烘焙到图片中，并刷新 APK 图标缓存。文件夹外围圆形背景由 40dp 放大至 48dp，采用淡蓝色填充和 3dp 蓝色阴影，暗色模式增强背景和阴影的可见度。选择、符号链接、加密及应用徽标保留。
- 点击 APK 文件后的操作弹窗按“取消、查看、安装”顺序等宽、等距排列按钮。
- 属性对话框顶部标签等宽平分、居中显示；书签和存储项编辑弹窗的“隐藏/显示或移除、取消、确定”按钮按顺序等宽、等距排列。
- 侧边栏按“存储空间、书签、快捷方式”分组，列表独立滚动；底部固定左“服务器”、右“设置”两个胶囊按钮。“关于”移至设置页最底部。“服务器”沿用原 FTP 服务功能。选中背景和触摸反馈仍使用无圆角、无描边的矩形，并覆盖整行至侧边栏右缘。
- 顶部“文件管理”标题与侧边栏按钮垂直居中对齐，移除文件夹和文件数量副标题；加载和错误状态继续由内容区显示。路径文字起点对齐导航图标左缘，使用现有手机、平板和 RTL 尺寸规则。
- 移除文件列表右下角悬浮菜单及预留空间；将“新建文件”“新建文件夹”按此顺序放在右上角菜单的“新建窗口”之后，沿用已有创建对话框和简体中文文案，移除“向上”“转到”菜单项。
- 目录观察触发静默刷新，合并扫描期间的变更，丢弃已取消或旧目录的加载结果；属性更新不再反复触发加载界面和条目淡入淡出。
- 未变化的缩略图和应用徽标保留已有请求及图像，动态图不会因目录刷新重新播放；实际文件内容、权限、链接目标或缩略图设置变更时重新加载，复用视图时拒绝旧请求回调。
- 压缩任务在文件列表底部显示扫描、写入和收尾进度及取消按钮，避开系统导航栏，并在底部操作工具栏展开时保持位于工具栏上方。多个任务的进度区域可滚动，并限制高度；返回应用或旋转后恢复最新状态。
- 创建压缩文件界面改为名称、格式下拉框和按格式显示的加密选项，预览实际输出名称，支持滚动，打开时不自动弹出键盘，支持系统返回键关闭。底部仅保留等宽的“取消、确定”按钮，任务进度显示在文件列表底部。压缩完成后以 Toast 提示“压缩文件创建成功”；取消、失败或压缩成功但源文件未全部删除时提示对应结果。任务结束时清理进度和失败输出。
- 新增 tar.gz，使用现有 libarchive 的 TAR 格式和 gzip 过滤器；7z 新增 AES-256 内容加密及可选文件名加密，补齐应用内解密读取。7z 继续支持 Android 7.0/API 24 及以上，使用 NIO desugaring 兼容旧系统。非本地目标先在应用私有缓存中生成最终加密归档，再顺序写出，避免 FTP 或文档提供方不支持随机写导致损坏。实现依据和可重复验证见 [7z](docs/7z.md) 与 [tar.gz](docs/tar-gzip.md)。
- “在终端中打开”保留 Android Terminal Emulator 支持，新增 Termux / MDTerm 的运行命令接口，并正确传递包含空格、引号及 URI 特殊字符的目录。
- 移除旧 `TextEditorActivity`、文本编辑布局及滚动 EditText，文件编辑入口改为 Sora Editor 0.24.6 独立界面。接入离线 TextMate 高亮、关键词和文档标识符补全、括号配对、自动缩进、符号栏、搜索替换、跳行、撤销重做、行列状态及编辑设置，支持 14 种常见语言并自动识别文件名。保留文件提供方读写和编码选择，旋转后保留正文、选择、滚动和撤销历史；保存期间继续输入不会被标为已保存。功能、验证及设备交互验收见 [Sora 编辑器](docs/sora-editor.md)，语法资源和许可来源见 [语言资源](docs/sora-language-assets.md)。

Termux / MDTerm 首次使用需要授予本应用“在 Termux 环境中运行命令”权限，并在终端的 `~/.termux/termux.properties` 中启用 `allow-external-apps=true`。终端本身必须已初始化且有权访问目标目录。应用会提供授权入口或启动失败说明，不会修改终端配置。正常退出或手动关闭终端会话不会提示打开失败。

压缩任务运行时，Android 要求前台服务保留通知，因此界面进度可见期间通知也会存在。Android 13 及以上还需允许本应用发送通知，后台进度才能显示在通知栏。

常规 Android 构建使用仓库原有 Gradle 配置：JDK 17 及以上、SDK 37.2、Build Tools 37.0.0、NDK 30.0.16248370，执行 `./gradlew assembleDebug lintDebug`。

远端 `master` 推送和手动触发的 GitHub Actions 改为构建已签名的 Release APK，执行 Release Lint，并在上传前验证证书指纹与 MDTerm 发布版一致。产物包含 APK、SHA-256 和校验记录；流程及 Secrets 名称见 [Release APK 构建说明](docs/release-apk.md)。

本机 Termux 验证使用 `~/tmp/materialfiles-check/` 中的隔离工具链与 Gradle init script。原有两个 JNI 库由 Termux Clang 21 和 NDK r29 的 Android API 23 sysroot 编译为 ARM64，再交给 Gradle 打包；通用项目仍使用常规的 Gradle 与 NDK 配置。本地产物采用调试签名，不能覆盖不同签名的官方安装版本。

产物为 `app/build/outputs/apk/debug/app-debug.apk`（ARM64，调试签名）。最新文件大小、SHA-256、构建、签名、XML、DEX 和 Sora 运行时检查结果见 `app/build/reports/sora-editor-validation.json`，Lint 报告见 `app/build/reports/lint-results-debug.html`。默认可翻译文本使用简体中文；生成的语言配置仅含 `zh-CN`，并保留依赖提供的简体中文文案。

新增归档验证直接运行项目实际 Java/Kotlin 代码和 AAR 内的 libarchive，并与官方 7-Zip、系统 tar 交叉检查。界面的实际视觉效果、权限弹窗、横竖屏、系统导航栏、并行任务和通知操作仍需设备交互验收；归档测试的覆盖范围及限制分别记录在上述文档中。

实现依据：

- [Termux RUN_COMMAND 接口](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent)
- [Termux login 实现](https://github.com/termux/termux-tools/blob/master/scripts/login.in)
- [Android 运行时权限](https://developer.android.com/training/permissions/requesting)
- [Android 前台服务](https://developer.android.com/develop/background-work/services/fgs)
- [Material ShapeableImageView](https://github.com/material-components/material-components-android/blob/1.14.0/lib/java/com/google/android/material/imageview/ShapeableImageView.java)
- [MaterialShapeDrawable 填充和阴影](https://github.com/material-components/material-components-android/blob/1.14.0/lib/java/com/google/android/material/shape/MaterialShapeDrawable.java)
- [Material NavigationView 样式](https://github.com/material-components/material-components-android/blob/1.14.0/lib/java/com/google/android/material/navigation/res/values/styles.xml)
- [Coil 2.7 图像请求](https://github.com/coil-kt/coil/blob/2.7.0/coil-base/src/main/java/coil/request/ImageRequest.kt)
- [Android NDK 与其他构建系统](https://developer.android.com/ndk/guides/other_build_systems)
- [Material 文本输入与格式下拉框](https://github.com/material-components/material-components-android/blob/1.14.0/docs/components/TextField.md)
- [Android 自适应图标图层实现](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/graphics/java/android/graphics/drawable/AdaptiveIconDrawable.java)
