package com.alicejump.okscripttoolkit.ui

/** Keeps a second swap from using annotations captured before the first save is visible in the list. */
internal class SwapRefreshGate {
    var inProgress: Boolean = false
        private set

    private var refreshFrom: Long? = null
    val awaitingRefresh: Boolean get() = refreshFrom != null

    fun begin(): Boolean {
        if (inProgress) return false
        inProgress = true
        return true
    }

    fun saveFailed() {
        refreshFrom = null
        inProgress = false
    }

    fun waitForRefresh(requestId: Long) {
        check(inProgress)
        refreshFrom = requestId
    }

    fun refreshed(requestId: Long, latestRequestId: Long): Boolean {
        val required = refreshFrom ?: return false
        if (requestId != latestRequestId || requestId < required) return false
        saveFailed()
        return true
    }
}
