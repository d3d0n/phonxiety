package com.phonxiety.bridge.feature.dashboard

/**
 * The endpoint id as a row shows it: the first eight and last four characters, so two ids are told
 * apart without printing a key that fills the row. The dashboard keeps the whole id one disclosure
 * away, and no shorter form is ever the stored value.
 */
internal fun shortEndpoint(id: String): String = if (id.length <= 12) id else "${id.take(8)}…${id.takeLast(4)}"
