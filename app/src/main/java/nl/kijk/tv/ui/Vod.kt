@file:OptIn(ExperimentalTvMaterial3Api::class)

package nl.kijk.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import nl.kijk.tv.AppViewModel
import nl.kijk.tv.Format
import nl.kijk.tv.Nav
import nl.kijk.tv.Screen
import nl.kijk.tv.data.Episode
import nl.kijk.tv.data.Movie
import nl.kijk.tv.data.MovieInfo
import nl.kijk.tv.data.Playable
import nl.kijk.tv.data.Show
import nl.kijk.tv.data.ShowDetails
import nl.kijk.tv.data.SubsQuery
import nl.kijk.tv.data.VodCategory

private const val CONTINUE = "\u0000verder"
private const val FAVS = "\u0000favorieten"

fun movieKey(m: Movie) = "m:" + m.id

fun moviePlayable(m: Movie, info: MovieInfo?) = Playable(
    movieKey(m), m.name, listOfNotNull(info?.year ?: m.year, info?.genre).joinToString(" · ").ifEmpty { null }, m.url,
    SubsQuery(tmdb = info?.tmdb, imdb = info?.imdb, query = m.name.replace(Regex("""\(\d{4}\)"""), "").trim(), year = info?.year ?: m.year),
)

fun episodePlayables(show: Show, d: ShowDetails): List<Playable> = d.episodes.map { e ->
    Playable(e.key, show.name, "S${e.season} · A${e.number} · ${e.title}", e.url,
        SubsQuery(parentTmdb = d.tmdb, query = show.name, season = e.season, episode = e.number))
}

// ---------------------------------------------------------------- overzicht

