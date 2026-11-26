package com.phonxiety.bridge.feature.lab

import com.phonxiety.bridge.model.LinkTestResult

/** The Opus profiles and PCM a codec trial compares, cheapest first. */
data class CodecProfile(val codec: String, val bitrate: Int?)

val TRIAL_PROFILES: List<CodecProfile> = listOf(
    CodecProfile("opus", 16_000),
    CodecProfile("opus", 32_000),
    CodecProfile("opus", 64_000),
    CodecProfile("pcm", null)
)

private const val HEALTHY_P95_MS = 400L
private const val HEALTHY_LOSS = 0.02

/**
 * Picks the profile to keep from measured trial runs.
 *
 * Order of preference: the highest Opus bitrate that still has a healthy run (the far end hears
 * it better), then any healthy Opus, then the run with the least loss, then the first run that
 * came back at all. PCM only wins when nothing else did, because it costs 768 kbit/s on a
 * mobile link.
 */
fun recommendTrial(runs: List<CodecTrialRun>): CodecTrialRun? {
    if (runs.isEmpty()) return null
    val opus = runs.filter { it.codec == "opus" }
    val healthyOpus = opus.filter { it.result.isHealthy() }
    if (healthyOpus.isNotEmpty()) return healthyOpus.maxByOrNull { it.bitrate ?: 0 }
    val healthyOther = runs.filter { it.result.isHealthy() }
    if (healthyOther.isNotEmpty()) return healthyOther.first()
    val lost = runs.filter { it.result.loss != null }
    if (lost.isNotEmpty()) return lost.minByOrNull { it.result.loss ?: 1.0 }
    return runs.first()
}

/** Whether a run is good enough to rely on: enough frames came back and control was not queued. */
fun LinkTestResult.isHealthy(): Boolean {
    if (healthy) return true
    val p95Ms = p95 ?: return false
    val lossValue = loss ?: 0.0
    return p95Ms <= HEALTHY_P95_MS && lossValue <= HEALTHY_LOSS && (frames.echoed > 0)
}
