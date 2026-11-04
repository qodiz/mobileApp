@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.kwspotter

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val Night = Color(0xFF101828)
private val Ink = Color(0xFFE8EEF9)
private val Muted = Color(0xFFB7C3D9)
private val Gold = Color(0xFFFFC857)
private val GoldInk = Color(0xFF2A1D00)
private val SafeGreen = Color(0xFF1E8E3E)
private val DangerRed = Color(0xFFC62828)
private val DoneGreen = Color(0xFF7DFFB3)

private const val WIFI_SEEN = "setup_wifi_seen"

private data class SetupStatus(
    val wifiDone: Boolean,
    val usbOn: Boolean,
    val paired: Boolean,
    val permissions: List<Pair<Int, Boolean>>,
    val overlay: Boolean,
    val accessibility: Boolean,
    val battery: Boolean,
    val coreReady: Boolean
) {
    val permissionsDone: Boolean get() = permissions.all { it.second }
    val debugDone: Boolean get() = usbOn && paired
}

/**
 * Первая настройка как короткая игра: уровни листаются свайпом.
 * Незакрытый уровень не пускает дальше. Когда всё собрано — зелёный экран.
 */
@Composable
fun SetupJourney(
    refresh: Int,
    onRequestPermissions: (Array<String>) -> Unit
) {
    val context = LocalContext.current
    val status = remember(refresh) { readStatus(context) }
    val quests = questOrder(status, onRequestPermissions)
    val firstIncomplete = quests.indexOfFirst { !it.done }.let { if (it == -1) quests.size else it }
    val safe = firstIncomplete == quests.size
    val pagerState = rememberPagerState(pageCount = { quests.size + 1 })
    val frontier = rememberUpdatedState(firstIncomplete)
    val scope = rememberCoroutineScope()
    val shake = remember { Animatable(0f) }
    val view = LocalView.current

    val serviceState by ListenerService.state.collectAsState()
    val serviceError by ListenerService.lastError.collectAsState()

    LaunchedEffect(status.coreReady) {
        if (status.coreReady) ListenerService.start(context)
    }

    LaunchedEffect(safe) {
        if (!safe) return@LaunchedEffect
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    val windowColor = when {
        safe && pagerState.currentPage == quests.size -> SafeGreen
        safe -> Night
        else -> DangerRed
    }
    androidx.compose.runtime.SideEffect {
        val window = (context as? android.app.Activity)?.window ?: return@SideEffect
        window.statusBarColor = windowColor.toArgb()
        window.navigationBarColor = if (safe && pagerState.currentPage == quests.size) {
            SafeGreen.toArgb()
        } else {
            Night.toArgb()
        }
    }

    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(firstIncomplete) {
        val page = pagerState.currentPage
        when {
            !opened -> {
                pagerState.scrollToPage(firstIncomplete)
                opened = true
            }
            page > firstIncomplete -> pagerState.animateScrollToPage(firstIncomplete)
            page < firstIncomplete && quests.getOrNull(page)?.done == true ->
                pagerState.animateScrollToPage(firstIncomplete)
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.targetPage }
            .distinctUntilChanged()
            .collect { target ->
                val limit = frontier.value
                if (target > limit) {
                    playShake(shake)
                    pagerState.animateScrollToPage(limit)
                }
            }
    }

    val showingSafe = safe && pagerState.currentPage == quests.size
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (showingSafe) SafeGreen else Night)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            if (!safe) {
                Text(
                    text = stringResource(R.string.setup_danger),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .offset { IntOffset(shake.value.roundToInt(), 0) }
                        .fillMaxWidth()
                        .background(DangerRed)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }

            if (!showingSafe) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.setup_title),
                    color = Gold,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    letterSpacing = 1.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(10.dp))
                LevelDots(
                    count = quests.size,
                    done = quests.map { it.done },
                    current = pagerState.currentPage.coerceAtMost(quests.lastIndex)
                )
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                key = { it }
            ) { page ->
                if (page == quests.size) {
                    SafeFinale(
                        listening = serviceState == ListenerService.State.LISTENING,
                        error = serviceError
                    )
                } else {
                    val quest = quests[page]
                    QuestPage(
                        level = page + 1,
                        total = quests.size,
                        emoji = quest.emoji,
                        title = stringResource(quest.title),
                        body = stringResource(quest.body),
                        done = quest.done,
                        onNext = {
                            scope.launch {
                                if (quest.done && page < frontier.value) {
                                    pagerState.animateScrollToPage(page + 1)
                                } else {
                                    playShake(shake)
                                }
                            }
                        },
                        extra = { quest.Extra() },
                        actions = { quest.Actions() }
                    )
                }
            }
        }
    }
}

