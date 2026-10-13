package ai.anywear.mvp

import android.app.Application
import ai.anywear.mvp.data.AppPreferences

class KabudaApplication : Application() {
    lateinit var preferences: AppPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        preferences = AppPreferences(this)
    }
}
