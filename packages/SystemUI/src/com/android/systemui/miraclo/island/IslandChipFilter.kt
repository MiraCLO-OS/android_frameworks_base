/*
 * SPDX-FileCopyrightText: MiraCLO
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.miraclo.island

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Status bar chips the Dynamic Island already shows, so the status bar leaves them out: the call
 * chip while the island has the call, the screen recording chip while it has the recording, and
 * the chips of notifications it shows (live updates, timers, ...). Chips for sharing or casting
 * the screen are privacy indicators and are never hidden.
 */
object IslandChipFilter {
    data class State(
        val hideCall: Boolean = false,
        val hideScreenRecord: Boolean = false,
        val notificationKeys: Set<String> = emptySet(),
        /**
         * The island shows something right now: the status bar leaves out every chip, so its left
         * side keeps only the clock (the island already shows what is going on).
         */
        val hideAll: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun set(state: State) {
        _state.value = state
    }
}
