package com.frop.montash

import android.Manifest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.junit.Test

class StandaloneLaunchTest {
    private val permissions = TestRule { base: Statement, _: Description ->
        object : Statement() {
            override fun evaluate() {
                InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                    "com.frop.montash", Manifest.permission.ACCESS_FINE_LOCATION)
                InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                    "com.frop.montash", Manifest.permission.ACCESS_COARSE_LOCATION)
                base.evaluate()
            }
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(permissions).around(compose)

    @Test fun opensObjectsAndGlobalProductsWithoutTeam() {
        compose.onNodeWithText("Створити об'єкт").assertIsDisplayed()
        compose.onNodeWithTag("navigation-home").assertDoesNotExist()
        compose.onAllNodesWithText("Бригада").assertCountEquals(0)
        compose.onAllNodesWithText("Моя бригада").assertCountEquals(0)
        compose.onNodeWithTag("navigation-products").performClick()
        compose.onNodeWithTag("object-products").assertIsDisplayed()
        compose.onNodeWithTag("navigation-objects").performClick()
        compose.onNodeWithText("Створити об'єкт").assertIsDisplayed()
    }
}
