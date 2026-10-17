package com.voicecaption.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.voicecaption.appContainer
import com.voicecaption.ui.permissions.Permissions
import com.voicecaption.ui.state.MainViewModel
import com.voicecaption.ui.theme.CaptionlyTheme

/** The launcher activity: hosts the Compose UI and re-reads permissions every time it resumes. */
class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels { appContainer.mainViewModelFactory() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CaptionlyTheme { AppRoot(vm) } }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshPermissions(Permissions.current(this))
    }
}
