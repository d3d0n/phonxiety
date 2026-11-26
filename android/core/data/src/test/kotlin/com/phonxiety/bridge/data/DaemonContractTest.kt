package com.phonxiety.bridge.data

import android.content.Context
import com.phonxiety.bridge.model.CallEventsPage
import com.phonxiety.bridge.model.CallSnapshot
import com.phonxiety.bridge.model.CallsPage
import com.phonxiety.bridge.model.CompatReport
import com.phonxiety.bridge.model.DaemonInfo
import com.phonxiety.bridge.model.DaemonStatus
import com.phonxiety.bridge.model.DeleteCallResult
import com.phonxiety.bridge.model.DoctorFixResult
import com.phonxiety.bridge.model.DoctorReport
import com.phonxiety.bridge.model.DtmfResult
import com.phonxiety.bridge.model.InstructionResult
import com.phonxiety.bridge.model.LinkTestResult
import com.phonxiety.bridge.model.LinkTests
import com.phonxiety.bridge.model.LoopbackResult
import com.phonxiety.bridge.model.MarksResponse
import com.phonxiety.bridge.model.PauseResult
import com.phonxiety.bridge.model.PlaceCallRequest
import com.phonxiety.bridge.model.ProbeCandidate
import com.phonxiety.bridge.model.ProbeResult
import com.phonxiety.bridge.model.RotateResult
import com.phonxiety.bridge.model.SettingsSnapshot
import com.phonxiety.bridge.model.SettingsUpdate
import com.phonxiety.bridge.model.SummaryResult
import com.phonxiety.bridge.model.TestCallRequest
import com.phonxiety.bridge.model.ToneResult
import com.phonxiety.bridge.model.TranscriptPage
import com.phonxiety.bridge.model.TranscriptTurn
import com.phonxiety.bridge.model.ValidationResult
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The phone half of the shared `/app/v1` contracts in `contracts/app-api`.
 *
 * Every exchange runs the real [DefaultDaemonApi] method over a recording transport:
 * the transport's envelope must equal the file's request, and the answer must decode
 * and contain exactly the model's keys.  A renamed daemon field, a dropped model
 * field or an enum value the models do not know fails here instead of passing both
 * hand-written suites.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DaemonContractTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun everyExchangeMatchesTheDaemonContract() = runTest {
        val checker = StructureChecker()
        val covered = mutableSetOf<String>()
        for (exchange in EXCHANGES) {
            val contract = load(exchange.file)
            val transport = RecordingTransport(contract)
            val api = DefaultDaemonApi(transport, context)
            val outcome = exchange.invoke(api, contract)
            assertEnvelope(exchange.file, contract, transport.captured)
            covered += exchange.methods
            when (outcome) {
                is Outcome.Raw -> {
                    val result = outcome.result
                    assertTrue("${exchange.file}: expected success", result is ApiResult.Success)
                    assertEquals(exchange.file, contract.status, (result as ApiResult.Success).status)
                }

                is Outcome.Typed -> when (val typed = outcome.outcome) {
                    is ApiOutcome.Ok<*> -> {
                        assertEquals(exchange.file + ": status", contract.status, typed.status)
                        checker.check(checkNotNull(exchange.descriptor), contract.responseBody, exchange.file)
                    }

                    is ApiOutcome.Failure -> {
                        assertEquals(exchange.file + ": status", contract.status, typed.status)
                        val body = contract.responseBody.jsonObject
                        assertEquals(
                            exchange.file + ": code",
                            body.getValue("code").jsonPrimitive.content,
                            typed.code
                        )
                        assertEquals(
                            exchange.file + ": message",
                            body.getValue("message").jsonPrimitive.content,
                            typed.message
                        )
                    }
                }
            }
        }
        checker.verifyAbsent()
        assertMethodCoverage(covered)
    }

    private fun load(file: String): Contract {
        val stream = javaClass.classLoader!!.getResourceAsStream("app-api/$file")
        assertNotNull("$file is not on the test resource path", stream)
        return Contract(stream!!.bufferedReader().use { it.readText() })
    }

    private fun assertEnvelope(file: String, contract: Contract, captured: Captured?) {
        assertNotNull("$file: the daemon API never sent a request", captured)
        assertEquals("$file: method", contract.method, captured!!.method)
        assertEquals("$file: path", contract.path, captured.path)
        assertEquals("$file: query", contract.query, captured.query)
        assertEquals("$file: body", contract.requestBody, captured.body)
    }

    /**
     * Every [DaemonApi] method must appear in an exchange, or be exempted with a
     * reason: an interface method the contracts never touch can silently drift.
     */
    private fun assertMethodCoverage(covered: Set<String>) {
        val declared = DaemonApi::class.java.declaredMethods
            .filterNot { it.isSynthetic || it.name.contains('$') }
            .map { it.name }
            .toSet()
        val missing = declared - covered - EXEMPT_METHODS.keys
        assertEquals("no exchange exercises these methods: $missing", emptySet<String>(), missing)
        val stale = EXEMPT_METHODS.keys - declared
        assertEquals("exempt methods that no longer exist: $stale", emptySet<String>(), stale)
    }

    private class Contract(text: String) {
        private val root: JsonObject = CONTRACT_JSON.parseToJsonElement(text).jsonObject
        val request: JsonObject = root.getValue("request").jsonObject
        private val response: JsonObject = root.getValue("response").jsonObject
        val status: Int = response.getValue("status").jsonPrimitive.content.toInt()
        val responseBody: JsonElement = response.getValue("body")
        val method: String = request.getValue("method").jsonPrimitive.content
        val path: String = request.getValue("path").jsonPrimitive.content
        val query: Map<String, String> = (request["query"] as? JsonObject)
            ?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
        val requestBody: JsonObject? = request["body"] as? JsonObject
        val callId: String get() = path.split("/")[4]
    }

    private data class Captured(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val body: JsonObject?
    )

    private class RecordingTransport(private val contract: Contract) : AppApi {
        var captured: Captured? = null

        override suspend fun request(
            method: String,
            path: String,
            query: Map<String, String>,
            body: JsonObject?
        ): ApiResult {
            captured = Captured(method, path, query, body)
            return reply()
        }

        override suspend fun download(
            path: String,
            query: Map<String, String>,
            sink: java.io.OutputStream
        ): ApiResult {
            captured = Captured("GET", path, query, null)
            return reply()
        }

        private fun reply(): ApiResult {
            val body = contract.responseBody
            return if (contract.status in 200..299) {
                ApiResult.Success(contract.status, body)
            } else {
                val error = body.jsonObject
                ApiResult.Failure(
                    contract.status,
                    error.getValue("code").jsonPrimitive.content,
                    error.getValue("message").jsonPrimitive.content
                )
            }
        }
    }

    private sealed interface Outcome {
        data class Typed(val outcome: ApiOutcome<*>) : Outcome
        data class Raw(val result: ApiResult) : Outcome
    }

    private data class Exchange(
        val file: String,
        val methods: Set<String>,
        val descriptor: SerialDescriptor? = null,
        val invoke: suspend (DefaultDaemonApi, Contract) -> Outcome
    )

    /**
     * Walks the decoded model against the file's JSON through the [SerialDescriptor]:
     * (a) every JSON key is an element or an [IGNORED] field, (b) every element
     * appears in at least one sample or is [ABSENT_OK], (c) every enum value is a
     * serial name exactly.  `JsonElement`/`JsonObject` fields are not descended.
     */
    private class StructureChecker {
        private val samples = LinkedHashMap<String, MutableList<Set<String>>>()
        private val descriptors = LinkedHashMap<String, SerialDescriptor>()

        fun check(descriptor: SerialDescriptor, element: JsonElement, file: String) {
            visit(descriptor, element, file, "")
        }

        fun verifyAbsent() {
            for ((type, objects) in samples) {
                val descriptor = descriptors.getValue(type)
                val seen = objects.flatten().toSet()
                val allowed = ABSENT_OK[type].orEmpty().keys
                val missing = descriptor.elementNames.filter { it !in seen && it !in allowed }
                assertTrue(
                    "$type never shows $missing in any sample; add a sample or list it in ABSENT_OK with a reason",
                    missing.isEmpty()
                )
            }
        }

        private fun visit(
            descriptor: SerialDescriptor,
            element: JsonElement,
            file: String,
            path: String
        ) {
            if (descriptor.serialName.startsWith("kotlinx.serialization.json.")) return
            when (descriptor.kind) {
                SerialKind.CONTEXTUAL -> return

                SerialKind.ENUM -> {
                    val where = "$file at ${path.ifEmpty { descriptor.serialName }}"
                    val text = (element as? JsonPrimitive)?.contentOrNull
                        ?: fail("$where: the enum value is not a string")
                    val names = descriptor.elementNames.toSet()
                    if (text !in names) {
                        fail("$where: \"$text\" is not a serial name of ${descriptor.serialName} ($names)")
                    }
                    return
                }

                StructureKind.LIST -> {
                    val values = element as? JsonArray ?: return
                    values.forEachIndexed { index, item ->
                        visit(descriptor.getElementDescriptor(0), item, file, "$path[$index]")
                    }
                    return
                }

                StructureKind.MAP -> return

                StructureKind.CLASS, StructureKind.OBJECT -> Unit

                else -> return
            }
            val obj = element as? JsonObject ?: fail("$file at $path: expected an object")
            descriptors[descriptor.serialName] = descriptor
            samples.getOrPut(descriptor.serialName) { mutableListOf() }.add(obj.keys.toSet())
            val indexes = descriptor.elementNames.withIndex().associate { (index, name) -> name to index }
            for ((key, value) in obj) {
                val where = "$file at ${path.ifEmpty { descriptor.serialName }}"
                val index = indexes[key]
                if (index == null) {
                    if (IGNORED[descriptor.serialName]?.containsKey(key) == true) continue
                    fail("${descriptor.serialName} has no element \"$key\" ($where); rename it here and in the daemon")
                }
                val child = if (path.isEmpty()) key else "$path.$key"
                visit(descriptor.getElementDescriptor(index!!), value, file, child)
            }
        }

        private fun fail(message: String): Nothing = throw AssertionError(message)
    }

    private companion object {
        val EXCHANGES: List<Exchange> = listOf(
            Exchange("info_get.json", setOf("info"), DaemonInfo.serializer().descriptor) { api, _ ->
                Outcome.Typed(api.info())
            },
            Exchange("status_get.json", setOf("status"), DaemonStatus.serializer().descriptor) { api, _ ->
                Outcome.Typed(api.status())
            },
            Exchange("link_test_post.json", setOf("linkTest"), LinkTestResult.serializer().descriptor) { api, c ->
                Outcome.Typed(api.linkTest(seconds = c.requestBody?.textOrNull("seconds")?.toDouble()))
            },
            Exchange("link_tests_get.json", setOf("linkTests"), LinkTests.serializer().descriptor) { api, c ->
                Outcome.Typed(api.linkTests(limit = c.query["limit"]?.toInt()))
            },
            Exchange("probe_post.json", setOf("probe"), ProbeResult.serializer().descriptor) { api, c ->
                val candidates = (c.requestBody?.get("candidates") as? JsonArray)
                    ?.map { it.jsonPrimitive.content }
                Outcome.Typed(api.probe(candidates))
            },
            Exchange("calls_get.json", setOf("calls"), CallsPage.serializer().descriptor) { api, c ->
                Outcome.Typed(api.calls(limit = c.query["limit"]?.toInt()))
            },
            Exchange("calls_place.json", setOf("placeCall"), CallSnapshot.serializer().descriptor) { api, c ->
                Outcome.Typed(api.placeCall(api.decodePlace(c.requestBody)))
            },
            Exchange(
                "calls_place_missing_confirmation.json",
                setOf("placeCall"),
                CallSnapshot.serializer().descriptor
            ) { api, c ->
                Outcome.Typed(api.placeCall(api.decodePlace(c.requestBody)))
            },
            Exchange("calls_validate_ok.json", setOf("validateCall"), ValidationResult.serializer().descriptor) { api, c ->
                Outcome.Typed(api.validateCall(api.decodePlace(c.requestBody)))
            },
            Exchange(
                "calls_validate_invalid.json",
                setOf("validateCall"),
                ValidationResult.serializer().descriptor
            ) { api, c ->
                Outcome.Typed(api.validateCall(api.decodePlace(c.requestBody)))
            },
            Exchange("calls_get_unknown.json", setOf("call"), CallSnapshot.serializer().descriptor) { api, c ->
                Outcome.Typed(api.call(c.callId))
            },
            Exchange(
                "calls_validate_wrong_method.json",
                setOf("call"),
                CallSnapshot.serializer().descriptor
            ) { api, c ->
                Outcome.Typed(api.call(c.callId))
            },
            Exchange("test_call_post.json", setOf("testCall"), CallSnapshot.serializer().descriptor) { api, c ->
                Outcome.Typed(api.testCall(api.json.decodeFromJsonElement(TestCallRequest.serializer(), c.requestBody!!)))
            },
            Exchange("call_get_live.json", setOf("call"), CallSnapshot.serializer().descriptor) { api, c ->
                Outcome.Typed(api.call(c.callId))
            },
            Exchange("call_get_ended.json", setOf("call"), CallSnapshot.serializer().descriptor) { api, c ->
                Outcome.Typed(api.call(c.callId))
            },
            Exchange("call_transcript_get.json", setOf("transcript"), TranscriptPage.serializer().descriptor) { api, c ->
                Outcome.Typed(
                    api.transcript(
                        c.callId,
                        cursor = c.query["cursor"]?.toLong(),
                        limit = c.query["limit"]?.toInt()
                    )
                )
            },
            Exchange("call_events_live.json", setOf("events"), CallEventsPage.serializer().descriptor) { api, c ->
                Outcome.Typed(
                    api.events(
                        c.callId,
                        since = c.query["since"]?.toLong(),
                        waitSeconds = c.query["wait_s"]?.toDouble()
                    )
                )
            },
            Exchange("call_events_ended.json", setOf("events"), CallEventsPage.serializer().descriptor) { api, c ->
                Outcome.Typed(
                    api.events(
                        c.callId,
                        since = c.query["since"]?.toLong(),
                        waitSeconds = c.query["wait_s"]?.toDouble()
                    )
                )
            },
            Exchange(
                "call_instruction_post.json",
                setOf("instruction"),
                InstructionResult.serializer().descriptor
            ) { api, c ->
                Outcome.Typed(api.instruction(c.callId, c.requestBody!!.getValue("text").jsonPrimitive.content))
            },
            Exchange("call_pause_post.json", setOf("pause"), PauseResult.serializer().descriptor) { api, c ->
                Outcome.Typed(api.pause(c.callId))
            },
            Exchange("call_resume_post.json", setOf("resume"), PauseResult.serializer().descriptor) { api, c ->
                Outcome.Typed(api.resume(c.callId))
            },
            Exchange("call_summarize_post.json", setOf("summarize"), SummaryResult.serializer().descriptor) { api, c ->
                Outcome.Typed(api.summarize(c.callId))
            },
            Exchange("call_dtmf_post.json", setOf("dtmf"), DtmfResult.serializer().descriptor) { api, c ->
                Outcome.Typed(api.dtmf(c.callId, c.requestBody!!.getValue("digits").jsonPrimitive.content))
            },
            Exchange("call_hangup_post.json", setOf("hangup"), CallSnapshot.serializer().descriptor) { api, c ->
                Outcome.Typed(api.hangup(c.callId))
            },
            Exchange("call_marks_get.json", setOf("marks"), MarksResponse.serializer().descriptor) { api, c ->
                Outcome.Typed(api.marks(c.callId))
            },
            Exchange("call_marks_post.json", setOf("addMark"), MarksResponse.serializer().descriptor) { api, c ->
                val body = c.requestBody!!
                Outcome.Typed(
                    api.addMark(
                        c.callId,
                        body.getValue("mark").jsonPrimitive.content,
                        body.textOrNull("note")
                    )
                )
            },
            Exchange("call_delete.json", setOf("deleteCall"), DeleteCallResult.serializer().descriptor) { api, c ->
                Outcome.Typed(api.deleteCall(c.callId))
            },
            Exchange("call_audio_get.json", setOf("downloadAudio")) { api, c ->
                Outcome.Raw(api.downloadAudio(c.callId, c.query.getValue("format"), ByteArrayOutputStream()))
            },
            Exchange("settings_get.json", setOf("settings"), SettingsSnapshot.serializer().descriptor) { api, _ ->
                Outcome.Typed(api.settings())
            },
            Exchange("settings_put.json", setOf("putSettings"), SettingsUpdate.serializer().descriptor) { api, c ->
                Outcome.Typed(api.putSettings(c.requestBody!!.getValue("values").jsonObject))
            },
            Exchange(
                "settings_put_invalid.json",
                setOf("putSettings"),
                SettingsUpdate.serializer().descriptor
            ) { api, c ->
                Outcome.Typed(api.putSettings(c.requestBody!!.getValue("values").jsonObject))
            },
            Exchange("loopback_post.json", setOf("loopback"), LoopbackResult.serializer().descriptor) { api, c ->
                val enabled = c.requestBody?.textOrNull("enabled")?.toBooleanStrict() ?: true
                Outcome.Typed(api.loopback(enabled))
            },
            Exchange("tone_post.json", setOf("tone"), ToneResult.serializer().descriptor) { api, c ->
                Outcome.Typed(
                    api.tone(
                        hz = c.requestBody?.textOrNull("hz")?.toInt(),
                        dbfs = c.requestBody?.textOrNull("dbfs")?.toDouble()
                    )
                )
            },
            Exchange("doctor_get.json", setOf("doctor"), DoctorReport.serializer().descriptor) { api, _ ->
                Outcome.Typed(api.doctor())
            },
            Exchange("doctor_fix_post.json", setOf("doctorFix"), DoctorFixResult.serializer().descriptor) { api, c ->
                Outcome.Typed(api.doctorFix(c.requestBody!!.getValue("fix").jsonPrimitive.content))
            },
            Exchange("diagnostics_get.json", setOf("diagnostics"), JsonObject.serializer().descriptor) { api, _ ->
                Outcome.Typed(api.diagnostics())
            },
            Exchange(
                "update_manifest_get.json",
                setOf("updateManifest"),
                JsonObject.serializer().descriptor
            ) { api, c ->
                Outcome.Typed(api.updateManifest(c.query["channel"]))
            },
            Exchange("update_artifact_get.json", setOf("downloadUpdateArtifact")) { api, c ->
                Outcome.Raw(
                    api.downloadUpdateArtifact(c.query.getValue("name"), ByteArrayOutputStream())
                )
            },
            Exchange("compat_get.json", setOf("compat"), CompatReport.serializer().descriptor) { api, c ->
                Outcome.Typed(
                    api.compat(
                        appApi = c.query["app_api"]?.toInt(),
                        protocol = c.query["protocol"]?.toInt()
                    )
                )
            },
            Exchange("pair_rotate_post.json", setOf("rotatePair"), RotateResult.serializer().descriptor) { api, _ ->
                Outcome.Typed(api.rotatePair(false))
            }
        )

        /**
         * Response fields the app deliberately ignores, per model type, with the
         * reason: daemon data the screens never render, so a rename is not a break.
         */
        val IGNORED: Map<String, Map<String, String>> = mapOf(
            LinkTestResult.serializer().descriptor.serialName to mapOf(
                "bitrate" to "a codec trial's bitrate is daemon-side Lab detail the app does not render"
            ),
            ProbeCandidate.serializer().descriptor.serialName to mapOf(
                "addresses" to "the daemon reports a list; the app's single `address` field is legacy"
            )
        )

        /**
         * Model elements the current daemon never emits, with the reason: older
         * daemons and the long-poll stream own these shapes, so there is no sample.
         */
        val ABSENT_OK: Map<String, Map<String, String>> = mapOf(
            TranscriptTurn.serializer().descriptor.serialName to mapOf(
                "final" to "paged and archived turns carry no final flag; only long-poll events do"
            ),
            ProbeCandidate.serializer().descriptor.serialName to mapOf(
                "address" to "older daemon shape: the current daemon reports `addresses` instead"
            )
        )

        /** Interface methods no exchange can exercise without nondeterministic bytes. */
        val EXEMPT_METHODS: Map<String, String> = mapOf(
            "createDiagnosticsBundle" to "the zip's size encodes this run's state paths; byte stream",
            "downloadBundle" to "the same zip page by page; byte stream"
        )
    }
}

private val CONTRACT_JSON = Json { ignoreUnknownKeys = true }

private fun JsonObject.textOrNull(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull

private fun DefaultDaemonApi.decodePlace(body: JsonObject?): PlaceCallRequest = json.decodeFromJsonElement(PlaceCallRequest.serializer(), body!!)
