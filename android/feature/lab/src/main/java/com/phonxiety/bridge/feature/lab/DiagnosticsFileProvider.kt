package com.phonxiety.bridge.feature.lab

import androidx.core.content.FileProvider

/**
 * The lab's own provider.  A subclass, not the bare [FileProvider], because the updates provider
 * in `:core:data` is the same class with a different authority and the manifest merger treats one
 * class as one node.
 */
class DiagnosticsFileProvider : FileProvider()
