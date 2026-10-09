# tar.gz 的实现与验证

创建压缩文件的 `tar.gz` 选项使用 `Archive.FORMAT_TAR` 和
`Archive.FILTER_GZIP`。文件和目录先由 libarchive 写入 tar，再经 gzip 过滤器输出，
并非仅修改文件扩展名。读取仍走项目现有 libarchive 路径。

依据为项目依赖的 `me.zhanghai.android.libarchive:library:1.1.7`：

- [官方 Archive.java](https://github.com/zhanghai/libarchive-android/blob/v1.1.7/library/src/main/java/me/zhanghai/android/libarchive/Archive.java)
  定义 `FORMAT_TAR = 0x30000`、`FILTER_GZIP = 1`。
- [官方 JNI 实现](https://github.com/zhanghai/libarchive-android/blob/v1.1.7/library/src/main/jni/archive-jni.c)
  将 `writeSetFormat()`、`writeAddFilter()` 分别转发给
  `archive_write_set_format()`、`archive_write_add_filter()`。
- 此版本固定的 libarchive 源码中，
  [格式映射](https://github.com/libarchive/libarchive/blob/7219b0134d771dc4b51bf86b4d01761b87398b1b/libarchive/archive_write_set_format.c)
  将通用 TAR 格式设为 restricted PAX，以兼容普通 tar，并支持需要扩展记录的长路径等信息。
- [gzip 写入实现](https://github.com/libarchive/libarchive/blob/7219b0134d771dc4b51bf86b4d01761b87398b1b/libarchive/archive_write_add_filter_gzip.c)
  负责 gzip 头、deflate 压缩和末尾校验。项目 `WriteArchive.close()` 调用
  `Archive.writeFree()`，让过滤器完成末尾数据写入后再关闭目标通道。

## 可重复验证

在 Android Termux 上运行，Python 仅使用标准库：

```sh
python3 tools/verify_tar_gzip.py --aar /path/to/library-1.1.7.aar
```

脚本在 `~/tmp` 下创建独立临时目录，直接从指定 AAR 提取当前架构的
`libarchive-jni.so`，调用其导出的 C API。它使用与 `WriteArchive` 相同的格式、
过滤器、8 KiB 输出块、UTF-8 路径和输出回调；不替换为系统 libarchive。

生成的压缩包分别通过以下验证：

1. AAR 自带 libarchive 读取并比较内容。
2. Python gzip 校验 gzip CRC32 和解压长度，tarfile 校验 tar 条目及内容。
3. 独立系统 `/system/bin/tar` 实际解压，再逐文件比较字节、检查目录和符号链接。
4. 输出回调每次最多写 137 字节时，重复全部读取和解压验证。
5. 输出回调发生写入失败时，确认错误由 libarchive 返回。

数据覆盖中文名称、名称中的空格、需要 PAX 扩展的长中文路径、空文件、空目录、
二进制数据、纳秒修改时间和相对符号链接。

2026-10-09 在 Android 15 / ARM64 环境验证通过。实际 AAR 的库报告
`libarchive 3.8.8`，系统 tar 为 `Toybox 0.8.11-android`。测试包含 5 个文件、
2 个目录和 1 个符号链接；普通写入及短写均生成 66,574 字节压缩包，均解压一致，
注入的输出失败也正确传播。所测 ARM64 库 SHA-256：

```text
435a83d238e251be55bc50f597dba0ce0f88dd7459c4e87bb992930498931985
```

此验证覆盖项目实际 native 库的格式和过滤器组合；不覆盖 Android 界面操作、
Java/JNI 参数传递、通知或不同文件提供方的行为，这些仍需应用层验证。
