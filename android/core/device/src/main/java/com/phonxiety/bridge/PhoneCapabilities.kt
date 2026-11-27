package com.phonxiety.bridge

/**
 * What the phone can do inside a bridge call: exactly the two bits the daemon
 * learns from `hello`.  Read from permissions, never from stored state.
 */
internal data class PhoneCapabilities(
    val callControl: Boolean,
    val frameworkAudio: Boolean
)
