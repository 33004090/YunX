package com.yunx.app.data.prefs

import android.content.Context

/**
 * 应用设置（SharedPreferences 持久化）。
 */
class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("yunx_settings", Context.MODE_PRIVATE)

    /** 下载线程数（分片并发数），默认 16，上限 512 */
    var downloadThreads: Int
        get() = prefs.getInt("download_threads", DEFAULT_DOWNLOAD_THREADS)
        set(value) {
            prefs.edit().putInt("download_threads", value.coerceIn(1, 512)).apply()
        }

    /** 百度网盘大文件限速提示：是否已选择「不再显示」 */
    var baiduLimitHintDismissed: Boolean
        get() = prefs.getBoolean("baidu_limit_hint_dismissed", false)
        set(value) {
            prefs.edit().putBoolean("baidu_limit_hint_dismissed", value).apply()
        }

    companion object {
        const val DEFAULT_DOWNLOAD_THREADS = 16
    }
}