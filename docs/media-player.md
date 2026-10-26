# 媒体播放器

本次从当前 `master` 独立实现，开发分支为 `codex/media3-bass`。

点击音频、视频默认使用内置播放器；“打开方式”保留系统选择器。当前目录中相同类型的文件
按界面排序形成播放列表，最多传入选中项周围的 100 项。压缩包中的媒体沿用提取预览流程。
播放器服务持有缓存租约，退出界面后后台播放和循环时不会提前清理预览文件。

## 播放界面

音乐与视频使用独立的 Material Design 3 布局，切换媒体时自动选择对应界面。

- 音乐页跟随应用的浅色、深色与系统动态配色。大圆角封面支持媒体内嵌图片，缺少封面时
  显示随主题着色的唱片插画；歌名、歌手、专辑、进度和播放状态分层展示。
- 音乐播放区提供大号播放按钮、前后曲、随机和循环状态；倍速与播放列表位于下方。
  宽屏时封面与控制区并排，小屏或大字体时内容可滚动。
- 视频页使用独立深色主题、渐变遮罩、中央播放及前后 10 秒控制，底部提供时间轴、
  倍速、字幕、列表和全屏。点击画面唤出控制，播放中闲置 3.5 秒后隐藏；拖动进度、
  打开设置或启用触摸探索时不自动隐藏。
- 播放列表和设置使用圆角底部面板。视频面板包含旋转、画幅、音轨、字幕及解码选项；
  音乐面板集中显示音频播放选项。两者均保留后台播放、系统控制、重试和停止入口。
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
| 旋转、画幅 | 跟随系统、横屏、竖屏；适应、裁切、拉伸、16:9、4:3 |
| 全屏 | 使用播放器全屏按钮切换，返回键先退出全屏 |
| 音轨、字幕 | Media3 `Tracks` 和 `TrackSelectionOverride`；自动、关闭或指定轨道 |
| 外挂字幕 | 系统文件选择器加载 SRT、ASS/SSA、WebVTT、TTML，保留播放位置；字幕显示以 Media3 支持范围为准 |
| 字幕大小 | 16–32sp，保留界面状态 |
| 后台播放 | `MediaSessionService` + `MediaSession`，界面用 `MediaController`；前台服务类型 `mediaPlayback` |
| 系统控制 | MediaStyle 通知、锁屏、耳机/蓝牙媒体按键、音频焦点与拔耳机暂停 |
| 画中画 | 视频手动进入系统 PiP；系统是否允许由设备设置决定 |
| 停止 | 菜单停止播放，清空列表并终止服务 |

软解仍依赖设备公开的软件 MediaCodec，不代表任意视频格式均可软解。未加入 FFmpeg/VLC
视频解码扩展。强制解码模式找不到可用解码器时显示播放错误。

## BASS 兜底

每项音频首先由 ExoPlayer 直接播放。只在容器或解码错误时尝试一次 BASS；权限、网络、
DRM、音频输出设备错误不触发兜底。识别为视频的内容不会因为音频轨道失败而丢弃视频。

`BassPcmDataSource` 使用 BASS no-sound device 和 `BASS_STREAM_DECODE | BASS_SAMPLE_FLOAT`，
通过 Android `AssetFileDescriptor` 读取文件。BASSALAC、BASSFLAC、BASSWV 补充 ALAC、FLAC、
WavPack 解码，其他格式取决于 BASS 核心能力；没有打包的插件格式不承诺支持。

解码结果作为可定位的 IEEE float RF64 音频流交给 Media3 WAV extractor。
不把整首音频解码到内存或临时文件，PCM 缓冲约 64 KiB；RF64 支持超过 4 GiB 的解码结果。
定位映射回 BASS 解码字节位置，保留非整帧字节范围。BASS 不创建独立 AudioTrack，不争用
音频焦点或媒体会话。浮点处理保留 24 位整数音频精度，但不宣称全链路位完美输出。

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
播放器提供系统媒体控制说明和通知设置入口。

一加官方 [OxygenOS 15 说明](https://www.oneplus.com/global/oxygenos15)的注释 11 也说明
Fluid Cloud 支持指定服务和应用，功能因国家、地区而异。另可参考
[Asteria 的 Android 15 适配说明](https://github.com/Xposed-Modules-Repo/io.github.lilymilongc.asteria)，
其明确需要在系统媒体控制组件侧追加包名。本次不安装模块或修改手机系统配置。

## 验证

遵守项目规则，不在本机编译。GitHub Actions 使用仓库常规 SDK/NDK 配置执行
`:app:assembleRelease :app:testReleaseUnitTest :app:lintRelease`，并验证签名、无跟踪 SDK、
三个媒体组件、BASS JNI 桥和四个 ARM64 库的 16 KiB 对齐。

单元测试覆盖 RF64 大文件头、无效帧参数、兜底错误边界和扩展名识别。
Termux 上通过 `python3 tools/verify_media_android.py <样本...>` 执行仓库实际 ARM64 BASS 库，
不播放声音、不编译。使用 Media3 `1.11.1` 官方测试资源：

| 样本 | 来源相对 `libraries/test_data/src/test/assets/media/` | 输入 SHA-256 | 解码字节数 |
| --- | --- | --- | --- |
| ALAC | `mp4/sample_alac.mp4` | `db8755b5b4d866b9776b69a8db21bdc312656d9478428a0bea5095577c874065` | 180224 |
| FLAC | `flac/bear.flac` | `8929048a502b463041181daaa6c990d695e4a651e99b63d03b24cd16c5155be2` | 1052544 |

两份样本完整解码，并各在 4 个位置重新定位，与顺序解码得到的 PCM 逐字节一致。
上游用于解析测试的 `sample_alac_20bit.mp4` 在 BASS 中提前结束，不能作为已通过的播放样本。

尚需安装 APK 后交互验收：横竖屏、PiP、外挂字幕、不同存储提供方、熄屏后台播放、
耳机/蓝牙控制、音频焦点，以及该 ROM 上的流体云展示。上述原生测试不等于这些交互已通过。

## 实现依据

- [Media3 支持格式](https://developer.android.com/media/media3/exoplayer/supported-formats)
- [MediaSessionService 后台播放](https://developer.android.com/media/media3/session/background-playback)
- [Media3 轨道选择](https://developer.android.com/media/media3/exoplayer/track-selection)
- [Media3 播放控制行为](https://github.com/androidx/media/blob/1.11.1/libraries/common/src/main/java/androidx/media3/common/util/Util.java)
- [Material Slider 属性](https://github.com/material-components/material-components-android/blob/1.14.0/lib/java/com/google/android/material/slider/res/values/attrs.xml)
- [Media3 1.11.1 WAV/RF64 读取实现](https://github.com/androidx/media/blob/1.11.1/libraries/extractor/src/main/java/androidx/media3/extractor/wav/WavHeaderReader.java)
- [BASS StreamCreateFile](https://www.un4seen.com/doc/bass/BASS_StreamCreateFile.html)
- [BASS ChannelGetData](https://www.un4seen.com/doc/bass/BASS_ChannelGetData.html)
