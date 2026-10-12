package com.aicode.feature.git.ui

import com.aicode.feature.git.service.GitTagVersionService.VersionCandidates
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.ButtonGroup
import javax.swing.JComponent

class GitTagVersionDialog(project: Project, private val candidates: VersionCandidates) :
    DialogWrapper(project, true) {
    private val majorButton =
        JBRadioButton(candidates.major.toTag() + "  —  Major (breaking changes)")
    private val minorButton =
        JBRadioButton(candidates.minor.toTag() + "  —  Minor (new functionality)")
    private val patchButton =
        JBRadioButton(candidates.patch.toTag() + "  —  Patch (bug fixes)", true)

    init {
        ButtonGroup().also {
            it.add(majorButton)
            it.add(minorButton)
            it.add(patchButton)
        }
        title = "Create Release Tag"
        setOKButtonText("Continue")
        init()
    }

    fun getSelectedTag(): String =
        when {
            majorButton.isSelected -> candidates.major.toTag()
            minorButton.isSelected -> candidates.minor.toTag()
            else -> candidates.patch.toTag()
        }

    override fun createCenterPanel(): JComponent =
        FormBuilder.createFormBuilder()
            .addComponent(JBLabel("Choose the semantic version increment for the new Git tag."))
            .addVerticalGap(12)
            .addComponent(JBLabel("Current version:  " + candidates.current.toTag()))
            .addVerticalGap(12)
            .addComponent(TitledSeparator("Next version"))
            .addVerticalGap(6)
            .addComponent(majorButton)
            .addVerticalGap(4)
            .addComponent(minorButton)
            .addVerticalGap(4)
            .addComponent(patchButton)
            .panel
            .apply {
                border = JBUI.Borders.empty(8, 12, 4, 12)
                preferredSize = JBUI.size(520, 210)
            }
}
