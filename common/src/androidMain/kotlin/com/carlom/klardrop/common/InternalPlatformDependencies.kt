package com.carlom.klardrop.common

import android.content.Context
import android.os.Build
import android.os.Environment
import com.carlom.klardrop.common.mdns.ServiceDiscoveryMdns
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.PlatformFileSystem
import okio.Path
import okio.Path.Companion.toOkioPath

actual class InternalPlatformDependencies(private val context: Context) {

  actual fun getRootPath(): String {
    return context.filesDir.absolutePath
  }

  actual fun getDeviceName(): String {
    return Build.MODEL
  }

  actual fun deviceType(): DeviceType {
    return DeviceType.MOBILE
  }

  actual fun getStoragePath(): Path {

    return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toOkioPath()
  }

  actual fun getTempStoragePath(): Path {
    return context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.toOkioPath() ?: context.filesDir.toOkioPath()
  }

  actual fun platformFileSystem(): PlatformFileSystem {
    return PlatformFileSystem(context)
  }

  actual fun serviceDiscoveryMdns(): ServiceDiscoveryMdns {
    return ServiceDiscoveryMdns(context)
  }

}