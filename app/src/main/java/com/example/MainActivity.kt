package com.example

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.ui.screens.MainScreen
import com.example.ui.theme.ScreenCraftTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ScreenCraftTheme {
                MainScreen(
                    onSetExcludeFromRecents = { exclude ->
                        setRecentAppsVisibility(exclude)
                    }
                )
            }
        }
    }

    private fun setRecentAppsVisibility(exclude: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val tasks = am?.appTasks
                if (!tasks.isNullOrEmpty()) {
                    tasks[0].setExcludeFromRecents(exclude)
                    Log.d("MainActivity", "AppTask setExcludeFromRecents: $exclude")
                }
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Could not set excludeFromRecents", e)
        }
    }
}
