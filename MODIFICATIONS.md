# 本地定制说明

基于 [Material Files](https://github.com/zhanghai/MaterialFiles) 的 `c9b29cb3`（1.7.5）。

- 文件浏览仅保留列表，移除网格入口、布局和视图类型设置；原有全局和各文件夹的排序设置继续可用。
- 文件与文件夹名称显示在第一行；第二行左侧显示文件夹的直接子项数量或文件大小，右侧按本地时区显示 `yyyy/MM/dd HH:mm`。文件夹项数在后台读取并缓存，无法读取时显示“—”。
- 全应用固定使用 Material 3，移除旧版设计切换和仅适用于旧版设计的手动颜色选择；保留系统动态配色。夜间模式统一为“跟随系统、开启、关闭、A屏黑”，纯黑模式不再使用独立开关；迁移旧偏好，A屏黑强制使用深色纯黑主题。
- 应用名称及主界面标题统一为“文件管理”。应用语言固定为简体中文，移除语言切换入口及其他语言翻译；默认文案也使用简体中文，非中文系统、旧语言设置和后台通知均按简体中文处理。
- 列表缩略图使用圆形裁切；APK 文件图标单独从原始自适应图标图层生成圆形，避免系统的圆角矩形蒙版已烘焙到图片中，并刷新 APK 图标缓存。文件夹外围采用 48dp 圆形背景和 3dp 阴影，参考 [USurf](https://github.com/ermanergoz/USurf-file-manager) 使用 `#1E168DDA` 底色和 `#168DDA` 阴影，深浅主题共用透明底色。选择、符号链接、加密及应用徽标保留。
- 主文件列表在即将退出时需要两次返回：第一次提示“再按一次返回键退出应用”，2 秒内再按返回退出。目录导航、选择模式、搜索、抽屉和文件选择器先处理各自的返回；导航或切后台后重新确认。
- 删除 Firebase Analytics、Crashlytics、Google Services 配置和初始化，调试/发行构建均无跟踪；关于页提供离线隐私说明，发布 APK 校验拒绝包含这些 SDK 的产物。
- 点击 APK 文件后的操作弹窗按“取消、查看、安装”顺序等宽、等距排列按钮。
- 属性对话框顶部标签等宽平分、居中显示；书签和存储项编辑弹窗的“隐藏/显示或移除、取消、确定”按钮按顺序等宽、等距排列。
- 侧边栏按“存储空间、书签、快捷方式”分组，列表独立滚动；底部固定左“服务器”、右“设置”两个胶囊按钮。“关于”移至设置页最底部。“服务器”沿用原 FTP 服务功能。选中背景和触摸反馈仍使用无圆角、无描边的矩形，并覆盖整行至侧边栏右缘。
- 顶部“文件管理”标题与侧边栏按钮垂直居中对齐，移除文件夹和文件数量副标题；加载和错误状态继续由内容区显示。路径文字起点对齐导航图标左缘，使用现有手机、平板和 RTL 尺寸规则。
- 长按文件进入选择模式，顶部显示“已选择 x 项”和全选、反选按钮；底部等宽排列剪切、复制、删除、压缩、重命名图标及“更多”。原行尾三点菜单中的操作合并到选择菜单，移除行尾三点按钮；单项操作按选择数量和只读状态启用。取消选择后恢复已有待粘贴内容。
- 恢复上游右下角悬浮菜单，并将 FAB 固定为圆形；“新建文件”“新建文件夹”移入 FAB，沿用已有创建对话框。列表保留底部空间，FAB 随底部工具栏上移，兼容手机和平板布局。
- 目录观察触发静默刷新，合并扫描期间的变更，丢弃已取消或旧目录的加载结果；属性更新不再反复触发加载界面和条目淡入淡出。
- 未变化的缩略图和应用徽标保留已有请求及图像，动态图不会因目录刷新重新播放；实际文件内容、权限、链接目标或缩略图设置变更时重新加载，复用视图时拒绝旧请求回调。
- 压缩进度融入 FAB 圆周：写入阶段显示进度，扫描和收尾阶段显示旋转圆环。FAB 中的“压缩任务”可查看各任务的实时详情和取消按钮；任务进行时，选择模式和只读目录内仍保留入口。返回应用或旋转后恢复最新状态，任务结束后移除圆环和任务入口。
- 创建压缩文件界面默认显示包含完整后缀的名称，切换格式时同步更新后缀；格式下拉框和加密选项按格式显示，预览实际输出名称，支持滚动，打开时不自动弹出键盘，支持系统返回键关闭。底部仅保留等宽的“取消、确定”按钮，任务进度与 FAB 同步。压缩完成后以 Toast 提示“压缩文件创建成功”；取消、失败或压缩成功但源文件未全部删除时提示对应结果。任务结束时清理进度和失败输出。
- 压缩/提取改为 7-Zip-JBinding-4Android 16.02-2.03，移除 libarchive 和旧的自研 7z JNI。创建支持 7z、ZIP、TAR、GZIP、BZIP2，移除 tar.gz/tar.bz2 组合创建并保留读取；读取包含上游全部 25 种枚举格式及原生库中的 XZ、PPMd、LZMA86、Hxs，共 29 种，含 RAR/RAR5、NSIS 和磁盘映像。7z 与 ZIP 均使用 AES-256，ZIP 密码受上游限制为最多 99 个可打印 ASCII 字符。小型原生属性适配一次设置压缩级别和加密属性，7z 文件名加密不再通过二次更新。补齐浏览加密归档时的密码提示及重试，分卷私有缓存直接读写。保留任务进度和源删除检查；移除无效的旧归档字符集设置。完整格式、许可限制和验证见 [归档引擎](docs/7z.md)。
- “在终端中打开”保留 Android Terminal Emulator 支持，新增 Termux / MDTerm 的运行命令接口，并正确传递包含空格、引号及 URI 特殊字符的目录。
- 移除旧 `TextEditorActivity`、文本编辑布局及滚动 EditText，文件编辑入口改为 Sora Editor 0.24.6 独立界面。接入离线 TextMate 高亮、关键词和文档标识符补全、括号配对、自动缩进、符号栏、搜索替换、跳行、撤销重做、行列状态及编辑设置，支持 14 种常见语言并自动识别文件名。保留文件提供方读写和编码选择，旋转后保留正文、选择、滚动和撤销历史；保存期间继续输入不会被标为已保存。功能、验证及设备交互验收见 [Sora 编辑器](docs/sora-editor.md)，语法资源和许可来源见 [语言资源](docs/sora-language-assets.md)。

Termux / MDTerm 首次使用需要授予本应用“在 Termux 环境中运行命令”权限，并在终端的 `~/.termux/termux.properties` 中启用 `allow-external-apps=true`。终端本身必须已初始化且有权访问目标目录。应用会提供授权入口或启动失败说明，不会修改终端配置。正常退出或手动关闭终端会话不会提示打开失败。

压缩任务运行时，Android 要求前台服务保留通知，因此界面进度可见期间通知也会存在。Android 13 及以上还需允许本应用发送通知，后台进度才能显示在通知栏。

常规 Android 构建使用仓库原有 Gradle 配置：JDK 17 及以上、SDK 37.2、Build Tools 37.0.0、NDK 30.0.16248370，执行 `./gradlew assembleDebug lintDebug`。

远端 `master` 推送和手动触发的 GitHub Actions 仅构建已签名的 Release APK，执行 Release Lint，并在上传前验证证书指纹与 MDTerm 发布版一致。产物包含 APK、SHA-256 和校验记录；流程及 Secrets 名称见 [Release APK 构建说明](docs/release-apk.md)。

本机 Termux 验证使用 `~/tmp/materialfiles-check/` 中的隔离工具链与 Gradle init script。原有两个 JNI 库由 Termux Clang 21 和 NDK r29 的 Android API 23 sysroot 编译为 ARM64，再交给 Gradle 打包；通用项目仍使用常规的 Gradle 与 NDK 配置。本地产物采用调试签名，不能覆盖不同签名的官方安装版本。

产物为 `app/build/outputs/apk/debug/app-debug.apk`（ARM64，调试签名）。本轮归档修复的 SHA-256、构建和运行验证结果见 [归档引擎](docs/7z.md)，Lint 报告见 `app/build/reports/lint-results-debug.html`；Sora 专项验证方式见 [Sora 编辑器](docs/sora-editor.md)。默认可翻译文本使用简体中文；生成的语言配置仅含 `zh-CN`，并保留依赖提供的简体中文文案。

归档验证直接运行 APK 的实际 Java/Kotlin 代码和 JBinding JNI，并与官方 7-Zip 及固定上游格式样本交叉检查。界面的实际视觉效果、权限弹窗、横竖屏、系统导航栏、并行任务和通知操作仍需设备交互验收；归档测试的覆盖范围及限制见上述文档。

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
