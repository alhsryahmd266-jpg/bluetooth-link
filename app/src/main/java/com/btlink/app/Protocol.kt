package com.btlink.app

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
    const val BYE = 9
    const val OK = 0
    const val ERR = 1
    const val BUF = 64 * 1024
}

sealed class ServerState {
    object Off : ServerState()
    object Listening : ServerState()
    data class Pending(val name: String) : ServerState()
    data class Connected(val name: String) : ServerState()
}

object Hub {
    val server = kotlinx.coroutines.flow.MutableStateFlow<ServerState>(ServerState.Off)
}

fun formatSize(b: Long): String = when {
    b >= 1L shl 30 -> String.format("%.2f GB", b / 1073741824.0)
    b >= 1L shl 20 -> String.format("%.1f MB", b / 1048576.0)
    b >= 1L shl 10 -> String.format("%.0f KB", b / 1024.0)
    else -> "$b B"
}
