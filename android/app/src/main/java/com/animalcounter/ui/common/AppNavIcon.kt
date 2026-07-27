package com.animalcounter.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.animalcounter.R

/**
 * The app launcher icon, sized for use as the leading `navigationIcon` of a
 * Material 3 top app bar — always present top-left, in front of the screen
 * title, at a size that suits the bar.
 *
 * Renders only the launcher **foreground** (the pig, a raster PNG on
 * transparent), enlarged so it reads well in the bar, with NO background
 * (the launcher background color is intentionally omitted per design).
 *
 * We reference `R.drawable.ic_launcher_foreground` (a PNG) and NOT
 * `R.mipmap.ic_launcher*` — on API 26+ the mipmap resolves to an
 * `<adaptive-icon>` XML and `painterResource` only handles `<vector>`/raster
 * assets, throwing `IllegalArgumentException: Only VectorDrawables and
 * rasterized asset types are supported` for an adaptive icon (which crashed
 * the app at launch). The foreground PNG is drawn at 56 dp inside a 44 dp
 * clipped box so the pig (~72% of the canvas) fills ~40 dp with no background.
 */
@Composable
fun AppNavIcon(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(44.dp).clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(56.dp),
        )
    }
}