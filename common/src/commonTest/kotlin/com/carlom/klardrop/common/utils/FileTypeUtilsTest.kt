package com.carlom.klardrop.common.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class FileTypeUtilsTest {

  @Test
  fun mimeLookupIgnoresExtensionCase() {
    // iPhone photos are named IMG_0001.JPG; an uppercase miss stored them as octet-stream,
    // so receivers didn't treat them as images (no preview).
    assertEquals("image/jpeg", FileTypeUtils.getMimeTypeFromExtension("JPG"))
    assertEquals("image/jpeg", FileTypeUtils.getMimeTypeFromExtension("jpg"))
    assertEquals("application/octet-stream", FileTypeUtils.getMimeTypeFromExtension("nope"))
  }
}
