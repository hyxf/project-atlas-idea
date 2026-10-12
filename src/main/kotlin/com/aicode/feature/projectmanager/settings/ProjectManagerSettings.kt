package com.aicode.feature.projectmanager.settings

import com.aicode.feature.projectmanager.infrastructure.persistence.ProjectJsonStore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.util.messages.Topic

fun interface ProjectManagerSettingsListener {
    fun settingsChanged(settings: ProjectManagerSettings.Data)

    companion object {
        val TOPIC = Topic.create("Project Atlas settings changed", ProjectManagerSettingsListener::class.java)
    }
}

class ProjectManagerSettings {
    enum class OpenMode { CURRENT_WINDOW, NEW_WINDOW }
    data class Data(
        var defaultOpenMode: OpenMode = OpenMode.CURRENT_WINDOW,
    )

    val state: Data
        get() = service<ProjectJsonStore>().settings().let { stored ->
            Data(
                runCatching { OpenMode.valueOf(stored.defaultOpenMode) }.getOrDefault(OpenMode.CURRENT_WINDOW),
            )
        }

    fun update(value: Data) {
        val store = service<ProjectJsonStore>()
        val previous = store.settings()
        store.replaceSettings(
            ProjectJsonStore.SettingsData(
                defaultOpenMode = value.defaultOpenMode.name,
                selectedFilter = previous.selectedFilter,
                selectedView = previous.selectedView,
                selectedListFilter = previous.selectedListFilter,
                tagProjectSpacing = previous.tagProjectSpacing,
                listProjectSpacing = previous.listProjectSpacing,
            ),
        )
        ApplicationManager.getApplication().messageBus
            .syncPublisher(ProjectManagerSettingsListener.TOPIC)
            .settingsChanged(value)
    }

}
