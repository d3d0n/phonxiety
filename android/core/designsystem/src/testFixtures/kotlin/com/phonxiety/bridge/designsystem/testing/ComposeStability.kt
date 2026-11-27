package com.phonxiety.bridge.designsystem.testing

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import org.junit.Assert.fail

/**
 * The stability promises a screen's state holders make to the Compose compiler.
 *
 * `@Immutable` and `@Stable` are binary-retained, so `Class.getAnnotations()` cannot see them and a
 * reflection test could not tell an annotated holder from a stripped one. The class file can: an
 * annotation type reaches a class file only as an annotation, so the constant pool mentioning
 * `androidx.compose.runtime.Immutable` is the same fact as the class carrying it.
 *
 * The expectations these helpers check are the ones a report run proved: keep a holder here only
 * while `-Pphonxiety.composeReports` shows its annotation changes the compiler's verdict.
 */
object ComposeStability {
    private const val IMMUTABLE = "Landroidx/compose/runtime/Immutable;"
    private const val STABLE = "Landroidx/compose/runtime/Stable;"

    /** True when [type] carries `@Immutable` or `@Stable` in its own class file. */
    fun isMarkedStable(type: Class<*>): Boolean = mentions(type, IMMUTABLE) || mentions(type, STABLE)

    private fun mentions(type: Class<*>, descriptor: String): Boolean {
        val resource = type.name.replace('.', '/') + ".class"
        val bytes = type.classLoader?.getResourceAsStream(resource)?.use { it.readBytes() }
            ?: error("$resource is not on the test classpath")
        return bytes.containsAscii(descriptor)
    }

    private fun ByteArray.containsAscii(text: String): Boolean {
        val needle = text.toByteArray(Charsets.US_ASCII)
        for (start in 0..size - needle.size) {
            var matched = true
            for (offset in needle.indices) {
                if (this[start + offset] != needle[offset]) {
                    matched = false
                    break
                }
            }
            if (matched) return true
        }
        return false
    }

    /** A Kotlin `var` is the one field that breaks the promise every kept annotation makes. */
    fun mutableFields(type: Class<*>): List<Field> = type.declaredFields
        .filterNot { Modifier.isStatic(it.modifiers) }
        .filterNot { Modifier.isFinal(it.modifiers) }
}

/**
 * Asserts each holder is annotated stable and has no `var` anywhere on it: the annotation is a
 * promise that the whole object graph is read-only and replaced wholesale, and half of that promise
 * is checkable here.
 */
fun assertStableHolders(vararg holders: Class<*>) {
    val faults = holders.mapNotNull { holder ->
        val mutable = ComposeStability.mutableFields(holder).map { it.name }
        when {
            !ComposeStability.isMarkedStable(holder) ->
                "${holder.name} carries no @Immutable or @Stable: a Compose report run showed the " +
                    "annotation changes its verdict, and this test is what keeps it from being dropped"

            mutable.isNotEmpty() -> "${holder.name} declares mutable field(s) $mutable"

            else -> null
        }
    }
    if (faults.isNotEmpty()) fail(faults.joinToString("\n"))
}
