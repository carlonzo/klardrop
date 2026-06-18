package com.carlom.klardrop.cli

/**
 * macOS: the CLI reuses the desktop JVM clipboard (AWT), which would otherwise
 * register a Dock icon. Mark the process as a UIElement (background agent) and
 * set a friendly process name for Activity Monitor / Cmd-Tab.
 *
 * Must run before any AWT class is loaded.
 */
internal actual fun configureCliPlatformRuntime() {
  if (!System.getProperty("os.name").orEmpty().lowercase().contains("mac")) return

  // Agent app: no Dock tile, but AWT services (clipboard) still work.
  System.setProperty("apple.awt.UIElement", "true")
  // Process / menu name in Activity Monitor and system UI.
  System.setProperty("apple.awt.application.name", "klardrop")
  // Legacy JVM flag; harmless on modern JDKs, helps some launchers.
  System.setProperty("com.apple.mrj.application.apple.menu.about.name", "klardrop")
}