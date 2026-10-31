package com.btlink.app

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.media.MediaScannerConnection
import androidx.core.content.FileProvider
import android.provider.MediaStore
import android.util.LruCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

sealed class ClientState {
    object Idle : ClientState()
    data class Connecting(val name: String) : ClientState()
    data class Waiting(val name: String) : ClientState()
    data class Connected(val name: String) : ClientState()
    data class Failed(val msg: String) : ClientState()
}

class RemoteError(msg: String) : Exception(msg)
data class RemoteFile(val name: String, val isDir: Boolean, val size: Long, val modified: Long)
data class RemoteMedia(val id: Long, val name: String, val size: Long, val date: Long, val mime: String)

/** status: 0 running, 1 done, 2 failed */
class Transfer(
    val name: String, val done: Long, val total: Long, val speed: Double,
    val status: Int, val msg: String = "", val uri: Uri? = null, val mime: String = ""
)

object LinkClient {
    val state = MutableStateFlow<ClientState>(ClientState.Idle)
    val transfer = MutableStateFlow<Transfer?>(null)
    val queued = MutableStateFlow(0)
    val completed = MutableStateFlow<Set<String>>(emptySet())
    val turbo = MutableStateFlow(false)
    val upgrading = MutableStateFlow(false)
    val wifiMsg = MutableStateFlow<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()

    @Volatile private var socket: java.io.Closeable? = null
    @Volatile private var inp: DataInputStream? = null
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var tcp: Socket? = null
    @Volatile private var netCm: ConnectivityManager? = null
    @Volatile private var netCb: ConnectivityManager.NetworkCallback? = null
    @Volatile private var activeNet: Network? = null
    @Volatile private var hostAddr: InetAddress? = null

    val mirrorFrame = MutableStateFlow<Bitmap?>(null)
    val mirrorActive = MutableStateFlow(false)
    val mirrorError = MutableStateFlow<String?>(null)
    val appsList = MutableStateFlow<List<RemoteApp>?>(null)
    @Volatile private var mirrorFrameSocket: Socket? = null
    @Volatile private var mirrorInputSocket: Socket? = null
    @Volatile private var mirrorOutStream: DataOutputStream? = null
    @Volatile private var mirrorStop = false

    private val thumbs = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun connectAsync(ctx: Context, device: BluetoothDevice, name: String) {
        val app = ctx.applicationContext
        scope.launch {
            if (connect(device, name)) {
                withContext(Dispatchers.Main) {
                    try {
                        app.startForegroundService(Intent(app, LinkService::class.java).setAction(LinkService.ACT_CLIENT))
                    } catch (_: Exception) {
                    }
                }
                upgrade(app)
            }
        }
    }

    private suspend fun connect(device: BluetoothDevice, name: String): Boolean = withContext(Dispatchers.IO) {
        val cur = state.value
        if (cur is ClientState.Connected || cur is ClientState.Connecting || cur is ClientState.Waiting) {
            return@withContext false
        }
        state.value = ClientState.Connecting(name)
        var s: BluetoothSocket? = null
        try {
            var lastErr: Exception? = null
            for (attempt in 1..3) {
                var cand: BluetoothSocket? = null
                try {
                    cand = if (attempt < 3) device.createRfcommSocketToServiceRecord(P.APP_UUID)
                    else device.createInsecureRfcommSocketToServiceRecord(P.APP_UUID)
                    cand.connect()
                    s = cand
                    lastErr = null
                    break
                } catch (e: Exception) {
                    lastErr = e
                    try { cand?.close() } catch (_: Exception) {}
                    if (attempt < 3) delay(1200)
                }
            }
            if (s == null) throw (lastErr ?: IOException("connect failed"))
            finishHandshake(s, s.inputStream, s.outputStream, name,
                "تعذّر الاتصال بالهاتف التاني. تأكد إنه مقترن، وإن التطبيق مثبّت عليه ومفتوح مرة واحدة على الأقل، وإن الإشعار \"الاستقبال شغال\" ظاهر هناك.")
        } catch (e: Exception) {
            try { s?.close() } catch (_: Exception) {}
            state.value = ClientState.Failed("تعذّر الاتصال بالهاتف التاني. تأكد إنه مقترن، وإن التطبيق مثبّت عليه ومفتوح مرة واحدة على الأقل، وإن الإشعار \"الاستقبال شغال\" ظاهر هناك. (${e.message})")
            false
        }
    }

