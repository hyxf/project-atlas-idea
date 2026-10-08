package com.aicode.feature.proxy

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.event.MouseEvent
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.SwingConstants

class ProxyStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = ProxyStatusBarWidget.ID
    override fun getDisplayName() = "Project Atlas Proxy"
    override fun isEnabledByDefault() = true
    override fun isConfigurable() = true
    override fun createWidget(project: Project) = ProxyStatusBarWidget(project)
}

class ProxyStatusBarWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {
    private var refreshTask: ScheduledFuture<*>? = null
    private var statusBar: StatusBar? = null

    override fun ID() = ID
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun getText() = "Proxy: ${ProxyPlatform.currentLabel()}"
    override fun getAlignment() = SwingConstants.CENTER.toFloat()
    override fun getTooltipText() = "IDEA global HTTP proxy. Click to choose a proxy."

    override fun getClickConsumer(): com.intellij.util.Consumer<MouseEvent> = com.intellij.util.Consumer {
        val action = ProxyModeAction()
        val context = com.intellij.ide.DataManager.getInstance().getDataContext(statusBar?.component)
        action.actionPerformed(com.intellij.openapi.actionSystem.AnActionEvent.createFromAnAction(
            action, it, com.intellij.openapi.actionSystem.ActionPlaces.STATUS_BAR_PLACE, context,
        ))
    }

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        refreshTask = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) this.statusBar?.updateWidget(ID)
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    override fun dispose() {
        refreshTask?.cancel(false)
        refreshTask = null
        statusBar = null
    }

    companion object { const val ID = "ProjectAtlas.ProxyStatus" }
}
