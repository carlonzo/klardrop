package com.carlom.klardrop.common.notifications

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * macOS native target stub. The active desktop path runs on the JVM
 * (`:desktopJvmMain`), so this only matters if `macosArm64()` is ever
 * re-enabled in `common/build.gradle.kts`. Until then, no-op.
 */
public actual class Notifier {
  public actual fun show(notification: AppNotification): Unit = Unit
  public actual fun cancel(id: String): Unit = Unit
  public actual val actions: Flow<NotificationAction> = emptyFlow()
}
