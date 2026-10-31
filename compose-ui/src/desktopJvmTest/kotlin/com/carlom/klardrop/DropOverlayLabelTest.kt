package com.carlom.klardrop

import kotlin.test.Test
import kotlin.test.assertEquals

class DropOverlayLabelTest {
  @Test
  fun labels() {
    assertEquals("Send file a.pdf to Pixel", dropOverlayLabel(DropHover(listOf("a.pdf")), "Pixel"))
    assertEquals("Send 2 files to Pixel", dropOverlayLabel(DropHover(listOf("a", "b")), "Pixel"))
    assertEquals("Send text to Pixel", dropOverlayLabel(DropHover(isText = true), "Pixel"))
    assertEquals("Send file to Pixel", dropOverlayLabel(DropHover(), "Pixel"))
  }
}
