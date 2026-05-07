package com.carlom.klardrop.common.notifications

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Desktop JVM no-op. macOS/Linux/Windows native notification surfaces aren't
 * wired up yet — the app keeps using the in-app banner / dialog there.
 */
actual class Notifier {
  actual fun show(notification: AppNotification) = Unit
  actual fun cancel(id: String) = Unit
  actual val actions: Flow<NotificationAction> = emptyFlow()
}
