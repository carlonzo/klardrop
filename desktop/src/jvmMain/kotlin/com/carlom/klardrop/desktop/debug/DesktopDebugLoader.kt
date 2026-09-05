package com.carlom.klardrop.desktop.debug

import com.carlom.klardrop.debug.DebugControlService

/**
 * Dynamically loads the DebugControlService implementation at runtime in debug builds.
 *
 * In release builds (processed by ProGuard), no static references to `com.carlom.klardrop.debug.DebugControl`
 * exist in the application graph, allowing ProGuard to completely strip DebugControl and its
 * server infrastructure from the released distribution.
 */
object DesktopDebugLoader {
  val instance: DebugControlService? by lazy {
    try {
      val clazz = Class.forName("com.carlom.klardrop.debug.DebugControl")
      clazz.getField("INSTANCE").get(null) as? DebugControlService
    } catch (_: Throwable) {
      null
    }
  }
}
