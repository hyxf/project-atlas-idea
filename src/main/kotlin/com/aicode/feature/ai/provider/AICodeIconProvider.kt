package com.aicode.feature.ai.provider

import com.aicode.feature.ai.icons.AICodeIcons
import com.intellij.ide.IconProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import javax.swing.Icon

class AICodeIconProvider : IconProvider() {
    override fun getIcon(element: PsiElement, flags: Int): Icon? {
        val psiFile = element as? PsiFile ?: return null
        val virtualFile = psiFile.virtualFile ?: return null
        return if (virtualFile.name == ".aicode.json") AICodeIcons.LOGO else null
    }
}
