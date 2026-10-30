/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.panels.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.qs.composefragment.QuickQuickSettingsLayout
import com.android.systemui.qs.composefragment.QuickSettingsLayout
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class QSFragmentComposeTest : SysuiTestCase() {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun portraitLayout_qqs() {
        composeTestRule.setContent {
            QuickQuickSettingsLayout(
                tiles = { Tiles(TILES_HEIGHT_PORTRAIT) },
                media = { Media() },
                mediaInRow = false,
            )
        }

        composeTestRule.waitForIdle()

        val tilesBounds = composeTestRule.onNodeWithTag(TILES).getBoundsInRoot()
        val mediaBounds = composeTestRule.onNodeWithTag(MEDIA).getBoundsInRoot()

        // All nodes aligned in a column
        assertThat(tilesBounds.left).isEqualTo(mediaBounds.left)
        assertThat(tilesBounds.right).isEqualTo(mediaBounds.right)
        assertThat(tilesBounds.bottom).isLessThan(mediaBounds.top)
    }

    @Test
    fun landscapeLayout_qqs() {
        composeTestRule.setContent {
            QuickQuickSettingsLayout(
                tiles = { Tiles(TILES_HEIGHT_LANDSCAPE) },
                media = { Media() },
                mediaInRow = true,
            )
        }

        composeTestRule.waitForIdle()

        val tilesBounds = composeTestRule.onNodeWithTag(TILES).getBoundsInRoot()
        val mediaBounds = composeTestRule.onNodeWithTag(MEDIA).getBoundsInRoot()

        // Media to the right of tiles
        assertThat(tilesBounds.right).isLessThan(mediaBounds.left)
        // "Same" width
        assertThat((tilesBounds.width - mediaBounds.width).abs()).isAtMost(1.dp)
        // Vertically centered
        assertThat((tilesBounds.centerY - mediaBounds.centerY).abs()).isAtMost(1.dp)
    }

    @Test
    fun portraitLayout_qs() {
        composeTestRule.setContent {
            QuickSettingsLayout(
                brightness = { Brightness() },
                tiles = { Tiles(TILES_HEIGHT_PORTRAIT) },
                media = { Media() },
                mediaInRow = false,
            )
        }

        composeTestRule.waitForIdle()

        val brightnessBounds = composeTestRule.onNodeWithTag(BRIGHTNESS).getBoundsInRoot()
        val tilesBounds = composeTestRule.onNodeWithTag(TILES).getBoundsInRoot()
        val mediaBounds = composeTestRule.onNodeWithTag(MEDIA).getBoundsInRoot()

        assertThat(brightnessBounds.left).isEqualTo(tilesBounds.left)
        assertThat(tilesBounds.left).isEqualTo(mediaBounds.left)

        assertThat(brightnessBounds.right).isEqualTo(tilesBounds.right)
        assertThat(tilesBounds.right).isEqualTo(mediaBounds.right)

        assertThat(brightnessBounds.bottom).isLessThan(tilesBounds.top)
        assertThat(tilesBounds.bottom).isLessThan(mediaBounds.top)
    }

    @Test
    fun landscapeLayout_qs() {
        composeTestRule.setContent {
            QuickSettingsLayout(
                brightness = { Brightness() },
                tiles = { Tiles(TILES_HEIGHT_PORTRAIT) },
                media = { Media() },
                mediaInRow = true,
            )
        }

        composeTestRule.waitForIdle()

        val brightnessBounds = composeTestRule.onNodeWithTag(BRIGHTNESS).getBoundsInRoot()
        val tilesBounds = composeTestRule.onNodeWithTag(TILES).getBoundsInRoot()
        val mediaBounds = composeTestRule.onNodeWithTag(MEDIA).getBoundsInRoot()

        // Brightness takes full width, with left end aligned with tiles and right end aligned with
        // media
        assertThat(brightnessBounds.left).isEqualTo(tilesBounds.left)
        assertThat(brightnessBounds.right).isEqualTo(mediaBounds.right)

        // Brightness above tiles and media
        assertThat(brightnessBounds.bottom).isLessThan(tilesBounds.top)
        assertThat(brightnessBounds.bottom).isLessThan(mediaBounds.top)

        // Media to the right of tiles
        assertThat(tilesBounds.right).isLessThan(mediaBounds.left)
        // "Same" width
        assertThat((tilesBounds.width - mediaBounds.width).abs()).isAtMost(1.dp)
        // Vertically centered
        assertThat((tilesBounds.centerY - mediaBounds.centerY).abs()).isAtMost(1.dp)
    }

    @Test
    fun portraitLayout_qqsBrightnessSettingsChange() {
        verifyQqsBrightnessSettingsChange(mediaInRow = false)
    }

    @Test
    fun landscapeLayout_qqsBrightnessSettingsChange() {
        verifyQqsBrightnessSettingsChange(mediaInRow = true)
    }

    @Test
    fun portraitLayout_qsBrightnessSettingsChange() {
        verifyQsBrightnessSettingsChange(mediaInRow = false)
    }

    @Test
    fun landscapeLayout_qsBrightnessSettingsChange() {
        verifyQsBrightnessSettingsChange(mediaInRow = true)
    }

    private fun verifyQqsBrightnessSettingsChange(mediaInRow: Boolean) {
        val showSlider = mutableIntStateOf(1)
        val sliderAtTop = mutableStateOf(true)
        composeTestRule.setContent {
            QuickQuickSettingsLayout(
                brightness = { Brightness() },
                tiles = { Tiles(if (mediaInRow) TILES_HEIGHT_LANDSCAPE else TILES_HEIGHT_PORTRAIT) },
                media = { Media() },
                mediaInRow = mediaInRow,
                showSlider = showSlider.intValue,
                sliderAtTop = sliderAtTop.value,
            )
        }

        // The default setting only shows brightness in expanded QS.
        composeTestRule.onNodeWithTag(BRIGHTNESS).assertDoesNotExist()
        val tilesTopWithoutBrightness = composeTestRule.onNodeWithTag(TILES).getBoundsInRoot().top

        composeTestRule.runOnIdle { showSlider.intValue = 2 }
        assertBrightnessPosition(sliderAtTop = true, mediaInRow = mediaInRow)

        composeTestRule.runOnIdle { sliderAtTop.value = false }
        assertBrightnessPosition(sliderAtTop = false, mediaInRow = mediaInRow)

        composeTestRule.runOnIdle { showSlider.intValue = 0 }
        composeTestRule.onNodeWithTag(BRIGHTNESS).assertDoesNotExist()
        assertThat(composeTestRule.onNodeWithTag(TILES).getBoundsInRoot().top)
            .isEqualTo(tilesTopWithoutBrightness)
    }

    private fun verifyQsBrightnessSettingsChange(mediaInRow: Boolean) {
        val showSlider = mutableIntStateOf(0)
        val sliderAtTop = mutableStateOf(true)
        composeTestRule.setContent {
            QuickSettingsLayout(
                brightness = { Brightness() },
                tiles = { afterTiles ->
                    Column {
                        Tiles(if (mediaInRow) TILES_HEIGHT_LANDSCAPE else TILES_HEIGHT_PORTRAIT)
                        afterTiles?.invoke()
                    }
                },
                media = { Media() },
                mediaInRow = mediaInRow,
                showSlider = showSlider.intValue,
                sliderAtTop = sliderAtTop.value,
            )
        }

        composeTestRule.onNodeWithTag(BRIGHTNESS).assertDoesNotExist()

        composeTestRule.runOnIdle { showSlider.intValue = 1 }
        assertBrightnessPosition(sliderAtTop = true, mediaInRow = mediaInRow)

        composeTestRule.runOnIdle { sliderAtTop.value = false }
        assertBrightnessPosition(sliderAtTop = false, mediaInRow = mediaInRow)

        // Showing brightness in both panels must keep it visible in expanded QS.
        composeTestRule.runOnIdle { showSlider.intValue = 2 }
        assertBrightnessPosition(sliderAtTop = false, mediaInRow = mediaInRow)

        composeTestRule.runOnIdle { showSlider.intValue = 0 }
        composeTestRule.onNodeWithTag(BRIGHTNESS).assertDoesNotExist()
    }

    private fun assertBrightnessPosition(sliderAtTop: Boolean, mediaInRow: Boolean) {
        val brightnessBounds = composeTestRule.onNodeWithTag(BRIGHTNESS).getBoundsInRoot()
        val tilesBounds = composeTestRule.onNodeWithTag(TILES).getBoundsInRoot()
        val mediaBounds = composeTestRule.onNodeWithTag(MEDIA).getBoundsInRoot()

        assertThat(brightnessBounds.left).isEqualTo(tilesBounds.left)
        if (sliderAtTop || !mediaInRow) {
            assertThat(brightnessBounds.right).isEqualTo(mediaBounds.right)
        } else {
            assertThat(brightnessBounds.right).isAtMost(mediaBounds.left)
        }
        if (sliderAtTop) {
            assertThat(brightnessBounds.bottom).isLessThan(tilesBounds.top)
            assertThat(brightnessBounds.bottom).isLessThan(mediaBounds.top)
        } else {
            assertThat(brightnessBounds.top).isGreaterThan(tilesBounds.bottom)
            if (!mediaInRow) {
                assertThat(brightnessBounds.bottom).isLessThan(mediaBounds.top)
            }
        }
        if (mediaInRow) {
            assertThat(tilesBounds.right).isLessThan(mediaBounds.left)
            assertThat((tilesBounds.width - mediaBounds.width).abs()).isAtMost(1.dp)
            if (sliderAtTop) {
                assertThat((tilesBounds.centerY - mediaBounds.centerY).abs()).isAtMost(1.dp)
            }
        } else {
            assertThat(tilesBounds.right).isEqualTo(mediaBounds.right)
            assertThat(tilesBounds.bottom).isLessThan(mediaBounds.top)
        }
    }

    private companion object {
        const val BRIGHTNESS = "brightness"
        const val TILES = "tiles"
        const val MEDIA = "media"
        val TILES_HEIGHT_PORTRAIT = 300.dp
        val TILES_HEIGHT_LANDSCAPE = 150.dp
        val MEDIA_HEIGHT = 100.dp
        val BRIGHTNESS_HEIGHT = 64.dp

        @Composable
        fun Brightness() {
            Box(modifier = Modifier.testTag(BRIGHTNESS).height(BRIGHTNESS_HEIGHT).fillMaxWidth())
        }

        @Composable
        fun Tiles(height: Dp) {
            Box(modifier = Modifier.testTag(TILES).height(height).fillMaxWidth())
        }

        @Composable
        fun Media() {
            Box(modifier = Modifier.testTag(MEDIA).height(MEDIA_HEIGHT).fillMaxWidth())
        }

        val DpRect.centerY: Dp
            get() = (top + bottom) / 2

        fun Dp.abs() = if (this > 0.dp) this else -this
    }
}
