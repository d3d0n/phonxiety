package com.phonxiety.bridge.engine

import com.phonxiety.bridge.model.LinkKind

/**
 * What the live link has already been told, shared by the hello writer, the
 * route publisher and the settings that may only push after a handshake.
 */
internal class HelloState(kind: LinkKind) {
    /** True once the live link's hello was accepted by the transport. */
    @Volatile var delivered: Boolean = false
    var kind: LinkKind = kind

    /** The route last accepted on the wire, so an unchanged one is not repeated. */
    @Volatile var lastRouteKey: String? = null
}
