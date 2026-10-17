package dev.underthehood

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import kotlinx.coroutines.runBlocking
import org.junit.*

class AccountConnectionUiTest {
 @get:Rule val ui=createAndroidComposeRule<MainActivity>()
 @Before fun interceptBrowser(){Intents.init();Intents.intending(hasAction(Intent.ACTION_VIEW)).respondWith(ActivityResult(Activity.RESULT_OK,null))}
 @After fun cleanup(){ui.runOnIdle{ViewModelProvider(ui.activity)[HoodModel::class.java].cancelConnection()};Intents.release()}
 @Test fun reportedNameStartsBrowserAndKeepsProgressVisible(){
  val model=ViewModelProvider(ui.activity)[HoodModel::class.java]
  val providers=runBlocking{model.app.engine.providers()}
  ui.runOnIdle{model.providers.value=providers;model.unlocked.value=true}
  ui.onNodeWithText("Accounts",useUnmergedTree=true).performClick()
  ui.onNodeWithContentDescription("Connect account").performClick()
  ui.onNodeWithText("Account name").performTextInput("M. Ayan Drive")
  ui.onNodeWithText("Connect",substring=false).performClick()
  ui.waitUntil(20000){model.authorizationUrl.value!=null || model.connectionError.value.isNotBlank()}
  Assert.assertEquals("",model.connectionError.value)
  Assert.assertTrue(model.authorizationUrl.value!!.startsWith("http://127.0.0.1:53682/auth?state="))
  ui.onNodeWithText("Connect a cloud").assertExists()
  ui.onNodeWithText("Open sign-in page in browser").assertExists()
  ui.onNodeWithText("Cancel connection").assertExists()
  ui.waitForIdle();Intents.intended(hasAction(Intent.ACTION_VIEW))
 }
}
