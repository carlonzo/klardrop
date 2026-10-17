package com.carlom.klardrop.common.notifications

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Desktop JVM no-op. macOS/Linux/Windows native notification surfaces aren't
 * wired up yet — the app keeps using the in-app banner / dialog there.
 */
public actual class Notifier {
  public actual fun show(notification: AppNotification): Unit = Unit
  public actual fun cancel(id: String): Unit = Unit
  public actual val actions: Flow<NotificationAction> = emptyFlow()
}