@Composable
fun VodTab(vm: AppViewModel, nav: Nav, series: Boolean, hs: HomeState? = null) {
    val source = vm.vod
    if (source == null) {
        EmptyState(if (vm.progress != null) "Laden…" else if (series) "Geen series gevonden" else "Geen films gevonden",
            "Je playlist bevat geen on-demand-aanbod. Met een Xtream-login krijg je films en series met posters en beschrijvingen.")
        return
    }
    var cats by remember(source, series) { mutableStateOf<List<VodCategory>?>(null) }
    var catId by rememberSaveable(series) { mutableStateOf<String?>(null) }
    var items by remember(series) { mutableStateOf<List<Any>?>(null) }
    var error by remember(series) { mutableStateOf<String?>(null) }
    val hiddenCats = if (series) vm.hiddenShowCats else vm.hiddenMovieCats
    // achtergrondfoto: pas ophalen als je even op een poster blijft staan
    var focusItem by remember(series) { mutableStateOf<Any?>(null) }
    var gridFocused by remember(series) { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(series) { onDispose { hs?.backdrop = null } }
    LaunchedEffect(focusItem) {
        val it = focusItem ?: return@LaunchedEffect
        delay(600)
        hs?.backdrop = when (it) {
            is Movie -> vm.movieInfo(it)?.backdrop ?: it.poster
            is Show -> it.poster
            else -> null
        }
    }
    val cont = remember(series, vm.dataVersion) { if (series) vm.continueShows() else vm.continueMovies() }
    val favs = remember(series, vm.vodFavs) { if (series) vm.favShows() else vm.favMovies() }

    LaunchedEffect(source, series) {
        cats = try { if (series) vm.showCategories() else vm.movieCategories() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = "Laden mislukt: ${e.message ?: e.javaClass.simpleName}"; emptyList() }
    }
    val visibleCats = remember(cats, hiddenCats, vm.unlocked, vm.lockOn) { cats?.filter { it.id !in hiddenCats } }
    val all = remember(visibleCats, cont, favs.isEmpty()) {
        (if (cont.isNotEmpty()) listOf(VodCategory(CONTINUE, "▶ Verder kijken")) else emptyList()) +
            (if (favs.isNotEmpty()) listOf(VodCategory(FAVS, "★ Favorieten")) else emptyList()) + (visibleCats ?: emptyList())
    }
    LaunchedEffect(all) { if (all.isNotEmpty() && (catId == null || all.none { it.id == catId })) catId = all.first().id }

    LaunchedEffect(catId) { focusItem = null; hs?.backdrop = null }   // andere categorie: oude titel en achtergrond weg
    LaunchedEffect(catId, vm.unlocked, cont, favs) {
        val id = catId ?: return@LaunchedEffect
        error = null
        val cat = all.firstOrNull { it.id == id }
        if (id == CONTINUE) { items = cont; return@LaunchedEffect }
        if (id == FAVS) { items = favs; return@LaunchedEffect }
        if (cat != null && vm.isLocked(cat.name) && !vm.unlocked) { items = emptyList(); return@LaunchedEffect }
        items = null
        delay(250) // niet laden bij elke categorie waar je langs scrollt
        items = try {
            val l: List<Any> = if (series) vm.shows(id) else vm.movies(id)
            if (vm.unlocked) l else l.filter { !vm.isLocked(if (it is Movie) it.name else (it as Show).name) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e   // weggescrold naar een andere categorie: geen fout
        } catch (e: Exception) { error = "Laden mislukt: ${e.message ?: e.javaClass.simpleName}"; emptyList() }
    }

    when {
        cats == null -> EmptyState("Laden…")
        all.isEmpty() -> EmptyState(error ?: "Niets gevonden", if (hiddenCats.isNotEmpty()) "Misschien staan alle categorieën uit bij Instellingen." else "")
        else -> Row(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.width(230.dp), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(6.dp)) {
                items(all) { cat ->
                    val locked = vm.isLocked(cat.name) && !vm.unlocked
                    FocusCard(
                        onClick = { if (locked) nav.askPin(vm) { vm.unlock(); catId = cat.id } else catId = cat.id },
                        onFocus = { catId = cat.id }, selected = cat.id == catId, modifier = Modifier.fillMaxWidth(),
                    ) { Label((if (locked) "🔒 " else "") + cat.name, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), weight = Kijk.Medium) }
                }
            }
            Spacer(Modifier.width(20.dp))
            val list = items
            val cur = all.firstOrNull { it.id == catId }
            when {
                cur != null && vm.isLocked(cur.name) && !vm.unlocked -> EmptyState("🔒 Vergrendeld", "Druk op OK op de categorie om je pincode in te voeren.")
                list == null -> EmptyState("Laden…")
                list.isEmpty() -> EmptyState(error ?: "Deze categorie is leeg")
                else -> Column(Modifier.fillMaxSize()) {
                    val fi = focusItem.takeIf { gridFocused }   // titel alleen als je echt op een poster staat
                    val head = when (fi) { is Movie -> listOfNotNull(fi.name, fi.year).joinToString("  ·  "); is Show -> listOfNotNull(fi.name, fi.year).joinToString("  ·  "); else -> "" }
                    Label(head, Modifier.padding(start = 12.dp, bottom = 2.dp).height(26.dp), size = Kijk.Title, weight = Kijk.Bold, color = Color.White)
                    LazyVerticalGrid(
                    columns = GridCells.Adaptive(118.dp), modifier = Modifier.fillMaxSize().onFocusChanged { gridFocused = it.hasFocus },
                    horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(12.dp),
                ) {
                    items(list) { item ->
                        when (item) {
                            is Movie -> PosterCard(item.name, item.poster, vm.store.progress(movieKey(item))?.takeIf { it.resumable }?.fraction,
                                fav = vm.isFav(item), onLongClick = { vm.toggleFav(item) }, onFocus = { focusItem = item }) {
                                vm.addRecentMovie(item); nav.push(Screen.MovieDetail(item))
                            }
                            is Show -> PosterCard(item.name, item.poster, null, fav = vm.isFav(item), onLongClick = { vm.toggleFav(item) }, onFocus = { focusItem = item },
                                badge = if (vm.isNew(item)) "Nieuw" else null) {
                                vm.addRecentShow(item); nav.push(Screen.ShowDetail(item))
                            }
                        }
                    }
                }
                }
            }
        }
    }
}

/** Gedimde achtergrondfoto achter Films/Series (kleine afbeelding + verloop i.p.v. zware blur). */
@Composable
fun VodBackdrop(url: String?) {
    androidx.compose.animation.Crossfade(url, animationSpec = androidx.compose.animation.core.tween(400), label = "backdrop") { u ->
        if (u != null) Box(Modifier.fillMaxSize()) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            AsyncImage(
                coil.request.ImageRequest.Builder(ctx).data(u).size(960, 540).crossfade(false).build(),
                contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().alpha(0.32f),
            )
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Kijk.Background, Kijk.Background.copy(alpha = 0.75f), Kijk.Background.copy(alpha = 0.2f)))))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Kijk.Background.copy(alpha = 0.6f), Color.Transparent, Kijk.Background))))
        }
    }
}

