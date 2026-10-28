# 媒体播放器

内置音视频播放器随 `master` 分支维护。

点击音频、视频默认使用内置播放器；“打开方式”保留系统选择器。当前目录中相同类型的文件
按界面排序形成播放列表，最多传入选中项周围的 100 项。压缩包中的媒体沿用提取预览流程。
播放器服务持有缓存租约，退出界面后后台播放和循环时不会提前清理预览文件。

## 播放界面

音乐与视频使用独立的 Material Design 3 布局，切换媒体时自动选择对应界面。
轨道解析后以实际音视频轨道决定布局，避免文件扩展名或 MIME 类型误标时只播放声音。

- 音乐页跟随应用的浅色、深色与系统动态配色。大圆角封面支持媒体内嵌图片，缺少封面时
  显示随主题着色的唱片插画；封面尺寸增大，移除顶部“正在播放”、向下按钮和右上角切换。
  歌名、歌手、专辑、进度和播放状态分层展示。
- 音乐播放区提供大号圆形播放按钮、上一曲、下一曲、随机和循环状态；前后曲按钮直接
  切换播放列表中的曲目，没有相邻曲目时禁用。底部依次提供倍速、播放列表、加载歌词和
  设置，后三项使用无胶囊背景的图标按钮；音视频倍速入口保留原位置和点击功能，使用
  无胶囊背景的文字按钮。宽屏时封面与控制区并排，小屏或
  大字体时内容可滚动。
- 点击音乐封面进入独立全屏歌词页，隐藏系统栏、封面、曲目信息和播放控件；点击歌词
  空白处或按返回键回到播放界面。歌词正文点击、滚动和停止惯性滚动的触摸不会触发返回；
  没有歌词时点击提示区域也可返回，播放界面底部的歌词图标打开文件选择器。
  优先读取手动选择的歌词，其次查找同目录
  同名 `.lrc` / `.LRC`，最后读取 Media3 已解析的内嵌歌词（MP3 ID3 USLT、M4A 歌词、
  FLAC / Ogg 的 LYRICS 标签等）。从文件列表打开时保留原始存储路径，支持通过文件系统
  提供方查找相邻歌词；外部应用只授予单个文件访问权限时，可手动加载 LRC / TXT。
  手动选择会记住曲目与歌词文件的对应关系，不联网搜索歌词。
- LRC 支持多时间戳、毫秒精度、全局 offset 和同一时刻的原文/译文合并；随播放、定位和
  倍速高亮当前行并自动跟随。手动滚动后暂缓跟随 4 秒，TalkBack 触摸探索时不自动滚动。
  无时间戳的歌词按文本显示。支持 UTF-8、带 BOM 的 UTF-16 和 GB18030；文件上限 256 KiB，
  文本上限 65536 字符。换曲和页面停止时取消旧任务、移除刷新回调。
- 在封面或已滚到顶部的播放页面空白处下滑可关闭音乐界面，滑动不足时回弹；
  歌词区域独立滚动，播放按钮、进度条、多指操作和未滚到顶部的页面不会触发关闭。
  返回文件列表后的播放行为遵循后台播放设置。
- 视频页使用独立深色主题、渐变遮罩、中央播放及前后 10 秒控制，底部提供时间轴、
  倍速、旋转、画幅、字幕、列表和全屏，窄屏或大字体下按钮分为两行。旋转按钮直接
  切换横屏/竖屏；画幅按钮依次切换适应屏幕、裁切填满、拉伸填满、16:9、4:3，并提示
  当前模式。点击画面唤出控制，播放中闲置 3.5 秒后隐藏；拖动进度、打开设置或启用
  触摸探索时不自动隐藏。
- 播放列表和设置使用圆角底部面板。视频设置保留音轨、字幕、解码和后台播放，音乐
  设置保留后台播放；设置菜单移除系统媒体控制、重试、停止播放、倍速和循环入口。
- 进度条支持触摸、键盘和无障碍定位；不可定位的媒体禁用拖动。拖动期间换曲时，
  松手不会把上一首的拖动位置应用到新曲目。页面停止时释放控制监听和进度刷新任务。

界面使用现有 Material Components 1.14.0、Media3 1.11.1 和 Coil 2.7.0，未增加播放引擎或
界面依赖。布局/资源静态检查不等同于设备验收；仍需在 APK 上确认横竖屏、动态配色、
封面、TalkBack、全屏与画中画的实际显示和交互。

## 功能与边界

