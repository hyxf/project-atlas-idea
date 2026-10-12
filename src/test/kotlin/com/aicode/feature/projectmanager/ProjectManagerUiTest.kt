package com.aicode.feature.projectmanager

import com.aicode.feature.projectmanager.feature.ui.ProjectManagerPanel
import com.aicode.feature.projectmanager.settings.ProjectManagerConfigurable
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class ProjectManagerUiTest {
    private lateinit var fixture: CodeInsightTestFixture

    @Before
    fun setUp() {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        fixture = factory.createCodeInsightFixture(factory.createFixtureBuilder("ProjectManagerUiTest").fixture)
        fixture.setUp()
    }

    @After
    fun tearDown() = fixture.tearDown()

    @Test
    fun `tool window panel and settings can be created`() {
        val panel = ProjectManagerPanel(fixture.project)
        val configurable = ProjectManagerConfigurable()

        assertNotNull(panel)
        assertNotNull(configurable.createComponent())

        configurable.disposeUIResources()
    }
}
