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

    /** 自定义下载保存目录（SAF tree Uri，content://...）；null/空 = 系统默认 Download 目录 */
    var downloadDirUri: String?
        get() = prefs.getString("download_dir_uri", null)
        set(value) {
            prefs.edit().putString("download_dir_uri", value).apply()
        }

    /** 百度网盘大文件限速提示：是否已选择「不再显示」 */
    var baiduLimitHintDismissed: Boolean
        get() = prefs.getBoolean("baidu_limit_hint_dismissed", false)
        set(value) {
            prefs.edit().putBoolean("baidu_limit_hint_dismissed", value).apply()
        }

    /** 深色模式：0=跟随系统，1=浅色，2=深色 */
    var darkMode: Int
        get() = prefs.getInt("dark_mode", 0)
        set(value) {
            prefs.edit().putInt("dark_mode", value.coerceIn(0, 2)).apply()
        }

    /** 主题色模式：0=动态色彩（Android12+ 壁纸取色，低版本回退默认蓝），1=默认蓝色，2=自定义种子色 */
    var themeColorMode: Int
        get() = prefs.getInt("theme_color_mode", 0)
        set(value) {
            prefs.edit().putInt("theme_color_mode", value.coerceIn(0, 2)).apply()
        }

    /** 自定义主题种子色（ARGB 值） */
    var themeSeedColor: Long
        get() = prefs.getLong("theme_seed_color", DEFAULT_SEED_COLOR)
        set(value) {
            prefs.edit().putLong("theme_seed_color", value).apply()
        }

    companion object {
        const val DEFAULT_DOWNLOAD_THREADS = 16

        /** 默认主题种子色：Material Blue（与内置默认方案一致） */
        const val DEFAULT_SEED_COLOR = 0xFF415F91L
    }
}