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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.OutlinedTextField
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
import kotlinx.coroutines.launch
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
    if (Build.VERSION.SDK_INT < 29) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
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

fun openOemAutostart(ctx: Context) {
    val candidates = listOf(
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startupapp.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        "com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutobootManageActivity",
        "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity"
    )
    for ((pkg, cls) in candidates) {
        try {
            ctx.startActivity(Intent().setClassName(pkg, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: Exception) {}
    }
    try {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {}
}

fun startRx(ctx: Context) {
    try {
        ctx.startForegroundService(Intent(ctx, LinkService::class.java).setAction(LinkService.ACT_START))
    } catch (_: Exception) {
    }
}

fun sendSvc(ctx: Context, action: String) {
    try { ctx.startService(Intent(ctx, LinkService::class.java).setAction(action)) } catch (_: Exception) {}
}

@Composable
fun App(act: MainActivity) {
    val ctx = LocalContext.current
    val cs by LinkClient.state.collectAsState()
    val srv by Hub.server.collectAsState()
    val c = cs
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0D1730), CBg)))) {
        if (c is ClientState.Connected) Browser(c.name) { LinkClient.disconnect() } else Dashboard(act)
    }
    val s = srv
    if (s is ServerState.Pending) {
        var remember by remember(s) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = {},
            containerColor = CSurf,
            icon = { Icon(Icons.Rounded.Shield, null, tint = CCyan) },
            title = { Text("طلب اتصال بلوتوث", color = Color.White) },
            text = {
                Column {
                    Text("الهاتف \"${s.name}\" يطلب الاتصال بهذا الهاتف وسحب ملفات منه طول هذه الجلسة. هل توافق؟", color = CDim)
                    if (s.address != null) {
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { remember = !remember }) {
                            Box(Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)).background(if (remember) CCyan else CCard), contentAlignment = Alignment.Center) {
                                if (remember) Icon(Icons.Rounded.Check, null, tint = Color.Black, modifier = Modifier.size(14.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            Text("تذكر هذا الهاتف ولا تسألني تاني", color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { sendSvc(ctx, if (remember) LinkService.ACT_ALLOW_REMEMBER else LinkService.ACT_ALLOW) }) { Text("موافق") }
            },
            dismissButton = { TextButton(onClick = { sendSvc(ctx, LinkService.ACT_DENY) }) { Text("رفض", color = CRed) } }
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

@Composable
fun RemoteConnectCard(busy: Boolean) {
    val ctx = LocalContext.current
    var host by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(false) }
    val cs by LinkClient.state.collectAsState()

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CSurf).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { open = !open }) {
            Icon(Icons.Rounded.Public, null, tint = CIndigo)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("الاتصال عن بعد (Tailscale)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("للتحكم من مكان بعيد، زي الشغل والبيت، عن طريق الإنترنت", color = CDim, fontSize = 12.sp)
            }
            Text(if (open) "إخفاء" else "فتح", color = CCyan, fontSize = 13.sp)
        }
        if (open) {
            Spacer(Modifier.height(12.dp))
            Text(
                "ثبّت تطبيق Tailscale (مجاني) على الهاتفين وادخل بنفس الحساب على الاتنين، وهتلاقي لكل هاتف عنوان ثابت زي 100.x.x.x داخل تطبيق Tailscale نفسه. انسخه هنا واضغط اتصال.",
                color = CDim, fontSize = 12.sp, lineHeight = 19.sp
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = host, onValueChange = { host = it },
                placeholder = { Text("100.x.x.x أو اسم الهاتف على Tailscale") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            when (val c = cs) {
                is ClientState.Connecting -> StatusLine("جاري الاتصال بـ ${c.name}…", CCyan, true)
                is ClientState.Waiting -> StatusLine("في انتظار موافقتك على الهاتف التاني…", CCyan, true)
                is ClientState.Failed -> StatusLine(c.msg, CRed, false)
                else -> {}
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { if (host.isNotBlank()) LinkClient.connectRemoteAsync(ctx, host.trim(), host.trim()) },
                enabled = !busy && host.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CIndigo)
            ) { Text("اتصال عن بعد", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun Dashboard(act: MainActivity) {
    val ctx = LocalContext.current
    val srv by Hub.server.collectAsState()
    val srvMsg by Hub.serverMsg.collectAsState()
    val cs by LinkClient.state.collectAsState()
    val tick = act.tick.intValue
    val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    val btOn = remember(tick) { adapter?.isEnabled == true }
    val hasPerm = remember(tick) { hasBt(ctx) }
    val allFiles = remember(tick) { Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager() }
    val battOk = remember(tick) { ctx.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }
    var paused by remember(tick) { mutableStateOf(rxPaused(ctx)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { act.tick.intValue++ }

    LaunchedEffect(Unit) {
        val missing = neededPerms().filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }
    LaunchedEffect(tick, btOn, hasPerm, paused) {
        if (hasPerm && btOn && !paused) startRx(ctx)
    }

    val devices = remember(tick) {
        try {
            adapter?.bondedDevices?.toList() ?: emptyList()
        } catch (_: SecurityException) { emptyList<BluetoothDevice>() }
    }
    val busy = cs is ClientState.Connecting || cs is ClientState.Waiting
    val s = srv
    val statusPair: Pair<String, Color> = when {
        s is ServerState.Connected -> "متصل مع ${s.name}" to CGreen
        s is ServerState.Pending -> "طلب اتصال من ${s.name}" to CCyan
        s is ServerState.Listening -> "✓ هذا الهاتف جاهز لاستقبال اتصال من هاتفك الرئيسي" to CGreen
        paused -> "الاستقبال موقوف" to CDim
        !hasPerm -> "محتاج أذونات البلوتوث" to CRed
        !btOn -> "البلوتوث مقفول" to CRed
        else -> (srvMsg ?: "جاري التجهيز…") to CDim
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Brush.linearGradient(listOf(CCyan, CIndigo))), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Bluetooth, null, tint = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Bluetooth Link", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                Text("تحكم في هواتفك من هاتف واحد", color = CDim, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(20.dp))

        // ---- this phone (receiver) status
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CSurf).padding(16.dp)) {
            Text("هذا الهاتف (${Build.MODEL})", color = CDim, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).alpha(if (s is ServerState.Listening) pulse() else 1f).clip(CircleShape).background(statusPair.second))
                Spacer(Modifier.width(10.dp))
                Text(statusPair.first, color = statusPair.second, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp)
            }
            if (s is ServerState.Connected) {
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { sendSvc(ctx, LinkService.ACT_DISCONNECT) },
                    colors = ButtonDefaults.buttonColors(containerColor = CRed, contentColor = Color.Black)
                ) { Text("قطع الاتصال", fontWeight = FontWeight.Bold) }
            } else {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = {
                    if (paused || s !is ServerState.Listening) {
                        setRxPaused(ctx, false); paused = false; startRx(ctx)
                    } else {
                        setRxPaused(ctx, true); paused = true; sendSvc(ctx, LinkService.ACT_STOP)
                    }
                }) { Text(if (s is ServerState.Listening) "إيقاف الاستقبال مؤقتاً" else "تشغيل الاستقبال", color = CCyan) }
            }
        }

        // ---- problems with one-tap fixes
        if (!hasPerm) {
            Spacer(Modifier.height(12.dp)); Warn("لازم تمنح إذن \"الأجهزة القريبة\" وباقي الأذونات.")
            Spacer(Modifier.height(6.dp)); Button(onClick = { permLauncher.launch(neededPerms().toTypedArray()) }) { Text("منح الأذونات") }
        }
        if (!btOn) {
            Spacer(Modifier.height(12.dp)); Warn("البلوتوث مقفول. شغّله من هنا.")
            Spacer(Modifier.height(6.dp)); Button(onClick = { try { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (_: Exception) {} }) { Text("فتح إعدادات البلوتوث") }
        }
        if (!battOk) {
            Spacer(Modifier.height(12.dp)); Warn("عشان الهاتف يفضل جاهز للاستقبال حتى لو التطبيق مقفول، استثنِ التطبيق من توفير البطارية.")
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = {
                try { ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))) } catch (_: Exception) {}
            }) { Text("السماح بالعمل في الخلفية") }
        }
        val manu = Build.MANUFACTURER.lowercase()
        if (manu.contains("oppo") || manu.contains("realme") || manu.contains("oneplus") || manu.contains("xiaomi") || manu.contains("redmi") || manu.contains("huawei") || manu.contains("honor") || manu.contains("vivo")) {
            Spacer(Modifier.height(12.dp))
            Warn("هواتف ${Build.MANUFACTURER} بتقفل التطبيقات اللي في الخلفية بقوة زيادة، وده بيقطع الاتصال لو خرجت من التطبيق. لازم تفتح إعداد خاص بالشركة وتسمح للتطبيق يشتغل في الخلفية.")
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { openOemAutostart(ctx) }) { Text("فتح إعداد الشركة المصنّعة") }
        }
        if (!allFiles) {
            Spacer(Modifier.height(12.dp)); Warn("عشان الهاتف الرئيسي يتصفح ملفات هذا الهاتف، فعّل \"الوصول لكل الملفات\" (الصور والفيديو شغالة بدونه).")
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = {
                try { ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName))) }
                catch (_: Exception) { try { ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (_: Exception) {} }
            }) { Text("فتح الإعداد") }
        }

        // ---- my other phones
        Spacer(Modifier.height(26.dp))
        Text("هواتفي (الأجهزة المقترنة)", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("اضغط على الهاتف اللي عايز تتحكم فيه. هيطلب موافقة على شاشته.", color = CDim, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        when (val c = cs) {
            is ClientState.Connecting -> StatusLine("جاري الاتصال بـ ${c.name}…", CCyan, true)
            is ClientState.Waiting -> StatusLine("في انتظار موافقتك على شاشة ${c.name}…", CCyan, true)
            is ClientState.Failed -> StatusLine(c.msg, CRed, false)
            else -> {}
        }
        if (devices.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("مفيش أجهزة مقترنة لسه. اقرن هاتفك التاني من إعدادات البلوتوث، وبعدين ارجع هنا.", color = CDim, fontSize = 14.sp, lineHeight = 21.sp)
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
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { try { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (_: Exception) {} }) { Text("إقران هاتف جديد") }

        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { clearTrustedDevices(ctx) }) { Text("نسيان كل الأجهزة الموثوقة (رجّع سؤال الموافقة للكل)", color = CDim, fontSize = 12.sp) }

        Spacer(Modifier.height(16.dp))
        RemoteConnectCard(busy)

        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CSurf).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Shield, null, tint = CGreen)
            Spacer(Modifier.width(12.dp))
            Text("أي اتصال بيحتاج موافقتك على الهاتف الآخر، وبيظهر إشعار ثابت هناك طول الجلسة، وتقدر تقطعه في أي لحظة.", color = CDim, fontSize = 13.sp, lineHeight = 20.sp)
        }
        Spacer(Modifier.height(24.dp))
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
    var controlOpen by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val turbo by LinkClient.turbo.collectAsState()
    val upg by LinkClient.upgrading.collectAsState()
    val wmsg by LinkClient.wifiMsg.collectAsState()
    val titles = listOf("صور", "فيديو", "صوت", "ملفات", "تطبيقات", "جهات")
    val icons = listOf(Icons.Rounded.ImageIcon, Icons.Rounded.Videocam, Icons.Rounded.MusicNote, Icons.Rounded.Folder, Icons.Rounded.Apps, Icons.Rounded.Contacts)

    if (controlOpen) {
        RemoteControlScreen { controlOpen = false }
        return
    }

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
        if (turbo) {
            Button(
                onClick = { controlOpen = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp).height(46.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CIndigo)
            ) { Icon(Icons.Rounded.PhoneAndroid, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("تحكم كامل في الهاتف (شاشة + لمس)", fontWeight = FontWeight.Bold) }
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
                4 -> AppsTab()
                else -> ContactsTab()
            }
        }
        TransferBar()
    }
}

