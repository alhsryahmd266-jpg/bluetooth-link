package com.btlink.receiver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.net.NetworkInterface

val CBg = Color(0xFF070B14)
val CSurf = Color(0xFF111A2B)
val CCard = Color(0xFF172238)
val CCyan = Color(0xFF22D3EE)
val CIndigo = Color(0xFF6366F1)
val CGreen = Color(0xFF34D399)
val CRed = Color(0xFFF87171)
val CDim = Color(0xFF8B9BB8)

fun localAddresses(): List<String> = try {
    NetworkInterface.getNetworkInterfaces().asSequence()
        .flatMap { it.inetAddresses.asSequence() }
        .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        .map { it.hostAddress ?: "" }
        .filter { it.isNotBlank() }
        .toList()
} catch (_: Exception) { emptyList() }

class MainActivity : ComponentActivity() {
    val tick = mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = CCyan, background = CBg, surface = CSurf, onBackground = Color.White, onSurface = Color.White)) {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Rtl) {
                    ReceiverApp(this)
                }
            }
        }
    }
    override fun onResume() { super.onResume(); tick.intValue++ }
}

@Composable
fun pulse(): Float {
    val t = rememberInfiniteTransition(label = "p")
    val v by t.animateFloat(0.9f, 1.08f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "pv")
    return v
}

fun openOemAutostart(ctx: android.content.Context) {
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

fun startRx(ctx: android.content.Context) {
    try { ctx.startForegroundService(Intent(ctx, LinkService::class.java).setAction(LinkService.ACT_START)) } catch (_: Exception) {}
}
fun sendSvc(ctx: android.content.Context, action: String) {
    try { ctx.startService(Intent(ctx, LinkService::class.java).setAction(action)) } catch (_: Exception) {}
}

@Composable
fun Warn(text: String) {
    Text(text, color = Color(0xFFFFD27A), fontSize = 13.sp, lineHeight = 20.sp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x22FFB020)).padding(12.dp))
}

