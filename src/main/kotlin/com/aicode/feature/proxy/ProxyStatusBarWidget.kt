package com.aicode.feature.proxy

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.ui.AnimatedIcon
import java.awt.event.MouseEvent
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.SwingConstants
import javax.swing.Icon

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

class ProxyCheckStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = ProxyCheckStatusBarWidget.ID
    override fun getDisplayName() = "Project Atlas Proxy Check Status"
    override fun isEnabledByDefault() = true
    override fun isConfigurable() = true
    override fun createWidget(project: Project) = ProxyCheckStatusBarWidget(project)
}

class ProxyCheckStatusBarWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.IconPresentation {
    private var refreshTask: ScheduledFuture<*>? = null
    private var statusBar: StatusBar? = null
    private var animationStartedAtNanos = 0L
    private var lastFrame = -2

    override fun ID() = ID
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun getIcon(): Icon? {
        val frame = currentFrame()
        return if (frame < 0) null else AnimatedIcon.Default.ICONS[frame]
    }
    override fun getTooltipText(): String? = if (ProxyPlatform.isCurrentProxyBeingChecked()) "Checking proxy connection…" else null

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        refreshTask = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
            if (!project.isDisposed) {
                val frame = currentFrame()
                if (frame != lastFrame) {
                    lastFrame = frame
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) this.statusBar?.updateWidget(ID)
                    }
                }
            }
        }, 0, 100, TimeUnit.MILLISECONDS)
    }

    override fun dispose() {
        refreshTask?.cancel(false)
        refreshTask = null
        statusBar = null
    }

    private fun currentFrame(): Int {
        if (!ProxyPlatform.isCurrentProxyBeingChecked()) {
            animationStartedAtNanos = 0
            return -1
        }
        if (animationStartedAtNanos == 0L) animationStartedAtNanos = System.nanoTime()
        val frameDurationNanos = TimeUnit.MILLISECONDS.toNanos(100)
        return ((System.nanoTime() - animationStartedAtNanos) / frameDurationNanos % AnimatedIcon.Default.ICONS.size).toInt()
    }

    companion object { const val ID = "ProjectAtlas.ProxyCheckStatus" }
}
