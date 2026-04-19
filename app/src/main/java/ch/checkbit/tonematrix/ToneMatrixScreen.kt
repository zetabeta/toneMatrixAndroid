package ch.checkbit.tonematrix

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel


/**
 * Full-screen composable for ToneMatrix Redux.
 *
 * Layout (top → bottom):
 *   Title + subtitle
 *   Grid canvas  (square, full width; loading spinner overlaid while initialising)
 *   Controls row (mute toggle | Clear Notes | instrument switcher)
 */
@Composable
fun ToneMatrixScreen(
    viewModel: ToneMatrixViewModel = viewModel(),
) {
    val gridTiles         by viewModel.gridTiles.collectAsStateWithLifecycle()
    val playheadX         by viewModel.playheadX.collectAsStateWithLifecycle()
    val isMuted           by viewModel.isMuted.collectAsStateWithLifecycle()
    val isDarkMode        by viewModel.isDarkMode.collectAsStateWithLifecycle()
    val isLoading         by viewModel.isLoading.collectAsStateWithLifecycle()
    val currentInstrument by viewModel.currentInstrument.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {

        // ── Title ─────────────────────────────────────────────────────────────
        Text(
            text = "ToneMatrix Redux",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 24.sp,
            fontWeight = FontWeight.W100,
        )
        Text(
            text = "A pentatonic step sequencer. Click tiles and make music.",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
        )

        // ── Grid canvas + loading overlay ─────────────────────────────────────
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            GridCanvas(
                tiles          = gridTiles,
                playheadX      = playheadX,
                modifier       = Modifier.fillMaxWidth(),
                onTileChanged  = { x, y, on -> viewModel.setTileValue(x, y, on) },
                getTileValue   = { x, y -> viewModel.getTileValue(x, y) },
            )
            if (isLoading) {
                CircularProgressIndicator(
                    color    = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp),
                )
            }
        }

        // ── Controls ──────────────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            // Mute toggle — mirrors the JS muteButton element
            IconButton(onClick = { viewModel.setMuted(!isMuted) }) {
                Icon(
                    imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff
                                  else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = if (isMuted) "Unmute" else "Mute",
                    tint = if (isMuted) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) 
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Theme toggle
            IconButton(onClick = { viewModel.toggleDarkMode() }) {
                Icon(
                    imageVector = if (isDarkMode) Icons.Default.LightMode
                                  else Icons.Default.DarkMode,
                    contentDescription = if (isDarkMode) "Light Mode" else "Dark Mode",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Clear Notes — mirrors the JS #clearnotes button
            OutlinedButton(
                onClick = { viewModel.clearAll() },
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor   = MaterialTheme.colorScheme.onBackground,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Text(
                    text = "Clear Notes",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Instrument switcher — sine (white) / sawtooth (orange)
            InstrumentButton(
                label = "SINE",
                color = MaterialTheme.colorScheme.primary,
                active = currentInstrument == 0,
                onClick = { viewModel.setCurrentInstrument(0) },
            )
            Spacer(modifier = Modifier.width(4.dp))
            InstrumentButton(
                label = "SAW",
                color = MaterialTheme.colorScheme.secondary,
                active = currentInstrument == 1,
                onClick = { viewModel.setCurrentInstrument(1) },
            )
        }
    }
}

/**
 * Small toggle button for the instrument switcher.
 *
 * Active state: border and label use [color] at full opacity.
 * Inactive state: border and label use [color] at 35% opacity.
 */
@Composable
private fun InstrumentButton(
    label: String,
    color: Color,
    active: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (active) color else color.copy(alpha = 0.35f)
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(1.dp, tint),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor   = tint,
            containerColor = if (active) color.copy(alpha = 0.12f) else Color.Transparent,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 12.dp, vertical = 6.dp,
        ),
    ) {
        Text(
            text       = label,
            fontSize   = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
    }
}
