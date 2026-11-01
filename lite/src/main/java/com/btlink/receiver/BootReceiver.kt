package com.btlink.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED && !rxPaused(c) && hasBt(c)) {
            try {
                c.startForegroundService(Intent(c, LinkService::class.java).setAction(LinkService.ACT_START))
            } catch (_: Exception) {
            }
        }
    }
}
