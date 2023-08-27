package com.carlom.klardrop.common.utils

import kotlin.random.Random

fun Random.nextString(length: Int, includeDigits: Boolean = true, includeUppercase: Boolean = true): String {
  val chars = ('a'..'z').toMutableList()
  if (includeDigits) chars += ('0'..'9')
  if (includeUppercase) chars +=  ('A'..'Z')

  return (1..length)
    .map { chars.random() }
    .joinToString("")
}