package com.phonxiety.bridge.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A call's identity, as the daemon names it.
 *
 * The wrapper keeps a call id apart from every other free-form string a screen holds, so a
 * repository or API method cannot silently be handed a destination or a request id in its place.
 * The value itself is not validated here: the daemon already restricts ids, and the `/app/v1`
 * path encoder refuses anything that could change a path. The model's wire fields stay `String`
 * and are converted at the API boundary, so the bytes on the link are unchanged.
 */
@Serializable(with = CallIdSerializer::class)
@JvmInline
value class CallId(val value: String) {
    override fun toString(): String = value
}

/** Serializes a [CallId] as the plain string its wire fields carry. */
object CallIdSerializer : KSerializer<CallId> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.phonxiety.bridge.model.CallId", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: CallId) = encoder.encodeString(value.value)

    override fun deserialize(decoder: Decoder): CallId = CallId(decoder.decodeString())
}
