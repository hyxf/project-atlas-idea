package com.aicode.common.util

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

object ClipboardService {
    @JvmStatic
    fun copyToClipboard(text: String) {
        val selection = StringSelection(text)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
    }
}
