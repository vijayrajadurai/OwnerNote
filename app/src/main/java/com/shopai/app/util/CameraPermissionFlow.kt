package com.shopai.app.util

/** What to do next when the owner wants the camera (bill scan, product photo). */
enum class CameraStep { OPEN_CAMERA, ASK_PERMISSION, SHOW_PROMPT, OPEN_SETTINGS }

/**
 * The camera permission flow: open at once when allowed; otherwise ask; a
 * first "no" shows "Owner, … camera permission venum." with Allow Camera; a
 * second "no" means Android won't ask again, so the app's settings open.
 */
object CameraPermissionFlow {
    fun onTap(granted: Boolean): CameraStep = if (granted) CameraStep.OPEN_CAMERA else CameraStep.ASK_PERMISSION

    fun onResult(granted: Boolean, deniedBefore: Boolean): CameraStep = when {
        granted -> CameraStep.OPEN_CAMERA
        deniedBefore -> CameraStep.OPEN_SETTINGS
        else -> CameraStep.SHOW_PROMPT
    }
}
