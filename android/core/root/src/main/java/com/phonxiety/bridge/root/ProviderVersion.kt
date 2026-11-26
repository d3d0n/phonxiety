package com.phonxiety.bridge.root

/**
 * A root provider's own version, parsed from what its CLI prints. The four CLIs answer in four
 * shapes:
 *
 * ```
 * magisk -v   31.1:MAGISK:R          version name, then the build flavour
 * magisk -V   31100                  build code
 * ksud -V     ksud 3.1.0 (uapi: 2)   command name, version name, kernel uapi
 * apd -V      apd 11125              command name, build code
 * ```
 *
 * The wizard shows [`Magisk 31.1 (31100)`][label] instead of handing the device's raw line to the
 * screen, so the name and the code stay separate here.
 */
data class ProviderVersion(val name: String?, val code: Int?) {
    /** `31.1 (31100)`, `3.1.0`, or `11125`; null when the output carried no version at all. */
    val label: String?
        get() = when {
            name != null && code != null -> "$name ($code)"
            else -> name ?: code?.toString()
        }

    companion object {
        /**
         * Reads one command's answer. A leading command name (`ksud`, `apd`) is dropped, a `:`
         * suffix inside the version token (`31.1:MAGISK:R`) is dropped, and `(uapi: 2)` is not
         * mistaken for a build code because only a parenthesised number or a lone number counts.
         */
        fun parse(output: String?): ProviderVersion? {
            val line = output
                ?.lineSequence()
                ?.map { it.trim() }
                ?.firstOrNull { it.isNotEmpty() }
                ?: return null
            val tokens = line.split(WHITESPACE).filter { it.isNotEmpty() }
            // `ksud` and `apd` print their own name first; `magisk` prints the bare value.
            val values = if (tokens.size > 1 && tokens.first().none { it.isDigit() }) tokens.drop(1) else tokens
            val name = values.firstNotNullOfOrNull(::versionName)
            val code = TRAILING_CODE.find(line)?.groupValues?.get(1)?.toIntOrNull()
                ?: values.singleOrNull()?.toIntOrNull()
            return if (name == null && code == null) null else ProviderVersion(name, code)
        }

        /** `31.1`, `3.1.0` or `1.0.7-45-gabc`: a `v`-stripped dotted number before any `:`. */
        private fun versionName(token: String): String? {
            val head = token.substringBefore(':').removePrefix("v").trim(',')
            return head.takeIf { DOTTED.matches(it) }
        }

        private val WHITESPACE = Regex("\\s+")

        private val DOTTED = Regex("\\d+(\\.\\d+)+([-+.~][0-9A-Za-z.+~-]+)?")

        private val TRAILING_CODE = Regex("\\((\\d+)\\)\\s*$")
    }
}

/**
 * One version label from the answers of several commands (`magisk -v` and `-V`): the first name and
 * the first code found win, so a provider that splits its answer is still shown whole.
 */
fun parseProviderVersion(vararg outputs: String?): String? {
    var name: String? = null
    var code: Int? = null
    for (output in outputs) {
        val parsed = ProviderVersion.parse(output) ?: continue
        if (name == null) name = parsed.name
        if (code == null) code = parsed.code
    }
    return ProviderVersion(name, code).label
}
