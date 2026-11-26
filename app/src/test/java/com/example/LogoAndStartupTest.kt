package com.example

import android.app.Application
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.example.ui.CameraScreen
import com.example.ui.components.PermissionScreen
import com.example.ui.components.StuDOLogo
import com.example.ui.theme.StuDOTheme
import com.example.viewmodel.CameraViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LogoAndStartupTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `adaptive launcher and monochrome resources resolve cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        assertNotNull(ContextCompat.getDrawable(context, R.mipmap.ic_launcher))
        assertNotNull(ContextCompat.getDrawable(context, R.mipmap.ic_launcher_round))
        assertNotNull(ContextCompat.getDrawable(context, R.drawable.ic_launcher_background))
        assertNotNull(ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground))
        assertNotNull(ContextCompat.getDrawable(context, R.drawable.ic_launcher_monochrome))

        val lensBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.studo_logo_lens)
        assertNotNull(lensBitmap)
        assertTrue(lensBitmap.width > 0 && lensBitmap.height > 0)
        assertEquals(lensBitmap.width, lensBitmap.height)
    }

    @Test
    fun `permission screen displays liquid glass logo and visible Grant Camera Access button`() {
        var requested = false
        composeTestRule.setContent {
            StuDOTheme {
                PermissionScreen(
                    permanentlyDenied = false,
                    onRequestPermission = { requested = true }
                )
            }
        }

        composeTestRule.onNodeWithTag("studo_theme_logo").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("StuDO Logo").assertIsDisplayed()
        composeTestRule.onNodeWithTag("grant_camera_permission_button").assertIsDisplayed()
        composeTestRule.onNodeWithText("Grant Camera Access").assertIsDisplayed()

        composeTestRule.onNodeWithTag("grant_camera_permission_button").performClick()
        assertTrue(requested)
    }

    @Test
    fun `permission screen displays Open Settings button when permanently denied`() {
        composeTestRule.setContent {
            StuDOTheme {
                PermissionScreen(
                    permanentlyDenied = true,
                    onRequestPermission = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("open_settings_button").assertIsDisplayed()
        composeTestRule.onNodeWithText("Open Settings").assertIsDisplayed()
    }

    @Test
    fun `theme aware StuDOLogo renders cleanly in both Light and Dark modes`() {
        composeTestRule.setContent {
            Column {
                StuDOLogo(
                    size = 96.dp,
                    isDarkTheme = false,
                    contentDescription = "StuDO Light Logo"
                )
                StuDOLogo(
                    size = 96.dp,
                    isDarkTheme = true,
                    contentDescription = "StuDO Dark Logo"
                )
                StuDOLogo(
                    size = 64.dp,
                    showBadgeBackdrop = true,
                    isDarkTheme = false,
                    contentDescription = "StuDO Badge Light"
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("StuDO Light Logo").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("StuDO Dark Logo").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("StuDO Badge Light").assertIsDisplayed()
    }

    @Test
    fun `camera screen launches immediately into permission screen with zero splash overlay`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = CameraViewModel(app)
        composeTestRule.setContent {
            StuDOTheme {
                CameraScreen(viewModel = vm)
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("splash_animation_overlay").assertDoesNotExist()
        composeTestRule.onNodeWithTag("grant_camera_permission_button").assertIsDisplayed()
        composeTestRule.onNodeWithText("Grant Camera Access").assertIsDisplayed()
    }
}
