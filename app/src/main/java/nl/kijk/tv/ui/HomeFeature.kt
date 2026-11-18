@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package nl.kijk.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import nl.kijk.tv.AppViewModel
import nl.kijk.tv.Format
import nl.kijk.tv.Nav
import nl.kijk.tv.Screen
import nl.kijk.tv.data.Channel

/** Alleen de bestaande EPG en zenderlogo's; geen previewplayer en geen extra providerstream. */
@Composable
internal fun FeaturedHomeBanner(
    vm: AppViewModel, nav: Nav, hs: HomeState,
    favorites: List<Channel>, onPlay: ((List<Channel>, Int) -> Unit)?,
) {
    val featured = remember(vm.channels, favorites, vm.dataVersion, vm.epgMatch) {
        favorites.firstOrNull { ch -> vm.nowNext(ch).any { p -> p.isLive(vm.now) } }
            ?: vm.channels.asSequence()
                .filter { !vm.isAdult(it.group) && !vm.isAdult(it.name) }
                .take(48).firstOrNull { ch -> vm.nowNext(ch).any { p -> p.isLive(vm.now) } }
    }
    val current = featured?.let { ch -> vm.nowNext(ch).firstOrNull { it.isLive(vm.now) } }
    val title = current?.title ?: "Ontdek wat er nu op TV is"
    val source = featured?.let { shortName(it.name) } ?: "Live televisie"
    val detail = current?.desc?.takeIf { it.isNotBlank() } ?: "Een overzicht van wat er op dit moment wordt uitgezonden."
    var options by remember { mutableStateOf(false) }
    if (options) HomeDetailsDialog(
        title, "LIVE · " + source + (current?.let { " · " + Format.time(it.start) + "–" + Format.time(it.stop) } ?: ""),
        detail,
        buildList {
            if (featured != null) add("▶  Nu kijken" to {
                options = false
                val idx = vm.channels.indexOfFirst { it.key == featured.key }
                if (idx >= 0) {
                    if (onPlay != null) onPlay(vm.channels, idx) else nav.push(Screen.Live(vm.channels, idx))
                }
            })
            add("▤  Openen in Gids" to {
                options = false
                hs.jump = featured
                hs.tab = TAB_GUIDE
                vm.store.lastTab = TAB_GUIDE
            })
        }, onDismiss = { options = false },
    )
    FocusCard(
        onClick = { options = true },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).height(158.dp),
        darkFocus = true, focusedScale = 1.005f, shape = RoundedCornerShape(18.dp),
    ) { _ ->
        Row(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(
            Color(0xFF0B2A50), Color(0xFF1A293E), Color(0xFF12151D)
        ))).padding(horizontal = 24.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.background(Kijk.Live, RoundedCornerShape(5.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)) {
                        Label("LIVE", size = 11.sp, weight = Kijk.Bold, color = Color.White)
                    }
                    Spacer(Modifier.width(12.dp))
                    Label(source, size = Kijk.Caption, weight = Kijk.Semibold, color = Color.White)
                }
                Spacer(Modifier.height(7.dp))
                Label(title, size = 27.sp, weight = Kijk.Bold, color = Color.White, maxLines = 1)
                Label(detail, size = 13.sp, color = Color(0xFFD1D1D6), maxLines = 1)
                if (current != null) {
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.width(320.dp).height(3.dp).background(Color(0x55FFFFFF), RoundedCornerShape(50))) {
                        Box(Modifier.fillMaxWidth(current.progress(vm.now).coerceIn(0f, 1f)).fillMaxHeight().background(Kijk.Accent, RoundedCornerShape(50)))
                    }
                    Spacer(Modifier.height(4.dp))
                    Label(Format.time(current.start) + " – " + Format.time(current.stop), size = 11.sp, color = Kijk.Secondary)
                }
                Spacer(Modifier.height(7.dp))
                Label("▶  Nu kijken   ·   ⓘ  Meer informatie", size = Kijk.Caption, weight = Kijk.Semibold, color = Color.White)
            }
            if (featured != null) {
                Spacer(Modifier.width(20.dp))
                ChannelLogo(featured.logo, featured.name,
                    Modifier.size(width = 148.dp, height = 88.dp), plate = false)
            }
        }
    }
}

/** Eenvoudig, direct te bedienen keuzemenu. Geen autoplay. */
@Composable
internal fun HomeDetailsDialog(
    title: String, subtitle: String, description: String,
    actions: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(500.dp).background(Color(0xFF191D29), RoundedCornerShape(18.dp))
            .padding(horizontal = 24.dp, vertical = 20.dp)) {
            Label(title, size = Kijk.Title, weight = Kijk.Bold, color = Color.White, maxLines = 2)
            Spacer(Modifier.height(5.dp))
            Label(subtitle, size = Kijk.Caption, color = Kijk.Accent, maxLines = 2)
            if (description.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Label(description, size = Kijk.Body, color = Color(0xFFD1D1D6), maxLines = 12)
            }
            Spacer(Modifier.height(15.dp))
            actions.forEachIndexed { i, action ->
                FocusCard(onClick = action.second,
                    modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                    focusedScale = 1f, darkFocus = true, shape = RoundedCornerShape(10.dp)) { _ ->
                    Label(action.first, Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        size = Kijk.Body, weight = Kijk.Semibold, color = Color.White)
                }
                Spacer(Modifier.height(5.dp))
            }
            FocusCard(onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().then(if (actions.isEmpty()) Modifier.focusRequester(first) else Modifier),
                focusedScale = 1f, darkFocus = true, shape = RoundedCornerShape(10.dp)) { _ ->
                Label("Sluiten", Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    size = Kijk.Body, color = Color.White)
            }
        }
    }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
}
