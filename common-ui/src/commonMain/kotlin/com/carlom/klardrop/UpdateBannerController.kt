package com.carlom.klardrop

import com.carlom.klardrop.common.di.CommonComponent
import com.carlom.klardrop.common.update.UpdateAction
import com.carlom.klardrop.common.update.UpdateStatus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Thin UI adapter over [CommonComponent]'s update checker. Exposes the status
 * flow for the banner and turns an [UpdateAction] into the concrete side effect
 * (copy a command to the clipboard, or open a download URL).
 */
class UpdateBannerController(private val commonComponent: CommonComponent) {

  val status: StateFlow<UpdateStatus> = commonComponent.updateChecker().status

  private val coroutines = commonComponent.coroutines()
  private val scope = coroutines.newScope(coroutines.mainDispatcher)

  /** Re-run the check (e.g. on a manual "check now"). */
  fun recheck() = commonComponent.updateChecker().checkNow()

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
