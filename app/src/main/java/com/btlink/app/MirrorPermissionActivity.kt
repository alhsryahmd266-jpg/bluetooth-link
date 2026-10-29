package com.btlink.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/** Transparent activity: the only way to show the system's screen-share consent dialog. */
class MirrorPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val mgr = getSystemService(MediaProjectionManager::class.java)
            startActivityForResult(mgr.createScreenCaptureIntent(), 42)
        } catch (e: Exception) {
            MirrorBridge.pending?.complete(Activity.RESULT_CANCELED to null)
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        MirrorBridge.pending?.complete(resultCode to data)
        finish()
    }
}
