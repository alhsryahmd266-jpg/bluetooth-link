package com.btlink.app

import android.content.Context
import java.util.UUID

object P {
    val APP_UUID: UUID = UUID.fromString("8f3c2a10-6b7d-4e55-9a41-0b1c5d7e9f20")
    const val MAGIC = 0x42544c4b
    const val HELLO = 1
    const val LIST_DIR = 2
    const val LIST_MEDIA = 3
    const val THUMB = 4
    const val PULL_FILE = 5
    const val PULL_MEDIA = 6
    const val CONTACTS = 7
    const val MIRROR = 10
    const val APP_LIST = 11
    const val APP_LAUNCH = 12
    const val APP_UNINSTALL = 13
    const val CONTACTS_LIST = 14
    const val UPGRADE = 8
    const val BYE = 9
    const val OK = 0
    const val ERR = 1
    const val TCP_PORT = 53217
    const val BUF = 256 * 1024
}

sealed class ServerState {
    object Off : ServerState()
    object Listening : ServerState()
    data class Pending(val name: String, val address: String? = null) : ServerState()
    data class Connected(val name: String) : ServerState()
}

object Hub {
    val server = kotlinx.coroutines.flow.MutableStateFlow<ServerState>(ServerState.Off)
    val serverMsg = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
}

fun formatSize(b: Long): String = when {
    b >= 1L shl 30 -> String.format("%.2f GB", b / 1073741824.0)
    b >= 1L shl 20 -> String.format("%.1f MB", b / 1048576.0)
    b >= 1L shl 10 -> String.format("%.0f KB", b / 1024.0)
    else -> "$b B"
}

fun rxPaused(c: Context): Boolean = c.getSharedPreferences("btlink", 0).getBoolean("paused", false)
fun setRxPaused(c: Context, v: Boolean) { c.getSharedPreferences("btlink", 0).edit().putBoolean("paused", v).apply() }

data class RemoteApp(val label: String, val pkg: String, val system: Boolean)

/** Bridges the MediaProjection system consent dialog (must run in an Activity) back to LinkService. */
object MirrorBridge {
    @Volatile var pending: java.util.concurrent.CompletableFuture<Pair<Int, android.content.Intent?>>? = null
}

data class RemoteContact(val name: String, val numbers: List<String>)

fun isTrustedDevice(c: Context, address: String): Boolean =
    c.getSharedPreferences("btlink", 0).getStringSet("trusted_devices", emptySet())?.contains(address) == true

fun addTrustedDevice(c: Context, address: String) {
    val p = c.getSharedPreferences("btlink", 0)
    val cur = HashSet(p.getStringSet("trusted_devices", emptySet()) ?: emptySet())
    cur.add(address)
    p.edit().putStringSet("trusted_devices", cur).apply()
}

fun clearTrustedDevices(c: Context) {
    c.getSharedPreferences("btlink", 0).edit().remove("trusted_devices").apply()
}
