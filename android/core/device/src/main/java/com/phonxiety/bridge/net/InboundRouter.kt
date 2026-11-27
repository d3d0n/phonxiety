package com.phonxiety.bridge.net

import com.phonxiety.bridge.model.ControlEnvelope

/**
 * The one gate every inbound control frame passes.
 *
 * Precedence is explicit: an `api_result` completes its pending request by id
 * first, and only then does the normal dispatch see the frame.  An unknown id,
 * or a frame that does not decode, is dropped with one line naming the frame's
 * class and id — never its payload — and leaves no state behind.
 */
internal class InboundRouter(
    private val requests: ApiRequestTable,
    private val dispatch: (type: String, text: String) -> Unit,
    private val log: (message: String) -> Unit = {}
) {
    fun route(text: String) {
        val type = ControlEnvelope.type(text) ?: return
        if (type == API_RESULT) {
            val result = runCatching { ControlEnvelope.decodeApiResult(text) }.getOrNull()
            if (result == null) {
                log("dropped an api_result that does not decode")
                return
            }
            if (!requests.complete(result)) {
                log("dropped an api_result for an unknown request ${result.id}")
                return
            }
        }
        dispatch(type, text)
    }

    companion object {
        private const val API_RESULT = "api_result"
    }
}
