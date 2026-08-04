package com.yunx.app

import android.app.Application
import com.yunx.app.crash.CrashHandler

class YunXApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this))
    }
}