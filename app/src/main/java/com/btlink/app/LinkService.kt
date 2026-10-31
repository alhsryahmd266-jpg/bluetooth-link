package com.btlink.app

import android.app.Notification
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.ContentUris
import android.content.ContentResolver
import android.content.Intent
import android.app.Activity
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.Settings
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Environment
import android.os.Looper
import android.os.IBinder
import android.provider.ContactsContract
import android.provider.MediaStore
import android.util.Size
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class LinkService : Service() {

    companion object {
        const val ACT_START = "com.btlink.START"
        const val ACT_STOP = "com.btlink.STOP"
        const val ACT_ALLOW = "com.btlink.ALLOW"
        const val ACT_ALLOW_REMEMBER = "com.btlink.ALLOW_REMEMBER"
        const val ACT_DENY = "com.btlink.DENY"
        const val ACT_DISCONNECT = "com.btlink.DISCONNECT"
        const val ACT_CLIENT = "com.btlink.CLIENT"
        private const val CH = "link_session"
        private const val CH_REQ = "link_request"
        private const val NID = 1
        private const val NID_REQ = 2
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var nm: NotificationManager
    @Volatile private var listening = false
    @Volatile private var started = false
    private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var active: Closeable? = null
    @Volatile private var remoteServer: ServerSocket? = null
    @Volatile private var remoteListening = false
    @Volatile private var decision: CompletableFuture<Boolean>? = null
    @Volatile private var pendingAddr: String? = null
    private var p2pCh: WifiP2pManager.Channel? = null
    private var tcpServer: ServerSocket? = null
    @Volatile private var tcp: Socket? = null

    @Volatile private var projection: MediaProjection? = null
    @Volatile private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var imageReader: ImageReader? = null
    @Volatile private var mirrorServer: ServerSocket? = null
    @Volatile private var mirrorOn = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CH, "جلسة البلوتوث", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_REQ, "طلبات الاتصال", NotificationManager.IMPORTANCE_HIGH))
        }
        scope.launch {
            combine(Hub.server, LinkClient.state) { s, c -> s to c }.collect { (s, c) ->
                if (started) {
                    val idle = s is ServerState.Off && (c is ClientState.Idle || c is ClientState.Failed)
                    if (idle) shutdown() else nm.notify(NID, buildNotification(s, c))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_START -> { if (goForeground()) { started = true; startListening() } }
            ACT_CLIENT -> { if (goForeground()) started = true }
            ACT_STOP -> stopListening()
            ACT_ALLOW -> decision?.complete(true)
            ACT_ALLOW_REMEMBER -> { pendingAddr?.let { addTrustedDevice(this, it) }; decision?.complete(true) }
            ACT_DENY -> decision?.complete(false)
            ACT_DISCONNECT -> {
                try { active?.close() } catch (_: Exception) {}
                LinkClient.disconnect()
            }
        }
        if (!started) stopSelf()
        return START_NOT_STICKY
    }

    private fun goForeground(): Boolean = try {
        val notif = buildNotification(Hub.server.value, LinkClient.state.value)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NID, notif)
        }
        true
    } catch (e: Exception) {
        Hub.serverMsg.value = "تعذر بدء الخدمة (امنح الأذونات): ${e.message}"
        stopSelf(); false
    }

    private fun shutdown() {
        started = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopListening()
        scope.cancel()
        super.onDestroy()
    }

    private fun pi(action: String, code: Int): PendingIntent =
        PendingIntent.getService(this, code, Intent(this, LinkService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun openApp(): PendingIntent =
        PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun buildNotification(s: ServerState, c: ClientState): Notification {
        val text = when {
            s is ServerState.Connected -> "🔵 متصل ببلوتوث مع ${s.name} — جلسة سحب نشطة"
            s is ServerState.Pending -> "طلب اتصال من ${s.name}"
            c is ClientState.Connected -> "🔵 متصل ببلوتوث مع ${c.name}"
            c is ClientState.Connecting || c is ClientState.Waiting -> "جاري الاتصال…"
            else -> "الاستقبال شغال — في انتظار هاتفك الرئيسي"
        }
        val b = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_bt)
            .setContentTitle("Bluetooth Link")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp())
        if (s is ServerState.Connected || c is ClientState.Connected) {
            b.addAction(0, "قطع الاتصال", pi(ACT_DISCONNECT, 1))
        } else if (s is ServerState.Listening) {
            b.addAction(0, "إيقاف الاستقبال", pi(ACT_STOP, 2))
        }
        return b.build()
    }

    private fun showRequest(name: String) {
        val n = NotificationCompat.Builder(this, CH_REQ)
            .setSmallIcon(R.drawable.ic_bt)
            .setContentTitle("طلب اتصال بلوتوث")
            .setContentText("الهاتف \"$name\" يريد الاتصال وسحب ملفات من هذا الهاتف")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setTimeoutAfter(60_000)
            .setContentIntent(openApp())
            .addAction(0, "موافق", pi(ACT_ALLOW, 3))
            .addAction(0, "رفض", pi(ACT_DENY, 4))
            .build()
        nm.notify(NID_REQ, n)
    }

    // ---------------- listening ----------------

    private fun startListening() {
        if (!listening) {
            val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
            if (adapter == null || !adapter.isEnabled) {
                Hub.serverMsg.value = "البلوتوث مقفول"
            } else {
                Hub.serverMsg.value = null
                listening = true
                thread(name = "bt-accept") {
                    try {
                        val ss = adapter.listenUsingRfcommWithServiceRecord("BluetoothLink", P.APP_UUID)
                        serverSocket = ss
                        while (listening) {
                            val s = ss.accept()
                            acceptSession(s, s.inputStream, s.outputStream)
                        }
                    } catch (e: Exception) {
                        if (listening) Hub.serverMsg.value = "تعذر تشغيل الاستقبال: ${e.message}"
                    } finally {
                        listening = false
                        try { serverSocket?.close() } catch (_: Exception) {}
                        serverSocket = null
                        if (active == null) Hub.server.value = ServerState.Off
                    }
                }
            }
        }
        startRemoteListening()
        if (Hub.server.value == ServerState.Off && (listening || remoteListening)) Hub.server.value = ServerState.Listening
    }

    /** Always-on plain TCP listener, reachable over any IP network that routes to this device — including a Tailscale / VPN virtual interface, with no extra setup beyond both phones joining the same tailnet. */
    private fun startRemoteListening() {
        if (remoteListening) return
        remoteListening = true
        thread(name = "remote-accept") {
            try {
                val ss = ServerSocket(P.TCP_PORT)
                remoteServer = ss
                while (remoteListening) {
                    val s = ss.accept()
                    s.tcpNoDelay = true
                    acceptSession(s, s.getInputStream(), s.getOutputStream())
                }
            } catch (_: Exception) {
            } finally {
                remoteListening = false
                try { remoteServer?.close() } catch (_: Exception) {}
                remoteServer = null
            }
        }
    }

    private fun stopListening() {
        listening = false
        remoteListening = false
        decision?.complete(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        try { remoteServer?.close() } catch (_: Exception) {}
        try { active?.close() } catch (_: Exception) {}
        cleanupWifi()
        Hub.server.value = ServerState.Off
    }

    // ---------------- session ----------------

    private fun acceptSession(closeable: Closeable, insRaw: java.io.InputStream, outsRaw: java.io.OutputStream) {
        if (active != null) {
            try { closeable.close() } catch (_: Exception) {}
            return
        }
        active = closeable
        thread(name = "bl-session") { handle(closeable, insRaw, outsRaw) }
    }

    private fun handle(closeable: Closeable, insRaw: java.io.InputStream, outsRaw: java.io.OutputStream) {
        try {
            val inp = DataInputStream(BufferedInputStream(insRaw, P.BUF))
            val out = DataOutputStream(BufferedOutputStream(outsRaw, P.BUF))
            if (inp.readInt() != P.MAGIC || inp.readInt() != P.HELLO) return
            val remote = inp.readUTF()
            val addr = (closeable as? BluetoothSocket)?.remoteDevice?.address

            val ok: Boolean
            if (addr != null && isTrustedDevice(this, addr)) {
                // Trusted device from a previous session: skip the prompt, but still tell the person it connected.
                ok = true
                Hub.server.value = ServerState.Connected(remote)
                try {
                    nm.notify(NID, NotificationCompat.Builder(this, CH).setSmallIcon(R.drawable.ic_bt)
                        .setContentTitle("Bluetooth Link").setContentText("اتصال تلقائي من جهاز موثوق: $remote")
                        .setOngoing(true).setContentIntent(openApp()).build())
                } catch (_: Exception) {}
            } else {
                val fut = CompletableFuture<Boolean>()
                decision = fut
                pendingAddr = addr
                Hub.server.value = ServerState.Pending(remote, addr)
                showRequest(remote)
                ok = try { fut.get(60, TimeUnit.SECONDS) } catch (_: Exception) { false }
                nm.cancel(NID_REQ)
                decision = null
                pendingAddr = null
            }
            out.writeInt(if (ok) P.OK else P.ERR); out.flush()
            if (!ok) { Thread.sleep(300); return }
            Hub.server.value = ServerState.Connected(remote)
            serve(inp, out)
        } catch (_: Exception) {
        } finally {
            try { closeable.close() } catch (_: Exception) {}
            cleanupWifi()
            active = null
            nm.cancel(NID_REQ)
            Hub.server.value = if (listening || remoteListening) ServerState.Listening else ServerState.Off
        }
    }

    private fun serve(inp: DataInputStream, out: DataOutputStream) {
        while (true) {
            val op = inp.readInt()
            try {
                when (op) {
                    P.LIST_DIR -> listDir(inp.readUTF(), out)
                    P.LIST_MEDIA -> listMedia(inp.readInt(), inp.readInt(), inp.readInt(), out)
                    P.THUMB -> thumb(inp.readInt(), inp.readLong(), out)
                    P.PULL_FILE -> pullFile(inp.readUTF(), out)
                    P.PULL_MEDIA -> pullMedia(inp.readInt(), inp.readLong(), out)
                    P.CONTACTS -> contacts(out)
                    P.UPGRADE -> upgrade(out)
                    P.MIRROR -> mirror(out)
                    P.APP_LIST -> appsList(out)
                    P.APP_LAUNCH -> appLaunch(inp.readUTF(), out)
                    P.APP_UNINSTALL -> appUninstall(inp.readUTF(), out)
                    P.CONTACTS_LIST -> contactsList(out)
                    else -> return
                }
            } catch (e: java.io.IOException) {
                throw e
            } catch (t: Throwable) {
                try { out.writeInt(P.ERR); out.writeUTF("خطأ غير متوقع على الهاتف المستقبل: ${t.message ?: t.javaClass.simpleName}") } catch (_: Exception) {}
            }
            out.flush()
        }
    }

    private fun stopMirror() {
        mirrorOn = false
        try { imageReader?.close() } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { mirrorServer?.close() } catch (_: Exception) {}
        imageReader = null; virtualDisplay = null; projection = null; mirrorServer = null
    }

    private fun imageToJpeg(img: android.media.Image): ByteArray {
        val plane = img.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * img.width
        val bmpW = img.width + rowPadding / pixelStride
        var bmp = Bitmap.createBitmap(bmpW, img.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(plane.buffer)
        if (rowPadding != 0) bmp = Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
        val bo = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 55, bo)
        return bo.toByteArray()
    }

    private fun mirror(out: DataOutputStream) {
        if (RemoteControlService.instance == null) {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
            err(out, "فعّل \"Bluetooth Link\" في إعدادات إمكانية الوصول على هذا الهاتف (فتحنا لك الشاشة)، ثم اطلب التحكم تاني")
            return
        }
        stopMirror()
        val fut = CompletableFuture<Pair<Int, Intent?>>()
        MirrorBridge.pending = fut
        try {
            startActivity(Intent(this, MirrorPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            err(out, "تعذر فتح نافذة موافقة مشاركة الشاشة"); return
        }
        val (code, data) = try { fut.get(40, TimeUnit.SECONDS) } catch (_: Exception) { Activity.RESULT_CANCELED to null }
        if (code != Activity.RESULT_OK || data == null) { err(out, "تم رفض مشاركة الشاشة أو انتهت المهلة"); return }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = try { mpm.getMediaProjection(code, data) } catch (e: Exception) { err(out, "تعذر بدء مشاركة الشاشة: ${e.message}"); return }
        projection = proj

        val metrics = resources.displayMetrics
        val realW = metrics.widthPixels
        val realH = metrics.heightPixels
        val scale = minOf(1f, 820f / maxOf(realW, realH))
        val cw = (realW * scale).toInt().coerceAtLeast(2) / 2 * 2
        val ch = (realH * scale).toInt().coerceAtLeast(2) / 2 * 2

        val reader = ImageReader.newInstance(cw, ch, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = proj.createVirtualDisplay(
            "bl-mirror", cw, ch, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null
        )

        val ss = ServerSocket(0)
        mirrorServer = ss
        mirrorOn = true
        val port = ss.localPort

        thread(name = "mirror-accept") {
            try {
                ss.soTimeout = 20_000
                val frameSocket = ss.accept()
                val inputSocket = ss.accept()
                frameSocket.tcpNoDelay = true
                val fo = DataOutputStream(BufferedOutputStream(frameSocket.getOutputStream(), 256 * 1024))

                reader.setOnImageAvailableListener({ r ->
                    if (!mirrorOn) return@setOnImageAvailableListener
                    val img = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
                    try {
                        val jpg = imageToJpeg(img)
                        synchronized(fo) {
                            fo.writeInt(jpg.size); fo.write(jpg); fo.flush()
                        }
                    } catch (_: Exception) {
                    } finally {
                        try { img.close() } catch (_: Exception) {}
                    }
                }, null)

                val ii = DataInputStream(BufferedInputStream(inputSocket.getInputStream(), 8192))
                val rc = RemoteControlService.instance
                while (mirrorOn) {
                    val type = ii.readByte().toInt()
                    when (type) {
                        1 -> { val x = ii.readFloat() * realW; val y = ii.readFloat() * realH; rc?.tap(x, y) }
                        2 -> {
                            val x1 = ii.readFloat() * realW; val y1 = ii.readFloat() * realH
                            val x2 = ii.readFloat() * realW; val y2 = ii.readFloat() * realH
                            val d = ii.readInt()
                            rc?.swipe(x1, y1, x2, y2, d.toLong())
                        }
                        3 -> when (ii.readByte().toInt()) { 1 -> rc?.back(); 2 -> rc?.home(); 3 -> rc?.recents() }
                        else -> break
                    }
                }
                try { frameSocket.close() } catch (_: Exception) {}
                try { inputSocket.close() } catch (_: Exception) {}
            } catch (_: Exception) {
            } finally {
                stopMirror()
            }
        }
        out.writeInt(P.OK); out.writeInt(port); out.writeInt(realW); out.writeInt(realH)
    }

    private fun appsList(out: DataOutputStream) {
        val apps = try {
            packageManager.getInstalledApplications(0)
                .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
                .map { RemoteApp(packageManager.getApplicationLabel(it).toString(), it.packageName, (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0) }
                .sortedBy { it.label.lowercase() }
        } catch (e: Exception) { err(out, "تعذرت قراءة التطبيقات: ${e.message}"); return }
        out.writeInt(P.OK); out.writeInt(apps.size)
        for (a in apps) { out.writeUTF(a.label); out.writeUTF(a.pkg); out.writeBoolean(a.system) }
    }

    private fun appLaunch(pkg: String, out: DataOutputStream) {
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) { err(out, "تعذر فتح التطبيق"); return }
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            out.writeInt(P.OK)
        } catch (e: Exception) { err(out, "تعذر فتح التطبيق: ${e.message}") }
    }

    private fun appUninstall(pkg: String, out: DataOutputStream) {
        try {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            out.writeInt(P.OK)
        } catch (e: Exception) { err(out, "تعذر فتح نافذة إلغاء التثبيت: ${e.message}") }
    }

    private fun cleanupWifi() {
        stopMirror()
        try { tcp?.close() } catch (_: Exception) {}
        try { tcpServer?.close() } catch (_: Exception) {}
        tcp = null; tcpServer = null
        try {
            val wp = getSystemService(WifiP2pManager::class.java)
            val ch = p2pCh
            if (wp != null && ch != null) wp.removeGroup(ch, null)
        } catch (_: Exception) {}
    }

    private fun listener(f: CompletableFuture<Boolean>) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() { f.complete(true) }
        override fun onFailure(reason: Int) { f.complete(false) }
    }

    /** Create a Wi-Fi Direct group (5GHz when possible) + a TCP server; client then joins and streams over Wi-Fi. */
    @SuppressLint("MissingPermission")
    private fun upgrade(out: DataOutputStream) {
        cleanupWifi()
        val wp = getSystemService(WifiP2pManager::class.java)
        if (wp == null) { err(out, "الواي فاي دايركت غير مدعوم على الهاتف المستقبل"); return }
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        fun rnd(n: Int) = (1..n).map { chars.random() }.joinToString("")
        val ssid = "DIRECT-bl-" + rnd(5)
        val pass = rnd(12)
        var ok = false
        try {
            val ch = p2pCh ?: wp.initialize(this, Looper.getMainLooper(), null).also { p2pCh = it }
            val rm = CompletableFuture<Boolean>()
            wp.removeGroup(ch, listener(rm))
            try { rm.get(3, TimeUnit.SECONDS) } catch (_: Exception) {}
            if (Build.VERSION.SDK_INT < 29) { err(out, "الوضع السريع يحتاج أندرويد 10 أو أحدث على هذا الهاتف، وسيعمل التطبيق عبر البلوتوث"); return }
            for (band in intArrayOf(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ, WifiP2pConfig.GROUP_OWNER_BAND_AUTO)) {
                val cfg = WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(pass).setGroupOperatingBand(band).build()
                val f = CompletableFuture<Boolean>()
                wp.createGroup(ch, cfg, listener(f))
                ok = try { f.get(12, TimeUnit.SECONDS) } catch (_: Exception) { false }
                if (ok) break
                val rm2 = CompletableFuture<Boolean>()
                wp.removeGroup(ch, listener(rm2))
                try { rm2.get(3, TimeUnit.SECONDS) } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            ok = false
        }
        if (!ok) { err(out, "تعذر تشغيل Wi-Fi Direct على الهاتف المستقبل (شغّل الواي فاي وامنح الأذونات)"); return }

        val ss = ServerSocket(0)
        tcpServer = ss
        thread(name = "bt-tcp") {
            try {
                ss.soTimeout = 45_000
                val s = ss.accept()
                try { ss.close() } catch (_: Exception) {}
                s.tcpNoDelay = true
                tcp = s
                serve(
                    DataInputStream(BufferedInputStream(s.getInputStream(), P.BUF)),
                    DataOutputStream(BufferedOutputStream(s.getOutputStream(), P.BUF))
                )
            } catch (_: Exception) {
            } finally {
                try { tcp?.close() } catch (_: Exception) {}
            }
        }
        out.writeInt(P.OK); out.writeUTF(ssid); out.writeUTF(pass); out.writeInt(ss.localPort)
    }

    private fun err(out: DataOutputStream, msg: String) {
        out.writeInt(P.ERR); out.writeUTF(msg)
    }

    private fun safe(path: String): File? {
        val root = Environment.getExternalStorageDirectory().canonicalFile
        val f = (if (path.isEmpty()) root else File(path)).canonicalFile
        return if (f.path == root.path || f.path.startsWith(root.path + "/")) f else null
    }

    private fun allFilesOk(): Boolean = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    private fun listDir(path: String, out: DataOutputStream) {
        val dir = safe(path)
        val files = if (allFilesOk()) dir?.listFiles() else null
        if (dir == null || files == null) {
            err(out, "فعّل \"الوصول لكل الملفات\" للتطبيق على الهاتف المستقبل عشان تتصفح الملفات"); return
        }
        val sorted = files.filter { !it.isHidden }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        out.writeInt(P.OK); out.writeUTF(dir.path); out.writeInt(sorted.size)
        for (f in sorted) {
            out.writeUTF(f.name); out.writeBoolean(f.isDirectory)
            out.writeLong(if (f.isDirectory) 0L else f.length()); out.writeLong(f.lastModified())
        }
    }

    private fun collection(kind: Int) = when (kind) {
        0 -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        1 -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }

    private fun listMedia(kind: Int, offset: Int, limit: Int, out: DataOutputStream) {
        val rows = ArrayList<RemoteMedia>()
        try {
            val b = Bundle().apply {
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
                putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_ADDED))
                putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            }
            val proj = arrayOf(
                MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.MIME_TYPE
            )
            contentResolver.query(collection(kind), proj, b, null)?.use { c ->
                while (c.moveToNext()) {
                    rows.add(RemoteMedia(c.getLong(0), c.getString(1) ?: "file", c.getLong(2), c.getLong(3), c.getString(4) ?: "application/octet-stream"))
                }
            }
        } catch (e: Exception) {
            err(out, "لا يوجد إذن قراءة الصور/الفيديو على الهاتف المستقبل"); return
        }
        out.writeInt(P.OK); out.writeInt(rows.size)
        for (m in rows) {
            out.writeLong(m.id); out.writeUTF(m.name); out.writeLong(m.size); out.writeLong(m.date); out.writeUTF(m.mime)
        }
    }

    private fun thumb(kind: Int, id: Long, out: DataOutputStream) {
        val bytes = try {
            val bmp: Bitmap? = if (Build.VERSION.SDK_INT >= 29) {
                val uri = ContentUris.withAppendedId(collection(kind), id)
                contentResolver.loadThumbnail(uri, Size(200, 200), null)
            } else {
                @Suppress("DEPRECATION")
                when (kind) {
                    0 -> MediaStore.Images.Thumbnails.getThumbnail(contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
                    1 -> MediaStore.Video.Thumbnails.getThumbnail(contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
                    else -> null
                }
            }
            if (bmp == null) ByteArray(0) else {
                val bo = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 65, bo)
                bo.toByteArray()
            }
        } catch (_: Throwable) {
            ByteArray(0)
        }
        out.writeInt(bytes.size); out.write(bytes)
    }

    private fun sendStream(ins: InputStream, out: DataOutputStream, size: Long) {
        val buf = ByteArray(P.BUF)
        var left = size
        while (left > 0) {
            val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw EOFException()
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun pullFile(path: String, out: DataOutputStream) {
        val f = safe(path)
        if (f == null || !f.isFile || !allFilesOk()) { err(out, "الملف غير متاح"); return }
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
        val ins = try { f.inputStream() } catch (_: Exception) { null }
        if (ins == null) { err(out, "تعذر فتح الملف"); return }
        ins.use {
            val size = f.length()
            out.writeInt(P.OK); out.writeUTF(f.name); out.writeUTF(mime); out.writeLong(size)
            sendStream(it, out, size)
        }
    }

    private fun pullMedia(kind: Int, id: Long, out: DataOutputStream) {
        val uri = ContentUris.withAppendedId(collection(kind), id)
        var name = "file"
        var mime = "application/octet-stream"
        var size = -1L
        try {
            val proj = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE)
            contentResolver.query(uri, proj, null, null, null)?.use {
                if (it.moveToFirst()) { name = it.getString(0) ?: name; mime = it.getString(1) ?: mime; size = it.getLong(2) }
            }
        } catch (_: Exception) {}
        if (size < 0) { err(out, "الملف غير موجود"); return }
        val ins = try { contentResolver.openInputStream(uri) } catch (_: Exception) { null }
        if (ins == null) { err(out, "تعذر فتح الملف"); return }
        ins.use {
            out.writeInt(P.OK); out.writeUTF(name); out.writeUTF(mime); out.writeLong(size)
            sendStream(it, out, size)
        }
    }

    private fun contactsList(out: DataOutputStream) {
        val map = LinkedHashMap<String, MutableSet<String>>()
        try {
            val proj = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
            contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(0) ?: continue
                    val p = c.getString(1) ?: continue
                    map.getOrPut(n) { LinkedHashSet() }.add(p)
                }
            }
        } catch (e: Exception) {
            err(out, "لا يوجد إذن قراءة جهات الاتصال على الهاتف المستقبل"); return
        }
        out.writeInt(P.OK); out.writeInt(map.size)
        for ((n, nums) in map) {
            out.writeUTF(n); out.writeInt(nums.size)
            for (p in nums) out.writeUTF(p)
        }
    }

    private fun contacts(out: DataOutputStream) {
        val map = LinkedHashMap<String, MutableSet<String>>()
        try {
            val proj = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
            contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(0) ?: continue
                    val p = c.getString(1) ?: continue
                    map.getOrPut(n) { LinkedHashSet() }.add(p)
                }
            }
        } catch (_: Exception) {
            err(out, "لا يوجد إذن قراءة جهات الاتصال على الهاتف المستقبل"); return
        }
        val sb = StringBuilder()
        for ((n, nums) in map) {
            sb.append("BEGIN:VCARD\nVERSION:3.0\nFN:").append(n).append('\n')
            for (p in nums) sb.append("TEL:").append(p).append('\n')
            sb.append("END:VCARD\n")
        }
        val bytes = sb.toString().toByteArray(Charsets.UTF_8)
        out.writeInt(P.OK); out.writeUTF("contacts.vcf"); out.writeUTF("text/x-vcard"); out.writeLong(bytes.size.toLong())
        out.write(bytes)
    }
}