private data class Quest(
    val emoji: String,
    val title: Int,
    val body: Int,
    val done: Boolean,
    val Extra: @Composable () -> Unit,
    val Actions: @Composable () -> Unit
)

@Composable
private fun questOrder(
    status: SetupStatus,
    onRequestPermissions: (Array<String>) -> Unit
): List<Quest> {
    val context = LocalContext.current
    return listOf(
        Quest(
            emoji = "📶",
            title = R.string.setup_wifi_title,
            body = R.string.setup_wifi_body,
            done = status.wifiDone,
            Extra = {
                CheckLine(
                    status.wifiDone,
                    stringResource(
                        if (status.wifiDone) R.string.setup_wifi_ready else R.string.setup_wifi_missing
                    )
                )
            },
            Actions = {
                if (!status.wifiDone) {
                    GameButton(stringResource(R.string.setup_wifi_button)) {
                        openSettings(context, Settings.ACTION_WIFI_SETTINGS)
                    }
                }
            }
        ),
        Quest(
            emoji = "👂",
            title = R.string.setup_a11y_title,
            body = R.string.setup_a11y_body,
            done = status.accessibility,
            Extra = {},
            Actions = {
                if (!status.accessibility) {
                    GameButton(stringResource(R.string.setup_a11y_button)) {
                        CallCaptureAccessibilityService.openSettings(context)
                    }
                }
            }
        ),
        Quest(
            emoji = "🔧",
            title = R.string.setup_debug_title,
            body = R.string.setup_debug_body,
            done = status.debugDone,
            Extra = {
                CheckLine(
                    status.usbOn,
                    stringResource(
                        if (status.usbOn) R.string.setup_debug_usb_on else R.string.setup_debug_usb_off
                    )
                )
                CheckLine(
                    status.paired,
                    stringResource(
                        if (status.paired) R.string.setup_debug_pair_on else R.string.setup_debug_pair_off
                    )
                )
            },
            Actions = {
                if (!status.usbOn) {
                    GameButton(stringResource(R.string.setup_debug_usb_button)) {
                        openSettings(context, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (!status.paired) {
                    GameButton(stringResource(R.string.button_pair)) {
                        PairingCoordinator.begin(context)
                    }
                }
            }
        ),
        Quest(
            emoji = "🎟",
            title = R.string.setup_permissions_title,
            body = R.string.setup_permissions_body,
            done = status.permissionsDone,
            Extra = {
                status.permissions.forEach { (label, granted) ->
                    CheckLine(granted, stringResource(label))
                }
            },
            Actions = {
                if (!status.permissionsDone) {
                    GameButton(stringResource(R.string.setup_permissions_button)) {
                        onRequestPermissions(runtimePermissions())
                    }
                    SettingsLink {
                        openSettings(
                            context,
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            "package:${context.packageName}"
                        )
                    }
                }
            }
        ),
        Quest(
            emoji = "🛡",
            title = R.string.setup_overlay_title,
            body = R.string.setup_overlay_body,
            done = status.overlay,
            Extra = {},
            Actions = {
                if (!status.overlay) {
                    GameButton(stringResource(R.string.setup_overlay_button)) {
                        openSettings(
                            context,
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            "package:${context.packageName}"
                        )
                    }
                }
            }
        ),
        Quest(
            emoji = "🔋",
            title = R.string.setup_battery_title,
            body = R.string.setup_battery_body,
            done = status.battery,
            Extra = {},
            Actions = {
                if (!status.battery) {
                    GameButton(stringResource(R.string.setup_battery_button)) {
                        openSettings(
                            context,
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            "package:${context.packageName}"
                        )
                    }
                }
            }
        )
    )
}

@Composable
private fun QuestPage(
    level: Int,
    total: Int,
    emoji: String,
    title: String,
    body: String,
    done: Boolean,
    onNext: () -> Unit,
    extra: @Composable () -> Unit,
    actions: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = emoji, fontSize = 64.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.setup_level, level, total),
            color = Gold,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = title,
            color = Ink,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = body,
            color = Muted,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )
        Spacer(modifier = Modifier.height(16.dp))
        extra()
        Spacer(modifier = Modifier.height(20.dp))
        actions()
        if (done) {
            GameButton(stringResource(R.string.setup_next), onNext)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.setup_swipe_next),
                color = DoneGreen,
                textAlign = TextAlign.Center
            )
        } else {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.setup_swipe_locked),
                color = Muted,
                textAlign = TextAlign.Center
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun SafeFinale(listening: Boolean, error: String?) {
    val scale = remember { Animatable(0.3f) }
    LaunchedEffect(Unit) {
        scale.animateTo(
            1f,
            spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)
        )
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(148.dp)
                .scale(scale.value)
                .background(Color.White, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(78.dp)) {
                val path = Path().apply {
                    moveTo(size.width * 0.18f, size.height * 0.52f)
                    lineTo(size.width * 0.42f, size.height * 0.74f)
                    lineTo(size.width * 0.82f, size.height * 0.28f)
                }
                drawPath(
                    path = path,
                    color = SafeGreen,
                    style = Stroke(
                        width = 12.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }
        Spacer(modifier = Modifier.height(28.dp))
        Text(
            text = stringResource(R.string.setup_safe),
            color = Color.White,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            lineHeight = 40.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.setup_safe_hint),
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )
        if (listening) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.status_listening),
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (!error.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = error,
                color = Color.White,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun LevelDots(count: Int, done: List<Boolean>, current: Int) {
    val pulse by rememberInfiniteTransition(label = "level-pulse").animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "level-pulse-value"
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(count) { index ->
            val color = when {
                done.getOrElse(index) { false } -> DoneGreen
                index == current -> Gold
                else -> Color.White.copy(alpha = 0.28f)
            }
            val side = if (index == current) 14.dp else 9.dp
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(side)
                    .background(
                        color.copy(alpha = if (index == current && !done.getOrElse(index) { false }) pulse else 1f),
                        CircleShape
                    )
            )
        }
    }
}

