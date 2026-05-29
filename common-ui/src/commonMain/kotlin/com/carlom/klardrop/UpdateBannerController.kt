package com.carlom.klardrop

import com.carlom.klardrop.common.di.CommonComponent
import com.carlom.klardrop.common.update.InstallProgress
import com.carlom.klardrop.common.update.UpdateAction
import com.carlom.klardrop.common.update.UpdateStatus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Thin UI adapter over [CommonComponent]'s update checker. Exposes the status and
 * self-install progress for the banner, turns an [UpdateAction] into the concrete
 * side effect (copy a command, open a download URL), and applies a staged update.
 */
class UpdateBannerController(private val commonComponent: CommonComponent) {

  val status: StateFlow<UpdateStatus> = commonComponent.updateChecker().status

  /** Progress of the in-app self-update; [InstallProgress.Ready] enables Restart. */
  val installProgress: StateFlow<InstallProgress> = commonComponent.updateChecker().install

  private val coroutines = commonComponent.coroutines()
  private val scope = coroutines.newScope(coroutines.mainDispatcher)

  /** Re-run the check (e.g. on a manual "check now"). */
  fun recheck() = commonComponent.updateChecker().checkNow()

  /** Apply the staged update: swap in the new build and relaunch. */
  fun onRestart() = commonComponent.updateChecker().applyUpdate()

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
      scope.launch { commonComponent.openUrl(action.url) }
      false
    }
  }
}
