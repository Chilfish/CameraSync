package dev.sebastiano.camerasync.onboarding

import android.content.Context

class OnboardingViewModel(context: Context) {
    private val prefs = context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)

    val hasCompleted: Boolean
        get() = prefs.getBoolean("completed", false)

    fun markCompleted() {
        prefs.edit().putBoolean("completed", true).apply()
    }
}