@Composable
private fun CheckLine(done: Boolean, text: String) {
    Text(
        text = (if (done) "✓  " else "○  ") + text,
        color = if (done) DoneGreen else Ink,
        fontSize = 16.sp,
        modifier = Modifier.padding(top = 6.dp)
    )
}

@Composable
private fun GameButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = Gold,
            contentColor = GoldInk
        )
    ) {
        Text(text = text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
private fun SettingsLink(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(text = stringResource(R.string.setup_app_settings), color = Gold)
    }
}

private suspend fun playShake(shake: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>) {
    val path = floatArrayOf(0f, -18f, 16f, -12f, 10f, -6f, 0f)
    for (value in path) {
        shake.animateTo(value, tween(40))
    }
}

private fun readStatus(context: Context): SetupStatus {
    val prefs = context.getSharedPreferences("kwspotter", Context.MODE_PRIVATE)
    val wifi = wifiOn(context)
    if (wifi) prefs.edit().putBoolean(WIFI_SEEN, true).apply()
    val paired = KeywordAdbManager.isPaired(context)
    val permissions = runtimePermissionLabels().map { (permission, label) ->
        label to (
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            )
    }
    val phoneGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_PHONE_STATE
    ) == PackageManager.PERMISSION_GRANTED
    val notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    return SetupStatus(
        wifiDone = wifi || prefs.getBoolean(WIFI_SEEN, false) || paired,
        usbOn = usbDebuggingOn(context),
        paired = paired,
        permissions = permissions,
        overlay = Settings.canDrawOverlays(context),
        accessibility = CallCaptureAccessibilityService.isEnabled(context),
        battery = ignoringBattery(context),
        coreReady = paired && Settings.canDrawOverlays(context) && phoneGranted && notificationsGranted
    )
}

private fun runtimePermissions(): Array<String> =
    runtimePermissionLabels().map { it.first }.toTypedArray()

private fun runtimePermissionLabels(): List<Pair<String, Int>> {
    val items = mutableListOf(
        Manifest.permission.RECORD_AUDIO to R.string.setup_perm_mic,
        Manifest.permission.READ_PHONE_STATE to R.string.setup_perm_phone,
        Manifest.permission.ANSWER_PHONE_CALLS to R.string.setup_perm_answer
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        items.add(Manifest.permission.POST_NOTIFICATIONS to R.string.setup_perm_notifications)
    }
    return items
}

@Suppress("DEPRECATION")
private fun wifiOn(context: Context): Boolean {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
    return connectivity.allNetworks.any { network ->
        connectivity.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
}

private fun usbDebuggingOn(context: Context): Boolean {
    return try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
    } catch (_: Exception) {
        true
    }
}

private fun ignoringBattery(context: Context): Boolean {
    val power = context.getSystemService(PowerManager::class.java) ?: return true
    return power.isIgnoringBatteryOptimizations(context.packageName)
}

private fun openSettings(context: Context, action: String, data: String? = null) {
    val intent = Intent(action).apply {
        if (data != null) this.data = Uri.parse(data)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
        )
    }
}
