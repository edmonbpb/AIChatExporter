package com.example.ui.navigation

/** A back press counts as the second one only inside the window and after a real first press. */
fun isExitDoublePress(now: Long, lastPress: Long, windowMs: Long = 2000L): Boolean =
    lastPress > 0L && now >= lastPress && now - lastPress <= windowMs
