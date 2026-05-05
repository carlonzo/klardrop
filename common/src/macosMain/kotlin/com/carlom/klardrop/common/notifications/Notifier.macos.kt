package com.carlom.klardrop.common.notifications

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * macOS native target stub. The active desktop path runs on the JVM
 * (`:desktopJvmMain`), so this only matters if `macosArm64()` is ever
 * re-enabled in `common/build.gradle.kts`. Until then, no-op.
 */
actual class Notifier {
  actual fun show(notification: AppNotification) = Unit
  actual fun cancel(id: String) = Unit
  actual val actions: Flow<NotificationAction> = emptyFlow()
}