@Composable
fun AppsTab() {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val apps by LinkClient.appsList.collectAsState()
    var confirmUninstall by remember { mutableStateOf<RemoteApp?>(null) }
    var busyPkg by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { if (apps == null) LinkClient.loadApps() }

    val list = apps
    when {
        list == null -> Loading()
        list.isEmpty() -> Msg("تعذر عرض التطبيقات")
        else -> Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                Text("${list.size} تطبيق مثبت على الهاتف التاني", color = CDim, fontSize = 13.sp)
            }
            msg?.let { Text(it, color = CRed, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp)) }
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.pkg }) { a ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(CCard), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Apps, null, tint = CCyan)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.label, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(a.pkg, color = CDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (busyPkg == a.pkg) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = CCyan, strokeWidth = 2.dp)
                        } else {
                            IconButton(onClick = {
                                scope.launch {
                                    busyPkg = a.pkg
                                    try { LinkClient.launchApp(a.pkg) } catch (e: Exception) { msg = e.message }
                                    busyPkg = null
                                }
                            }) { Icon(Icons.Rounded.PlayArrow, null, tint = CGreen) }
                            if (!a.system) IconButton(onClick = { confirmUninstall = a }) { Icon(Icons.Rounded.Close, null, tint = CRed) }
                        }
                    }
                }
            }
        }
    }

    confirmUninstall?.let { a ->
        AlertDialog(
            onDismissRequest = { confirmUninstall = null }, containerColor = CSurf,
            title = { Text("حذف ${a.label}؟", color = Color.White) },
            text = { Text("هتظهر نافذة تأكيد على شاشة الهاتف التاني نفسه، لازم تتحكم فيها من شاشة التحكم الكامل.", color = CDim) },
            confirmButton = { Button(onClick = {
                val pkg = a.pkg; confirmUninstall = null
                scope.launch { try { LinkClient.uninstallApp(pkg); LinkClient.loadApps() } catch (e: Exception) { msg = e.message } }
            }, colors = ButtonDefaults.buttonColors(containerColor = CRed)) { Text("حذف") } },
            dismissButton = { TextButton(onClick = { confirmUninstall = null }) { Text("إلغاء") } }
        )
    }
}

