package com.frop.montash

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class StandaloneLaunchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun opensObjectsAndGlobalProductsWithoutTeam() {
        compose.onNodeWithText("Дані на пристрої").assertIsDisplayed()
        compose.onAllNodesWithText("Бригада").assertCountEquals(0)
        compose.onAllNodesWithText("Моя бригада").assertCountEquals(0)
        compose.onNodeWithTag("navigation-products").performClick()
        compose.onNodeWithTag("object-products").assertIsDisplayed()
        compose.onNodeWithTag("navigation-objects").performClick()
        compose.onNodeWithText("Створити об'єкт").assertIsDisplayed()
    }
}