| 功能 | 实现 |
| --- | --- |
| 视频、音频 | AndroidX Media3 1.11.1 ExoPlayer；HLS、DASH 模块一同打包 |
| 视频自动/硬解/软解 | `DefaultRenderersFactory` + `MediaCodecSelector`，按 `hardwareAccelerated` / `softwareOnly` 过滤设备解码器；自动模式优先硬解并允许解码器初始化回退 |
| 循环、随机、前后曲 | Media3 repeat / shuffle / playlist API |
| 倍速 | ExoPlayer `PlaybackParameters`，0.25–3 倍；适用于 BASS 兜底音频 |
| 旋转、画幅 | 初始跟随系统，底部按钮直接切换横屏/竖屏；画幅循环切换适应、裁切、拉伸、16:9、4:3 |
| 全屏 | 使用播放器全屏按钮切换，返回键先退出全屏 |
| 音轨、字幕 | Media3 `Tracks` 和 `TrackSelectionOverride`；自动、关闭或指定轨道 |
| 外挂字幕 | 系统文件选择器加载 SRT、ASS/SSA、WebVTT、TTML，保留播放位置；字幕显示以 Media3 支持范围为准 |
| 字幕大小 | 16–32sp，覆盖内嵌字号，保留界面状态及其他字幕样式 |
| 后台播放 | `MediaSessionService` + `MediaSession`，界面用 `MediaController`；前台服务类型 `mediaPlayback` |
| 系统控制 | MediaStyle 通知、锁屏、耳机/蓝牙媒体按键、音频焦点与拔耳机暂停 |
| 画中画 | 视频手动进入系统 PiP；复用现有播放器页面，系统是否允许由设备设置决定 |
| 页面状态恢复 | 服务列表为空时恢复系统保存的列表、位置、倍速、循环、随机、暂停状态及外挂字幕；仍在运行的服务状态优先 |

播放错误后使用上一曲、下一曲会重新准备所选曲目，并保留暂停或播放意图。
从列表明确选曲时继续播放。音轨或字幕选择面板在换曲、轨道更新时关闭，提交前再次检查
对应曲目与轨道，避免把旧 TrackGroup 应用到新的媒体。

外挂字幕选择结果先保留在页面状态中，控制器连接后再提交；处理期间停止页面时保留请求，
重连后重发。服务检查目标曲目，相同字幕重复提交不会重新定位或加载视频。固定字幕字号
关闭 Media3 的内嵌字号覆盖，字幕颜色等其他样式继续保留。

系统重建页面且服务列表为空时，从界面保存状态恢复最多 100 项；MediaController 不提供
LocalConfiguration，因此通过稳定 mediaId 和来源元数据恢复原始 URI、MIME 及外挂字幕，
BASS 兜底项也恢复原始来源。保存时排除封面和无关元数据。新的打开请求优先于旧状态；
已存活服务的列表和进度优先于界面快照。关闭 PiP 或页面进入停止状态时，禁用后台播放
会暂停；进入 PiP 的 onPause 保持播放。

软解仍依赖设备公开的软件 MediaCodec，不代表任意视频格式均可软解。未加入 FFmpeg/VLC
视频解码扩展。强制解码模式找不到可用解码器时显示播放错误。

## BASS 兜底

每项音频首先由 ExoPlayer 直接播放。只在容器或解码错误时尝试一次 BASS；权限、网络、
DRM、音频输出设备错误不触发兜底。仅接受 `content` / `file` 来源，拒绝有 DRM 配置的内容；
检测到视频轨道时，即使文件被误标为音乐也不进行纯音频兜底。

`BassPcmDataSource` 使用 BASS no-sound device 和
`BASS_STREAM_DECODE | BASS_SAMPLE_FLOAT | BASS_STREAM_PRESCAN`，
通过 Android `AssetFileDescriptor` 读取文件。BASSALAC、BASSFLAC、BASSWV 补充 ALAC、FLAC、
WavPack 解码，其他格式取决于 BASS 核心能力；没有打包的插件格式不承诺支持。

解码结果作为可定位的 IEEE float RF64 音频流交给 Media3 WAV extractor。
不把整首音频解码到内存或临时文件，PCM 缓冲约 64 KiB；RF64 支持超过 4 GiB 的解码结果。
定位映射回 BASS 解码字节位置，保留非整帧字节范围。BASS 不创建独立 AudioTrack，不争用
音频焦点或媒体会话。浮点处理保留 24 位整数音频精度，但不宣称全链路位完美输出。
MP3 等格式预扫描后再生成 RF64 头，避免将估算长度当作精确长度而提前结束；大文件或慢速
存储的首次打开和重新打开可能因此变慢。范围请求遵循 Media3 `DataSource` 约定：固定长度
请求返回请求长度、读取受实际 EOF 限制，越界定位使用 `ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE`。

