# 内置媒体播放器

文件列表点击音频或视频进入内置播放器，按当前列表顺序建立同目录播放列表（最多 200 项）；“打开方式”继续提供外部应用。压缩包中的媒体沿用提取预览流程，播放服务持有预览缓存租约，退出界面后后台播放不会提前删除文件。可用标准 ACTION_VIEW 打开 audio/*、video/* 和常见流媒体类型。

## 视频与后台播放

使用官方 AndroidX Media3 1.11.1 ExoPlayer、PlayerView 和 MediaSessionService，接入 progressive、HLS、DASH 和 RTSP。支持播放、暂停、拖动、前后跳转、同目录切换、单曲/列表循环、0.25–3 倍速、横竖屏旋转、原始/裁剪/拉伸/16:9/4:3 画幅、内置音轨与字幕选择、关闭字幕及 SRT/WebVTT/ASS/SSA/TTML 外挂字幕。

软硬解通过 ExoPlayer 的 `MediaCodecSelector` 选择设备报告的 `softwareOnly` / `hardwareAccelerated` 解码器；自动模式优先硬件，开启标准 `setEnableDecoderFallback(true)` 初始化回退。解码模式改变时重建 ExoPlayer，保留播放列表、进度、速度、循环和轨道选择。强制模式没有可用解码器时报告播放失败。未捆绑 FFmpeg、libVLC 或第三方视频解码器，支持范围以 ExoPlayer 的解封装支持及当前设备的 MediaCodec 为准。

音频默认后台播放；视频通过菜单开启后台播放，默认在离开界面时暂停，选择外部字幕时保持播放。旋转不会重建服务或丢失进度。支持设备可手动进入画中画。MediaSessionService 的媒体前台服务提供通知、锁屏、耳机和系统媒体控件；音频焦点、来电干扰及拔出耳机由 ExoPlayer 处理。

## 音频兜底

始终先使用 ExoPlayer。仅在文件音频发生解码/解封装不支持错误时，重建对应媒体源为 BASS PCM 数据源；权限、网络和文件不存在错误不触发兜底。BASS 核心和 ALAC、APE、WavPack、FLAC、Opus、DSD 插件为官方 ARM64 版本。

`BassDataSource` 从普通文件或 content 文件描述符读取，在 Media3 加载线程用 `BASS_STREAM_DECODE | BASS_SAMPLE_FLOAT` 解码；输出虚拟 IEEE float WAV/RF64，由官方 `WavExtractor` 和 ExoPlayer 音频输出播放。PCM 不落盘，不创建第二个播放器。循环、倍速、拖动、音频焦点和通知共用同一 Media3 播放器。文件管理的远程提供方通过现有可寻址 FileProvider 文件描述符接入；直接 HTTP 不支持格式流需要先保存为文件，再由 BASS 兜底。

BASS 的头部和 PCM 帧读取适配任意字节块以及非帧边界读取，RF64 保留大于 4GB 的解码长度。`BassPcmReaderTest` 验证小块读取、跨帧定位、限长读取、循环、EOF、损坏输入与释放资源。BASS 为专有、非商业免费库，发行条款见 `third_party/bass/README.md` 和应用离线许可页。

## ColorOS 音乐流体云

按 VLC Android 的方式发布标准媒体会话和 MediaStyle 通知：当前曲目、封面、时长、位置、播放速度、播放/暂停与前后切换动作由 Media3 同步到 Android 媒体会话；保留系统前后切换操作槽位。ColorOS 系统媒体控制器获取会话数据，并负责音乐流体云胶囊、展开面板、播放进度和设备选择交互。

参考的 VLC 上游版本为 `2527071008b703d903866372719dd7b6c78b2a66`：

- [PlaybackService.kt](https://github.com/videolan/vlc-android/blob/2527071008b703d903866372719dd7b6c78b2a66/application/vlc-android/src/org/videolan/vlc/PlaybackService.kt) 的 `initMediaSession` 和播放状态更新。
- [NotificationHelper.kt](https://github.com/videolan/vlc-android/blob/2527071008b703d903866372719dd7b6c78b2a66/application/vlc-android/src/org/videolan/vlc/gui/helpers/NotificationHelper.kt) 的 `createPlaybackNotification` / `MediaStyle.setMediaSession`。
- [OPPO 流体云音乐模板](https://open.oppomobile.com/documentation/page/info?id=12658)说明音乐展示区域的数据由系统自动获取。

VLC 上游未接入 Seedling SDK；本项目也采用标准会话路径。系统是否展示音乐流体云由 ColorOS 版本、系统媒体控制器、通知/流体云设置决定，不能由应用强制开启。其他系统有标准媒体通知与锁屏控制。应用内另提供可收起胶囊与展开音频卡片；该界面独立于系统流体云。

若未来改为自行发布 UPK 泛在卡片，则需另行开通 OPPO 白名单、申请与应用签名绑定的授权码、服务 ID 和意图并发布卡片；参见 [快速开始](https://open.oppomobile.com/documentation/page/info?id=12639)和 [SDK 接入指南](https://open.oppomobile.com/documentation/page/info?id=12719)。当前标准音乐会话路径不包含自建 UPK。

## 验证

本机仅执行依赖校验和 XML/差异检查；遵守项目不在 Termux 本机编译的规则。GitHub Actions 执行 Release 构建、单元测试、Lint、签名校验，并检查播放器类和 BASS 原生库打包。软硬解能力、字幕渲染、后台/耳机交互、画中画及 ColorOS 真机流体云展示需要设备实测。

`Media playback validation` 工作流另外在远端编译 Android ART 回归程序，并生成 WAV、ALAC、FLAC 和 WavPack 无损样本。下载与当前提交一致的 `media-art-harness-<commit>` 产物后，可运行 `python3 tools/verify_media_android.py --harness-dir <产物目录>`，在 Android ARM64 设备验证官方 BASS JNI、六个插件加载、文件与 `ParcelFileDescriptor` 两种读取方式、小块 PCM 读取和非帧边界定位。该命令只运行远端编译好的程序，不在本机编译、不安装应用、不输出可听音频；结果保存在产物目录的 `art-media.json` 和 `art-media.log`。它不覆盖安装后的播放器界面、实际音频输出或流体云展示。

接口依据：[Media3 官方源码](https://github.com/androidx/media/tree/1.11.1)、[ExoPlayer 支持格式](https://developer.android.com/media/media3/exoplayer/supported-formats)、[后台播放](https://developer.android.com/media/media3/session/background-playback)、[BASS 下载和许可](https://www.un4seen.com/bass.html)。
