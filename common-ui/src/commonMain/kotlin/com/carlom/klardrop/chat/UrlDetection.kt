package com.carlom.klardrop.chat

// Match the entire trimmed text against a small allow-list of openable URL schemes.
// Kept narrow on purpose — accepting arbitrary schemes would let any received text
// trigger an OS handler we didn't intend to expose (file://, ssh://, etc.).
private val OPENABLE_URL_REGEX = Regex(
  pattern = "^(?:https?|ftp|mailto|tel)://?\\S+$|^(?:mailto|tel):\\S+$",
  option = RegexOption.IGNORE_CASE,
)

/**
 * Returns the URL when [text] is, after trimming, a single openable URL — http(s), ftp,
 * mailto, or tel. Returns null otherwise. Used by the chat UI to decide whether a text
 * bubble should render as a tappable link and dispatch to the system handler on click.
 */
fun openableUrlOrNull(text: String): String? {
  val trimmed = text.trim()
  if (trimmed.isEmpty() || trimmed.contains(' ')) return null
  return if (OPENABLE_URL_REGEX.matches(trimmed)) trimmed else null
}
