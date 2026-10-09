# Sora 编辑器

文件编辑入口使用 `SoraEditorActivity` / `SoraEditorFragment`，旧的
`TextEditorActivity`、`TextEditorFragment`、`TextEditorViewModel`、编辑布局、
菜单和 `ScrollingChildEditText` 已删除。依赖固定为 Maven Central 的
`io.github.rosemoe:editor-bom:0.24.6`、`editor` 与 `language-textmate`，采用
Java 17 和项目已有的 NIO core-library desugaring。

界面由 Sora 的 `CodeEditor`、`SymbolInputView`、选择操作窗口和自动补全窗口
组成，并按照官方示例接入搜索、替换和状态显示。正文自行处理滚动、行号、
选区、输入法和缩放，外层不再使用 EditText 或嵌套文本滚动容器。

## 功能

- Java、Kotlin、JavaScript/JSX、TypeScript/TSX、JSON/JSONC、XML、HTML、CSS、
  Markdown、Python、Shell、YAML、C、C++ 离线高亮；按文件名自动识别，菜单可
  手动选择语言或纯文本。HTML 内的 JS/CSS、Markdown 中已支持语言的代码块
  使用对应的嵌入语法。
- Sora 的关键词与文档标识符补全、括号配对与高亮、自动缩进、代码块引导线、
  作用域固定显示、选择复制剪切粘贴及底部符号输入栏。
- 撤销、重做、全选、增加或减少缩进、复制当前行或选区。复制空行可用，复制
  CRLF/CR 文件中的行时保持相应换行符。
- 查找、上一个/下一个匹配、替换当前/全部、匹配计数、区分大小写、全字和
  正则表达式模式。正则替换保留全文上下文，支持分组引用和前后向断言。
- 跳转行号、行列/选区/语言/编码/换行符状态、只读模式；字体大小、双指缩放、
  自动换行、行号、空白字符、缩进宽度、自动补全与作用域显示设置。
- 显示与输入偏好持久保存；编辑器、符号栏、补全和选择浮窗遵循应用的 Material 3、
  动态颜色、明暗和 A 屏黑主题。

快捷键由 Sora 处理常规选择、复制粘贴、撤销、重做和缩进。宿主额外提供
`Ctrl+S` 保存、`Ctrl+F` 查找、`Ctrl+H` 替换、`Ctrl+G` 跳行，搜索框获得焦点时
这四个快捷键也可用。

TextMate 提供语法和编辑辅助。补全来自关键词及当前文档，没有接入语言服务器；
因此菜单不展示没有相应实现的编译诊断、语义重构或自动格式化操作。各语言的
资源范围、固定源版本和完整许可见 [语言资源说明](sora-language-assets.md)。

## 文件和生命周期

读写继续通过文件管理器的 `java8.nio.file.Path` 和 `FileJobService`，覆盖现有
本地、文档、Root 和远程文件提供方。保留编码选择、重新加载及舍弃未保存修改
的确认行为；沿用文件读取的 1 MiB 上限。保存和重新解码时保留原有换行符，
新输入的换行采用文档第一行的换行符。

`SoraEditorDocument` 在 ViewModel 内持有 Sora `Content`；旋转或切换主题时
复用正文、光标、选区及 UndoManager，待异步换行布局完成后恢复滚动。正文和
撤销记录不放入 Activity 的 Bundle。这里的恢复覆盖配置变化，不是进程死亡
后的未保存文档持久化。

保存使用点击时的文本与编码快照。文件写入完成仅更新已保存基线，保留此后
输入的内容和未保存状态。执行保存期间不能重新加载或更改编码。查找替换在
后台计算全文快照，写回前检查文档身份、版本及编辑权限，避免覆盖后续编辑；
替换可通过 Sora 的撤销记录恢复。

视图销毁时取消搜索替换和语言加载，释放 `CodeEditor` 及未安装语言的分析器、
格式化器和主题监听器，关闭临时对话框。语法和主题的缓存仅持有应用资源，
多个编辑窗口使用各自的语言实例与配色。

## 验证

常规构建使用仓库工具链：

```sh
./gradlew :app:assembleDebug :app:lintDebug
```

本机 Termux 采用现有隔离工具链：

```sh
sh ~/tmp/materialfiles-check/run-gradle.sh --console=plain :app:assembleDebug :app:lintDebug
```

[`tools/verify_sora_editor.py`](../tools/verify_sora_editor.py) 将实际应用编译类、
官方 Sora/TextMate AAR 和测试代码转成 DEX，用 Android 的 `app_process`
执行。脚本拒绝过期的应用编译类，不以假的 Content 或平台实现替代运行结果。
使用与 Gradle 相同的 JDK 和 Termux 动态库环境后执行：

```sh
python3 -B tools/verify_sora_editor.py \
  --gradle-home ~/tmp/materialfiles-check/gradle \
  --java-home "$JAVA_HOME" \
  --android-jar ~/tmp/materialfiles-check/sdk/platforms/android-37.2/android.jar
```

测试覆盖 Unicode 和 CRLF/CR/LF 往返、光标和撤销历史、异步保存基线、重新加载、
编码切换、替换边界，以及实际 TextMate 引擎对所有语法、明暗主题、HTML/TSX/
Markdown 嵌入语法与多行状态的处理。日志位于 `~/tmp/materialfiles-sora-check/`。

2026-10-09 在 Android API 35 上通过文档 110 项、语言 1,827 项、替换 87 项，
共 2,024 项运行时检查。替换用例包括相邻分组引用、前后向断言、零宽匹配、
取消、只读和过期快照保护，以及一次撤销恢复全部替换。三组通过日志另存于
`app/build/reports/sora-*-runtime.log`；最终构建、Lint、APK 签名、旧编辑器移除
及打包资源检查记录在 `app/build/reports/sora-editor-validation.json`。

设备交互验收仍需检查：

1. 从本地、文档和远程目录打开文件，编辑、保存、重开并核对内容。
2. 中文输入法组合输入、选区拖动、补全选择、符号栏和硬件快捷键。
3. 带选区和未保存正文时旋转、切换主题；确认撤销历史、光标和滚动保留。
4. 搜索替换时横屏并弹出键盘，确认面板可滚动、编辑区域及底部不被导航栏遮挡。
5. 快速切换语言、多窗口明暗主题，以及只读状态下所有编辑入口的行为。

## 实现依据

- [Sora 0.24.6 官方示例](https://github.com/Rosemoe/sora-editor/tree/0.24.6/app/src/main)
- [CodeEditor API 和生命周期](https://github.com/Rosemoe/sora-editor/blob/0.24.6/editor/src/main/java/io/github/rosemoe/sora/widget/CodeEditor.java)
- [EditorSearcher 匹配语义](https://github.com/Rosemoe/sora-editor/blob/0.24.6/editor/src/main/java/io/github/rosemoe/sora/widget/EditorSearcher.java)
- [TextMate 模块](https://github.com/Rosemoe/sora-editor/tree/0.24.6/language-textmate)
- [SymbolInputView](https://github.com/Rosemoe/sora-editor/blob/0.24.6/editor/src/main/java/io/github/rosemoe/sora/widget/SymbolInputView.java)

Sora、TM4E、Joni、JCodings、SnakeYAML Engine 和 Gson 已登记在应用许可证页。
TM4E 使用的 EPL 2.0 全文随应用打包；语法与主题资源保留各自的许可和源代码链接。
