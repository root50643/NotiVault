package tw.notivault.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tw.notivault.MainActivity
import tw.notivault.VaultApplication
import tw.notivault.core.CapturedNotification
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = VaultApplication::class, qualifiers = "zh-rTW-w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun onboardingNavigationCalendarWebDavSettingsAndThemeSwitch() {
        compose.waitForIdle()
        capture("01-onboarding")
        compose.onNodeWithText("稍後設定，先看看介面").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("每一則，都留存。").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("本機已典藏通知").assertExists()
        val graph = (compose.activity.application as VaultApplication).graph
        runBlocking {
            listOf(4, 12, 5, 9, 2, 7, 3).forEachIndexed { day, amount -> repeat(amount) { index ->
                val time = LocalDate.now(ZoneId.of("Asia/Taipei")).minusDays(day.toLong()).atTime(9 + index % 10, index * 3 % 60).atZone(ZoneId.of("Asia/Taipei")).toInstant().toEpochMilli()
                graph.repository.capture(CapturedNotification(time, "ui-fixture-$day-$index", "test.fixture", "測試資料", "通知介面測試 ${index + 1}", "這是只供畫面驗證的測試通知，不會內建於 App。", buildJsonObject { put("postTime", time) }, buildJsonObject { put("text", "fixture-$index") }, buildJsonObject {}, emptyList()))
            } }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("42").fetchSemanticsNodes().isNotEmpty() }
        capture("02-dashboard-dark")
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(4)
        capture("03-dashboard-chart")
        compose.onNodeWithText("歷史").performClick()
        compose.onNodeWithText("回到那一天。").assertExists()
        capture("04-history-dark")
        compose.onNodeWithContentDescription("上個月").performClick()
        compose.onNodeWithContentDescription("下個月").performClick()
        compose.onNodeWithText("WebDAV").performClick()
        compose.onNodeWithText("讓記憶，有備份。").assertExists()
        capture("05-webdav-dark")
        compose.onNodeWithText("設定").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("淺色"))
        compose.onNodeWithText("淺色").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("淺色") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        capture("06-settings-light")
        compose.onNodeWithText("總覽").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        capture("07-dashboard-light")
        compose.onNodeWithText("設定").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("深色"))
        compose.onNodeWithText("深色").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("深色") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("總覽").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithText("每一則，都留存。").assertExists()
        compose.runOnIdle { org.robolectric.RuntimeEnvironment.setFontScale(1.5f) }
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        capture("08-dashboard-large-font")
        compose.onNodeWithText("歷史").performClick()
        capture("09-calendar-large-font")
        compose.onNodeWithContentDescription("上個月").performClick()
        compose.onNodeWithContentDescription("下個月").performClick()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val output = File("build/outputs/qa/$name.png").apply { parentFile.mkdirs() }
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
