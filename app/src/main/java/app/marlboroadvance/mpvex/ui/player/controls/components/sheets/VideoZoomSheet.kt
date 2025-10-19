package app.marlboroadvance.mpvex.ui.player.controls.components.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.components.PlayerSheet
import app.marlboroadvance.mpvex.presentation.components.SliderItem
import app.marlboroadvance.mpvex.ui.theme.spacing
import `is`.xyz.mpv.MPVLib
import org.koin.compose.koinInject

@Composable
fun VideoZoomSheet(
  videoZoom: Float,
  onSetVideoZoom: (Float) -> Unit,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val playerPreferences = koinInject<PlayerPreferences>()
  val defaultZoom by playerPreferences.defaultVideoZoom.collectAsState()
  var zoom by remember { mutableFloatStateOf(videoZoom) }

  val currentOnSetVideoZoom by rememberUpdatedState(onSetVideoZoom)

  LaunchedEffect(Unit) {
    zoom = (MPVLib.getPropertyDouble("video-zoom") ?: defaultZoom.toDouble()).toFloat()
  }

  LaunchedEffect(zoom) {
    MPVLib.setPropertyDouble("video-zoom", zoom.toDouble())
    currentOnSetVideoZoom(zoom)
  }

  PlayerSheet(onDismissRequest = onDismissRequest) {
    ZoomVideoSheet(
      zoom = zoom,
      onZoomChange = { newZoom -> zoom = newZoom },
      onSetAsDefault = {
        // Save current zoom as default for all videos
        playerPreferences.defaultVideoZoom.set(zoom)
      },
      modifier = modifier,
    )
  }
}

@Composable
private fun ZoomVideoSheet(
  zoom: Float,
  onZoomChange: (Float) -> Unit,
  onSetAsDefault: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val playerPreferences = koinInject<PlayerPreferences>()
  val defaultZoom by playerPreferences.defaultVideoZoom.collectAsState()
  val isDefault = zoom == defaultZoom

  Column(
    modifier = modifier
      .fillMaxWidth()
      .verticalScroll(rememberScrollState())
      .padding(vertical = MaterialTheme.spacing.medium),
  ) {
    SliderItem(
      label = stringResource(id = R.string.player_sheets_zoom_slider_label),
      value = zoom,
      valueText = if (isDefault) "%.2fx (default)".format(zoom) else "%.2fx".format(zoom),
      onChange = onZoomChange,
      max = 3f,
      min = -2f,
      modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
    )

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = MaterialTheme.spacing.medium),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller, Alignment.End),
    ) {
      Button(
        onClick = onSetAsDefault,
      ) {
        Text("Set as default")
      }

      Button(
        onClick = {
          onZoomChange(0f)
        },
      ) {
        Text("Reset")
      }
    }
  }
}
