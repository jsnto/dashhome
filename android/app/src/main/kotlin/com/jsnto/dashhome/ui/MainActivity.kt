package com.jsnto.dashhome.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.jsnto.dashhome.api.DashApi
import com.jsnto.dashhome.data.Prefs

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Restore the persisted server URL into the API client (it does
        // not survive process death on its own). DashhomeRoot resolves the
        // theme (the persisted pick, else the OS preference) and wraps the
        // UI in it.
        DashApi.baseUrl = Prefs.base(this)
        setContent {
            DashhomeRoot()
        }
    }
}
