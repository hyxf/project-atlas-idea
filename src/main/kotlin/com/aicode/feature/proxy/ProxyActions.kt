package com.aicode.feature.proxy

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.util.ui.JBUI
import com.intellij.util.net.HttpConfigurable
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URLConnection
import java.net.Socket
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JList
import javax.swing.ListSelectionModel

private const val NOTIFICATION_GROUP = "AICode.Proxy"

class ProxyModeAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) { e.presentation.text = "Proxy: ${ProxyPlatform.currentLabel()}" }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val entries = ProxyConfigStore().read()
                ApplicationManager.getApplication().invokeLater {
                    ProxySelectionPopup(project, entries).show(e.dataContext)
                }
            } catch (ex: Exception) { ApplicationManager.getApplication().invokeLater { notify(project, ex.message ?: "Could not load proxy configuration.", NotificationType.ERROR) } }
        }
    }
}

class EditProxyConfigAction : AnAction("Open Proxy Configuration") {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val path = ProxyConfigStore.defaultConfigPath()
                ProxyConfigStore(path).read()
                ApplicationManager.getApplication().invokeLater {
                    val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                    if (vf != null && e.project != null) FileEditorManager.getInstance(e.project!!).openFile(vf, true)
                    else notify(e.project, "Proxy configuration created at $path", NotificationType.INFORMATION)
                }
            } catch (ex: Exception) { ApplicationManager.getApplication().invokeLater { notify(e.project, ex.message ?: "Could not open proxy configuration.", NotificationType.ERROR) } }
        }
    }
}

class CheckProxyAction : AnAction("Check Current Proxy") {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val proxy = ProxyPlatform.currentProxyAddress()
        if (proxy == null) {
            val message = if (ProxyPlatform.isProxyEnabled()) {
                "IDEA manages this proxy with PAC or SOCKS, so it cannot be checked here." to NotificationType.WARNING
            } else "Direct mode is active." to NotificationType.INFORMATION
            ApplicationManager.getApplication().invokeLater { notify(project, message.first, message.second) }
            return
        }
        val endpointKey = "${proxy.first}:${proxy.second}"
        if (ProxyHealthState.isChecking(endpointKey)) return
        ProxyHealthState.begin(endpointKey)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { ProxyChecker().check(proxy.first, proxy.second) }
            ApplicationManager.getApplication().invokeLater {
                val connected = result.isSuccess
                ProxyHealthState.complete(endpointKey, connected)
                notify(
                    project,
                    if (connected) "Proxy connected" else "Proxy not connected",
                    if (connected) NotificationType.INFORMATION else NotificationType.ERROR,
                )
            }
        }
    }
}

data class ProxyChoice(val label: String, val url: String?, val preserveCurrent: Boolean = false)

class ProxySelectionPopup(private val project: Project?, private val configured: List<NamedProxy>) {
    private val model = DefaultListModel<ProxyChoice>()
    private val list = JBList(model)
    private val content = JPanel(BorderLayout())
    private lateinit var popup: JBPopup
    private val choices = buildList {
        add(ProxyChoice("Direct", null))
        configured.forEach { add(ProxyChoice("${it.name} — ${it.url}", it.url)) }
        val current = ProxyPlatform.currentUrl()
        if (ProxyPlatform.isProxyEnabled() && (current == null || configured.none { it.url == current })) {
            add(ProxyChoice("system${current?.let { " — $it" } ?: " (IDE managed)"}", current ?: "", preserveCurrent = current == null))
        }
    }

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.fixedCellHeight = JBUI.scale(38)
        list.border = JBUI.Borders.empty(4, 0)
        list.cellRenderer = object : ColoredListCellRenderer<ProxyChoice>() {
            override fun customizeCellRenderer(
                list: JList<out ProxyChoice>, value: ProxyChoice?, index: Int,
                selected: Boolean, hasFocus: Boolean,
            ) {
                value ?: return
                if (isSelected(value)) append("✓  ", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                else append("   ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append(value.label, if (isSelected(value)) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }
        list.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2 && list.locationToIndex(e.point) >= 0) selectCurrent()
            }
        })
        list.getInputMap(JComponent.WHEN_FOCUSED).put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0), "selectCurrent")
        list.actionMap.put("selectCurrent", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = selectCurrent()
        })
        content.preferredSize = Dimension(560, (choices.size.coerceAtMost(7) * JBUI.scale(38)) + JBUI.scale(8))
        content.add(JBScrollPane(list), BorderLayout.CENTER)
        reload()
    }

    fun show(context: com.intellij.openapi.actionSystem.DataContext) {
        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(content, list)
            .setTitle("Select HTTP Proxy")
            .setFocusable(true)
            .setRequestFocus(true)
            .setResizable(true)
            .setMovable(true)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .setDimensionServiceKey(project, "ProjectAtlas.ProxySelectionPopup.NoSearch", false)
            .createPopup()
        if (project != null) popup.showCenteredInCurrentWindow(project)
        else popup.showInBestPositionFor(context)
    }

    private fun selectCurrent() {
        val choice = list.selectedValue ?: return
        if (choice.preserveCurrent) return
        popup.cancel()
        try {
            ProxyPlatform.apply(choice.url)
            notify(project, "IDE proxy is now ${if (choice.url == null) "Direct" else choice.url}.", NotificationType.INFORMATION)
        } catch (ex: Exception) {
            notify(project, "Could not change IDE proxy: ${ex.message}", NotificationType.ERROR)
        }
    }

    private fun isSelected(choice: ProxyChoice): Boolean {
        val current = ProxyPlatform.currentUrl()
        val enabled = ProxyPlatform.isProxyEnabled()
        return choice.url == current || (choice.url == null && current == null && !enabled) ||
            (choice.url == "" && current == null && enabled)
    }

    private fun reload() {
        model.clear()
        choices.forEach(model::addElement)
        val selectedIndex = choices.indexOfFirst(::isSelected)
        if (selectedIndex >= 0) list.selectedIndex = selectedIndex
        else if (!model.isEmpty) list.selectedIndex = 0
    }
}

