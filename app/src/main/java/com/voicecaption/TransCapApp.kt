package com.voicecaption

import android.app.Application
import android.content.Context
import com.voicecaption.app.AppContainer
import com.voicecaption.service.ServiceDependencies
import kotlinx.coroutines.launch

/** Application class: creates the [AppContainer] and plugs the live settings into the service. */
class TransCapApp : Application() {
    /** The process-wide dependency container. */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        val settings = container.settingsState // start sharing now so the service sees real values soon
        ServiceDependencies.settings = { settings.value }
        ServiceDependencies.savePositions = { translationDp, originalDp ->
            container.appScope.launch {
                container.settingsRepository.update {
                    it.copy(translationOffsetDp = translationDp, originalOffsetDp = originalDp)
                }
            }
        }
    }
}

/** The app's [AppContainer], from any Context (Activity, Service...). */
val Context.appContainer: AppContainer
    get() = (applicationContext as TransCapApp).container
