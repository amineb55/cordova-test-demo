package com.actionverite.live.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.actionverite.live.data.common.ForegroundActivityProvider
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks the currently-resumed [Activity] via [Application.ActivityLifecycleCallbacks]
 * and exposes it to the data layer (used for Firebase phone-verification reCAPTCHA).
 * A [WeakReference] avoids leaking the activity.
 */
@Singleton
class ActivityTracker @Inject constructor() :
    ForegroundActivityProvider,
    Application.ActivityLifecycleCallbacks {

    private var current: WeakReference<Activity> = WeakReference(null)

    override fun current(): Activity? = current.get()

    override fun onActivityResumed(activity: Activity) {
        current = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (current.get() === activity) current = WeakReference(null)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
