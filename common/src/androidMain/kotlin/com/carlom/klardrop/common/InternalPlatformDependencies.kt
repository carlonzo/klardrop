package com.carlom.klardrop.common

import android.content.Context
import android.os.Environment
import com.carlom.klardrop.common.features.ClipboardReaderWriter
import com.carlom.klardrop.common.mdns.ServiceDiscoveryMdns
import com.carlom.klardrop.common.utils.PlatformFileSystem
import kotlinx.io.files.Path

actual class InternalPlatformDependencies(private val context: Context) {

  actual fun getDownloadStoragePath(): Path {
    return Path(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath)
  }

  actual fun platformFileSystem(): PlatformFileSystem {
    return PlatformFileSystem(context)
  }

  actual fun serviceDiscoveryMdns(): ServiceDiscoveryMdns {
    return ServiceDiscoveryMdns(context)
  }

  actual fun clipboardReaderWriter(): ClipboardReaderWriter {
    return ClipboardReaderWriter(context)
  }

  actual fun getPrivateAppStoragePath(): Path {
    return Path(context.filesDir.absolutePath)
  }

}