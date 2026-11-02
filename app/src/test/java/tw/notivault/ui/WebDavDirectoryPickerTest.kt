package tw.notivault.ui

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tw.notivault.backup.DavEntry
import tw.notivault.backup.WebDavConfig

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class WebDavDirectoryPickerTest {
    @get:Rule val compose = createComposeRule()
    @Test fun browseChildrenGoUpAndSelectDecodedRelativeDirectory() {
        var selected: String? = null
        compose.setContent { VaultTheme("dark") { WebDavDirectoryPicker(WebDavConfig("https://example.invalid/dav/", "old", "", ""), {}, { selected = it }, loadDirectory = { path ->
            when (path) {
                emptyList<String>() -> listOf(DavEntry("備份 中文", true, 0), DavEntry("ignore.csv", false, 10))
                listOf("備份 中文") -> listOf(DavEntry("手機 + 平板", true, 0))
                else -> emptyList()
            }
        }) } }
        compose.onNodeWithText("ignore.csv").assertDoesNotExist()
        compose.onNodeWithText("備份 中文").performClick()
        compose.onNodeWithText("手機 + 平板").performClick()
        compose.onNodeWithContentDescription("上一層目錄").performClick()
        compose.onNodeWithText("/備份 中文").assertExists()
        compose.onNodeWithText("選取此目錄").performClick()
        assertEquals("備份 中文", selected)
    }
    @Test fun failedListingCannotBeSelectedAndCanRetryAndSelectRoot() {
        var attempts = 0; var selected: String? = null
        compose.setContent { VaultTheme("light") { WebDavDirectoryPicker(WebDavConfig("https://example.invalid/", "old", "", ""), {}, { selected = it }, loadDirectory = {
            attempts++; if (attempts == 1) throw java.io.IOException("PROPFIND HTTP 405") else emptyList()
        }) } }
        compose.onNodeWithText("PROPFIND HTTP 405").assertExists()
        compose.onNodeWithText("選取此目錄").assertIsNotEnabled()
        compose.onNodeWithText("重新載入").performClick()
        compose.onNodeWithText("選取此目錄").performClick()
        assertEquals("", selected)
    }
}
