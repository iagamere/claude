@file:OptIn(ExperimentalMaterial3Api::class)
package com.abdo.ps4monitor
import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.core.view.WindowCompat
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleShare(intent) }
    private fun handleShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val t = i.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            Regex("""https?://\S+""").find(t)?.value?.let { Inbox.url.value = it }
        }
    }
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        Browser.init(applicationContext)
        if (Browser.tabs.isEmpty()) Browser.restore()
        runCatching { DownloadMonitor.restore() }
        handleShare(intent)
        setContent { Root() }
    }
    override fun onStop() { super.onStop(); Browser.save() }
}

@Composable fun Root() {
    val dark = when (Store.theme.intValue) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val ctx = LocalContext.current
    // Phone (Material You) colours when available and enabled; otherwise a purple fallback.
    val cs = if (Store.dynamic.value && Build.VERSION.SDK_INT >= 31) { if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx) }
             else if (dark) darkColorScheme(
                 primary = Color(0xFF7AB8FF), onPrimary = Color(0xFF00315A), primaryContainer = Color(0xFF0B3D75), onPrimaryContainer = Color(0xFFD3E6FF),
                 secondary = Color(0xFFB5C8E3), onSecondary = Color(0xFF1F3147), secondaryContainer = Color(0xFF2A3F5C), onSecondaryContainer = Color(0xFFD6E4FA),
                 tertiary = Color(0xFF5EE0C1), onTertiary = Color(0xFF00382D), tertiaryContainer = Color(0xFF0B4F42), onTertiaryContainer = Color(0xFFB5F5E4),
                 background = Color(0xFF0B1220), onBackground = Color(0xFFE3E8F2), surface = Color(0xFF0B1220), onSurface = Color(0xFFE3E8F2),
                 surfaceVariant = Color(0xFF1C2638), onSurfaceVariant = Color(0xFFA9B4C8),
                 surfaceContainerLowest = Color(0xFF070C16), surfaceContainerLow = Color(0xFF111A2B), surfaceContainer = Color(0xFF152033),
                 surfaceContainerHigh = Color(0xFF1B2840), surfaceContainerHighest = Color(0xFF22314D),
                 outline = Color(0xFF66748C), outlineVariant = Color(0xFF2B3850))
             else lightColorScheme(
                 primary = Color(0xFF1558D6), onPrimary = Color.White, primaryContainer = Color(0xFFD9E5FF), onPrimaryContainer = Color(0xFF001A47),
                 secondary = Color(0xFF4B5B78), onSecondary = Color.White, secondaryContainer = Color(0xFFDDE5F5), onSecondaryContainer = Color(0xFF0E1B33),
                 tertiary = Color(0xFF00796B), onTertiary = Color.White, tertiaryContainer = Color(0xFFC4F2E8), onTertiaryContainer = Color(0xFF00201A),
                 background = Color(0xFFF6F8FC), onBackground = Color(0xFF121826), surface = Color(0xFFF6F8FC), onSurface = Color(0xFF121826),
                 surfaceVariant = Color(0xFFE1E6F0), onSurfaceVariant = Color(0xFF4A5468),
                 surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White, surfaceContainer = Color(0xFFEDF1F8),
                 surfaceContainerHigh = Color(0xFFE6EBF4), surfaceContainerHighest = Color(0xFFDFE5F0),
                 outline = Color(0xFF76809A), outlineVariant = Color(0xFFC8D0E0))
    val view = LocalView.current
    SideEffect {
        val w = (view.context as? Activity)?.window ?: return@SideEffect
        w.statusBarColor = cs.surface.toArgb(); w.navigationBarColor = cs.surfaceContainer.toArgb()
        WindowCompat.getInsetsController(w, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
    }
    val shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp))
    val base = Typography()
    val typo = base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium))
    CompositionLocalProvider(LocalLayoutDirection provides if (Lang.isAr) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        MaterialTheme(colorScheme = cs, shapes = shapes, typography = typo) { Surface(Modifier.fillMaxSize(), color = cs.surface) { AppNav(); LaunchedEffect(Unit) { Tour.startIfFirstRun() }; TourOverlay() } }
    }
}

private val TABS = listOf(Triple("home", R.drawable.ic_home, "Home" to "الرئيسية"), Triple("browser", R.drawable.ic_globe, "Browser" to "المتصفح"),
    Triple("downloads", R.drawable.ic_download, "Downloads" to "التحميلات"), Triple("settings", R.drawable.ic_settings, "Settings" to "الإعدادات"))

@Composable fun AppNav() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val topLevel = TABS.any { it.first == route }
    val active by DownloadRepo.all.collectAsState()
    val activeCount = active.count { it.state.active }
    Scaffold(bottomBar = {
        if (topLevel) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
            TABS.forEach { (r, icon, label) ->
                NavigationBarItem(selected = route == r, label = { Lbl(tr(label.first, label.second), style = MaterialTheme.typography.labelMedium) },
                    icon = { BadgedBox(badge = { if (r == "downloads" && activeCount > 0) Badge { Text("$activeCount") } }) { Ico(icon) } },
                    onClick = { nav.navigate(r) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true } })
            }
        }
    }) { pad ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(pad)) {
            composable("home") { HomeScreen(nav) }
            composable("browser") { BrowserScreen() }
            composable("downloads") { DownloadsScreen(nav) }
            composable("downloads/{id}", listOf(navArgument("id") { type = NavType.StringType })) { DownloadDetail(it.arguments?.getString("id").orEmpty(), nav) }
            composable("files") { FilesScreen(nav) }
            composable("ps4info") { Ps4InfoScreen(nav) }
            composable("pkg/{ps4}/{path}", listOf(navArgument("ps4") { type = NavType.StringType }, navArgument("path") { type = NavType.StringType })) {
                PkgScreen(it.arguments?.getString("ps4").orEmpty(), it.arguments?.getString("path").orEmpty(), nav) }
            composable("settings") { SettingsScreen(nav) }
            composable("settings/ps4s") { Ps4ListScreen(nav) }
            composable("settings/ps4/{id}", listOf(navArgument("id") { type = NavType.StringType })) { Ps4EditScreen(it.arguments?.getString("id").orEmpty(), nav) }
            composable("settings/server") { ServerScreen(nav) }
            composable("settings/advanced") { AdvancedScreen(nav) }
            composable("settings/log") { LogScreen(nav) }
        }
    }
    Inbox.url.value?.let { u ->
        SendDialog(initialUrl = u, onAddPs4 = { Inbox.url.value = null; nav.navigate("settings/ps4/new") },
            onSent = { nav.navigate("downloads") { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true } },
            close = { Inbox.url.value = null })
    }
}
