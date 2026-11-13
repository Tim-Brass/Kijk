package nl.kijk.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import nl.kijk.tv.data.Channel
import nl.kijk.tv.data.Movie
import nl.kijk.tv.data.Playable
import nl.kijk.tv.data.Show
import nl.kijk.tv.phone.PhoneServer
import nl.kijk.tv.ui.ConfirmScreen
import nl.kijk.tv.ui.GitHubSyncScreen
import nl.kijk.tv.ui.EpgChannelsScreen
import nl.kijk.tv.ui.EpgLinkScreen
import nl.kijk.tv.ui.GroupsScreen
import nl.kijk.tv.ui.HomeScreen
import nl.kijk.tv.ui.KijkTheme
import nl.kijk.tv.ui.LivePlayer
import nl.kijk.tv.ui.MovieDetailScreen
import nl.kijk.tv.ui.OsConfigScreen
import nl.kijk.tv.ui.SyncPairScreen
import nl.kijk.tv.ui.PinScreen
import nl.kijk.tv.ui.SetupScreen
import nl.kijk.tv.ui.ShowDetailScreen
import nl.kijk.tv.ui.SubSearchScreen
import nl.kijk.tv.ui.VodPlayer

/** Logo's van IPTV-aanbieders zijn vaak SVG; die kan Coil met deze decoder tonen. */
class KijkApplication : android.app.Application(), coil.ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        // Bij een crash de foutmelding bewaren; de volgende keer toont Kijk hem (en hij staat op http://<tv>:8080/crash).
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val text = "Kijk ${BuildConfig.VERSION_NAME} · ${java.util.Date()} · thread ${t.name}\n" + android.util.Log.getStackTraceString(e)
                java.io.File(filesDir, "crash.txt").writeText(text)
                java.io.File(filesDir, "crash_last.txt").writeText(text)
            } catch (_: Throwable) { }
            prev?.uncaughtException(t, e)
        }
    }

    override fun newImageLoader(): coil.ImageLoader =
        coil.ImageLoader.Builder(this).components { add(coil.decode.SvgDecoder.Factory()) }.crossfade(true).build()
}

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by androidx.activity.viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KijkTheme { KijkApp(vm) } }
    }
    // GitHub: bijwerken bij het openen van Kijk en wegschrijven bij het verlaten (niet tussendoor)
    override fun onStart() { super.onStart(); vm.githubSyncNow(quiet = true) }
    override fun onStop() { super.onStop(); vm.githubSyncNow(quiet = true) }
}

/** Alle schermen. Een nieuw scherm komt bovenop de stapel; Terug haalt het eraf. */
sealed interface Screen {
    val id: String
    data object Setup : Screen { override val id = "setup" }
    data object Home : Screen { override val id = "home" }
    data class Live(val list: List<Channel>, val index: Int) : Screen { override val id = "live" }
    data class MovieDetail(val movie: Movie) : Screen { override val id = "movie:${movie.id}" }
    data class ShowDetail(val show: Show) : Screen { override val id = "show:${show.id}" }
    data class Vod(val items: List<Playable>, val index: Int, val start: Long, val showId: String?) : Screen { override val id = "vod" }
    data class Groups(val kind: String) : Screen { override val id = "groups:$kind" }
    data object EpgChannels : Screen { override val id = "epgchans" }
    data class EpgLink(val channel: Channel) : Screen { override val id = "epglink:${channel.key}" }
    data object OsConfig : Screen { override val id = "osconfig" }
    data object SyncPair : Screen { override val id = "syncpair" }
    data object GitHub : Screen { override val id = "github" }
    data class SubSearch(val item: Playable, val onPick: (Long) -> Unit) : Screen { override val id = "subsearch" }
    data class Pin(val title: String, val sub: String, val check: (String) -> Boolean, val onResult: (String?) -> Unit) : Screen { override val id = "pin:$title" }
    data class Confirm(val title: String, val text: String, val yes: String, val onResult: (Boolean) -> Unit) : Screen { override val id = "confirm" }
}

