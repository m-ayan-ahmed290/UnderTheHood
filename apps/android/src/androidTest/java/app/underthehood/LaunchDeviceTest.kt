package app.underthehood

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun unlockAndCreateVirtualFolder() {
        compose
            .onNodeWithText("Passphrase · at least 12 characters")
            .performTextInput("instrumentation test passphrase")
        val fresh =
            compose.onAllNodesWithText("Confirm passphrase").fetchSemanticsNodes().isNotEmpty()
        if (fresh)
            compose
                .onNodeWithText("Confirm passphrase")
                .performTextInput("instrumentation test passphrase")
        compose.onNodeWithText(if (fresh) "Create profile" else "Unlock").performClick()
        compose.waitUntil(60000) {
            compose.onAllNodesWithText("My Cloud").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("New folder").performClick()
        compose.onNodeWithText("Value").performTextInput("Device integration folder")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10000) {
            compose
                .onAllNodesWithText("Device integration folder")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("Device integration folder").assertIsDisplayed()
    }
}
