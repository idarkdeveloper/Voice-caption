package com.voicecaption.service

/** Whether the TransCap screen is currently shown; set by the activity, read by the screen recorder to trim itself. */
object AppVisibility {
    @Volatile
    var visible: Boolean = false
}
