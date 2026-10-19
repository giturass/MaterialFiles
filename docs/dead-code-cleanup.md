# 残留与死代码清理核验

2026-10-19 根据 `~/canliu.md` 逐项复核当前源码、Manifest、XML、动态主题名称和依赖资源后清理。清单仅作为线索，不以“没有本地直接引用”作为唯一删除依据。

已清理：

- 无入口的手动配色主题、颜色选择器及配套布局、图标和文案。
- 无入口的旧 Material Components 主题链及其独占样式；保留 Material 3 仍使用的 CardView、下拉列表、导航形状和 ActionBar overlay。
- 无消费者的视图类型迁移、偏好键及默认值；保留其他历史版本的数据迁移。
- pre-28 PDF 缩略图设置、偏好界面和缓存键参数；支持的 Android 版本继续正常显示 PDF 缩略图。
- 无引用的 UI 类、`ZoomOutPageTransformer`、7 个独立 drawable 和清单中的 24 项文案；保留仍使用的 `DepthPageTransformer`。
- `minSdk 30` 下恒定的 62 处 SDK 比较（清单记为 59 处，实际按改动前后源码重新统计），以及其不可达分支和专用辅助代码。保留文件类型、服务提供方、存储权限及参数是否为空等实际条件。
- 合并 Material 3 在 API 27、29、30 的主题属性，保留 API 33 的设置资源覆盖；将 `sw360dp-v13` 简化为 `sw360dp`。7-Zip 创建按钮的旧 visibility 资源已无消费者，直接删除。
- 不存在的 `app/libs` 对应 `fileTree` 配置。

色板清单不能直接采用。核对实际的 Material 1.14.0、AppCompat 1.8.0 和 appcompat-resources 1.8.0 AAR 后，发现以下 8 个名称同时属于 AppCompat 库资源，因而保留：

- `material_grey_50`、`material_grey_100`、`material_grey_300`、`material_grey_600`、`material_grey_800`、`material_grey_900`
- `material_blue_grey_800`、`material_blue_grey_900`

其中 `material_grey_50`、`material_grey_100`、`material_grey_600`、`material_grey_800`、`material_grey_900` 确认存在 AppCompat 内部 XML 引用；其余 3 项也不按“无本地引用”删除同名覆盖。

加上文件夹图标使用的 `material_blue_500`，254 个色板颜色保留 9 个，删除 245 个。通知和快捷方式仍使用的 `color_primary`、`color_primary_light` 等颜色也继续保留；纯黑主题仍使用 `color_primary_dark_black`，其有效值在全部支持版本上保持透明。

以下内容继续保留：

- `mtrl` popup、AppCompat 同名样式、`m3_*` color state list 等库资源覆盖；仅凭本地引用不足以证明它们无效。
- 两个主题控件中的 Material 2 fallback；本次没有进行控件的明暗主题视觉验收，不把疑似分支列为已确认死代码。
- 两个 reader 验证脚本入口。它们仍可独立执行，属于功能重复的命令入口。
- `libarchive_jbinding.so` 属性桥、7-Zip 源码和测试样例、旧引擎防回归守卫、Apktool 使用的 commons-compress 及第三方库公共 API。
- 历史远程分支。它们不属于本次工作区代码清理。

验证结果：

- 全部资源通过本机 AAPT2 编译，240 个资源 XML 可解析；469 个已删除资源名称没有残留源码/XML引用。
- 对比清理前后 API 30–37 的浅色、深色、纯黑、透明及沉浸主题，共 144 组有效属性比较一致，包括颜色别名解析。
- 52 个改动过的 Kotlin 文件通过 Kotlin 2.3.21 PSI 语法检查。
- 12 个改动过的平台兼容封装与 4 个相关源文件使用本机 Android API 35、Kotlin 2.3.21、AndroidX Core 1.16.0 和 Annotation 1.9.1 编译通过。
- `git diff --check` 通过。

本机缺少项目要求的 SDK 37.2、NDK 30 和 AGP 9.4.1，尚未完成整包 Gradle 构建、Lint 或设备交互验收；以上检查不能替代这些验证。
