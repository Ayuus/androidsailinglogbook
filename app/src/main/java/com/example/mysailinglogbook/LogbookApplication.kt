package com.example.mysailinglogbook

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/** Forces the app's DayNight theme to always resolve to values-night/ (dark), regardless of the
 * system's own dark-mode setting -- found in practice on an Android 8.1 tablet: system-wide dark
 * mode isn't exposed to the user at all before Android 10, so values-night/ never activated there
 * and the app fell back to its light theme (white background) even though every other tested
 * device (system dark mode on) showed the intended dark theme. Forcing it here makes the app look
 * the same dark-themed way on every Android version, not just ones with a system dark toggle. */
class LogbookApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
    }
}
