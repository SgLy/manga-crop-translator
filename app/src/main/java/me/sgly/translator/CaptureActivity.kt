package me.sgly.translator

class CaptureActivity : CapturePermissionActivity() {
    private var requested = false

    override fun onResume() {
        super.onResume()
        if (!requested) {
            requested = true
            startCapture()
        }
    }

    override fun onCaptureDone(started: Boolean) {
        finish()
    }
}
