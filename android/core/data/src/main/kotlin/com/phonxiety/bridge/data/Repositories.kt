package com.phonxiety.bridge.data

import com.phonxiety.bridge.model.BridgeState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The live bridge state for every surface. */
interface BridgeStateRepository {
    val state: StateFlow<BridgeState>
}

/** Pairing with the daemon on the user's computer: stored payload, parsing and the result of a pairing attempt. */
interface PairingRepository {
    val paired: StateFlow<Boolean>

    /** Parses and stores a `phonxiety://pair?...` link; starts the bridge service when valid. */
    suspend fun pair(link: String): PairResult

    suspend fun unpair()
}

sealed interface PairResult {
    data object Paired : PairResult

    data class Invalid(val reason: String) : PairResult
}

/**
 * The daemon's app API over the device link (`/app/v1`). One call = one request; errors come
 * back as [ApiResult.Failure] with the daemon's code, never as exceptions for HTTP-like failures.
 */
interface AppApi {
    suspend fun request(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JsonObject? = null
    ): ApiResult

    /** Bulk bytes (recordings, bundles) on their own stream; paged fallback on WebSocket links. */
    suspend fun download(path: String, query: Map<String, String>, sink: java.io.OutputStream): ApiResult
}

sealed interface ApiResult {
    data class Success(val status: Int, val result: JsonElement) : ApiResult

    data class Failure(val status: Int, val code: String, val message: String) : ApiResult
}