    /** Connects over a plain IP address/hostname instead of Bluetooth — works over any routed network, including a Tailscale (or other VPN) virtual interface, so the two phones don't need to be nearby or on the same Wi-Fi. */
    fun connectRemoteAsync(ctx: Context, host: String, name: String) {
        val app = ctx.applicationContext
        scope.launch {
            if (connectRemote(host, name)) {
                withContext(Dispatchers.Main) {
                    try { app.startForegroundService(Intent(app, LinkService::class.java).setAction(LinkService.ACT_CLIENT)) } catch (_: Exception) {}
                }
            }
        }
    }

    private suspend fun connectRemote(host: String, name: String): Boolean = withContext(Dispatchers.IO) {
        val cur = state.value
        if (cur is ClientState.Connected || cur is ClientState.Connecting || cur is ClientState.Waiting) return@withContext false
        state.value = ClientState.Connecting(name)
        var s: Socket? = null
        try {
            val sock = Socket()
            sock.connect(InetSocketAddress(host, P.TCP_PORT), 8000)
            sock.tcpNoDelay = true
            s = sock
            finishHandshake(sock, sock.getInputStream(), sock.getOutputStream(), name,
                "تعذّر الاتصال بـ $host. تأكد إن Tailscale شغال ومتصل على الهاتفين، وإن العنوان صح، وإن تطبيق Bluetooth Link مفتوح مرة على الهاتف التاني.")
        } catch (e: Exception) {
            try { s?.close() } catch (_: Exception) {}
            state.value = ClientState.Failed("تعذّر الاتصال بـ $host. تأكد إن Tailscale شغال ومتصل على الهاتفين. (${e.message})")
            false
        }
    }

    /** Shared handshake: write HELLO, wait for the other phone's consent, and (on success) adopt this transport as the active session — used by both the Bluetooth and the plain-IP/Tailscale paths. */
    private fun finishHandshake(closeable: java.io.Closeable, insRaw: java.io.InputStream, outsRaw: java.io.OutputStream, name: String, rejectMsg: String): Boolean {
        val i = DataInputStream(BufferedInputStream(insRaw, P.BUF))
        val o = DataOutputStream(BufferedOutputStream(outsRaw, P.BUF))
        o.writeInt(P.MAGIC); o.writeInt(P.HELLO); o.writeUTF(android.os.Build.MODEL); o.flush()
        state.value = ClientState.Waiting(name)
        val r = i.readInt()
        if (r != P.OK) {
            try { closeable.close() } catch (_: Exception) {}
            state.value = ClientState.Failed(rejectMsg)
            return false
        }
        socket = closeable; inp = i; out = o
        thumbs.evictAll()
        completed.value = emptySet()
        transfer.value = null
        state.value = ClientState.Connected(name)
        return true
    }

    fun upgradeAsync(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { upgrade(app) }
    }

    /** Switch the data channel from Bluetooth to Wi-Fi Direct (much faster). Falls back silently to Bluetooth. */
    private suspend fun upgrade(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        if (turbo.value || upgrading.value || state.value !is ClientState.Connected) return@withContext false
        if (android.os.Build.VERSION.SDK_INT < 29) {
            wifiMsg.value = "الوضع السريع يحتاج أندرويد 10 أو أحدث على هذا الهاتف"
            return@withContext false
        }
        upgrading.value = true
        wifiMsg.value = null
        var cb: ConnectivityManager.NetworkCallback? = null
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        try {
            val info = call { i, o ->
                o.writeInt(P.UPGRADE); o.flush()
                if (i.readInt() != P.OK) throw RemoteError(i.readUTF())
                Triple(i.readUTF(), i.readUTF(), i.readInt())
            }
            val (ssid, pass, port) = info

            val spec = WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(pass).build()
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec).build()
            val avail = CompletableDeferred<Network?>()
            cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { avail.complete(network) }
                override fun onUnavailable() { avail.complete(null) }
            }
            cm.requestNetwork(req, cb, 30_000)
            val net = avail.await() ?: throw RemoteError("لم يتم الاتصال بشبكة الواي فاي المباشرة (تأكد من تشغيل الواي فاي ووافق على النافذة)")

