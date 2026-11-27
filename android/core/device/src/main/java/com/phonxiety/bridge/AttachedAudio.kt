package com.phonxiety.bridge

/**
 * The audio of the owned call together with the store stream its stats belong
 * to.  They are one value so every teardown can name the stream it actually
 * detached: a cleanup that finds nothing has nothing to reset.
 */
internal class AttachedAudio(val audio: PhoneAudio, val stream: AudioStream)
