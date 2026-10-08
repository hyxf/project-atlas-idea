package com.aicode.feature.projectmanager

import com.intellij.openapi.util.IconLoader
import com.intellij.icons.AllIcons
import com.intellij.ui.LayeredIcon
import com.intellij.util.IconUtil

object ProjectManagerIcons {
    @JvmField
    val FilterByTag = IconLoader.getIcon("/icons/project/filterByTag.svg", ProjectManagerIcons::class.java)

    @JvmField
    val TagGroup = IconLoader.getIcon("/icons/project/tagGroup.svg", ProjectManagerIcons::class.java)

    @JvmField
    val FavoriteFolder = LayeredIcon(2).apply {
        setIcon(AllIcons.Nodes.Folder, 0)
        setIcon(IconUtil.resizeSquared(AllIcons.Nodes.Favorite, 11), 1, 5, 5)
    }

    @JvmField
    val ImportLocalProjects = IconLoader.getIcon("/icons/project/importLocalProjects.svg", ProjectManagerIcons::class.java)

}
