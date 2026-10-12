package com.anttech.smsapp

import android.app.Application

class SmsApp : Application() {

    override fun onCreate() {
        super.onCreate()
        com.anttech.smsapp.notify.NotificationChannels.ensure(this)
    }

    companion object {
        const val TAG = "SmsApp"
    }
}
