package com.carlom.klardrop.common.notifications

import kotlinx.coroutines.flow.StateFlow

/**
 * Per-platform "is the app currently visible to the user?" signal.
 *
 * Used to gate system notifications: the in-app banner / dialog handles
 * foreground prompts, and the [Notifier] only fires while [isForeground] is
 * false. Each platform plugs in its own concept of foreground:
 *  - Android: [androidx.lifecycle.ProcessLifecycleOwner] STARTED state.
 *  - iOS: [platform.UIKit.UIApplication.applicationState] == active.
 *  - Desktop JVM: any window has focus (or always-true if we don't care).
 */
expect class ForegroundState {
  val isForeground: StateFlow<Boolean>
}
