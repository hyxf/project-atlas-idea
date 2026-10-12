package com.aicode.feature.ai.model

import java.util.LinkedHashMap

/** Data model for .aicode.json. Supports multiple ordered context groups. */
class AICodeConfig {
    private var activeGroup: String? = DEFAULT_GROUP
    private var groups: MutableMap<String, MutableList<String>>? =
        linkedMapOf(DEFAULT_GROUP to mutableListOf())

    fun getActiveGroup(): String {
        if (activeGroup.isNullOrEmpty()) activeGroup = DEFAULT_GROUP
        return activeGroup ?: DEFAULT_GROUP
    }

    fun setActiveGroup(activeGroup: String?) {
        this.activeGroup = activeGroup
    }

    fun getGroups(): MutableMap<String, MutableList<String>> {
        if (groups == null) groups = LinkedHashMap()
        return groups ?: LinkedHashMap<String, MutableList<String>>().also { groups = it }
    }

    fun setGroups(groups: MutableMap<String, MutableList<String>>?) {
        this.groups = groups
    }

    fun getActivePaths(): MutableList<String> =
        getGroups().computeIfAbsent(getActiveGroup()) { ArrayList() }

    fun setActivePaths(paths: MutableList<String>?) {
        getGroups()[getActiveGroup()] = paths ?: ArrayList()
    }

    companion object {
        const val DEFAULT_GROUP = "Default"
    }
}
