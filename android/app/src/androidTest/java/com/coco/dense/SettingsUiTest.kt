package com.coco.dense

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun avatarPickerOpensWithoutCrashing() {
        compose.onNode(hasSetTextAction()).performTextInput("fictional-master")
        if (compose.onAllNodes(hasText("创建保险箱")).fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithText("创建保险箱").performClick()
        else compose.onNodeWithText("解锁保险箱").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasText("设置")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithContentDescription("更换头像").performClick()
        compose.waitForIdle()
    }
}