            var host: InetAddress? = null
            for (k in 1..10) {
                host = cm.getLinkProperties(net)?.dhcpServerAddress
                if (host != null) break
                delay(300)
            }
            val target = host ?: InetAddress.getByName("192.168.49.1")
            var sock: Socket? = null
            for (k in 1..8) {
                try {
                    val s = net.socketFactory.createSocket()
                    s.connect(InetSocketAddress(target, port), 4000)
                    sock = s
                    break
                } catch (_: Exception) {
                    delay(600)
                }
            }
            val s = sock ?: throw RemoteError("تعذر فتح قناة السرعة العالية")
            s.tcpNoDelay = true
            try { s.receiveBufferSize = 1 shl 20 } catch (_: Exception) {}
            val ti = DataInputStream(BufferedInputStream(s.getInputStream(), P.BUF))
            val to = DataOutputStream(BufferedOutputStream(s.getOutputStream(), P.BUF))
            lock.withLock {
                if (state.value is ClientState.Connected) {
                    inp = ti; out = to; tcp = s
                    netCm = cm; netCb = cb
                    activeNet = net; hostAddr = target
                    turbo.value = true
                } else {
                    try { s.close() } catch (_: Exception) {}
                }
            }
            turbo.value
        } catch (e: Exception) {
            try { cb?.let { cm.unregisterNetworkCallback(it) } } catch (_: Exception) {}
            wifiMsg.value = (e as? RemoteError)?.message ?: "تعذر تفعيل الوضع السريع، التطبيق شغال عبر البلوتوث"
            false
        } finally {
            upgrading.value = false
        }
    }

    fun startMirror(ctx: Context) {
        if (mirrorActive.value) return
        mirrorError.value = null
        scope.launch(Dispatchers.IO) {
            try {
                val (port, rw_, rh) = call { i, o ->
                    o.writeInt(P.MIRROR); o.flush()
                    if (i.readInt() != P.OK) throw RemoteError(i.readUTF())
                    Triple(i.readInt(), i.readInt(), i.readInt())
                }
                val net = activeNet ?: throw RemoteError("فعّل الوضع السريع أولاً")
                val host = hostAddr ?: throw RemoteError("فعّل الوضع السريع أولاً")
                val fs = net.socketFactory.createSocket(); fs.connect(InetSocketAddress(host, port), 5000); fs.tcpNoDelay = true
                val ins = net.socketFactory.createSocket(); ins.connect(InetSocketAddress(host, port), 5000); ins.tcpNoDelay = true
                mirrorFrameSocket = fs; mirrorInputSocket = ins
                mirrorOutStream = DataOutputStream(BufferedOutputStream(ins.getOutputStream(), 8192))
                mirrorStop = false
                mirrorActive.value = true
                val fi = DataInputStream(BufferedInputStream(fs.getInputStream(), 256 * 1024))
                while (!mirrorStop) {
                    val n = fi.readInt()
                    val buf = ByteArray(n)
                    fi.readFully(buf)
                    val bmp = BitmapFactory.decodeByteArray(buf, 0, buf.size)
                    if (bmp != null) mirrorFrame.value = bmp
                }
            } catch (e: Exception) {
                mirrorError.value = (e as? RemoteError)?.message ?: "فشل الاتصال بشاشة الهاتف الآخر"
            } finally {
                mirrorActive.value = false
                try { mirrorFrameSocket?.close() } catch (_: Exception) {}
                try { mirrorInputSocket?.close() } catch (_: Exception) {}
                mirrorFrameSocket = null; mirrorInputSocket = null; mirrorOutStream = null
            }
        }
    }

    fun stopMirror() {
        mirrorStop = true
        try { mirrorOutStream?.let { synchronized(it) { it.writeByte(9); it.flush() } } } catch (_: Exception) {}
        try { mirrorFrameSocket?.close() } catch (_: Exception) {}
        try { mirrorInputSocket?.close() } catch (_: Exception) {}
        mirrorFrame.value = null
    }

    fun sendTap(nx: Float, ny: Float) {
        val o = mirrorOutStream ?: return
        try { synchronized(o) { o.writeByte(1); o.writeFloat(nx); o.writeFloat(ny); o.flush() } } catch (_: Exception) {}
    }

    fun sendSwipe(nx1: Float, ny1: Float, nx2: Float, ny2: Float, durMs: Int) {
        val o = mirrorOutStream ?: return
        try { synchronized(o) { o.writeByte(2); o.writeFloat(nx1); o.writeFloat(ny1); o.writeFloat(nx2); o.writeFloat(ny2); o.writeInt(durMs); o.flush() } } catch (_: Exception) {}
    }

    /** 1 = back, 2 = home, 3 = recents */
    fun sendKey(code: Int) {
        val o = mirrorOutStream ?: return
        try { synchronized(o) { o.writeByte(3); o.writeByte(code); o.flush() } } catch (_: Exception) {}
    }

    fun loadApps() {
        scope.launch {
            try {
                val list = call { i, o ->
                    o.writeInt(P.APP_LIST); o.flush()
                    if (i.readInt() != P.OK) throw RemoteError(i.readUTF())
                    val n = i.readInt()
                    List(n) { RemoteApp(i.readUTF(), i.readUTF(), i.readBoolean()) }
                }
                appsList.value = list
            } catch (_: Exception) {
                appsList.value = emptyList()
            }
        }
    }

    suspend fun launchApp(pkg: String) = call { i, o ->
        o.writeInt(P.APP_LAUNCH); o.writeUTF(pkg); o.flush()
        if (i.readInt() != P.OK) throw RemoteError(i.readUTF())
    }

    suspend fun uninstallApp(pkg: String) = call { i, o ->
        o.writeInt(P.APP_UNINSTALL); o.writeUTF(pkg); o.flush()
        if (i.readInt() != P.OK) throw RemoteError(i.readUTF())
    }

    suspend fun listContacts(): List<RemoteContact> = call { i, o ->
        o.writeInt(P.CONTACTS_LIST); o.flush()
        if (i.readInt() != P.OK) throw RemoteError(i.readUTF())
        val n = i.readInt()
        List(n) {
            val name = i.readUTF()
            val cnt = i.readInt()
            RemoteContact(name, List(cnt) { i.readUTF() })
        }
    }

    /** Builds and saves a vCard containing only the contacts the user picked — entirely from data already fetched, no extra device trip. */
    fun saveSelectedContacts(ctx: Context, selected: List<RemoteContact>) {
        val sb = StringBuilder()
        for (c in selected) {
            sb.append("BEGIN:VCARD\nVERSION:3.0\nFN:").append(c.name).append('\n')
            for (p in c.numbers) sb.append("TEL:").append(p).append('\n')
            sb.append("END:VCARD\n")
        }
        val bytes = sb.toString().toByteArray(Charsets.UTF_8)
        val name = "contacts_${selected.size}.vcf"
        val mime = "text/x-vcard"
        val resolver = ctx.contentResolver
        var uri: Uri? = null
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/BluetoothLink")
                }
                uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv)
                uri?.let { resolver.openOutputStream(it)?.use { os -> os.write(bytes) } }
            } else {
                val dir = java.io.File(Environment.getExternalStorageDirectory(), "Download/BluetoothLink").apply { mkdirs() }
                val f = java.io.File(dir, name)
                f.writeBytes(bytes)
                try { MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), arrayOf(mime), null) } catch (_: Exception) {}
                uri = try { FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f) } catch (_: Exception) { null }
            }
            transfer.value = Transfer(name, bytes.size.toLong(), bytes.size.toLong(), 0.0, 1, "", uri, mime)
        } catch (e: Exception) {
            transfer.value = Transfer(name, 0, 0, 0.0, 2, "فشل حفظ ملف جهات الاتصال: ${e.message}")
        }
    }

    fun disconnect() {
        scope.launch(Dispatchers.IO) {
            try { out?.let { it.writeInt(P.BYE); it.flush() } } catch (_: Exception) {}
            drop(null)
        }
    }

    private fun drop(msg: String?) {
        stopMirror()
        appsList.value = null
        try { tcp?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        try { netCb?.let { netCm?.unregisterNetworkCallback(it) } } catch (_: Exception) {}
        tcp = null; netCb = null; netCm = null; activeNet = null; hostAddr = null
        turbo.value = false; upgrading.value = false
        socket = null; inp = null; out = null
        state.value = if (msg == null) ClientState.Idle else ClientState.Failed(msg)
    }

    private suspend fun <T> call(block: (DataInputStream, DataOutputStream) -> T): T =
        lock.withLock {
            withContext(NonCancellable + Dispatchers.IO) {
                val i = inp
                val o = out
                if (i == null || o == null) throw IOException("غير متصل")
                try {
                    block(i, o)
                } catch (e: IOException) {
                    drop("انقطع الاتصال")
                    throw e
                }
            }
        }

    suspend fun listDir(path: String): Pair<String, List<RemoteFile>> = call { i, o ->
        o.writeInt(P.LIST_DIR); o.writeUTF(path); o.flush()
        if (i.readInt() != P.OK) throw RemoteError(i.readUTF())
        val p = i.readUTF()
        val n = i.readInt()
        p to List(n) { RemoteFile(i.readUTF(), i.readBoolean(), i.readLong(), i.readLong()) }
    }

    suspend fun listMedia(kind: Int, offset: Int, limit: Int): List<RemoteMedia> = call { i, o ->
        o.writeInt(P.LIST_MEDIA); o.writeInt(kind); o.writeInt(offset); o.writeInt(limit); o.flush()
        if (i.readInt() != P.OK) throw RemoteError(i.readUTF())
        val n = i.readInt()
        List(n) { RemoteMedia(i.readLong(), i.readUTF(), i.readLong(), i.readLong(), i.readUTF()) }
    }

    suspend fun thumb(kind: Int, id: Long): Bitmap? {
        val key = "$kind:$id"
        thumbs.get(key)?.let { return it }
        val bytes = call { i, o ->
            o.writeInt(P.THUMB); o.writeInt(kind); o.writeLong(id); o.flush()
            val n = i.readInt()
            val b = ByteArray(n)
            i.readFully(b)
            b
        }
        if (bytes.isEmpty()) return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        thumbs.put(key, bmp)
        return bmp
    }

    fun pullMedia(ctx: Context, kind: Int, id: Long): Job =
        enqueue(ctx, "m:$kind:$id") { o -> o.writeInt(P.PULL_MEDIA); o.writeInt(kind); o.writeLong(id) }

    fun pullFile(ctx: Context, path: String): Job =
        enqueue(ctx, "f:$path") { o -> o.writeInt(P.PULL_FILE); o.writeUTF(path) }

    fun pullContacts(ctx: Context): Job =
        enqueue(ctx, "contacts") { o -> o.writeInt(P.CONTACTS) }

    private fun enqueue(ctx: Context, key: String, req: (DataOutputStream) -> Unit): Job {
        val app = ctx.applicationContext
        queued.update { it + 1 }
        return scope.launch {
            try {
                call { i, o ->
                    req(o); o.flush()
                    receive(app, i)
                }
                completed.update { it + key }
            } catch (e: RemoteError) {
                transfer.value = Transfer(key, 0, 0, 0.0, 2, e.message ?: "خطأ")
            } catch (e: IOException) {
                transfer.value = Transfer(key, 0, 0, 0.0, 2, "انقطع الاتصال أثناء النقل")
            } finally {
                queued.update { it - 1 }
            }
        }
    }

    private fun receive(ctx: Context, i: DataInputStream) {
        if (i.readInt() != P.OK) throw RemoteError(i.readUTF())
        val name = i.readUTF()
        val mime = i.readUTF()
        val size = i.readLong()

        val legacy = Build.VERSION.SDK_INT < 29
        val subdir = when {
            mime.startsWith("image/") -> "Pictures/BluetoothLink"
            mime.startsWith("video/") -> "Movies/BluetoothLink"
            mime.startsWith("audio/") -> "Music/BluetoothLink"
            else -> "Download/BluetoothLink"
        }

        var uri: Uri? = null
        var os: OutputStream? = null
        var legacyFile: java.io.File? = null
        val resolver = ctx.contentResolver

        if (legacy) {
            val base = Environment.getExternalStorageDirectory()
            val dir = java.io.File(base, subdir).apply { mkdirs() }
            val f = java.io.File(dir, name)
            legacyFile = f
            os = try { f.outputStream() } catch (_: Exception) { null }
        } else {
            val collection = when {
                mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                mime.startsWith("audio/") -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, subdir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            uri = resolver.insert(collection, cv)
            os = if (uri != null) try { resolver.openOutputStream(uri) } catch (_: Exception) { null } else null
        }
        var failed = os == null

        transfer.value = Transfer(name, 0, size, 0.0, 0)
        val buf = ByteArray(P.BUF)
        var got = 0L
        val t0 = SystemClock.elapsedRealtime()
        var last = t0
        var lastBytes = 0L
        while (got < size) {
            val n = i.read(buf, 0, minOf(buf.size.toLong(), size - got).toInt())
            if (n < 0) throw EOFException()
            if (os != null) {
                try { os.write(buf, 0, n) } catch (_: IOException) { failed = true; try { os.close() } catch (_: Exception) {}; os = null }
            }
            got += n
            val now = SystemClock.elapsedRealtime()
            if (now - last >= 200) {
                transfer.value = Transfer(name, got, size, (got - lastBytes) * 1000.0 / (now - last), 0)
                lastBytes = got; last = now
            }
        }
        try { os?.close() } catch (_: Exception) {}
        if (failed) {
            if (uri != null) try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            try { legacyFile?.delete() } catch (_: Exception) {}
            throw RemoteError("فشل حفظ الملف على هذا الهاتف (تأكد من مساحة التخزين)")
        }

        val finalUri: Uri?
        if (legacy) {
            val f = legacyFile!!
            try { MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), arrayOf(mime), null) } catch (_: Exception) {}
            finalUri = try { FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f) } catch (_: Exception) { null }
        } else {
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri!!, done, null, null)
            finalUri = uri
        }
        val dt = maxOf(1L, SystemClock.elapsedRealtime() - t0)
        transfer.value = Transfer(name, size, size, size * 1000.0 / dt, 1, "", finalUri, mime)
    }
}
