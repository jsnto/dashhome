package com.jsnto.dashhome.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jsnto.dashhome.api.ApiConfig
import com.jsnto.dashhome.api.Board

/**
 * The board's server-side layout state, shared between the tablet's grid
 * board and its Settings drill-down. The board layout is never stored
 * locally (it lives in the server's board file, the web's
 * localStorage-free state), so this holds only the latest server answer:
 * the app adopts the board every read/write returns (the web's
 * commitViews/adopt pattern).
 */
class BoardState {

    /** The latest GET /api/config answer; null while the first load runs. */
    var apiConfig by mutableStateOf<ApiConfig?>(null)

    /** Adopt the first GET /api/config load. The first answer wins (the
     *  web's `c ? ... : c` guard): a late duplicate load never replaces a
     *  board the writes below have already adopted on top of. */
    fun load(config: ApiConfig) {
        if (apiConfig == null) apiConfig = config
    }

    /** Adopt the board a write just persisted server-side (the web's
     *  onBoard). A write before the first load lands is dropped, like
     *  the web's `c ? ... : c` guard. */
    fun adopt(board: Board) {
        apiConfig = apiConfig?.copy(board = board)
    }
}
