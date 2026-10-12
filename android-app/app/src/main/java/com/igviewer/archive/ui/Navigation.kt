package com.igviewer.archive.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Screen {
    IMPORT, LIST, CHAT, SEARCH, SETTINGS,
}

class NavState {
    var current by mutableStateOf(Screen.IMPORT)
        private set
    private val stack = mutableListOf(Screen.IMPORT)

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
