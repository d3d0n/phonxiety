package com.phonxiety.bridge.net

import com.phonxiety.bridge.model.DeviceApiRequest
import com.phonxiety.bridge.model.DeviceApiResult
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The refresh that follows a hello: one `/app/v1/info` per connection, merged
 * with the daemon's pin; a failure is only logged.
 */
class LinkCandidateRefreshTest {
    private val pin = "ab".repeat(32)
    private val lan = "wss://192.168.1.10:18763/device"
    private val tailscale = "wss://100.64.0.2:18763/device"

    private fun store(): CandidateStore = CandidateStore(File(createTempDirectory("candidates").toFile(), "link-candidates.json"))

    private fun answer(
        certificate: String? = pin,
        lanUrls: List<String> = listOf(lan),
        tailscaleUrls: List<String> = listOf(tailscale)
    ): DeviceApiResult = DeviceApiResult(
        id = "answer",
        status = 200,
        result = buildJsonObject {
            put(
                "connection",
                buildJsonObject {
                    if (certificate != null) put("certificate", certificate)
                    put("lan", buildJsonArray { lanUrls.forEach { add(it) } })
                    put("tailscale", buildJsonArray { tailscaleUrls.forEach { add(it) } })
                }
            )
        }
    )

    @Test
    fun theRefreshThatFollowsAHelloAsksForInfoAndStoresTheAddresses() = runTest {
        val store = store()
        val requests = mutableListOf<DeviceApiRequest>()
        val refresh = LinkCandidateRefresh(
            store = store,
            appToken = { "app-token" },
            exchange = { request ->
                requests += request
                answer()
            }
        )

        refresh.refresh()

        assertEquals(1, requests.size)
        assertEquals("GET", requests[0].method)
        assertEquals("/app/v1/info", requests[0].path)
        assertEquals("app-token", requests[0].appToken)
        assertEquals(listOf(lan, tailscale), store.load().map { it.url })
        assertTrue(store.load().all { it.certSha256 == pin })
    }

    @Test
    fun oneRefreshPerConnectionAndOneMoreAfterTheLinkCameBack() = runTest {
        var exchanges = 0
        val refresh = LinkCandidateRefresh(
            store = store(),
            appToken = { "app-token" },
            exchange = {
                exchanges++
                answer()
            }
        )

        refresh.refresh()
        refresh.refresh()
        assertEquals(1, exchanges)

        refresh.linkDown()
        refresh.refresh()
        assertEquals(2, exchanges)
    }

    @Test
    fun aFailedAnswerIsOnlyLoggedAndLeavesTheStoreAlone() = runTest {
        val store = store()
        val refresh = LinkCandidateRefresh(
            store = store,
            appToken = { "app-token" },
            exchange = { throw IllegalStateException("link went away") }
        )

        refresh.refresh()

        assertTrue(store.load().isEmpty())
    }

    @Test
    fun anAnswerWithoutAPinStoresNothing() = runTest {
        val store = store()
        val refresh = LinkCandidateRefresh(
            store = store,
            appToken = { "app-token" },
            exchange = { answer(certificate = null) }
        )

        refresh.refresh()

        assertTrue(store.load().isEmpty())
    }

    @Test
    fun aLinkWithoutAnAppTokenNeverAsks() = runTest {
        val store = store()
        var asked = false
        val refresh = LinkCandidateRefresh(
            store = store,
            appToken = { null },
            exchange = {
                asked = true
                answer()
            }
        )

        refresh.refresh()

        assertEquals(false, asked)
        assertNull(store.pairingIdentity())
    }
}
