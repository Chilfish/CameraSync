package dev.sebastiano.camerasync.di

import android.app.Activity
import android.app.AppComponentFactory
import android.app.Application
import android.app.Service
import android.content.Intent
import androidx.annotation.Keep
import dev.sebastiano.camerasync.CameraSyncApp
import dev.sebastiano.camerasync.MainActivity

@Keep
class MetroAppComponentFactory : AppComponentFactory() {

    private lateinit var appGraph: AppGraph

    override fun instantiateApplication(cl: ClassLoader, className: String): Application {
        val app = super.instantiateApplication(cl, className)
        if (app is CameraSyncApp) {
            appGraph = app.appGraph
        }
        return app
    }

    override fun instantiateActivity(
        cl: ClassLoader,
        className: String,
        intent: Intent?,
    ): Activity {
        return if (className == MainActivity::class.java.name) {
            appGraph.mainActivity()
        } else {
            super.instantiateActivity(cl, className, intent)
        }
    }

    override fun instantiateService(cl: ClassLoader, className: String, intent: Intent?): Service {
        return super.instantiateService(cl, className, intent)
    }
}
