package com.aicode.feature.github.ui

import com.aicode.feature.github.model.GitHubConfiguration
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Dimension
import java.net.URI
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.UIManager
import javax.swing.border.TitledBorder

data class GitHubSettingsDraft(
    val token: String,
    val user: String,
    val proxyEnabled: Boolean,
    val httpProxy: String,
    val socketProxy: String,
)

class GitHubSettingsDialog(project: Project, private val current: GitHubConfiguration) : DialogWrapper(project) {
    private val token = JBPasswordField().apply { columns = 42; text = current.token }
    private val user = JBTextField(current.user, 42)
    private val showToken = JBCheckBox("Show token")
    private val useProxy = JBCheckBox("Use proxy for GitHub API refresh and clone", current.proxyEnabled)
    private val httpProxy = JBTextField(current.httpProxy, 42)
    private val socksProxy = JBTextField(current.socketProxy, 42)
    private val defaultEchoChar = UIManager.getLookAndFeelDefaults().get("PasswordField.echoChar") as? Char ?: '\u2022'

    init {
        title = "GitHub Settings"
        setOKButtonText("Save")
        setResizable(true)
        init()
        updateProxyFields()
    }

    fun settings(): GitHubSettingsDraft = GitHubSettingsDraft(
        String(token.password), user.text.trim(), useProxy.isSelected, httpProxy.text.trim(), socksProxy.text.trim(),
    )

    override fun createCenterPanel(): JComponent {
        showToken.addActionListener { token.echoChar = if (showToken.isSelected) '\u0000' else defaultEchoChar }
        useProxy.addActionListener { updateProxyFields() }

        val credentials = verticalForm().apply {
            add(field("Personal access token", JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                add(token, BorderLayout.CENTER)
                add(showToken, BorderLayout.EAST)
            }))
            add(field("GitHub username", user))
            add(helper("The token must be allowed to list the repositories you want to see."))
        }

        val proxyFields = verticalForm().apply {
            add(useProxy)
            add(javax.swing.Box.createVerticalStrut(JBUI.scale(8)))
            add(field("HTTP / HTTPS proxy", httpProxy))
            add(helper("Default: http://127.0.0.1:1087"))
            add(javax.swing.Box.createVerticalStrut(JBUI.scale(6)))
            add(field("SOCKS proxy", socksProxy))
            add(helper("Default: socks5://127.0.0.1:1086"))
        }

        return verticalForm().apply {
            preferredSize = Dimension(JBUI.scale(600), JBUI.scale(440))
            minimumSize = Dimension(JBUI.scale(500), JBUI.scale(400))
            add(section("GitHub account", credentials))
            add(javax.swing.Box.createVerticalStrut(JBUI.scale(12)))
            add(section("Network", proxyFields))
        }
    }

    override fun doValidate(): ValidationInfo? {
        val draft = settings()
        if (draft.token.isBlank()) return ValidationInfo("Enter a GitHub personal access token.", token)
        if (draft.user.isBlank()) return ValidationInfo("Enter the GitHub username for this token.", user)
        if (draft.proxyEnabled && draft.httpProxy.isBlank() && draft.socketProxy.isBlank()) {
            return ValidationInfo("Enter an HTTP or SOCKS proxy URL, or turn off proxy use.", useProxy)
        }
        validateProxy(draft.httpProxy, setOf("http", "https"))?.let {
            return ValidationInfo("HTTP proxy must be a valid HTTP or HTTPS URL.", httpProxy)
        }
        validateProxy(draft.socketProxy, setOf("socks", "socks4", "socks4a", "socks5", "socks5h"))?.let {
            return ValidationInfo("SOCKS proxy must use a supported SOCKS URL.", socksProxy)
        }
        return null
    }

    override fun getPreferredFocusedComponent(): JComponent = user

    private fun updateProxyFields() {
        val enabled = useProxy.isSelected
        httpProxy.isEnabled = enabled
        socksProxy.isEnabled = enabled
    }

    private fun section(title: String, content: JComponent): JComponent = JPanel(BorderLayout()).apply {
        border = TitledBorder(title)
        add(content.apply { border = JBUI.Borders.empty(8, 10, 10, 10) }, BorderLayout.CENTER)
    }

    private fun verticalForm() = JPanel().apply {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private fun field(label: String, component: JComponent) = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        add(javax.swing.JLabel(label), BorderLayout.NORTH)
        add(component, BorderLayout.CENTER)
    }

    private fun helper(text: String) = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        add(javax.swing.JLabel(text).apply { foreground = JBColor.GRAY })
    }

    private fun validateProxy(value: String, schemes: Set<String>): Boolean? {
        if (value.isBlank()) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return (uri.scheme?.lowercase() in schemes && !uri.host.isNullOrBlank()).not()
    }
}