@Composable
fun PosterCard(title: String, poster: String?, progress: Float?, modifier: Modifier = Modifier, fav: Boolean = false, onLongClick: (() -> Unit)? = null, onFocus: (() -> Unit)? = null, badge: String? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.onFocusChanged { focused = it.hasFocus }) {
        FocusCard(onClick = onClick, onLongClick = onLongClick, onFocus = onFocus, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f), shape = RoundedCornerShape(10.dp), focusedScale = 1.08f) {
            Box(Modifier.fillMaxSize().background(Kijk.Elevated)) {
                if (poster != null) AsyncImage(poster, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Label(title, Modifier.align(Alignment.Center).padding(10.dp), size = Kijk.Body, weight = Kijk.Semibold, color = Color.White, maxLines = 4)
                if (progress != null) ProgressBar(progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp), height = 4.dp)
                if (badge != null) Box(Modifier.align(Alignment.TopStart).padding(6.dp).background(Kijk.Accent, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp)) {
                    Label(badge, size = Kijk.Caption, weight = Kijk.Bold, color = Color.White)
                }
                if (fav) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp).background(Color(0xCC000000), RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                    Label("★", size = Kijk.Body, color = Color(0xFFFFD60A))
                }
            }
        }
        Label(title, Modifier.padding(top = 10.dp, start = 2.dp, end = 2.dp), size = Kijk.Caption,
            weight = if (focused) Kijk.Semibold else null, color = if (focused) Color.White else Kijk.Secondary, maxLines = if (focused) 2 else 1)
    }
}

// ---------------------------------------------------------------- detail

@Composable
private fun Backdrop(url: String?) {
    if (url != null) AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().alpha(0.35f))
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Kijk.Background, Kijk.Background.copy(alpha = 0.85f), Color.Transparent))))
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Kijk.Background), startY = 500f)))
}

@Composable
private fun Poster(url: String?, title: String, w: Int, h: Int) {
    Box(Modifier.size(w.dp, h.dp).clip(RoundedCornerShape(12.dp)).background(Kijk.Elevated), contentAlignment = Alignment.Center) {
        if (url != null) AsyncImage(url, title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Label(title, Modifier.padding(12.dp), size = Kijk.Headline, weight = Kijk.Semibold, maxLines = 4)
    }
}

@Composable
fun MovieDetailScreen(vm: AppViewModel, nav: Nav, movie: Movie) {
    var info by remember { mutableStateOf<MovieInfo?>(null) }
    LaunchedEffect(movie.id) { info = vm.movieInfo(movie) }
    val prog = vm.store.progress(movieKey(movie))
    val resume = prog?.takeIf { it.resumable }?.pos ?: 0L
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    fun play(start: Long) = nav.push(Screen.Vod(listOf(moviePlayable(movie, info)), 0, start, null))

    Box(Modifier.fillMaxSize().background(Kijk.Background)) {
        Backdrop(info?.backdrop ?: movie.poster)
        Row(Modifier.fillMaxSize().padding(horizontal = Kijk.SafeH, vertical = Kijk.SafeV + 20.dp)) {
            Poster(movie.poster, movie.name, 210, 315)
            Spacer(Modifier.width(36.dp))
            Column(Modifier.weight(1f)) {
                Label(movie.name, size = Kijk.LargeTitle, weight = Kijk.Bold, maxLines = 2)
                Spacer(Modifier.height(8.dp))
                val meta = listOfNotNull(info?.year ?: movie.year, Format.runtime(info?.duration), movie.rating?.let { "★ $it" }, info?.genre).joinToString("  ·  ")
                if (meta.isNotEmpty()) Label(meta, size = Kijk.Body, color = Kijk.Secondary)
                Spacer(Modifier.height(14.dp))
                info?.plot?.let { Label(it, size = Kijk.Body, color = Color(0xFFE5E5EA), maxLines = 7) }
                info?.cast?.let { Label("Met: $it", Modifier.padding(top = 10.dp), size = Kijk.Caption, color = Kijk.Secondary, maxLines = 2) }
                info?.director?.let { Label("Regie: $it", Modifier.padding(top = 4.dp), size = Kijk.Caption, color = Kijk.Secondary) }
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (resume > 0) {
                        PillButton("▶  Verder vanaf ${Format.clock(resume)}", Modifier.focusRequester(first)) { play(resume) }
                        PillButton("Vanaf het begin") { play(0L) }
                    } else PillButton("▶  Afspelen", Modifier.focusRequester(first)) { play(0L) }
                    PillButton(if (vm.isFav(movie)) "★  Favoriet" else "☆  Favoriet", selected = vm.isFav(movie)) { vm.toggleFav(movie) }
                }
            }
        }
    }
}