class Nav(start: Screen) {
    val stack = mutableStateListOf(start)
    val top get() = stack.last()
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun replaceAll(s: Screen) { stack.clear(); stack.add(s) }

    /** Pincode vragen; [then] wordt uitgevoerd als de code klopt. */
    fun askPin(vm: AppViewModel, title: String = "Pincode", sub: String = "Deze inhoud is vergrendeld. Voer je pincode in.", then: () -> Unit) {
        push(Screen.Pin(title, sub, { vm.store.pinOk(it) }) { code -> pop(); if (code != null) then() })
    }

    fun confirm(title: String, text: String, yes: String = "Wissen", then: () -> Unit) {
        push(Screen.Confirm(title, text, yes) { ok -> pop(); if (ok) then() })
    }
}

@Composable
private fun KijkApp(vm: AppViewModel) {
    val nav = remember { Nav(if (vm.configured) Screen.Home else Screen.Setup) }
    val saveable = rememberSaveableStateHolder()
    val ctx = LocalContext.current

    // Instellen via de telefoon: de ingebouwde webserver draait zolang de app open is.
    DisposableEffect(Unit) {
        val receiver: (org.json.JSONObject) -> Unit = { json -> vm.importSettings(json) }
        PhoneServer.start(ctx, receiver)
        onDispose { PhoneServer.stop(receiver) }
    }
    // Instellingen ontvangen: van het instelscherm door naar Home.
    LaunchedEffect(vm.imports) { if (vm.imports > 0 && vm.configured && (nav.top is Screen.Setup || nav.top is Screen.SyncPair || (nav.top is Screen.GitHub && nav.stack.firstOrNull() is Screen.Setup))) nav.replaceAll(Screen.Home) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(8_000); vm.checkUpdate() }
    LaunchedEffect(vm.notice) { if (vm.notice != null) { kotlinx.coroutines.delay(5000); vm.notice = null } }

    var crash by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val f = java.io.File(ctx.filesDir, "crash.txt")
        if (f.exists()) { crash = runCatching { f.readText() }.getOrNull(); f.delete() }
    }

    if (!vm.startupDone && crash == null) { nl.kijk.tv.ui.SplashScreen(vm); return }

    val screen = nav.top
    // Herinnering: OK opent altijd de zender, waar de selectie ook staat
    fun openReminder(r: nl.kijk.tv.data.Reminder) {
        vm.dismissReminder()
        val list = vm.channels.takeIf { l -> l.any { it.key == r.channelKey } } ?: vm.allChannels
        val idx = list.indexOfFirst { it.key == r.channelKey }
        if (idx < 0) { vm.notice = "Zender niet gevonden: ${r.channelName}"; return }
        if (nav.top is Screen.Live || nav.top is Screen.Vod) nav.pop()
        nav.push(Screen.Live(list, idx))
    }
    Box(Modifier.fillMaxSize().onPreviewKeyEvent { e ->
        val r = vm.dueReminder
        if (r != null && e.type == KeyEventType.KeyDown && (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter)) { openReminder(r); true } else false
    }) {
    saveable.SaveableStateProvider(screen.id) {
      nl.kijk.tv.ui.FadeIn(screen, enabled = screen !is Screen.Live && screen !is Screen.Vod) {
        when (screen) {
            Screen.Setup -> {
                if (vm.configured) BackHandler { nav.replaceAll(Screen.Home) }
                SetupScreen(vm, onPair = { nav.push(Screen.SyncPair) }, onGitHub = { nav.push(Screen.GitHub) }, onDone = { nav.replaceAll(Screen.Home) })
            }
            Screen.Home -> HomeScreen(vm, nav)
            is Screen.Live -> LivePlayer(vm, nav, screen.list, screen.index)
            is Screen.MovieDetail -> { BackHandler { nav.pop() }; MovieDetailScreen(vm, nav, screen.movie) }
            is Screen.ShowDetail -> { BackHandler { nav.pop() }; ShowDetailScreen(vm, nav, screen.show) }
            is Screen.Vod -> VodPlayer(vm, nav, screen.items, screen.index, screen.start, screen.showId)
            is Screen.Groups -> GroupsScreen(vm, nav, screen.kind)
            Screen.EpgChannels -> { BackHandler { nav.pop() }; EpgChannelsScreen(vm, nav) }
            is Screen.EpgLink -> { BackHandler { nav.pop() }; EpgLinkScreen(vm, nav, screen.channel) }
            Screen.OsConfig -> { BackHandler { nav.pop() }; OsConfigScreen(vm, nav) }
            Screen.SyncPair -> { BackHandler { nav.pop() }; SyncPairScreen(vm, nav) }
            Screen.GitHub -> { BackHandler { vm.cancelGitHubLogin(); nav.pop() }; GitHubSyncScreen(vm, nav) }
            is Screen.SubSearch -> SubSearchScreen(vm, screen.item, { nav.pop() }, screen.onPick)
            is Screen.Pin -> { BackHandler { screen.onResult(null) }; PinScreen(screen) }
            is Screen.Confirm -> { BackHandler { screen.onResult(false) }; ConfirmScreen(screen) }
        }
      }
    }
    crash?.let { c ->
        BackHandler { crash = null }
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().background(Color(0xFA101014)).padding(48.dp)) {
            nl.kijk.tv.ui.Label("Kijk is de vorige keer onverwacht gestopt", size = nl.kijk.tv.ui.Kijk.Title, weight = nl.kijk.tv.ui.Kijk.Bold)
            nl.kijk.tv.ui.Label("Maak een foto van dit scherm, of open op je laptop http://${PhoneServer.address() ?: "<tv>:8080"}/crash en kopieer de tekst. Druk op Terug om verder te gaan.",
                size = nl.kijk.tv.ui.Kijk.Body, color = nl.kijk.tv.ui.Kijk.Secondary, maxLines = 2)
            androidx.compose.foundation.layout.Spacer(Modifier.padding(6.dp))
            nl.kijk.tv.ui.Label(c.lines().filter { it.isNotBlank() }.take(22).joinToString("\n"), size = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp),
                color = Color(0xFFD1D1D6), maxLines = 24)
        }
    }
    vm.dueReminder?.let { r ->
        BackHandler { vm.dismissReminder() }
        val fr = remember(r) { androidx.compose.ui.focus.FocusRequester() }
        LaunchedEffect(r) { kotlinx.coroutines.delay(120); runCatching { fr.requestFocus() }; kotlinx.coroutines.delay(90_000); vm.dismissReminder() }
        Box(Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 40.dp)) {
            nl.kijk.tv.ui.FocusCard(onClick = { openReminder(r) }, modifier = Modifier.width(420.dp).focusRequester(fr), focusedScale = 1.03f) {
                androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                    nl.kijk.tv.ui.Label("🔔  Begint nu", size = nl.kijk.tv.ui.Kijk.Caption, weight = nl.kijk.tv.ui.Kijk.Semibold)
                    nl.kijk.tv.ui.Label(r.title, size = nl.kijk.tv.ui.Kijk.Title, weight = nl.kijk.tv.ui.Kijk.Bold)
                    nl.kijk.tv.ui.Label("${r.channelName} · ${Format.time(r.start)}", size = nl.kijk.tv.ui.Kijk.Body)
                    nl.kijk.tv.ui.Label("OK = kijken · Terug = negeren", Modifier.padding(top = 4.dp), size = nl.kijk.tv.ui.Kijk.Caption)
                }
            }
        }
    }
    vm.notice?.let { n ->
        Box(Modifier.align(Alignment.TopCenter).padding(top = 24.dp).background(Color(0xF21C1C22), RoundedCornerShape(14.dp)).padding(horizontal = 24.dp, vertical = 12.dp)) {
            nl.kijk.tv.ui.Label(n, size = nl.kijk.tv.ui.Kijk.Headline, weight = nl.kijk.tv.ui.Kijk.Semibold, color = if (n.startsWith("✕")) nl.kijk.tv.ui.Kijk.Live else Color.White)
        }
    }
    }
}
