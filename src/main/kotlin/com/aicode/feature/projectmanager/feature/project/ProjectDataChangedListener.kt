package com.aicode.feature.projectmanager.feature.project

import com.intellij.util.messages.Topic

fun interface ProjectDataChangedListener {
    fun projectsChanged()

    companion object {
        val TOPIC = Topic.create("Project Atlas projects changed", ProjectDataChangedListener::class.java)
    }
}
