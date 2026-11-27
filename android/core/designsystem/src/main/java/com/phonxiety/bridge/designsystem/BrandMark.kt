package com.phonxiety.bridge.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/** The glow state a [BrandMark] actually drew, readable from tests and tooling. */
val BrandGlowKey = SemanticsPropertyKey<Boolean>("BrandGlow")

/** Whether this node's mark drew its glow. */
var SemanticsPropertyReceiver.brandGlow by BrandGlowKey

/**
 * The brand's arrow. Signal on dark, its darkened twin on light; the glow exists only on dark, so
 * a mark placed on Paper never gains a halo. The caller may size it, but never below 16 dp, and
 * the aspect ratio stays 1:1: the mark is not stretched.
 */
@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    glow: Boolean = true
) {
    val brand = LocalBrand.current
    val effectiveGlow = glow && brand.glowAllowed
    Canvas(
        modifier = Modifier
            .sizeIn(minWidth = BrandMarkMinSize, minHeight = BrandMarkMinSize)
            .then(modifier)
            .aspectRatio(1f)
            .semantics {
                brandGlow = effectiveGlow
                // The mark is decorative unless a caller names it; the lockup already names the
                // brand, so the arrow inside it never repeats the name.
                if (contentDescription != null) this.contentDescription = contentDescription
            }
    ) {
        val side = min(size.width, size.height)
        val origin = Offset((size.width - side) / 2f, (size.height - side) / 2f)
        with(BrandArrow) {
            drawMark(origin = origin, side = side, color = brand.mark, glow = effectiveGlow)
        }
    }
}

/**
 * The arrow plus the wordmark, with the kit's spacing and the mark's clear space kept. The word
 * is never shorter than 18 dp; the whole lockup scales from that height, unrotated and un-tinted
 * beyond the two brand versions.
 */
@Composable
fun BrandLockup(
    modifier: Modifier = Modifier,
    contentDescription: String? = "phonxiety",
    glow: Boolean = true,
    height: Dp = 24.dp
) {
    val brand = LocalBrand.current
    val wordHeight = maxOf(height, BrandWordMinHeight)
    val markBox = wordHeight * 0.86f
    Row(
        modifier = modifier
            .heightIn(min = BrandWordMinHeight)
            .height(wordHeight)
            .semantics(mergeDescendants = true) {
                if (contentDescription != null) this.contentDescription = contentDescription
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        BrandMark(modifier = Modifier.size(markBox), contentDescription = null, glow = glow)
        // The kit starts the word after a gap of about half the mark's own clear space; the
        // mark's 140-unit box already carries its inner clear space.
        Spacer(Modifier.width(wordHeight * 0.25f))
        Image(
            painter = painterResource(brand.wordmark),
            contentDescription = null,
            modifier = Modifier
                .height(wordHeight)
                .aspectRatio(456.6f / 117f)
        )
    }
}