object ProxyPlatform {
    private val controller = IntelliJProxyController()
    fun currentProxyAddress(): Pair<String, Int>? = controller.read()?.let { it.host to it.port }
    fun isProxyEnabled(): Boolean {
        val config = HttpConfigurable.getInstance()
        return config.USE_HTTP_PROXY || config.USE_PROXY_PAC
    }
    fun currentUrl(): String? = currentProxyAddress()?.let { "http://${it.first}:${it.second}" }
    fun currentLabel(): String = currentUrl() ?: if (isProxyEnabled()) "Proxy (IDE managed)" else "Direct"
    fun isCurrentProxyBeingChecked(): Boolean = currentProxyAddress()?.let { ProxyHealthState.isChecking("${it.first}:${it.second}") } == true

    fun apply(url: String?) = controller.apply(url)
}

enum class ProxyReachability { CHECKING, CONNECTED, DISCONNECTED }

object ProxyHealthState {
    @Volatile private var endpointKey: String? = null
    @Volatile private var reachability: ProxyReachability? = null

    @Synchronized fun begin(endpoint: String) {
        endpointKey = endpoint
        reachability = ProxyReachability.CHECKING
    }

    @Synchronized fun complete(endpoint: String, connected: Boolean) {
        if (endpointKey != endpoint) return
        reachability = if (connected) ProxyReachability.CONNECTED else ProxyReachability.DISCONNECTED
    }

    @Synchronized fun resultFor(endpoint: String): ProxyReachability? {
        return if (endpointKey == endpoint) reachability else null
    }

    @Synchronized fun isChecking(endpoint: String) = resultFor(endpoint) == ProxyReachability.CHECKING
}

data class ProxyEndpoint(val host: String, val port: Int)

interface ProxyController {
    fun read(): ProxyEndpoint?
    fun apply(url: String?)
}

class IntelliJProxyController : ProxyController {
    override fun read(): ProxyEndpoint? {
        val config = HttpConfigurable.getInstance()
        return if (config.USE_HTTP_PROXY && !config.USE_PROXY_PAC && !config.PROXY_TYPE_IS_SOCKS && config.PROXY_HOST.isNotBlank() && config.PROXY_PORT > 0) ProxyEndpoint(config.PROXY_HOST, config.PROXY_PORT) else null
    }

    override fun apply(url: String?) {
        val config = HttpConfigurable.getInstance()
        if (url == null) { config.USE_HTTP_PROXY = false; config.USE_PROXY_PAC = false; return }
        val uri = java.net.URI(ProxyConfigStore.normalizeUrl(url))
        config.PROXY_TYPE_IS_SOCKS = false
        config.PROXY_HOST = uri.host
        config.PROXY_PORT = if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
        config.USE_PROXY_PAC = false
        config.USE_HTTP_PROXY = true
    }
}

class ProxyChecker(private val tcpConnect: (String, Int) -> Unit = ::connectTcp,
                   private val request: (String, Int) -> Int = ::requestThroughProxy) {
    fun check(host: String, port: Int): Int {
        tcpConnect(host, port)
        return request(host, port)
    }
    companion object {
        private fun connectTcp(host: String, port: Int) { Socket().use { it.connect(InetSocketAddress(host, port), 5_000) } }
        private fun requestThroughProxy(host: String, port: Int): Int {
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
            val connection = java.net.URI("https://www.google.com/generate_204").toURL().openConnection(proxy) as URLConnection
            connection.connectTimeout = 5_000; connection.readTimeout = 5_000
            return (connection as java.net.HttpURLConnection).responseCode.also { connection.disconnect() }
        }
    }
}

private fun notify(project: Project?, message: String, type: NotificationType) {
    NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
        .createNotification("Project Atlas Proxy", message, type).notify(project)
}
