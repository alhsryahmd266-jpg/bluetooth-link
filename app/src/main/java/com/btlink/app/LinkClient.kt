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
import android.os.SystemClock
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

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var inp: DataInputStream? = null
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var tcp: Socket? = null
    @Volatile private var netCm: ConnectivityManager? = null
    @Volatile private var netCb: ConnectivityManager.NetworkCallback? = null

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
            val i = DataInputStream(BufferedInputStream(s.inputStream, P.BUF))
            val o = DataOutputStream(BufferedOutputStream(s.outputStream, P.BUF))
            o.writeInt(P.MAGIC); o.writeInt(P.HELLO); o.writeUTF(android.os.Build.MODEL); o.flush()
            state.value = ClientState.Waiting(name)
            val r = i.readInt()
            if (r != P.OK) {
                try { s.close() } catch (_: Exception) {}
                state.value = ClientState.Failed("الهاتف الآخر رفض الاتصال أو لم يرد في الوقت المحدد")
                return@withContext false
            }
            socket = s; inp = i; out = o
            thumbs.evictAll()
            completed.value = emptySet()
            transfer.value = null
            state.value = ClientState.Connected(name)
            true
        } catch (e: Exception) {
            try { s?.close() } catch (_: Exception) {}
            state.value = ClientState.Failed("تعذّر الاتصال بالهاتف التاني. تأكد إنه مقترن، وإن التطبيق مثبّت عليه ومفتوح مرة واحدة على الأقل، وإن الإشعار \"الاستقبال شغال\" ظاهر هناك. (${e.message})")
            false
        }
    }

    fun upgradeAsync(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { upgrade(app) }
    }

    /** Switch the data channel from Bluetooth to Wi-Fi Direct (much faster). Falls back silently to Bluetooth. */
    private suspend fun upgrade(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        if (turbo.value || upgrading.value || state.value !is ClientState.Connected) return@withContext false
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

    fun disconnect() {
        scope.launch(Dispatchers.IO) {
            try { out?.let { it.writeInt(P.BYE); it.flush() } } catch (_: Exception) {}
            drop(null)
        }
    }

    private fun drop(msg: String?) {
        try { tcp?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        try { netCb?.let { netCm?.unregisterNetworkCallback(it) } } catch (_: Exception) {}
        tcp = null; netCb = null; netCm = null
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

        val (collection, rel) = when {
            mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Pictures/BluetoothLink"
            mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Movies/BluetoothLink"
            mime.startsWith("audio/") -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Music/BluetoothLink"
            else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Download/BluetoothLink"
        }
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(collection, cv)
        var os: OutputStream? = if (uri != null) try { resolver.openOutputStream(uri) } catch (_: Exception) { null } else null
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
        if (failed || uri == null) {
            if (uri != null) try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            throw RemoteError("فشل حفظ الملف على هذا الهاتف (تأكد من مساحة التخزين)")
        }
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        val dt = maxOf(1L, SystemClock.elapsedRealtime() - t0)
        transfer.value = Transfer(name, size, size, size * 1000.0 / dt, 1, "", uri, mime)
    }
}
