package com.phonxiety.bridge.data

import com.phonxiety.bridge.data.updates.ManifestParse
import com.phonxiety.bridge.data.updates.ReleaseSource
import com.phonxiety.bridge.data.updates.UpdateManifestCodec
import com.phonxiety.bridge.data.updates.source
import com.phonxiety.bridge.model.AppGateway
import com.phonxiety.bridge.model.AppGatewaySource
import com.phonxiety.bridge.model.AttachmentHeader
import com.phonxiety.bridge.model.AttachmentOutcome
import com.phonxiety.bridge.model.CallEvent
import com.phonxiety.bridge.model.CallEventsPage
import com.phonxiety.bridge.model.CallId
import com.phonxiety.bridge.model.CallSnapshot
import com.phonxiety.bridge.model.CallsPage
import com.phonxiety.bridge.model.CompatReport
import com.phonxiety.bridge.model.DaemonInfo
import com.phonxiety.bridge.model.DaemonStatus
import com.phonxiety.bridge.model.DeleteCallResult
import com.phonxiety.bridge.model.DeviceApiRequest
import com.phonxiety.bridge.model.DeviceApiResult
import com.phonxiety.bridge.model.DiagnosticsBundle
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
import java.io.OutputStream
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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

    @Test
    fun everyExchangeMatchesTheDaemonContract() = runTest {
        val checker = StructureChecker()
        val covered = mutableSetOf<String>()
        for (exchange in EXCHANGES) {
            val contract = load(exchange.file)
            val gateway = if (exchange.paged) ContractPageGateway(contract, exchange.file) else null
            val transport: AppApi = if (gateway == null) {
                RecordingTransport(contract)
            } else {
                EnvelopeTransport(
                    AppApiEnvelope(
                        AppGatewaySource { gateway },
                        pageBytes = PAGE_BYTES
                    )
                )
            }
            val api = DefaultDaemonApi(transport)
            val outcome = exchange.invoke(api, contract)
            if (transport is RecordingTransport) {
                assertEnvelope(exchange.file, contract, transport.captured)
            }
            covered += exchange.methods
            when (outcome) {
                is Outcome.Download -> assertDownload(exchange.file, contract, outcome, gateway!!)

                is Outcome.Completed -> {
                    // A write's success arm and the fields the fixture carries are asserted too:
                    // a status-and-shape check alone would let a wrong arm through.
                    val body = contract.responseBody.jsonObject
                    val status = when (val arm = outcome.arm) {
                        is DeleteOutcome.Deleted -> arm.status

                        is MarkOutcome.Marked -> {
                            val marks = body.getValue("marks").jsonArray
                            assertEquals(exchange.file + ": marks", marks.size, arm.marks.size)
                            assertEquals(
                                exchange.file + ": mark",
                                marks.first().jsonObject.getValue("mark").jsonPrimitive.content,
                                arm.marks.first().mark
                            )
                            arm.status
                        }

                        is HangUpOutcome.HungUp -> {
                            assertEquals(
                                exchange.file + ": call id",
                                body.getValue("call_id").jsonPrimitive.content,
                                arm.snapshot.callId
                            )
                            assertEquals(
                                exchange.file + ": state",
                                body.getValue("state").jsonPrimitive.content,
                                arm.snapshot.state
                            )
                            arm.status
                        }

                        is PauseOutcome.Paused -> {
                            assertEquals(
                                exchange.file + ": muted",
                                body.getValue("muted").jsonPrimitive.boolean,
                                arm.result.muted
                            )
                            arm.status
                        }

                        is ResumeOutcome.Resumed -> {
                            assertEquals(
                                exchange.file + ": muted",
                                body.getValue("muted").jsonPrimitive.boolean,
                                arm.result.muted
                            )
                            arm.status
                        }

                        else -> throw AssertionError(
                            exchange.file + ": not a success arm: " + arm::class.simpleName
                        )
                    }
                    assertEquals(exchange.file + ": status", contract.status, status)
                    checker.check(checkNotNull(exchange.descriptor), contract.responseBody, exchange.file)
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
        checker.verifyPresence()
        assertMethodCoverage(covered)
    }

    /** The update document is installed through the same codec the update screen calls. */
    @Test
    fun theUpdateManifestSatisfiesTheProductionCodec() {
        val contract = load("update_manifest_get.json")
        val parsed = UpdateManifestCodec.parse(contract.responseBody)
        val manifest = (parsed as? ManifestParse.Valid)?.manifest
            ?: throw AssertionError("the contracted manifest is not installable: $parsed")
        assertEquals("0.2.0", manifest.version)
        assertEquals(2L, manifest.versionCode)
        assertEquals(3, manifest.daemon.protocol)
        assertEquals(ReleaseSource.Daemon("apk"), manifest.apk.source())
        assertEquals("app.apk", manifest.apk.file)
        assertEquals(ReleaseSource.Daemon("module"), manifest.module.source())
        assertEquals("module.zip", manifest.module.file)
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
     * A paged download is the same request the contract records plus the production
     * page keys: the sink must hold the page's own bytes, and the page's `total` must
     * be what ended the loop.
     */
    private fun assertDownload(
        file: String,
        contract: Contract,
        outcome: Outcome.Download,
        gateway: ContractPageGateway
    ) {
        val success = outcome.result as? ApiResult.Success
        assertTrue("$file: expected success, got ${outcome.result}", success != null)
        assertEquals("$file: status", contract.status, success!!.status)
        val page = contract.responseBody.jsonObject
        val data = Base64.getDecoder().decode(page.pageData(file))
        val total = page.getValue("total").jsonPrimitive.content.toLong()
        assertArrayEquals("$file: the sink is not the contract page's bytes", data, outcome.sink.toByteArray())
        assertEquals("$file: the page's total is not what the sink holds", total, outcome.sink.size().toLong())
        val requests = gateway.requests
        assertEquals(
            "$file: one page per $PAGE_BYTES bytes of $total",
            (total + PAGE_BYTES - 1) / PAGE_BYTES,
            requests.size.toLong()
        )
        assertEquals(
            "$file: the page offsets did not drive the read",
            (0 until requests.size).map { index -> (index * PAGE_BYTES).toString() },
            requests.map { it.query["offset"] }
        )
        for (request in requests) {
            assertEquals("$file: method", contract.method, request.method)
            assertEquals("$file: path", contract.path, request.path)
            assertEquals(
                "$file: query",
                contract.query + mapOf("limit" to PAGE_BYTES.toString()),
                request.query - "offset"
            )
        }
    }

    /**
     * Every [DaemonApi] method must appear in an exchange, or be exempted with a
     * reason: an interface method the contracts never touch can silently drift.
     */
    private fun assertMethodCoverage(covered: Set<String>) {
        // A method with a value-class parameter is compiled under a mangled JVM name
        // (`pause-<hash>`). The hash changes with the parameter types, so compare the source
        // name only; the suffix never contains a dash of its own.
        val declared = DaemonApi::class.java.declaredMethods
            .filterNot { it.isSynthetic || it.name.contains('$') }
            .map { it.name.substringBefore('-') }
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
        val callId: CallId get() = CallId(path.split("/")[4])
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

        /** Downloads must run through the production paging decoder, never this fake. */
        override suspend fun download(
            path: String,
            query: Map<String, String>,
            sink: java.io.OutputStream
        ): ApiResult = throw AssertionError(
            "$path: a download exchange must go through the production paging decoder (paged = true)"
        )

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

    /** The same [AppApi] surface, backed by the production envelope and its page decoder. */
    private class EnvelopeTransport(private val envelope: AppApiEnvelope) : AppApi {
        override suspend fun request(
            method: String,
            path: String,
            query: Map<String, String>,
            body: JsonObject?
        ): ApiResult = envelope.request(method, path, query, body)

        override suspend fun download(
            path: String,
            query: Map<String, String>,
            sink: OutputStream
        ): ApiResult = envelope.download(path, query, sink)
    }

    /**
     * Answers the paged download from the contract's page: the whole artifact is the
     * page's decoded `data`, sliced by the `offset`/`limit` the production decoder asks
     * for, and every answer carries the contract's own `total`.
     */
    private class ContractPageGateway(contract: Contract, file: String) : AppGateway {
        private val page: JsonObject = contract.responseBody.jsonObject
        private val data: ByteArray = Base64.getDecoder().decode(page.pageData(file))
        private val total: Long = page.getValue("total").jsonPrimitive.content.toLong()
        val requests = mutableListOf<DeviceApiRequest>()

        override val appToken: StateFlow<String?> = MutableStateFlow<String?>(APP_TOKEN)
        override val supportsAttachments: Boolean = false

        override suspend fun exchange(request: DeviceApiRequest, deadlineMs: Long): DeviceApiResult {
            requests += request
            val offset = request.query["offset"]?.toLong() ?: 0L
            val limit = request.query["limit"]?.toInt() ?: data.size
            val start = minOf(offset, total).toInt()
            val end = minOf(total, offset + limit).toInt()
            val body = buildJsonObject {
                put("total", total)
                put("offset", offset)
                put("limit", limit)
                put("data", Base64.getEncoder().encodeToString(data.copyOfRange(start, end)))
            }
            return DeviceApiResult(id = request.id, status = 200, result = body)
        }

        override suspend fun receiveAttachment(
            header: AttachmentHeader,
            sink: OutputStream
        ): AttachmentOutcome = AttachmentOutcome.Unsupported
    }

    private sealed interface Outcome {
        data class Typed(val outcome: ApiOutcome<*>) : Outcome

        data class Download(val result: ApiResult, val sink: ByteArrayOutputStream) : Outcome

        /** A write verb answered with its sealed arm; the arm and its payload are asserted too. */
        data class Completed(val arm: Any) : Outcome
    }

    private data class Exchange(
        val file: String,
        val methods: Set<String>,
        val descriptor: SerialDescriptor? = null,
        /** Runs the file's download through the production paging decoder, not the fake. */
        val paged: Boolean = false,
        val invoke: suspend (DefaultDaemonApi, Contract) -> Outcome
    )

    /**
     * An element only some response variants carry: why, the files that may omit it,
     * and, when only particular objects may, those objects by their string `name`.
     */
    private class Allowance(
        val reason: String,
        val omittedIn: Set<String>,
        val omittedFor: Set<String>? = null
    )

    /**
     * Walks the decoded model against the file's JSON through the [SerialDescriptor]:
     * (a) every JSON key is an element or an [IGNORED] field, (b) every element
     * appears in every object decoded as that type unless [SOMETIMES] allows it to
     * varies, (c) an element no sample carries must be listed in [NEVER_SENT], (d) a
     * type whose objects are discriminated by a `type` field is checked per group,
     * (e) every enum value is a serial name exactly, and (f) a null is only ever a
     * nullable element.  `JsonElement`/`JsonObject` fields are not descended.
     */
    private class StructureChecker {
        /** [name] identifies an object whose type carries a string `name`; null otherwise. */
        private class Sample(
            val group: String?,
            val keys: Set<String>,
            val file: String,
            val name: String?
        )

        private val samples = LinkedHashMap<String, MutableList<Sample>>()
        private val descriptors = LinkedHashMap<String, SerialDescriptor>()

        fun check(descriptor: SerialDescriptor, element: JsonElement, file: String) {
            visit(descriptor, element, file, "")
        }

        fun verifyPresence() {
            val problems = mutableListOf<String>()
            for ((type, objects) in samples) {
                val descriptor = descriptors.getValue(type)
                val sometimes = SOMETIMES[type].orEmpty()
                val never = NEVER_SENT[type].orEmpty()
                val anywhere = objects.flatMap { it.keys }.toSet()
                val sampled = objects.map { it.file }.toSet()
                for ((key, allowance) in sometimes) {
                    if (key !in anywhere) {
                        problems += "$type lists $key in SOMETIMES, but no sample carries it"
                    }
                    for (file in allowance.omittedIn - sampled) {
                        problems += "$type lists $key as omittable in $file, but no $type sample" +
                            " came from that file"
                    }
                    val scoped = allowance.omittedFor
                    if (scoped == null) {
                        for (file in allowance.omittedIn) {
                            val fromFile = objects.filter { it.file == file }
                            if (fromFile.isNotEmpty() && fromFile.none { key !in it.keys }) {
                                problems += "$file: $type lists $key as omittable, but every sample" +
                                    " from that file carries it"
                            }
                        }
                    } else {
                        for (name in scoped) {
                            val named = objects.filter { it.file in allowance.omittedIn && it.name == name }
                            if (named.isEmpty()) {
                                problems += "$type lists $name in SOMETIMES, but no sample names it"
                            } else if (named.none { key !in it.keys }) {
                                problems += "$name: $type lists $key as omittable, but every sample" +
                                    " carrying that name has it"
                            }
                        }
                        for (sample in objects.filter { it.file in allowance.omittedIn }) {
                            if (key !in sample.keys && sample.name !in scoped) {
                                problems += "${sample.file}: $type \"${sample.name}\" omits $key," +
                                    " which SOMETIMES allows only for $scoped"
                            }
                        }
                    }
                }
                for (key in never.keys intersect anywhere) {
                    problems += "$type lists $key in NEVER_SENT, but " +
                        "a sample carries it: ${objects.first { key in it.keys }.file}"
                }
                if (type in DISCRIMINATED) {
                    for ((group, members) in objects.groupBy { it.group }) {
                        val seen = members.flatMap { it.keys }.toSet()
                        for (member in members) {
                            val missing = (seen - member.keys) - omittable(type, member)
                            if (missing.isNotEmpty()) {
                                problems += "${member.file}: $type \"$group\" misses $missing," +
                                    " which another sample of the same event kind carries"
                            }
                        }
                    }
                    val missing = descriptor.elementNames.filter { it !in anywhere && it !in never.keys }
                    if (missing.isNotEmpty()) {
                        problems += "$type never shows $missing in any sample;" +
                            " add a sample or list it in NEVER_SENT with a reason"
                    }
                } else {
                    for (sample in objects) {
                        val allowed = omittable(type, sample)
                        val missing = descriptor.elementNames.filter {
                            it !in sample.keys && it !in allowed && it !in never.keys
                        }
                        if (missing.isNotEmpty()) {
                            problems += "${sample.file}: $type misses $missing; every answer of a type" +
                                " carries every element, or SOMETIMES names the responses that may omit it"
                        }
                    }
                }
            }
            assertTrue(problems.joinToString("\n"), problems.isEmpty())
        }

        /** The elements [sample] may omit: a file-wide allowance, or one that names it. */
        private fun omittable(type: String, sample: Sample): Set<String> = SOMETIMES[type].orEmpty()
            .filter { (_, allowance) ->
                sample.file in allowance.omittedIn &&
                    (allowance.omittedFor == null || sample.name in allowance.omittedFor)
            }
            .keys

        private fun visit(
            descriptor: SerialDescriptor,
            element: JsonElement,
            file: String,
            path: String
        ) {
            if (element is JsonNull) {
                if (!descriptor.isNullable) {
                    fail(
                        "$file at ${path.ifEmpty { descriptor.serialName }}: null for a non-nullable" +
                            " ${descriptor.serialName}; the app's decoder would coerce it to a default"
                    )
                }
                return
            }
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
            val type = descriptor.serialName.removeSuffix("?")
            descriptors[type] = descriptor
            val group = if (type in DISCRIMINATED) {
                (obj["type"] as? JsonPrimitive)?.contentOrNull
                    ?: fail("$file at $path: ${descriptor.serialName} has no string \"type\"")
            } else {
                null
            }
            samples.getOrPut(type) { mutableListOf() }.add(
                Sample(group, obj.keys, file, (obj["name"] as? JsonPrimitive)?.contentOrNull)
            )
            val indexes = descriptor.elementNames.withIndex().associate { (index, name) -> name to index }
            for ((key, value) in obj) {
                val where = "$file at ${path.ifEmpty { descriptor.serialName }}"
                val index = indexes[key]
                if (index == null) {
                    if (IGNORED[type]?.containsKey(key) == true) continue
                    fail("${descriptor.serialName} has no element \"$key\" ($where); rename it here and in the daemon")
                }
                val child = if (path.isEmpty()) key else "$path.$key"
                visit(descriptor.getElementDescriptor(index!!), value, file, child)
            }
        }

        private fun fail(message: String): Nothing = throw AssertionError(message)
    }

    private companion object {
        /** The production page length is overridden so a short artifact still pages. */
        const val PAGE_BYTES = 4

        /** The app token the envelope needs; the contract files never carry it. */
        const val APP_TOKEN = "contract-app-token"

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
                Outcome.Completed(api.pause(c.callId))
            },
            Exchange("call_resume_post.json", setOf("resume"), PauseResult.serializer().descriptor) { api, c ->
                Outcome.Completed(api.resume(c.callId))
            },
            Exchange("call_summarize_post.json", setOf("summarize"), SummaryResult.serializer().descriptor) { api, c ->
                Outcome.Typed(api.summarize(c.callId))
            },
            Exchange("call_dtmf_post.json", setOf("dtmf"), DtmfResult.serializer().descriptor) { api, c ->
                Outcome.Typed(api.dtmf(c.callId, c.requestBody!!.getValue("digits").jsonPrimitive.content))
            },
            Exchange("call_hangup_post.json", setOf("hangup"), CallSnapshot.serializer().descriptor) { api, c ->
                Outcome.Completed(api.hangup(c.callId))
            },
            Exchange("call_marks_get.json", setOf("marks"), MarksResponse.serializer().descriptor) { api, c ->
                Outcome.Typed(api.marks(c.callId))
            },
            Exchange("call_marks_post.json", setOf("addMark"), MarksResponse.serializer().descriptor) { api, c ->
                val body = c.requestBody!!
                Outcome.Completed(
                    api.addMark(
                        c.callId,
                        body.getValue("mark").jsonPrimitive.content,
                        body.textOrNull("note")
                    )
                )
            },
            Exchange("call_delete.json", setOf("deleteCall"), DeleteCallResult.serializer().descriptor) { api, c ->
                Outcome.Completed(api.deleteCall(c.callId))
            },
            Exchange("call_audio_get.json", setOf("downloadAudio"), paged = true) { api, c ->
                val sink = ByteArrayOutputStream()
                Outcome.Download(api.downloadAudio(c.callId, c.query.getValue("format"), sink), sink)
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
                "diagnostics_bundle_post.json",
                setOf("createDiagnosticsBundle"),
                DiagnosticsBundle.serializer().descriptor
            ) { api, _ ->
                Outcome.Typed(api.createDiagnosticsBundle())
            },
            Exchange(
                "update_manifest_get.json",
                setOf("updateManifest"),
                JsonObject.serializer().descriptor
            ) { api, c ->
                Outcome.Typed(api.updateManifest(c.query["channel"]))
            },
            Exchange("update_artifact_get.json", setOf("downloadUpdateArtifact"), paged = true) { api, c ->
                val sink = ByteArrayOutputStream()
                Outcome.Download(api.downloadUpdateArtifact(c.query.getValue("name"), sink), sink)
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

        /** Types whose objects are a union tagged by a `type` field, checked per tag. */
        val DISCRIMINATED: Set<String> = setOf(CallEvent.serializer().descriptor.serialName)

        /**
         * Response fields the app deliberately ignores, per model type, with the
         * reason: daemon data the screens never render, so a rename is not a break.
         */
        val IGNORED: Map<String, Map<String, String>> = mapOf(
            LinkTestResult.serializer().descriptor.serialName to mapOf(
                "bitrate" to "a codec trial's bitrate is daemon-side Lab detail the app does not render"
            )
        )

        /**
         * Response elements only some variants carry: each names the exchanges that may
         * omit it, so an action answer's snapshot is never proof that a record read
         * still sends the key.  Every other sample of the type must carry it, and every
         * listed exchange must really omit it somewhere.
         */
        val SOMETIMES: Map<String, Map<String, Allowance>> = mapOf(
            CallSnapshot.serializer().descriptor.serialName to mapOf(
                "marks" to Allowance(
                    "only a record read carries the marks; an action answer is the bridge's snapshot",
                    setOf("calls_place.json", "test_call_post.json", "call_hangup_post.json")
                ),
                "audio_codec" to Allowance(
                    "only a record read names the negotiated codec",
                    setOf("calls_place.json", "test_call_post.json", "call_hangup_post.json")
                ),
                "request" to Allowance(
                    "only a record read echoes the stored request",
                    setOf("calls_place.json", "test_call_post.json", "call_hangup_post.json")
                ),
                "far_end" to Allowance(
                    "only a record read names the far end",
                    setOf("calls_place.json", "test_call_post.json", "call_hangup_post.json")
                ),
                "live" to Allowance(
                    "only the live record carries the bridge's live block",
                    setOf(
                        "calls_place.json",
                        "test_call_post.json",
                        "call_hangup_post.json",
                        "call_get_ended.json"
                    )
                ),
                "counters" to Allowance(
                    "only the live record carries the bridge's counters",
                    setOf(
                        "calls_place.json",
                        "test_call_post.json",
                        "call_hangup_post.json",
                        "call_get_ended.json"
                    )
                ),
                "confirmation_id" to Allowance(
                    "only a placed call echoes the caller's confirmation id",
                    setOf(
                        "test_call_post.json",
                        "call_get_live.json",
                        "call_get_ended.json",
                        "call_hangup_post.json"
                    )
                ),
                "profile" to Allowance(
                    "only a test call names the test profile",
                    setOf(
                        "calls_place.json",
                        "call_get_live.json",
                        "call_get_ended.json",
                        "call_hangup_post.json"
                    )
                ),
                "recording" to Allowance(
                    "only an ended record carries the recording descriptor",
                    setOf(
                        "calls_place.json",
                        "test_call_post.json",
                        "call_get_live.json",
                        "call_hangup_post.json"
                    )
                ),
                "next_cursor" to Allowance(
                    "an ended record's transcript is complete, so its read has no cursor",
                    setOf("call_get_ended.json")
                ),
                "has_more" to Allowance(
                    "an ended record's transcript is complete, so its read has no more pages",
                    setOf("call_get_ended.json")
                )
            ),
            ValidationResult.serializer().descriptor.serialName to mapOf(
                "request" to Allowance(
                    "an invalid request is not echoed back",
                    setOf("calls_validate_invalid.json")
                )
            ),
            ProbeCandidate.serializer().descriptor.serialName to mapOf(
                "detail" to Allowance(
                    "a candidate that was never measured has no detail line",
                    setOf("probe_post.json"),
                    omittedFor = setOf("tailscale")
                ),
                "addresses" to Allowance(
                    "only a path whose own address the daemon can name carries addresses",
                    setOf("probe_post.json"),
                    omittedFor = setOf("iroh", "tailscale")
                )
            )
        )

        /**
         * Model elements the current daemon never emits, with the reason: older
         * daemons and the long-poll stream own these shapes, so no exchange carries
         * them.  A sample that starts carrying one fails [StructureChecker].
         */
        val NEVER_SENT: Map<String, Map<String, String>> = mapOf(
            TranscriptTurn.serializer().descriptor.serialName to mapOf(
                "final" to "paged and archived turns carry no final flag; the long-poll event does"
            )
        )

        /** Interface methods no exchange can exercise without nondeterministic bytes. */
        val EXEMPT_METHODS: Map<String, String> = mapOf(
            "downloadBundle" to "the bundle's pages are the same zip; byte stream"
        )
    }
}

private val CONTRACT_JSON = Json { ignoreUnknownKeys = true }

/** The page's base64 payload, with a message that names the missing field. */
private fun JsonObject.pageData(file: String): String = (get("data") as? JsonPrimitive)?.contentOrNull
    ?: throw AssertionError("$file: the attachment page has no \"data\" field, which the paged decoder reads")

private fun JsonObject.textOrNull(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull

private fun DefaultDaemonApi.decodePlace(body: JsonObject?): PlaceCallRequest = json.decodeFromJsonElement(PlaceCallRequest.serializer(), body!!)