原生引擎按使用计数管理，同步创建/释放 no-sound device 和插件；不同 Media3 loader
重叠时不会互相释放引擎。提前遇到解码 EOF 作为失败返回，避免把截断文件视为播放完成。
网络存储通过文件管理器的可定位 ContentProvider 访问；BASS 不直接接管 HTTP 直播地址。

版本、来源、SHA-256 和许可见 [BASS](../third_party/bass/README.md)。

## 一加 10 Pro / OxygenOS 15 流体云

已在当前 NE2215、API 35 设备上只读核对：`SystemUI.apk` 中的
`assets/app_systemui_oplus_media_controller_config.xml`（版本 `20250618`）包含系统媒体白名单，
包含 Spotify、VLC 等包名，没有 `me.zhanghai.android.files`。系统也可能通过 RUS 更新名单；
内置名单检查不能代表所有地区、版本或当前云控状态。

媒体状态已通过标准 MediaSession 发布，满足系统读取媒体元数据和控制播放的应用侧接口。
**当前不能宣称“文件管理”已获得原生流体云胶囊展示资格。** OxygenOS 15 没有通用 Android
公开 API 让任意应用自行加入该名单。Android 16 的 promoted ongoing notifications API
也不能用于解决 Android 15 的限制。需要系统侧支持该包名，或用户单独选择系统适配方案。

