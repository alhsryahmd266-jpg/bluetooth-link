package com.btlink.app

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Image as ImageIcon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.content.ContextCompat
import java.io.File

val CBg = Color(0xFF070B14)
val CSurf = Color(0xFF111A2B)
val CCard = Color(0xFF172238)
val CCyan = Color(0xFF22D3EE)
val CIndigo = Color(0xFF6366F1)
val CGreen = Color(0xFF34D399)
val CRed = Color(0xFFF87171)
val CDim = Color(0xFF8B9BB8)

enum class Screen { Home, Main, Receiver }

fun neededPerms(): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES) else add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= 33) {
        add(Manifest.permission.POST_NOTIFICATIONS)
        add(Manifest.permission.READ_MEDIA_IMAGES)
        add(Manifest.permission.READ_MEDIA_VIDEO)
        add(Manifest.permission.READ_MEDIA_AUDIO)
    } else {
        add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    add(Manifest.permission.READ_CONTACTS)
}

fun hasBt(ctx: Context): Boolean =
    Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

fun BluetoothDevice.safeName(): String = try { name ?: address } catch (_: SecurityException) { "جهاز" }

class MainActivity : ComponentActivity() {
    val tick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BtTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    App(this)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        tick.intValue++
    }
}

@Composable
fun BtTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = CCyan, onPrimary = Color.Black, secondary = CIndigo,
            background = CBg, surface = CSurf, onSurface = Color.White, onBackground = Color.White,
            surfaceVariant = CCard
        ),
        content = content
    )
}

@Composable
fun pulse(from: Float = 0.55f, to: Float = 1f, ms: Int = 900): Float {
    val t = rememberInfiniteTransition(label = "pulse")
    val v by t.animateFloat(from, to, infiniteRepeatable(tween(ms), RepeatMode.Reverse), label = "pv")
    return v
}

@Composable
fun App(act: MainActivity) {
    var screen by remember { mutableStateOf(Screen.Home) }
    BackHandler(screen != Screen.Home) { screen = Screen.Home }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0D1730), CBg)))) {
        when (screen) {
            Screen.Home -> HomeScreen(act) { screen = it }
            Screen.Main -> MainScreen(act) { screen = Screen.Home }
            Screen.Receiver -> ReceiverScreen(act) { screen = Screen.Home }
        }
    }
}

// ---------------------------------------------------------------- Home

@Composable
fun HomeScreen(act: MainActivity, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { act.tick.intValue++ }
    LaunchedEffect(Unit) {
        val missing = neededPerms().filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray())
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(36.dp))
        val p = pulse(0.92f, 1.06f, 1400)
        Box(
            Modifier.size(120.dp).scale(p).clip(CircleShape)
                .background(Brush.radialGradient(listOf(CCyan.copy(alpha = 0.35f), CIndigo.copy(alpha = 0.10f), Color.Transparent))),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.size(84.dp).clip(CircleShape).background(Brush.linearGradient(listOf(CCyan, CIndigo))),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.Bluetooth, null, tint = Color.White, modifier = Modifier.size(46.dp)) }
        }
        Spacer(Modifier.height(18.dp))
        Text("Bluetooth Link", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        Spacer(Modifier.height(6.dp))
        Text("هاتفك التاني يبقى كأنه جزء من هاتفك", color = CDim, fontSize = 15.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(34.dp))

        RoleCard("هذا هاتفي الرئيسي", "اتصل بهاتفك التاني واسحب أي صورة أو فيديو أو ملف بضغطة واحدة", Icons.Rounded.BluetoothConnected, listOf(CCyan, CIndigo)) { go(Screen.Main) }
        Spacer(Modifier.height(16.dp))
        RoleCard("هذا هو الهاتف التاني", "شغّل الاستقبال ووافق على الطلب لما هاتفك الرئيسي يتصل", Icons.Rounded.PhoneAndroid, listOf(CIndigo, Color(0xFFA855F7))) { go(Screen.Receiver) }

        Spacer(Modifier.height(28.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CSurf).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Shield, null, tint = CGreen)
            Spacer(Modifier.width(12.dp))
            Text(
                "الأمان: كل جلسة لازم توافق عليها من الهاتف التاني، ويفضل إشعار ثابت ظاهر عليه طول ما الاتصال شغال، وتقدر تقطعه في أي لحظة.",
                color = CDim, fontSize = 13.sp, lineHeight = 20.sp
            )
        }
    }
}

