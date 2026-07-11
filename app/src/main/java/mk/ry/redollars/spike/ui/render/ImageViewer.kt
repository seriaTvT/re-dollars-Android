package mk.ry.redollars.spike.ui.render

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Provided by the app root so any rendered image (block images, later reply thumbnails)
 * can request the full-screen viewer without threading a callback through every layer.
 */
val LocalImageViewer = staticCompositionLocalOf<(String) -> Unit> { {} }
