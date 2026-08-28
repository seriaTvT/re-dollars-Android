package mk.ry.redollars.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen image viewer: pinch to zoom (1x–5x, temp shrink to 0.55x with spring-back),
 * drag to pan when zoomed, double-tap to toggle zoom, single-tap to toggle chrome / dismiss.
 * Chrome (download/collect/close) auto-hides after 2.2s; the image itself stays at
 * normal brightness (no dimming scrim behind the action buttons).
 */
@Composable
fun Lightbox(
    url: String,
    onDismiss: () -> Unit,
    onSaveSticker: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val scope = rememberCoroutineScope()
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val scaleAnim = remember { Animatable(1f) }
        var chromeVisible by remember { mutableStateOf(true) }
        var hideJob by remember { mutableStateOf<Job?>(null) }

        fun resetHideTimer() {
            hideJob?.cancel()
            hideJob = scope.launch {
                delay(2200)
                chromeVisible = false
            }
        }
        suspend fun animateScaleTo(target: Float) {
            scaleAnim.animateTo(target, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
            scale = target
        }
        fun showChrome() {
            chromeVisible = true
            resetHideTimer()
        }
        LaunchedEffect(chromeVisible) {
            if (chromeVisible) resetHideTimer()
        }
        // keep Animatable in sync, auto spring back when temporarily shrunk
        LaunchedEffect(scale) {
            scaleAnim.snapTo(scale)
            if (scale < 1f) {
                kotlinx.coroutines.delay(80)
                if (scale < 1f) {
                    animateScaleTo(1f)
                    offset = Offset.Zero
                }
            }
        }

        fun reset() {
            scale = 1f
            offset = Offset.Zero
            scope.launch { scaleAnim.snapTo(1f) }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.97f))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            if (scale > 1.05f) {
                                reset()
                                showChrome()
                            } else {
                                if (chromeVisible) {
                                    // if chrome visible, first hide it, second tap dismisses
                                    // to avoid accidental dismiss, require chrome hidden
                                    chromeVisible = false
                                    hideJob?.cancel()
                                } else {
                                    onDismiss()
                                }
                            }
                        },
                        onDoubleTap = {
                            showChrome()
                            if (scale > 1.05f) reset() else {
                                scale = 2.5f
                                scope.launch { scaleAnim.snapTo(2.5f) }
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        showChrome()
                        val tempMin = 0.55f
                        val maxScale = 5f
                        val next = (scale * zoom).coerceIn(tempMin, maxScale)
                        scale = next
                        // follow finger a bit when shrinking
                        offset = if (scale > 1f) offset + pan else pan * 0.35f
                        scope.launch { scaleAnim.snapTo(next) }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = url,
                contentDescription = "预览图片",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scaleAnim.value,
                        scaleY = scaleAnim.value,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
            )
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
            ) {
                FilledTonalIconButton(
                    onClick = onDismiss,
                    modifier = Modifier,
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }
            // Bottom capsule for actions (moved from top to avoid covering image top)
            if (onSaveSticker != null || onDownload != null) {
                AnimatedVisibility(
                    visible = chromeVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp),
                ) {
                    Row(
                        Modifier
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onDownload != null) {
                            FilledTonalIconButton(onClick = {
                                onDownload()
                                showChrome()
                            }) {
                                Icon(DownloadIcon, contentDescription = "保存图片到相册")
                            }
                        }
                        if (onSaveSticker != null) {
                            FilledTonalIconButton(
                                onClick = {
                                    onSaveSticker()
                                    onDismiss()
                                },
                            ) {
                                Icon(Icons.Filled.Star, contentDescription = "收藏为表情")
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Tabler/Material-style download glyph (arrow into a tray); drawn locally because the
 *  bundled material-icons-core set has no Download icon. */
private val DownloadIcon: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    ImageVector.Builder(
        name = "Download",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(5f, 20f)
            horizontalLineToRelative(14f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(-14f)
            close()
            moveTo(12f, 3f)
            verticalLineToRelative(9.59f)
            lineToRelative(3.29f, -3.3f)
            lineToRelative(1.42f, 1.42f)
            lineToRelative(-5.71f, 5.71f)
            lineToRelative(-5.71f, -5.71f)
            lineToRelative(1.42f, -1.42f)
            lineToRelative(3.29f, 3.3f)
            verticalLineToRelative(-9.59f)
            close()
        }
    }.build()
}