@Composable
fun RoleCard(title: String, sub: String, icon: androidx.compose.ui.graphics.vector.ImageVector, colors: List<Color>, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(colors.map { it.copy(alpha = 0.22f) }))
            .border(1.dp, colors[0].copy(alpha = 0.55f), RoundedCornerShape(22.dp))
            .clickable(onClick = onClick).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(colors)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(4.dp))
            Text(sub, fontSize = 13.sp, color = CDim, lineHeight = 19.sp)
        }
    }
}

@Composable
fun TopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, null, tint = Color.White) }
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

// ---------------------------------------------------------------- Receiver

@Composable
fun ReceiverScreen(act: MainActivity, back: () -> Unit) {
    val ctx = LocalContext.current
    val st by Hub.server.collectAsState()
    val allFiles = remember(act.tick.intValue) { Environment.isExternalStorageManager() }
    val btOn = remember(act.tick.intValue) { ctx.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true }

    fun send(action: String) {
        try {
            if (action == LinkService.ACT_START) ctx.startForegroundService(Intent(ctx, LinkService::class.java).setAction(action))
            else ctx.startService(Intent(ctx, LinkService::class.java).setAction(action))
        } catch (_: Exception) {}
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        act.tick.intValue++
        if (hasBt(ctx)) send(LinkService.ACT_START)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar("وضع الاستقبال", back)
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val color = when (st) { is ServerState.Off -> CDim; is ServerState.Connected -> CGreen; else -> CCyan }
            val p = if (st is ServerState.Off) 1f else pulse(0.9f, 1.08f, 1100)
            Box(Modifier.size(150.dp).scale(p).clip(CircleShape).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Box(Modifier.size(100.dp).clip(CircleShape).background(color.copy(alpha = 0.30f)), contentAlignment = Alignment.Center) {
                    Icon(if (st is ServerState.Connected) Icons.Rounded.BluetoothConnected else Icons.Rounded.Bluetooth, null, tint = color, modifier = Modifier.size(52.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            val s = st
            val label = when (s) {
                is ServerState.Off -> "الاستقبال متوقف"
                is ServerState.Listening -> "في انتظار هاتفك الرئيسي…"
                is ServerState.Pending -> "طلب اتصال من ${s.name}"
                is ServerState.Connected -> "متصل مع ${s.name}"
            }
            Text(label, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))

            if (!btOn) {
                Warn("البلوتوث مقفول. شغّله من إعدادات الهاتف، وتأكد إن الهاتفين مقترنين ببعض.")
                Spacer(Modifier.height(14.dp))
            }
            if (!allFiles) {
                Warn("عشان الهاتف الرئيسي يقدر يتصفح ملفاتك، فعّل \"الوصول لكل الملفات\" لهذا التطبيق (الصور والفيديو تشتغل بدونه).")
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    try {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName)))
                    } catch (_: Exception) {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }) { Text("فتح الإعداد") }
                Spacer(Modifier.height(14.dp))
            }

            when (st) {
                is ServerState.Off -> Button(
                    onClick = {
                        if (!hasBt(ctx)) permLauncher.launch(neededPerms().toTypedArray()) else send(LinkService.ACT_START)
                    },
                    modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)
                ) { Text("تشغيل الاستقبال", fontSize = 17.sp, fontWeight = FontWeight.Bold) }

                is ServerState.Listening -> OutlinedButton(
                    onClick = { send(LinkService.ACT_STOP) }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)
                ) { Text("إيقاف الاستقبال", fontSize = 16.sp) }

                is ServerState.Connected -> Button(
                    onClick = { send(LinkService.ACT_DISCONNECT) },
                    colors = ButtonDefaults.buttonColors(containerColor = CRed, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)
                ) { Text("قطع الاتصال", fontSize = 17.sp, fontWeight = FontWeight.Bold) }

                else -> {}
            }
        }
    }

    val s = st
    if (s is ServerState.Pending) {
        AlertDialog(
            onDismissRequest = {},
            containerColor = CSurf,
            icon = { Icon(Icons.Rounded.Shield, null, tint = CCyan) },
            title = { Text("طلب اتصال بلوتوث", color = Color.White) },
            text = { Text("الهاتف \"${s.name}\" يطلب الاتصال بهذا الهاتف وسحب ملفات منه طول هذه الجلسة. هل توافق؟", color = CDim) },
            confirmButton = { Button(onClick = { send(LinkService.ACT_ALLOW) }) { Text("موافق") } },
            dismissButton = { TextButton(onClick = { send(LinkService.ACT_DENY) }) { Text("رفض", color = CRed) } }
        )
    }
}

