package app.basis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.basis.feature.chat.ChatScreen
import app.basis.feature.home.HomeScreen
import app.basis.feature.logs.LogsScreen
import app.basis.feature.timeline.TimelineScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BasisTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "home") {
                    composable("home") {
                        HomeScreen(onOpenLogs = { nav.navigate("logs") }, onOpenTranscripts = { nav.navigate("transcripts") }, onOpenChat = { nav.navigate("chat") })
                    }
                    composable("chat") { ChatScreen(onBack = { nav.popBackStack() }) }
                    composable("transcripts") { TimelineScreen(onBack = { nav.popBackStack() }) }
                    composable("logs") { LogsScreen(onBack = { nav.popBackStack() }) }
                }
            }
        }
    }
}

@Composable
private fun BasisTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = scheme, content = content)
}
