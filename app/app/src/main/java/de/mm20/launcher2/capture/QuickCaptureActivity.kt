package de.mm20.launcher2.capture

/** Internal home buttons may open a picker/recorder. External shares use the passive inbox. */
class QuickCaptureActivity : CaptureActivity() {
    protected override fun initialCaptureMode(): String? = intent.getStringExtra("capture_mode")
}