@Composable
fun Warn(text: String) {
    Text(
        text, color = Color(0xFFFFD27A), fontSize = 13.sp, lineHeight = 20.sp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x22FFB020)).padding(12.dp)
    )
}

// ---------------------------------------------------------------- Main (puller)

@Composable
fun MainScreen(act: MainActivity, back: () -> Unit) {
    val cs by LinkClient.state.collectAsState()
    val s = cs
    if (s is ClientState.Connected) Browser(s.name, back) else DevicePicker(act, s, back)
}

@Composable
fun DevicePicker(act: MainActivity, cs: ClientState, back: () -> Unit) {
    val ctx = LocalContext.current
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { act.tick.intValue++ }
    val devices = remember(act.tick.intValue) {
        try {
            ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.toList() ?: emptyList()
        } catch (_: SecurityException) { emptyList<BluetoothDevice>() }
    }
    val busy = cs is ClientState.Connecting || cs is ClientState.Waiting

    Column(Modifier.fillMaxSize()) {
        TopBar("اختر هاتفك التاني", back)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            if (!hasBt(ctx)) {
                Warn("محتاجين إذن البلوتوث الأول.")
                Spacer(Modifier.height(8.dp))
                Button(onClick = { permLauncher.launch(neededPerms().toTypedArray()) }) { Text("منح الأذونات") }
                Spacer(Modifier.height(16.dp))
            }
            when (cs) {
                is ClientState.Connecting -> StatusLine("جاري الاتصال بـ ${cs.name}…", CCyan, true)
                is ClientState.Waiting -> StatusLine("في انتظار موافقتك على الهاتف التاني ${cs.name}…", CCyan, true)
                is ClientState.Failed -> StatusLine(cs.msg, CRed, false)
                else -> {}
            }
            Spacer(Modifier.height(8.dp))
            if (devices.isEmpty()) {
                Text("مفيش أجهزة مقترنة. اقرن هاتفك التاني من إعدادات البلوتوث، وبعدين ارجع هنا.", color = CDim, fontSize = 14.sp, lineHeight = 21.sp)
            }
            for (d in devices) {
                val n = d.safeName()
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(18.dp)).background(CCard)
                        .clickable(enabled = !busy) { LinkClient.connectAsync(ctx, d, n) }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.PhoneAndroid, null, tint = CCyan, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(n, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(d.address, color = CDim, fontSize = 12.sp)
                    }
                    Text("اتصال", color = CCyan, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(14.dp))
            OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("إقران هاتف جديد") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun StatusLine(text: String, color: Color, spinner: Boolean) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.12f)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (spinner) { CircularProgressIndicator(Modifier.size(20.dp), color = color, strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)) }
        Text(text, color = color, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable
fun Browser(name: String, back: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val ctx = LocalContext.current
    val turbo by LinkClient.turbo.collectAsState()
    val upg by LinkClient.upgrading.collectAsState()
    val wmsg by LinkClient.wifiMsg.collectAsState()
    val titles = listOf("صور", "فيديو", "صوت", "ملفات", "جهات")
    val icons = listOf(Icons.Rounded.ImageIcon, Icons.Rounded.Videocam, Icons.Rounded.MusicNote, Icons.Rounded.Folder, Icons.Rounded.Contacts)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(CGreen.copy(alpha = 0.14f)).padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = back) { Icon(Icons.Rounded.ArrowBack, null, tint = Color.White) }
            Box(Modifier.size(10.dp).alpha(pulse()).clip(CircleShape).background(CGreen))
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Rounded.BluetoothConnected, null, tint = CGreen, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("متصل ببلوتوث · $name", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = { LinkClient.disconnect() }) { Text("قطع", color = CRed, fontWeight = FontWeight.Bold) }
        }
        Row(
            Modifier.fillMaxWidth().background(if (turbo) CGreen.copy(alpha = 0.08f) else CCard).padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (upg) { CircularProgressIndicator(Modifier.size(16.dp), color = CCyan, strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }
            Text(
                when {
                    turbo -> "⚡ الوضع السريع شغال (Wi-Fi Direct)"
                    upg -> "جاري تفعيل الوضع السريع… وافق على نافذة الواي فاي لو ظهرت"
                    else -> wmsg ?: "الاتصال حالياً عبر البلوتوث (أبطأ)"
                },
                color = if (turbo) CGreen else CDim, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f)
            )
            if (!turbo && !upg) TextButton(onClick = { LinkClient.upgradeAsync(ctx) }) { Text("تفعيل السريع", fontSize = 12.sp, color = CCyan) }
        }
        TabRow(selectedTabIndex = tab, containerColor = Color.Transparent, contentColor = CCyan) {
            titles.forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, icon = { Icon(icons[i], null, modifier = Modifier.size(22.dp)) }, text = { Text(t, fontSize = 12.sp) })
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> MediaTab(0)
                1 -> MediaTab(1)
                2 -> MediaTab(2)
                3 -> FilesTab()
                else -> ContactsTab()
            }
        }
        TransferBar()
    }
}

