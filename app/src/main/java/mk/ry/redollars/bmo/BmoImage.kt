package mk.ry.redollars.bmo

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap

/**
 * A composited `(bmoC…)` emoji at whatever size [modifier] dictates. The 63×63
 * pixel-art bitmap is drawn unfiltered; while compositing (or on failure) the
 * space is reserved so surrounding layout doesn't jump.
 */
@Composable
fun BmoImage(code: String, modifier: Modifier = Modifier) {
    val renderer = LocalBmoRenderer.current
    val bitmap by produceState<ImageBitmap?>(null, code, renderer) {
        value = renderer?.render(code)
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = code,
            filterQuality = FilterQuality.None,
            modifier = modifier,
        )
    } else {
        Box(modifier)
    }
}
