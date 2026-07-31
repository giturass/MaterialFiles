package me.zhanghai.android.files.provider.remote;

import me.zhanghai.android.files.provider.remote.IRemoteFileSystem;
import me.zhanghai.android.files.provider.remote.IRemoteFileSystemProvider;
import me.zhanghai.android.files.provider.remote.IRemotePosixFileAttributeView;
import me.zhanghai.android.files.provider.remote.IRemotePosixFileStore;
import me.zhanghai.android.files.provider.remote.ParcelableObject;

interface IRemoteFileService {
    void destroy() = 16777114;

    IRemoteFileSystemProvider getRemoteFileSystemProviderInterface(String scheme) = 0;

    IRemoteFileSystem getRemoteFileSystemInterface(in ParcelableObject fileSystem) = 1;

    IRemotePosixFileStore getRemotePosixFileStoreInterface(in ParcelableObject fileStore) = 2;

    IRemotePosixFileAttributeView getRemotePosixFileAttributeViewInterface(
        in ParcelableObject attributeView
    ) = 3;
}
