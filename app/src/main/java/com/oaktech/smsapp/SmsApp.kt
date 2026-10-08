package com.oaktech.smsapp

import android.app.Application

class SmsApp : Application() {

    override fun onCreate() {
        super.onCreate()
        com.oaktech.smsapp.notify.NotificationChannels.ensure(this)
    }

    companion object {
        const val TAG = "SmsApp"
    }
}