@Composable
fun RemoteControlScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val frame by LinkClient.mirrorFrame.collectAsState()
    val err by LinkClient.mirrorError.collectAsState()
    var start by remember { mutableStateOf<Offset?>(null) }
    var boxSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    LaunchedEffect(Unit) { LinkClient.startMirror(ctx) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { LinkClient.stopMirror() } }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().background(CSurf).padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.Rounded.ArrowBack, null, tint = Color.White) }
            Text("تحكم كامل", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { LinkClient.sendKey(1) }) { Text("رجوع", color = CCyan, fontSize = 13.sp) }
            TextButton(onClick = { LinkClient.sendKey(2) }) { Text("الرئيسية", color = CCyan, fontSize = 13.sp) }
            TextButton(onClick = { LinkClient.sendKey(3) }) { Text("الأخيرة", color = CCyan, fontSize = 13.sp) }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .onSizeChanged { boxSize = it }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        start = down.position
                        var last = down.position
                        var moved = false
                        do {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) { last = c.position; break }
                            last = c.position
                            if ((last - down.position).getDistance() > 12f) moved = true
                        } while (true)
                        val w = boxSize.width.toFloat().coerceAtLeast(1f)
                        val h = boxSize.height.toFloat().coerceAtLeast(1f)
                        val nx1 = (down.position.x / w).coerceIn(0f, 1f); val ny1 = (down.position.y / h).coerceIn(0f, 1f)
                        if (!moved) {
                            LinkClient.sendTap(nx1, ny1)
                        } else {
                            val nx2 = (last.x / w).coerceIn(0f, 1f); val ny2 = (last.y / h).coerceIn(0f, 1f)
                            LinkClient.sendSwipe(nx1, ny1, nx2, ny2, 220)
                        }
                        start = null
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            val bmp = frame
            when {
                bmp != null -> Image(bmp.asImageBitmap(), null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                err != null -> Msg(err!!)
                else -> Loading()
            }
        }
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
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var items by remember(kind) { mutableStateOf<List<RemoteMedia>?>(null) }
    var err by remember(kind) { mutableStateOf<String?>(null) }
    val completed by LinkClient.completed.collectAsState()
    var confirmAll by remember(kind) { mutableStateOf(false) }
    var picked by remember(kind) { mutableStateOf<RemoteMedia?>(null) }
    var deleteMsg by remember(kind) { mutableStateOf<String?>(null) }
    var deletedIds by remember(kind) { mutableStateOf<Set<Long>>(emptySet()) }

    LaunchedEffect(kind) {
        items = null; err = null
        try { items = LinkClient.listMedia(kind, 0, 400) } catch (e: Exception) { err = e.message ?: "خطأ" }
    }

    val list = items?.filter { it.id !in deletedIds }
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
                                .clickable { picked = m }
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
                        FileRow(m.name, formatSize(m.size), Icons.Rounded.MusicNote, key in completed) { picked = m }
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

    picked?.let { m ->
        AlertDialog(
            onDismissRequest = { picked = null }, containerColor = CSurf,
            title = { Text(m.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = { Text(formatSize(m.size), color = CDim) },
            confirmButton = {
                Row {
                    TextButton(onClick = { LinkClient.pullMedia(ctx, kind, m.id, autoOpen = true); picked = null }) { Text("فتح") }
                    TextButton(onClick = { LinkClient.pullMedia(ctx, kind, m.id, autoOpen = false); picked = null }) { Text("سحب بس") }
                    TextButton(onClick = {
                        val id = m.id; picked = null
                        scope.launch {
                            try { LinkClient.deleteMedia(kind, id); deletedIds = deletedIds + id }
                            catch (e: Exception) { deleteMsg = e.message }
                        }
                    }) { Text("حذف", color = CRed) }
                }
            },
            dismissButton = { TextButton(onClick = { picked = null }) { Text("إلغاء") } }
        )
    }
    deleteMsg?.let { msg ->
        AlertDialog(
            onDismissRequest = { deleteMsg = null }, containerColor = CSurf,
            title = { Text("تعذر الحذف", color = Color.White) },
            text = { Text(msg, color = CDim) },
            confirmButton = { TextButton(onClick = { deleteMsg = null }) { Text("حسناً") } }
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
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var path by remember { mutableStateOf("") }
    var root by remember { mutableStateOf<String?>(null) }
    var cur by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<RemoteFile>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    val completed by LinkClient.completed.collectAsState()
    var picked by remember { mutableStateOf<RemoteFile?>(null) }
    var deleteMsg by remember { mutableStateOf<String?>(null) }
    var deletedNames by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(path) {
        items = null; err = null; deletedNames = emptySet()
        try {
            val (p, l) = LinkClient.listDir(path)
            cur = p
            if (root == null) root = p
            items = l
        } catch (e: Exception) { err = e.message ?: "خطأ" }
    }
    val c = cur
    BackHandler(enabled = c != null && c != root) { path = File(c!!).parent ?: "" }

    val list = items?.filter { it.name !in deletedNames }
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
                else FileRow(f.name, formatSize(f.size), Icons.Rounded.InsertDriveFile, "f:$full" in completed) { picked = f }
            }
        }
    }

    picked?.let { f ->
        val full = (cur ?: "") + "/" + f.name
        AlertDialog(
            onDismissRequest = { picked = null }, containerColor = CSurf,
            title = { Text(f.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = { Text(formatSize(f.size), color = CDim) },
            confirmButton = {
                Row {
                    TextButton(onClick = { LinkClient.pullFile(ctx, full, autoOpen = true); picked = null }) { Text("فتح") }
                    TextButton(onClick = { LinkClient.pullFile(ctx, full, autoOpen = false); picked = null }) { Text("سحب بس") }
                    TextButton(onClick = {
                        val name = f.name; picked = null
                        scope.launch {
                            try { LinkClient.deleteFile(full); deletedNames = deletedNames + name }
                            catch (e: Exception) { deleteMsg = e.message }
                        }
                    }) { Text("حذف", color = CRed) }
                }
            },
            dismissButton = { TextButton(onClick = { picked = null }) { Text("إلغاء") } }
        )
    }
    deleteMsg?.let { msg ->
        AlertDialog(
            onDismissRequest = { deleteMsg = null }, containerColor = CSurf,
            title = { Text("تعذر الحذف", color = Color.White) },
            text = { Text(msg, color = CDim) },
            confirmButton = { TextButton(onClick = { deleteMsg = null }) { Text("حسناً") } }
        )
    }
}

@Composable
fun ContactsTab() {
    val ctx = LocalContext.current
    var contacts by remember { mutableStateOf<List<RemoteContact>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(Unit) {
        try { contacts = LinkClient.listContacts() } catch (e: Exception) { err = e.message ?: "خطأ" }
    }

    val list = contacts
    val e = err
    when {
        e != null -> Msg(e)
        list == null -> Loading()
        list.isEmpty() -> Msg("مفيش جهات اتصال على الهاتف التاني")
        else -> Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${selected.size} من ${list.size} محدد", color = CDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    selected = if (selected.size == list.size) emptySet() else list.map { it.name + it.numbers.joinToString() }.toSet()
                }) { Text(if (selected.size == list.size) "إلغاء الكل" else "تحديد الكل", fontSize = 13.sp) }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(list, key = { it.name + it.numbers.joinToString() }) { c ->
                    val key = c.name + c.numbers.joinToString()
                    val checked = key in selected
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { selected = if (checked) selected - key else selected + key }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(24.dp).clip(CircleShape)
                                .background(if (checked) CCyan else CCard),
                            contentAlignment = Alignment.Center
                        ) { if (checked) Icon(Icons.Rounded.Check, null, tint = Color.Black, modifier = Modifier.size(16.dp)) }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(c.numbers.joinToString("، "), color = CDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Button(
                onClick = {
                    val picked = list.filter { (it.name + it.numbers.joinToString()) in selected }
                    if (picked.isNotEmpty()) LinkClient.saveSelectedContacts(ctx, picked)
                },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(14.dp).height(50.dp),
                shape = RoundedCornerShape(14.dp)
            ) { Text(if (selected.isEmpty()) "اختار جهات اتصال الأول" else "سحب ${selected.size} جهة اتصال محددة", fontWeight = FontWeight.Bold) }
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
