package io.github.sajadinmaker.vpnbox.ui

import io.github.sajadinmaker.vpnbox.BuildConfig

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.view.HapticFeedbackConstants
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalView
import io.github.sajadinmaker.vpnbox.HomeUi
import io.github.sajadinmaker.vpnbox.R

enum class HomeAction { ConnectFast, ReconnectLast, PickCountry, Refresh, Disconnect, Settings, About }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(ui: HomeUi, onAction: (HomeAction) -> Unit) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(ui.message) {
        if (ui.message.isNotBlank()) {
            snackbarHostState.showSnackbar(ui.message, duration = SnackbarDuration.Long)
        }
    }
    Scaffold(
        topBar = {
            var menuOpen by remember { mutableStateOf(false) }
            TopAppBar(
                title = { Text("vpnbox", fontWeight = FontWeight.Bold) },
                actions = {
                    Text(
                        BuildConfig.VERSION_NAME,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.menu_more),
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_settings)) },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Settings, contentDescription = null)
                                },
                                onClick = {
                                    menuOpen = false
                                    onAction(HomeAction.Settings)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_about)) },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Info, contentDescription = null)
                                },
                                onClick = {
                                    menuOpen = false
                                    onAction(HomeAction.About)
                                },
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = ui.message.startsWith("Refreshing server list"),
            onRefresh = { onAction(HomeAction.Refresh) },
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusHero(ui)
                MetricsRow(ui)
                ActionsCard(ui, onAction)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.home_footer),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun StatusHero(ui: HomeUi) {
    val connected = ui.connected
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (connected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (connected) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.16f)
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (connected) Icons.Filled.CheckCircle else Icons.Filled.PowerSettingsNew,
                    contentDescription = null,
                    tint = if (connected) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(Modifier.size(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (connected) stringResource(R.string.home_connected)
                    else stringResource(R.string.home_not_connected),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (connected) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (connected) {
                        stringResource(R.string.home_exit_ip,
                        ui.geoLabel.ifBlank { stringResource(R.string.home_checking) })
                    } else {
                        stringResource(R.string.home_not_protected)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (connected) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun MetricsRow(ui: HomeUi) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MetricCard(
            value = "${ui.servers}",
            label = stringResource(R.string.home_metric_servers),
            modifier = Modifier.weight(1f),
        )
        MetricCard(
            value = "${ui.countries}",
            label = stringResource(R.string.home_metric_countries),
            modifier = Modifier.weight(1f),
        )
    }
    ui.cacheAgeMin?.let {
        Text(
            stringResource(R.string.home_refreshed, ageText(it)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MetricCard(value: String, label: String, modifier: Modifier = Modifier) {
    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActionsCard(ui: HomeUi, onAction: (HomeAction) -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            // quick reconnect: the last session's country/city, one tap away
            ui.lastCountry?.let { last ->
                if (!ui.connected) {
                    ActionRow(
                        icon = Icons.Filled.Redeem,
                        title = if (ui.lastCity.isNullOrBlank())
                            stringResource(R.string.action_reconnect_title, ui.lastCountryLabel ?: last)
                        else stringResource(
                            R.string.action_reconnect_title_city,
                            ui.lastCountryLabel ?: last, ui.lastCity!!,
                        ),
                        subtitle = stringResource(R.string.action_reconnect_subtitle),
                        enabled = ui.servers > 0,
                        tint = MaterialTheme.colorScheme.primary,
                    ) { onAction(HomeAction.ReconnectLast) }
                }
            }
            ActionRow(
                icon = Icons.Filled.Bolt,
                title = stringResource(R.string.action_connect_title),
                subtitle = stringResource(R.string.action_connect_subtitle),
                enabled = ui.servers > 0,
                tint = MaterialTheme.colorScheme.primary,
            ) { onAction(HomeAction.ConnectFast) }
            ActionRow(
                icon = Icons.Filled.Public,
                title = stringResource(R.string.action_pick_title),
                subtitle = stringResource(R.string.action_pick_subtitle, ui.countries),
                enabled = ui.servers > 0,
                tint = MaterialTheme.colorScheme.primary,
            ) { onAction(HomeAction.PickCountry) }
            ActionRow(
                icon = Icons.Filled.Refresh,
                title = stringResource(R.string.action_refresh_title),
                subtitle = stringResource(R.string.action_refresh_subtitle),
                enabled = true,
                tint = MaterialTheme.colorScheme.primary,
            ) { onAction(HomeAction.Refresh) }
            ActionRow(
                icon = Icons.Filled.PowerSettingsNew,
                title = stringResource(R.string.action_disconnect_title),
                subtitle = stringResource(R.string.action_disconnect_subtitle),
                enabled = ui.connected,
                tint = MaterialTheme.colorScheme.error,
            ) { onAction(HomeAction.Disconnect) }
        }
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    tint: Color,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (enabled) tint.copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
        trailingContent = {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.outlineVariant,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(enabled = enabled) {
            // light tactile confirmation on every primary action tap (API-safe)
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        },
    )
}

@Composable
private fun ageText(min: Long): String = when {
    min < 1 -> stringResource(R.string.age_just_now)
    min < 60 -> stringResource(R.string.age_minutes, min)
    else -> stringResource(R.string.age_hours, min / 60)
}