一加官方 [OxygenOS 15 说明](https://www.oneplus.com/global/oxygenos15)的注释 11 也说明
Fluid Cloud 支持指定服务和应用，功能因国家、地区而异。另可参考
[Asteria 的 Android 15 适配说明](https://github.com/Xposed-Modules-Repo/io.github.lilymilongc.asteria)，
其明确需要在系统媒体控制组件侧追加包名。本次不安装模块或修改手机系统配置。

## 验证

遵守项目规则，不在本机编译。GitHub Actions 使用仓库常规 SDK/NDK 配置执行
`:app:assembleRelease :app:testReleaseUnitTest :app:lintRelease`，并验证签名、无跟踪 SDK、
三个媒体组件、BASS JNI 桥和四个 ARM64 库的 16 KiB 对齐。

单元测试覆盖 RF64 大文件头、无效帧参数、兜底错误边界、视频误分类、来源与 DRM 限制和扩展名识别。
2026-10-28 回归增加错误后前后曲准备与暂停意图、命令可用性、实际轨道决定布局、外挂字幕
重复提交及过期结果、界面快照 Parcel 往返、原始 URI/MIME/字幕恢复、存活服务优先与旧轨道
覆盖清理。Bundle、Uri 和 Parcel 使用仅测试依赖 Robolectric 4.17（API 35），不加入 APK。
这些测试使用记录调用的 Player，不模拟真实解码或系统 Activity / PiP 生命周期。
歌词测试新增多时间戳、offset、前后定位、译文合并、空歌词、编码、ID3 USLT 及异常输入；
本地仅检查资源 XML、引用和差异格式，单元测试由 GitHub Actions 编译运行。歌词滚动与
下滑退出仍需 APK 实机交互验收。
Termux 上通过 `python3 tools/verify_media_android.py <样本...>` 执行仓库实际 ARM64 BASS 库，
不播放声音、不编译。使用 Media3 `1.11.1` 官方测试资源：

| 样本 | 来源相对 `libraries/test_data/src/test/assets/media/` | 输入 SHA-256 | 解码字节数 |
| --- | --- | --- | --- |
| ALAC | `mp4/sample_alac.mp4` | `db8755b5b4d866b9776b69a8db21bdc312656d9478428a0bea5095577c874065` | 180224 |
| FLAC | `flac/bear.flac` | `8929048a502b463041181daaa6c990d695e4a651e99b63d03b24cd16c5155be2` | 1052544 |

两份样本完整解码，并各在 4 个位置重新创建未播放的流后定位，与顺序解码得到的 PCM 逐字节一致。
上游用于解析测试的 `sample_alac_20bit.mp4` 在 BASS 中提前结束，不能作为已通过的播放样本。

2026-10-27 复查增加 MP3 回归：官方 `mp3/bear-vbr-no-seek-table.mp3`
（SHA-256 `a52e1caccd56a6a3b0e583be016f0c411b45e540949920de5c12469f18ef00d9`）
原样解码得到 1074040 字节；将完整 MPEG-1 Layer III 帧重复 24 次得到较长的有效 VBR 流，
旧配置声明 25616248 字节，实际解码 25874296 字节，预扫描后声明长度与实际一致，4 处定位均通过。
回归工具会一直解码到真实 EOF，并在全新的流上检查定位，避免旧工具按声明长度停止或顺序解码
顺带建立定位表而掩盖问题。复现命令（样本放在 `$HOME/tmp/materialfiles-media/`）：

```sh
python3 tools/verify_media_android.py --repeat-mp3 24 "$HOME/tmp/materialfiles-media/bear-vbr-no-seek-table.mp3"
# 以下旧配置对照应报 incorrect declared length：
python3 tools/verify_media_android.py --repeat-mp3 24 --no-prescan "$HOME/tmp/materialfiles-media/bear-vbr-no-seek-table.mp3"
```

官方 `bear-cbr-no-seek-table-trailing-garbage.mp3` 在预扫描后仍不能完整解码到声明长度，
保持失败处理；预扫描不等于允许损坏或无法完整解码的文件通过。

同次复查修复：歌词高亮引用 AppCompat 的 `colorPrimary`；将调用方提供的 MIME 类型传入
MediaItem，使无扩展名的 HLS / DASH 地址选择正确的媒体源；播放错误后从列表选曲先重新
`prepare()`；移除任务时沿用 MediaSessionService 的前台状态判断，禁用后台播放时调用
`pauseAllPlayersAndStopSelf()`；按曲目保留 BASS 切换前的内嵌歌词，移出列表后清理。

尚需安装 APK 后交互验收：横竖屏、PiP、外挂字幕、不同存储提供方、熄屏后台播放、
耳机/蓝牙控制、音频焦点，以及该 ROM 上的流体云展示。上述原生测试不等于这些交互已通过。

## 实现依据

- [Media3 支持格式](https://developer.android.com/media/media3/exoplayer/supported-formats)
- [MediaSessionService 后台播放](https://developer.android.com/media/media3/session/background-playback)
- [Media3 轨道选择](https://developer.android.com/media/media3/exoplayer/track-selection)
- [Android PiP：singleTask 与 onStop 播放控制](https://developer.android.com/develop/ui/views/picture-in-picture)
- [Media3 1.11.1 MediaItem：控制器省略 LocalConfiguration 与完整 Bundle 保存](https://github.com/androidx/media/blob/1.11.1/libraries/common/src/main/java/androidx/media3/common/MediaItem.java)
- [Media3 1.11.1 TrackSelectionOverride：相等的 TrackGroup 才能应用选择](https://github.com/androidx/media/blob/1.11.1/libraries/common/src/main/java/androidx/media3/common/TrackSelectionOverride.java)
- [Media3 1.11.1 SubtitleView：内嵌字号覆盖](https://github.com/androidx/media/blob/1.11.1/libraries/ui/src/main/java/androidx/media3/ui/SubtitleView.java)
- [Media3 播放控制行为](https://github.com/androidx/media/blob/1.11.1/libraries/common/src/main/java/androidx/media3/common/util/Util.java)
- [Material Slider 属性](https://github.com/material-components/material-components-android/blob/1.14.0/lib/java/com/google/android/material/slider/res/values/attrs.xml)
- [Media3 1.11.1 WAV/RF64 读取实现](https://github.com/androidx/media/blob/1.11.1/libraries/extractor/src/main/java/androidx/media3/extractor/wav/WavHeaderReader.java)
- [BASS StreamCreateFile](https://www.un4seen.com/doc/bass/BASS_StreamCreateFile.html)
- [BASS ChannelGetData](https://www.un4seen.com/doc/bass/BASS_ChannelGetData.html)
- [BASS ChannelGetLength：估算长度与预扫描](https://www.un4seen.com/doc/bass/BASS_ChannelGetLength.html)
- [BASS ChannelSetPosition：精确定位](https://www.un4seen.com/doc/bass/BASS_ChannelSetPosition.html)
- [Media3 1.11.1 DataSource：范围请求约定](https://github.com/androidx/media/blob/1.11.1/libraries/datasource/src/main/java/androidx/media3/datasource/DataSource.java)
- [Media3 1.11.1 DefaultMediaSourceFactory：URI 与 MIME 选择](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/DefaultMediaSourceFactory.java)
- [Media3 1.11.1 MediaSessionService：移除任务与停止前台服务](https://github.com/androidx/media/blob/1.11.1/libraries/session/src/main/java/androidx/media3/session/MediaSessionService.java)
