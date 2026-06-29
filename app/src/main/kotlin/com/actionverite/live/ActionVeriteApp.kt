package com.actionverite.live

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.actionverite.live.di.ActivityTrackerEntryPoint
import com.google.firebase.crashlytics.ktx.crashlytics
import com.google.firebase.ktx.Firebase
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point. `@HiltAndroidApp` triggers Hilt's code generation and
 * makes the dependency graph available app-wide.
 */
@HiltAndroidApp
class ActionVeriteApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Firebase.crashlytics.setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)
        createNotificationChannels()

        // Register the foreground-activity tracker so the data layer can reach the
        // current Activity (e.g. Firebase phone verification).
        val tracker = EntryPointAccessors
            .fromApplication(this, ActivityTrackerEntryPoint::class.java)
            .activityTracker()
        registerActivityLifecycleCallbacks(tracker)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            getString(R.string.notif_channel_default),
            getString(R.string.notif_channel_default_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = getString(R.string.notif_channel_default_desc) }
        manager.createNotificationChannel(channel)
    }
}
