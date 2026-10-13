package com.igviewer.archive.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Screen {
    IMPORT, LIST, CHAT, SEARCH, SETTINGS,
}

class NavState(start: Screen = Screen.IMPORT) {
    var current by mutableStateOf(start)
        private set
    private val stack = mutableListOf(start)

    /** True when there is a previous screen to return to. */
    val canGoBack: Boolean get() = stack.size > 1

    fun navigate(screen: Screen) {
        if (current != screen) {
            stack.add(screen)
            current = screen
        }
    }

    fun back(): Boolean {
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            current = stack.last()
            return true
        }
        return false
    }

    fun resetTo(screen: Screen) {
        stack.clear()
        stack.add(screen)
        current = screen
    }
}
