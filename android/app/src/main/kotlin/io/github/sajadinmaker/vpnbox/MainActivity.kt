package io.github.sajadinmaker.vpnbox

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sajadinmaker.vpnbox.data.ThemeMode
import io.github.sajadinmaker.vpnbox.ui.AboutScreen
import io.github.sajadinmaker.vpnbox.ui.CountrySheet
import io.github.sajadinmaker.vpnbox.ui.HomeAction
import io.github.sajadinmaker.vpnbox.ui.HomeScreen
import io.github.sajadinmaker.vpnbox.ui.SettingsScreen
import io.github.sajadinmaker.vpnbox.ui.TerminalScreen
import io.github.sajadinmaker.vpnbox.ui.theme.VpnboxTheme

class MainActivity : ComponentActivity() {

    /** Connect request waiting behind one of the two system dialogs. */
    private var pendingConnect: Pair<String?, String?>? = null

    /** Step 1 of the chain: the system VPN consent dialog. */
    private val vpnConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val pending = pendingConnect
        if (result.resultCode == RESULT_OK && pending != null) {
            // consent granted — now (best-effort) the notification permission,
            // then the actual connect
            requestNotificationThenConnect(pending.first, pending.second)
        } else {
            pendingConnect = null
            vmRef?.consentDeclined()
        }
    }

    /** Step 2: POST_NOTIFICATIONS (Android 13+). Non-blocking for connect. */
    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        val pending = pendingConnect
        pendingConnect = null
        if (pending != null) vmRef?.connect(pending.first, pending.second)
    }

    /** ViewModel lives in the composition; the launchers need it earlier. */
    private var vmRef: VpnViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: VpnViewModel = viewModel()
            vmRef = vm
            val settings by vm.settings.collectAsState()
            val systemDark = isSystemInDarkTheme()
            VpnboxTheme(
                darkTheme = when (settings.themeMode) {
                    ThemeMode.SYSTEM -> systemDark
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                },
                dynamicColor = settings.dynamicColor,
            ) {
                LaunchedEffect(Unit) { vm.start() }
                val screen by vm.screen.collectAsState()
                val home by vm.home.collectAsState()
                val terminal by vm.terminal.collectAsState()
                val countries by vm.countries.collectAsState()
                var picking by remember { mutableStateOf(false) }

                BackHandler(enabled = screen != Screen.HOME) { vm.onSystemBack() }

                Crossfade(targetState = screen, label = "screen") { s ->
                    when (s) {
                        Screen.TERMINAL -> TerminalScreen(
                            ui = terminal,
                            onBack = { vm.dismissAndGoHome() },
                            onKill = { vm.killTunnel() },
                        )
                        Screen.SETTINGS -> SettingsScreen(
                            settings = settings,
                            onBack = { vm.dismissAndGoHome() },
                            onThemeMode = { vm.setThemeMode(it) },
                            onDynamicColor = { vm.setDynamicColor(it) },
                        )
                        Screen.ABOUT -> AboutScreen(onBack = { vm.dismissAndGoHome() })
                        else -> {
                            HomeScreen(
                                ui = home,
                                onAction = { action ->
                                    when (action) {
                                        HomeAction.ConnectFast ->
                                            guardedConnect(null, null)
                                        HomeAction.ReconnectLast ->
                                            guardedConnect(
                                                vm.home.value.lastCountry,
                                                vm.home.value.lastCity,
                                            )
                                        HomeAction.PickCountry -> {
                                            vm.rebuildCountries()
                                            picking = true
                                        }
                                        HomeAction.Refresh -> vm.refresh()
                                        HomeAction.Disconnect -> vm.disconnect()
                                        HomeAction.Settings -> vm.goSettings()
                                        HomeAction.About -> vm.goAbout()
                                    }
                                },
                            )
                            if (picking) {
                                CountrySheet(
                                    countries = countries,
                                    onPick = { pick ->
                                        picking = false
                                        guardedConnect(pick.cc, null)
                                    },
                                    onDismiss = { picking = false },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Every connect goes through the system dialogs first: `VpnService
     * .prepare()` consent (result delivered through [vpnConsentLauncher],
     * never through this Activity's onActivityResult) and — best effort —
     * POST_NOTIFICATIONS on Android 13+. Disconnect is untouched.
     */
    private fun guardedConnect(country: String?, city: String?) {
        val consent = VpnService.prepare(this)
        if (consent != null) {
            pendingConnect = Pair(country, city)
            vpnConsentLauncher.launch(consent)
        } else {
            requestNotificationThenConnect(country, city)
        }
    }

    private fun requestNotificationThenConnect(country: String?, city: String?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingConnect = Pair(country, city)
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vmRef?.connect(country, city)
        }
    }
}
