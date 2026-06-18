package com.carlom.klardrop.common.update

/**
 * Returns true when [candidate] is a strictly newer version than [current].
 *
 * Handles `X.Y.Z` and `X.Y.Z-pre` (e.g. the local `0.0.0-dev` fallback). The
 * numeric core is compared field-by-field; when cores are equal a release
 * (no pre-release suffix) outranks a pre-release, and two pre-releases compare
 * lexically. An unparseable [candidate] is never considered newer; an
 * unparseable [current] (e.g. a dev build) means any parseable candidate wins.
 */
internal fun isNewerVersion(candidate: String, current: String): Boolean {
  val cand = SemVer.parse(candidate) ?: return false
  val cur = SemVer.parse(current) ?: return true
  return cand > cur
}

internal data class SemVer(
  val major: Int,
  val minor: Int,
  val patch: Int,
  val preRelease: String?,
) : Comparable<SemVer> {

  override fun compareTo(other: SemVer): Int {
    (major - other.major).let { if (it != 0) return it }
    (minor - other.minor).let { if (it != 0) return it }
    (patch - other.patch).let { if (it != 0) return it }
    // Equal cores: a release (null pre) is newer than any pre-release.
    return when {
      preRelease == null && other.preRelease == null -> 0
      preRelease == null -> 1
      other.preRelease == null -> -1
      else -> comparePreRelease(preRelease, other.preRelease)
    }
  }

  /**
   * Compares two pre-release strings per semver §11: dot-separated identifiers compared
   * left to right; both-numeric identifiers compare numerically (so `nightly.1000` >
   * `nightly.999`), a numeric identifier ranks below an alphanumeric one, and a longer
   * run of identifiers outranks a shorter prefix.
   */
  private fun comparePreRelease(a: String, b: String): Int {
    val ai = a.split('.')
    val bi = b.split('.')
    for (i in 0 until minOf(ai.size, bi.size)) {
      val x = ai[i]
      val y = bi[i]
      val xn = x.toLongOrNull()
      val yn = y.toLongOrNull()
      val cmp = when {
        xn != null && yn != null -> xn.compareTo(yn)
        xn != null -> -1            // numeric identifiers have lower precedence
        yn != null -> 1
        else -> x.compareTo(y)
      }
      if (cmp != 0) return cmp
    }
    return ai.size - bi.size
  }

  companion object {
    fun parse(raw: String): SemVer? {
      val trimmed = raw.trim().removePrefix("v")
      if (trimmed.isEmpty()) return null
      // Split off build metadata (+...) then the pre-release (-...).
      val noBuild = trimmed.substringBefore('+')
      val core = noBuild.substringBefore('-')
      val pre = noBuild.substringAfter('-', missingDelimiterValue = "").ifEmpty { null }
      val parts = core.split('.')
      if (parts.isEmpty() || parts.size > 3) return null
      val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
      val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
      val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
      return SemVer(major, minor, patch, pre)
    }
  }
}
