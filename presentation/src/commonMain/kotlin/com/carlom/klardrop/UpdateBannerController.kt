package com.carlom.klardrop

import com.carlom.klardrop.common.di.CommonComponent
import com.carlom.klardrop.common.update.InstallProgress
import com.carlom.klardrop.common.update.UpdateAction
import com.carlom.klardrop.common.update.UpdateStatus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Thin UI adapter over [CommonComponent]'s update checker. Exposes the status and
 * self-install progress for the banner and the settings sheet, turns an [UpdateAction]
 * into the concrete side effect (copy a command, open a download URL), and applies a
 * staged update.
 */
class UpdateBannerController(private val commonComponent: CommonComponent) {

  private val checker = commonComponent.updateChecker()

  val status: StateFlow<UpdateStatus> = checker.status

  /** Progress of the in-app self-update; [InstallProgress.Ready] enables Restart. */
  val installProgress: StateFlow<InstallProgress> = checker.install

  /** This build's version, shown in the settings sheet. */
  val currentVersion: String get() = checker.version

  /** The update track this build follows ("stable" / "nightly"). */
  val releaseChannel: String get() = checker.channel

  /**
   * Whether this platform checks for updates at all — false on Android/iOS, where
   * the store owns updates. The settings sheet hides its Updates section when false.
   */
  val updatesSupported: Boolean get() = checker.supported

  private val coroutines = commonComponent.coroutines()
  private val scope = coroutines.newScope(coroutines.mainDispatcher)

  /** Re-run the check (e.g. on a manual "check now"). */
  fun recheck() = checker.checkNow()

  /** Apply the staged update: swap in the new build and relaunch. */
  fun onRestart() = checker.applyUpdate()

  /** Open a URL (release notes, download page) in the system browser. */
  fun openUrl(url: String) {
    scope.launch { commonComponent.openUrl(url) }
  }

  /**
   * Perform [action]. Returns true when it copied a command to the clipboard
   * (so the banner can flip its button to a "Copied!" confirmation); false when
   * it opened a URL instead.
   */
  fun onAction(action: UpdateAction): Boolean = when (action) {
    is UpdateAction.RunCommand -> {
      commonComponent.clipboardManager().write(action.command)
      true
    }

    is UpdateAction.OpenUrl -> {
      openUrl(action.url)
      false
    }
  }
}