@Composable
fun Msg(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = CDim, textAlign = TextAlign.Center, lineHeight = 22.sp)
    }
}

@Composable
fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = CCyan) }
}

@Composable
fun MediaTab(kind: Int) {
    val ctx = LocalContext.current
    var items by remember(kind) { mutableStateOf<List<RemoteMedia>?>(null) }
    var err by remember(kind) { mutableStateOf<String?>(null) }
    val completed by LinkClient.completed.collectAsState()
    var confirmAll by remember(kind) { mutableStateOf(false) }

    LaunchedEffect(kind) {
        items = null; err = null
        try { items = LinkClient.listMedia(kind, 0, 400) } catch (e: Exception) { err = e.message ?: "خطأ" }
    }

    val list = items
    val e = err
    when {
        e != null -> Msg(e)
        list == null -> Loading()
        list.isEmpty() -> Msg("مفيش شيء هنا")
        else -> Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${list.size} عنصر", color = CDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { confirmAll = true }) {
                    Icon(Icons.Rounded.Download, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("سحب الكل")
                }
            }
            if (kind < 2) {
                LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    gridItems(list, key = { it.id }) { m ->
                        val key = "m:$kind:${m.id}"
                        Box(
                            Modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(CCard)
                                .clickable { LinkClient.pullMedia(ctx, kind, m.id) }
                        ) {
                            Thumb(kind, m.id, Modifier.fillMaxSize())
                            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))).padding(6.dp)) {
                                Text(formatSize(m.size), color = Color.White, fontSize = 11.sp)
                            }
                            if (kind == 1) Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(32.dp).alpha(0.9f))
                            if (key in completed) Box(Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp).clip(CircleShape).background(CGreen), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Check, null, tint = Color.Black, modifier = Modifier.size(15.dp))
                            }
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.id }) { m ->
                        val key = "m:$kind:${m.id}"
                        FileRow(m.name, formatSize(m.size), Icons.Rounded.MusicNote, key in completed) { LinkClient.pullMedia(ctx, kind, m.id) }
                    }
                }
            }
        }
    }

    if (confirmAll && list != null) {
        AlertDialog(
            onDismissRequest = { confirmAll = false }, containerColor = CSurf,
            title = { Text("سحب الكل؟", color = Color.White) },
            text = { Text("هيتم سحب ${list.size} عنصر (${formatSize(list.sumOf { it.size })}) بالترتيب. البلوتوث أبطأ من الواي فاي، فالحجم الكبير ياخد وقت.", color = CDim) },
            confirmButton = { Button(onClick = { confirmAll = false; list.forEach { LinkClient.pullMedia(ctx, kind, it.id) } }) { Text("ابدأ السحب") } },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("إلغاء") } }
        )
    }
}

