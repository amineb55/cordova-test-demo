package com.actionverite.live.feature.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.actionverite.live.domain.model.ConnectionQuality
import com.actionverite.live.domain.model.VideoGridLayout
import com.actionverite.live.ui.components.Avatar

/**
 * Adaptive video wall for 2–6 players. Renders [VideoGridLayout.rows] rows by
 * [VideoGridLayout.columns] columns of equal-weight [PlayerTile]s, letting the final
 * row be short when the player count doesn't fill the grid. The [layout] itself is
 * computed in the domain by `ComputeVideoGridLayoutUseCase`, so this composable only
 * does the rendering.
 *
 * @param activeUid uid of the player whose turn it is; that tile gets a highlight ring.
 */
@Composable
fun VideoGrid(
    tiles: List<PlayerTile>,
    layout: VideoGridLayout,
    activeUid: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (row in 0 until layout.rows) {
            val first = row * layout.columns
            val count = layout.tilesInRow(row)
            if (count <= 0) continue
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (col in 0 until count) {
                    val tile = tiles.getOrNull(first + col) ?: continue
                    VideoTile(
                        tile = tile,
                        isActive = tile.player.uid == activeUid,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * A single participant tile. Shows the live video (or an [Avatar] fallback when the
 * camera is off), the pseudo + level, mic/camera status icons and a connection-quality
 * dot — matching the spec's "Caméra" section (pseudo, niveau, micro, caméra, ping).
 */
@Composable
private fun VideoTile(
    tile: PlayerTile,
    isActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val player = tile.player
    val shape = RoundedCornerShape(16.dp)
    val ringColor = if (isActive) MaterialTheme.colorScheme.primary else Color.Transparent

    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(width = 3.dp, color = ringColor, shape = shape),
        contentAlignment = Alignment.Center,
    ) {
        if (tile.hasVideoTrack) {
            // WEBRTC ATTACH POINT
            // ───────────────────────────────────────────────────────────────
            // When a video track is present, the WebRTC `SurfaceViewRenderer` for
            // `tile.track` should be attached here via an AndroidView, e.g.:
            //
            //   AndroidView(
            //       factory = { ctx -> SurfaceViewRenderer(ctx).also(rtcRenderer::bind) },
            //       update  = { it.attach(tile.track) },
            //       modifier = Modifier.fillMaxSize(),
            //   )
            //
            // The renderer/EglBase plumbing lives in the :data WebRTC layer; this
            // placeholder keeps the UI module free of the WebRTC dependency.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            )
        } else {
            Avatar(photoUrl = player.photoUrl, size = 64)
        }

        // Bottom info strip: pseudo + level + mic/camera state.
        TileInfoStrip(
            tile = tile,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(),
        )

        // Top-right connection-quality / ping dot.
        ConnectionDot(
            quality = tile.connection,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
        )

        if (tile.isSelf) {
            SelfBadge(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            )
        }
    }
}

@Composable
private fun TileInfoStrip(
    tile: PlayerTile,
    modifier: Modifier = Modifier,
) {
    val player = tile.player
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = player.displayName,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )

        LevelChip(level = player.level)

        Spacer(Modifier.weight(1f))

        Icon(
            imageVector = if (player.micEnabled) Icons.Filled.Mic else Icons.Filled.MicOff,
            contentDescription = if (player.micEnabled) "Microphone on" else "Microphone off",
            tint = if (player.micEnabled) Color.White else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        Icon(
            imageVector = if (player.cameraEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
            contentDescription = if (player.cameraEnabled) "Camera on" else "Camera off",
            tint = if (player.cameraEnabled) Color.White else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun LevelChip(level: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Star,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(10.dp),
        )
        Text(
            text = level.toString(),
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Coloured dot reflecting the peer's [ConnectionQuality] ("ping" indicator). */
@Composable
private fun ConnectionDot(quality: ConnectionQuality, modifier: Modifier = Modifier) {
    val color = when (quality) {
        ConnectionQuality.EXCELLENT -> Color(0xFF22C55E)
        ConnectionQuality.GOOD -> Color(0xFF84CC16)
        ConnectionQuality.FAIR -> Color(0xFFEAB308)
        ConnectionQuality.POOR -> Color(0xFFF97316)
        ConnectionQuality.DISCONNECTED -> Color(0xFFEF4444)
    }
    Box(
        modifier = modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(color)
            .border(width = 1.dp, color = Color.White.copy(alpha = 0.7f), shape = CircleShape),
    )
}

@Composable
private fun SelfBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = "You",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