@Composable
fun ReceiverApp(act: MainActivity) {
    val ctx = LocalContext.current
    val srv by Hub.server.collectAsState()
    val srvMsg by Hub.serverMsg.collectAsState()
    val tick = act.tick.intValue
    val hasPerm = remember(tick) { hasBt(ctx) }
    val btOn = remember(tick) { (ctx.getSystemService(android.bluetooth.BluetoothManager::class.java))?.adapter?.isEnabled == true }
    val battOk = remember(tick) { ctx.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }
    val allFiles = remember(tick) { Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager() }
    var paused by remember(tick) { mutableStateOf(rxPaused(ctx)) }
    val addrs = remember(tick) { localAddresses() }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { act.tick.intValue++ }
    LaunchedEffect(Unit) {
        val missing = neededPerms().filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }
    LaunchedEffect(tick, btOn, hasPerm, paused) {
        if (hasPerm && btOn && !paused) startRx(ctx)
    }

    val s = srv
    val statusPair: Pair<String, Color> = when {
        s is ServerState.Connected -> "متصل مع ${s.name}" to CGreen
        s is ServerState.Pending -> "طلب اتصال من ${s.name}" to CCyan
        s is ServerState.Listening -> "✓ جاهز لاستقبال اتصال من هاتفك الرئيسي" to CGreen
        paused -> "الاستقبال موقوف" to CDim
        !hasPerm -> "محتاج أذونات البلوتوث" to CRed
        !btOn -> "البلوتوث مقفول" to CRed
        else -> (srvMsg ?: "جاري التجهيز…") to CDim
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0D1730), CBg)))) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(20.dp))
            val p = if (s is ServerState.Listening) pulse() else 1f
            Box(Modifier.size(110.dp).scale(p).clip(CircleShape).background(statusPair.second.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Box(Modifier.size(72.dp).clip(CircleShape).background(statusPair.second.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                    Icon(if (s is ServerState.Connected) Icons.Rounded.BluetoothConnected else Icons.Rounded.Bluetooth, null, tint = statusPair.second, modifier = Modifier.size(38.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Bluetooth Link — استقبال", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text(statusPair.first, color = statusPair.second, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))

            if (!hasPerm) {
                Warn("لازم تمنح إذن الأجهزة القريبة وباقي الأذونات.")
                Spacer(Modifier.height(6.dp)); Button(onClick = { permLauncher.launch(neededPerms().toTypedArray()) }) { Text("منح الأذونات") }
                Spacer(Modifier.height(12.dp))
            }
            if (!btOn) {
                Warn("البلوتوث مقفول.")
                Spacer(Modifier.height(6.dp)); Button(onClick = { try { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (_: Exception) {} }) { Text("فتح إعدادات البلوتوث") }
                Spacer(Modifier.height(12.dp))
            }
            if (!battOk) {
                Warn("استثنِ التطبيق من توفير البطارية عشان يفضل شغال في الخلفية.")
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { try { ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))) } catch (_: Exception) {} }) { Text("السماح بالعمل في الخلفية") }
                Spacer(Modifier.height(12.dp))
            }
            val manu = Build.MANUFACTURER.lowercase()
            if (manu.contains("oppo") || manu.contains("realme") || manu.contains("oneplus") || manu.contains("xiaomi") || manu.contains("redmi") || manu.contains("huawei") || manu.contains("honor") || manu.contains("vivo")) {
                Warn("هواتف ${Build.MANUFACTURER} بتقفل تطبيقات الخلفية بقوة زيادة. افتح إعداد الشركة واسمح للتطبيق يشتغل في الخلفية.")
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { openOemAutostart(ctx) }) { Text("فتح إعداد الشركة المصنّعة") }
                Spacer(Modifier.height(12.dp))
            }
            if (!allFiles) {
                Warn("فعّل \"الوصول لكل الملفات\" عشان الهاتف الرئيسي يتصفح كل ملفاتك (الصور والفيديو شغالة بدونه).")
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = {
                    try { ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName))) }
                    catch (_: Exception) { try { ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (_: Exception) {} }
                }) { Text("فتح الإعداد") }
                Spacer(Modifier.height(12.dp))
            }

            if (s is ServerState.Connected) {
                Button(onClick = { sendSvc(ctx, LinkService.ACT_DISCONNECT) }, colors = ButtonDefaults.buttonColors(containerColor = CRed, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) { Text("قطع الاتصال", fontWeight = FontWeight.Bold) }
            } else {
                OutlinedButton(onClick = {
                    if (paused || s !is ServerState.Listening) { setRxPaused(ctx, false); paused = false; startRx(ctx) }
                    else { setRxPaused(ctx, true); paused = true; sendSvc(ctx, LinkService.ACT_STOP) }
                }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp)) {
                    Text(if (s is ServerState.Listening) "إيقاف الاستقبال مؤقتاً" else "تشغيل الاستقبال")
                }
            }

            Spacer(Modifier.height(10.dp))
            TextButton(onClick = { clearTrustedDevices(ctx) }) { Text("نسيان كل الأجهزة الموثوقة", color = CDim, fontSize = 12.sp) }

            if (addrs.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CSurf).padding(14.dp)) {
                    Text("عنوان هذا الهاتف (للاتصال عن بعد)", color = CDim, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    for (a in addrs) Text(a, color = Color.White, fontSize = 14.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    Spacer(Modifier.height(6.dp))
                    Text("اكتب أحد العناوين دي في خانة \"الاتصال عن بعد\" بالهاتف الرئيسي.", color = CDim, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CSurf).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Shield, null, tint = CGreen)
                Spacer(Modifier.width(12.dp))
                Text("كل اتصال بيحتاج موافقتك هنا أول مرة، وتقدر تخليه يتصل تلقائياً بعد كده.", color = CDim, fontSize = 12.sp, lineHeight = 18.sp)
            }
            Spacer(Modifier.height(20.dp))
        }

        if (s is ServerState.Pending) {
            var remember by remember(s) { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = {}, containerColor = CSurf,
                icon = { Icon(Icons.Rounded.Shield, null, tint = CCyan) },
                title = { Text("طلب اتصال بلوتوث", color = Color.White) },
                text = {
                    Column {
                        Text("الهاتف \"${s.name}\" يطلب الاتصال بهذا الهاتف. هل توافق؟", color = CDim)
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
                confirmButton = { Button(onClick = { sendSvc(ctx, if (remember) LinkService.ACT_ALLOW_REMEMBER else LinkService.ACT_ALLOW) }) { Text("موافق") } },
                dismissButton = { TextButton(onClick = { sendSvc(ctx, LinkService.ACT_DENY) }) { Text("رفض", color = CRed) } }
            )
        }
    }
}