@Composable
fun Thumb(kind: Int, id: Long, modifier: Modifier) {
    val bmp: State<Bitmap?> = produceState<Bitmap?>(null, kind, id) {
        value = try { LinkClient.thumb(kind, id) } catch (_: Exception) { null }
    }
    val b = bmp.value
    if (b != null) Image(b.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop) else Box(modifier.background(CCard))
}

@Composable
fun FileRow(title: String, sub: String, icon: androidx.compose.ui.graphics.vector.ImageVector, done: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(CCard), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = CCyan)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, color = CDim, fontSize = 12.sp)
        }
        if (done) Icon(Icons.Rounded.Check, null, tint = CGreen) else Icon(Icons.Rounded.Download, null, tint = CDim)
    }
}

@Composable
fun FilesTab() {
    val ctx = LocalContext.current
    var path by remember { mutableStateOf("") }
    var root by remember { mutableStateOf<String?>(null) }
    var cur by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<RemoteFile>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    val completed by LinkClient.completed.collectAsState()

    LaunchedEffect(path) {
        items = null; err = null
        try {
            val (p, l) = LinkClient.listDir(path)
            cur = p
            if (root == null) root = p
            items = l
        } catch (e: Exception) { err = e.message ?: "خطأ" }
    }
    val c = cur
    BackHandler(enabled = c != null && c != root) { path = File(c!!).parent ?: "" }

    val list = items
    val e = err
    when {
        e != null -> Msg(e)
        list == null -> Loading()
        else -> LazyColumn(Modifier.fillMaxSize()) {
            if (c != null && c != root) item {
                FileRow(".. للخلف", c, Icons.Rounded.Folder, false) { path = File(c).parent ?: "" }
            }
            if (list.isEmpty()) item { Msg("المجلد فاضي") }
            items(list, key = { it.name }) { f ->
                val full = (c ?: "") + "/" + f.name
                if (f.isDir) FileRow(f.name, "مجلد", Icons.Rounded.Folder, false) { path = full }
                else FileRow(f.name, formatSize(f.size), Icons.Rounded.InsertDriveFile, "f:$full" in completed) { LinkClient.pullFile(ctx, full) }
            }
        }
    }
}

@Composable
fun ContactsTab() {
    val ctx = LocalContext.current
    val completed by LinkClient.completed.collectAsState()
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Rounded.Contacts, null, tint = CCyan, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text("جهات الاتصال والأرقام", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("اسحب كل الأسماء والأرقام من الهاتف التاني في ملف واحد (vCard) تفتحه وتستورده في جهات الاتصال.", color = CDim, textAlign = TextAlign.Center, lineHeight = 21.sp)
        Spacer(Modifier.height(22.dp))
        Button(onClick = { LinkClient.pullContacts(ctx) }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
            Text(if ("contacts" in completed) "تم! اسحب مرة تانية" else "سحب جهات الاتصال", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun TransferBar() {
    val ctx = LocalContext.current
    val t by LinkClient.transfer.collectAsState()
    val q by LinkClient.queued.collectAsState()
    val tr = t ?: return
    Column(Modifier.fillMaxWidth().background(CSurf).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = when (tr.status) {
                    0 -> "${formatSize(tr.done)} / ${formatSize(tr.total)} · ${String.format("%.2f", tr.speed / 1048576.0)} MB/s" + if (q > 1) " · ${q - 1} في الانتظار" else ""
                    1 -> "تم الحفظ · متوسط السرعة ${String.format("%.2f", tr.speed / 1048576.0)} MB/s" + if (q > 0) " · ${q} في الانتظار" else ""
                    else -> tr.msg
                }
                Text(sub, color = if (tr.status == 2) CRed else CDim, fontSize = 12.sp)
            }
            if (tr.status == 1 && tr.uri != null) TextButton(onClick = {
                try {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(tr.uri, tr.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {}
            }) { Text("فتح") }
            if (tr.status != 0) IconButton(onClick = { LinkClient.transfer.value = null }) { Icon(Icons.Rounded.Close, null, tint = CDim) }
        }
        if (tr.status == 0) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { if (tr.total > 0) (tr.done.toFloat() / tr.total).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = CCyan, trackColor = CCard
            )
        }
    }
}
