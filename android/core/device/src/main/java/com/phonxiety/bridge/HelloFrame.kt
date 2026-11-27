package com.phonxiety.bridge

import com.phonxiety.bridge.model.DeviceActiveCall
import com.phonxiety.bridge.model.DeviceCapabilities
import com.phonxiety.bridge.model.DeviceOutbound

/** The `hello` a link sends first: the phone's identity, call capabilities and preferences. */
internal fun helloFrame(
    capabilities: PhoneCapabilities,
    device: String,
    bootId: String,
    busy: Boolean,
    activeCall: DeviceActiveCall?,
    preferredCodec: String,
    maxBitrate: Int,
    adbTunnel: Boolean
): DeviceOutbound.Hello = DeviceOutbound.Hello(
    device = device,
    bootId = bootId,
    capabilities = DeviceCapabilities(
        callControl = capabilities.callControl,
        frameworkAudio = capabilities.frameworkAudio,
        busy = busy,
        linkTest = true,
        adbTunnel = adbTunnel,
        audioCodecs = listOf("pcm", "opus")
    ),
    activeCall = activeCall,
    preferredCodec = preferredCodec,
    maxBitrate = maxBitrate
)