@Composable
fun ShowDetailScreen(vm: AppViewModel, nav: Nav, show: Show) {
    var details by remember { mutableStateOf<ShowDetails?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(show.id) {
        vm.markSeen(show)
        details = try { vm.showDetails(show) } catch (e: Exception) { error = "Afleveringen laden mislukt: ${e.message ?: e.javaClass.simpleName}"; null }
    }
    val eps = details?.episodes.orEmpty()
    val seasons = remember(eps) { eps.map { it.season }.distinct() }
    val lastKey = vm.store.lastEpisodes[show.id]
    val lastEp = eps.firstOrNull { it.key == lastKey }
    var season by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(eps) { if (season == null || season !in seasons) season = lastEp?.season ?: seasons.firstOrNull() }
    val playables = remember(details) { details?.let { episodePlayables(show, it) } ?: emptyList() }
    val first = remember { FocusRequester() }
    LaunchedEffect(eps.isNotEmpty()) { if (eps.isNotEmpty()) { delay(80); runCatching { first.requestFocus() } } }

    fun play(ep: Episode) {
        vm.store.setLastEpisode(show.id, ep.key)
        val p = vm.store.progress(ep.key)
        nav.push(Screen.Vod(playables, eps.indexOf(ep), p?.takeIf { it.resumable }?.pos ?: 0L, show.id))
    }

    Box(Modifier.fillMaxSize().background(Kijk.Background)) {
        Backdrop(details?.backdrop ?: show.poster)
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = Kijk.SafeH, vertical = Kijk.SafeV + 12.dp)) {
            item(key = "header") {
                Row(Modifier.padding(bottom = 12.dp)) {
                    Poster(show.poster, show.name, 150, 225)
                    Spacer(Modifier.width(30.dp))
                    Column(Modifier.weight(1f)) {
                        Label(show.name, size = Kijk.LargeTitle, weight = Kijk.Bold, maxLines = 2)
                        Spacer(Modifier.height(6.dp))
                        val meta = listOfNotNull(show.year, show.rating?.let { "★ $it" },
                            seasons.size.takeIf { it > 0 }?.let { if (it == 1) "1 seizoen" else "$it seizoenen" }).joinToString("  ·  ")
                        if (meta.isNotEmpty()) Label(meta, size = Kijk.Body, color = Kijk.Secondary)
                        Spacer(Modifier.height(10.dp))
                        (details?.plot ?: show.plot)?.let { Label(it, size = Kijk.Body, color = Color(0xFFE5E5EA), maxLines = 4) }
                        Spacer(Modifier.height(16.dp))
                        when {
                            error != null -> Label(error!!, size = Kijk.Body, color = Kijk.Live, maxLines = 2)
                            details == null -> Label("Afleveringen laden…", size = Kijk.Body, color = Kijk.Secondary)
                            eps.isEmpty() -> Label("Geen afleveringen gevonden.", size = Kijk.Body, color = Kijk.Secondary)
                            else -> {
                                // verder bij de laatst bekeken aflevering, of de volgende als die uit is
                                val t0 = lastEp ?: eps.first()
                                val t = if (vm.store.progress(t0.key)?.finished == true) eps.getOrNull(eps.indexOf(t0) + 1) ?: t0 else t0
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    PillButton((if (lastEp != null) "▶  Verder: " else "▶  Afspelen: ") + "S${t.season} A${t.number}", Modifier.focusRequester(first)) { play(t) }
                                    PillButton(if (vm.isFav(show)) "★  Favoriet" else "☆  Favoriet", selected = vm.isFav(show)) { vm.toggleFav(show) }
                                }
                            }
                        }
                    }
                }
            }
            if (seasons.size > 1) item(key = "seasons") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)) {
                    items(seasons) { s ->
                        FocusCard(onClick = { season = s }, onFocus = { season = s }, selected = s == season, shape = RoundedCornerShape(50), focusedScale = 1.06f) {
                            Label("Seizoen $s", Modifier.padding(horizontal = 18.dp, vertical = 7.dp), size = Kijk.Body, weight = Kijk.Semibold)
                        }
                    }
                }
            }
            items(eps.filter { it.season == season }) { ep -> EpisodeRow(vm, ep) { play(ep) } }
        }
    }
}

@Composable
private fun EpisodeRow(vm: AppViewModel, ep: Episode, onClick: () -> Unit) {
    val prog = vm.store.progress(ep.key)
    FocusCard(onClick = onClick, modifier = Modifier.fillMaxWidth(), focusedScale = 1.02f) { focused ->
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(160.dp, 90.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x22FFFFFF))) {
                if (ep.still != null) AsyncImage(ep.still, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (prog?.resumable == true) ProgressBar(prog.fraction, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(6.dp), height = 4.dp)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("${ep.number}. ${ep.title}", Modifier.weight(1f, fill = false), size = Kijk.Headline, weight = Kijk.Semibold)
                    if (prog?.finished == true) Label("  ✓ Bekeken", size = Kijk.Caption, color = secondary(focused))
                }
                Format.runtime(ep.duration)?.let { Label(it, size = Kijk.Caption, color = secondary(focused)) }
                ep.plot?.let { Label(it, Modifier.padding(top = 4.dp), size = Kijk.Body, color = secondary(focused), maxLines = 2) }
            }
        }
    }
}
