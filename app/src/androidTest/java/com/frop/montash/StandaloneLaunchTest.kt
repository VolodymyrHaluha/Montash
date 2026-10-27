package com.frop.montash

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class StandaloneLaunchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun opensHomeAndProductsWithoutAttendanceApp() {
        compose.onNodeWithText("Дані на пристрої").assertIsDisplayed()
        compose.onNodeWithText("Вироби").performClick()
        compose.onNodeWithText("Вироби з усіх об'єктів • PDF-креслення на пристрої").assertIsDisplayed()
    }
}
