package com.carlom.klardrop

import io.github.vinceglb.filekit.PlatformFile

public sealed interface OnDataToSend {
  public data class Text(public val text: String) : OnDataToSend
  public data class FilesList(public val files: List<PlatformFile>) : OnDataToSend
}
