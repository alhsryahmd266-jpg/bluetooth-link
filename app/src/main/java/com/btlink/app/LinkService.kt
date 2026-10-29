package com.btlink.app

import android.app.Notification
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
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
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
    @Volatile private var active: BluetoothSocket? = null
    @Volatile private var decision: CompletableFuture<Boolean>? = null
    private var p2pCh: WifiP2pManager.Channel? = null
    private var tcpServer: ServerSocket? = null
    @Volatile private var tcp: Socket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "جلسة البلوتوث", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_REQ, "طلبات الاتصال", NotificationManager.IMPORTANCE_HIGH))
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
        startForeground(NID, buildNotification(Hub.server.value, LinkClient.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        true
    } catch (e: Exception) {
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
        val b = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_bt)
            .setContentTitle("Bluetooth Link")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp())
        if (s is ServerState.Connected || c is ClientState.Connected) {
            b.addAction(Notification.Action.Builder(null, "قطع الاتصال", pi(ACT_DISCONNECT, 1)).build())
        } else if (s is ServerState.Listening) {
            b.addAction(Notification.Action.Builder(null, "إيقاف الاستقبال", pi(ACT_STOP, 2)).build())
        }
        return b.build()
    }

    private fun showRequest(name: String) {
        val n = Notification.Builder(this, CH_REQ)
            .setSmallIcon(R.drawable.ic_bt)
            .setContentTitle("طلب اتصال بلوتوث")
            .setContentText("الهاتف \"$name\" يريد الاتصال وسحب ملفات من هذا الهاتف")
            .setCategory(Notification.CATEGORY_CALL)
            .setTimeoutAfter(60_000)
            .setContentIntent(openApp())
            .addAction(Notification.Action.Builder(null, "موافق", pi(ACT_ALLOW, 3)).build())
            .addAction(Notification.Action.Builder(null, "رفض", pi(ACT_DENY, 4)).build())
            .build()
        nm.notify(NID_REQ, n)
    }

    // ---------------- listening ----------------

    private fun startListening() {
        if (listening) return
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            Hub.server.value = ServerState.Off
            return
        }
        listening = true
        Hub.server.value = ServerState.Listening
        thread(name = "bt-accept") {
            try {
                val ss = adapter.listenUsingRfcommWithServiceRecord("BluetoothLink", P.APP_UUID)
                serverSocket = ss
                while (listening) {
                    val s = ss.accept()
                    if (active != null) {
                        try { s.close() } catch (_: Exception) {}
                        continue
                    }
                    active = s
                    thread(name = "bt-session") { handle(s) }
                }
            } catch (_: Exception) {
            } finally {
                listening = false
                try { serverSocket?.close() } catch (_: Exception) {}
                serverSocket = null
                if (active == null) Hub.server.value = ServerState.Off
            }
        }
    }

    private fun stopListening() {
        listening = false
        decision?.complete(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        try { active?.close() } catch (_: Exception) {}
        cleanupWifi()
        Hub.server.value = ServerState.Off
    }

    // ---------------- session ----------------

    private fun handle(s: BluetoothSocket) {
        try {
            val inp = DataInputStream(BufferedInputStream(s.inputStream, P.BUF))
            val out = DataOutputStream(BufferedOutputStream(s.outputStream, P.BUF))
            if (inp.readInt() != P.MAGIC || inp.readInt() != P.HELLO) return
            val remote = inp.readUTF()
            val fut = CompletableFuture<Boolean>()
            decision = fut
            Hub.server.value = ServerState.Pending(remote)
            showRequest(remote)
            val ok = try { fut.get(60, TimeUnit.SECONDS) } catch (_: Exception) { false }
            nm.cancel(NID_REQ)
            decision = null
            out.writeInt(if (ok) P.OK else P.ERR); out.flush()
            if (!ok) { Thread.sleep(300); return }
            Hub.server.value = ServerState.Connected(remote)
            serve(inp, out)
        } catch (_: Exception) {
        } finally {
            try { s.close() } catch (_: Exception) {}
            cleanupWifi()
            active = null
            nm.cancel(NID_REQ)
            Hub.server.value = if (listening) ServerState.Listening else ServerState.Off
        }
    }

    private fun serve(inp: DataInputStream, out: DataOutputStream) {
        while (true) {
            when (inp.readInt()) {
                P.LIST_DIR -> listDir(inp.readUTF(), out)
                P.LIST_MEDIA -> listMedia(inp.readInt(), inp.readInt(), inp.readInt(), out)
                P.THUMB -> thumb(inp.readInt(), inp.readLong(), out)
                P.PULL_FILE -> pullFile(inp.readUTF(), out)
                P.PULL_MEDIA -> pullMedia(inp.readInt(), inp.readLong(), out)
                P.CONTACTS -> contacts(out)
                P.UPGRADE -> upgrade(out)
                else -> return
            }
            out.flush()
        }
    }

    private fun cleanupWifi() {
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

    private fun listDir(path: String, out: DataOutputStream) {
        val dir = safe(path)
        val files = if (Environment.isExternalStorageManager()) dir?.listFiles() else null
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
            val uri = ContentUris.withAppendedId(collection(kind), id)
            val bmp: Bitmap = contentResolver.loadThumbnail(uri, Size(200, 200), null)
            val bo = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 65, bo)
            bo.toByteArray()
        } catch (_: Exception) {
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
        if (f == null || !f.isFile || !Environment.isExternalStorageManager()) { err(out, "الملف غير متاح"); return }
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
