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
import com.intellij.util.net.HttpConfigurable
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URLConnection
import java.net.Socket

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
                    val current = ProxyPlatform.currentUrl()
                    val proxyActive = ProxyPlatform.isProxyEnabled()
                    val choices = buildList {
                        add(ProxyChoice("Direct", null))
                        entries.forEach { add(ProxyChoice("${it.name} — ${it.url}", it.url)) }
                        if (proxyActive && (current == null || entries.none { it.url == current })) add(ProxyChoice("system${current?.let { " — $it" } ?: " (IDE managed)"}", current ?: ""))
                    }.map { choice ->
                        val selected = choice.url == current || (choice.url == null && current == null && !proxyActive) || (choice.url == "" && current == null && proxyActive)
                        if (selected) "✓ ${choice.label}" else "   ${choice.label}"
                    }
                    JBPopupFactory.getInstance().createPopupChooserBuilder(choices)
                        .setTitle("Select HTTP Proxy")
                        .setItemChosenCallback { selected ->
                            val index = choices.indexOf(selected)
                            val choice = buildList {
                                add(ProxyChoice("Direct", null)); entries.forEach { add(ProxyChoice("${it.name} — ${it.url}", it.url)) }
                                if (proxyActive && (current == null || entries.none { it.url == current })) add(ProxyChoice("system${current?.let { " — $it" } ?: " (IDE managed)"}", current ?: ""))
                            }.getOrNull(index) ?: return@setItemChosenCallback
                            try {
                                if (choice.url.isNullOrEmpty() && proxyActive) return@setItemChosenCallback
                                ProxyPlatform.apply(choice.url)
                                notify(project, "IDE proxy is now ${if (choice.url == null) "Direct" else choice.url}.", NotificationType.INFORMATION)
                            }
                            catch (ex: Exception) { notify(project, "Could not change IDE proxy: ${ex.message}", NotificationType.ERROR) }
                        }.createPopup().showInBestPositionFor(e.dataContext)
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
                "The IDE proxy is managed by PAC or uses SOCKS; its HTTP proxy endpoint cannot be checked by this action."
            } else "Direct mode is active; there is no proxy to check."
            notify(project, message, if (ProxyPlatform.isProxyEnabled()) NotificationType.WARNING else NotificationType.INFORMATION)
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { ProxyChecker().check(proxy.first, proxy.second) }
            ApplicationManager.getApplication().invokeLater {
                result.onSuccess { status ->
                    val type = if (status in 200..299) NotificationType.INFORMATION else NotificationType.WARNING
                    notify(project, "Proxy ${proxy.first}:${proxy.second} responded with HTTP $status.", type)
                }.onFailure { notify(project, "Proxy check failed for ${proxy.first}:${proxy.second}: ${it.message}", NotificationType.ERROR) }
            }
        }
    }
}

data class ProxyChoice(val label: String, val url: String?)

object ProxyPlatform {
    private val controller = IntelliJProxyController()
    fun currentProxyAddress(): Pair<String, Int>? = controller.read()?.let { it.host to it.port }
    fun isProxyEnabled(): Boolean {
        val config = HttpConfigurable.getInstance()
        return config.USE_HTTP_PROXY || config.USE_PROXY_PAC
    }
    fun currentUrl(): String? = currentProxyAddress()?.let { "http://${it.first}:${it.second}" }
    fun currentLabel(): String = currentUrl() ?: if (isProxyEnabled()) "Proxy (IDE managed)" else "Direct"

    fun apply(url: String?) = controller.apply(url)
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
