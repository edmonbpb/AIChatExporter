package com.example.domain.bidi

import com.example.data.model.TextDirection

enum class AlignSide { LEFT, RIGHT }

/**
 * The one side decision every writer asks, so a document aligns a paragraph the way the screen
 * already aligns the same text.
 *
 * MIXED is measured again per line on purpose: the stored direction was computed over the whole
 * message, while every writer emits one paragraph per source line, so a line has to be judged on
 * the words it carries itself.
 */
fun paragraphSide(direction: TextDirection, text: String): AlignSide = when (direction) {
    TextDirection.RTL -> AlignSide.RIGHT
    TextDirection.LTR -> AlignSide.LEFT
    TextDirection.MIXED ->
        if (BidiEngine.detectDirection(text) == TextDirection.LTR) AlignSide.LEFT else AlignSide.RIGHT
}