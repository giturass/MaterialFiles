# TAR.GZ / TAR.BZ2

创建菜单和 writer 已移除 TAR.GZ、TAR.BZ2 组合格式。
创建支持 7z、ZIP、TAR，以及仅压缩单个常规文件的 GZIP、BZIP2、XZ。

已有 TAR.GZ、TAR.BZ2 仍由
[7-Zip-JBinding-4Android](https://github.com/omicronapps/7-Zip-JBinding-4Android)
识别外层压缩流并打开内层 TAR；TAR.XZ、TAR.LZMA、TAR.Z 也可以浏览和提取。

实现、完整格式表、限制与可重复验证见 [归档引擎说明](7z.md)。
