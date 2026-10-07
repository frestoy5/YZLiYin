package top.aryun.yzliyin.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import top.aryun.yzliyin.core.model.Album
import top.aryun.yzliyin.core.model.Artist
import top.aryun.yzliyin.core.model.Playlist
import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.core.model.Source
import top.aryun.yzliyin.core.net.BiliApi
import top.aryun.yzliyin.core.net.KugouApi
import top.aryun.yzliyin.core.net.NeteaseApi
import top.aryun.yzliyin.player.PlayerController
import top.aryun.yzliyin.ui.Routes
import top.aryun.yzliyin.ui.components.SongRow

private val catalogTabs = listOf("歌曲", "歌单", "歌手", "专辑")

/**
 * 跨页面带入的搜索关键词（首页热搜词点一下就进来），
 * 进入搜索页时消费一次即清空。
 */
object SearchQueryBus {
    var pending: String = ""
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(nav: NavController, initialTab: Int = 0) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playState by PlayerController.state.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var source by remember { mutableStateOf(Source.NETEASE) }
    var tab by remember { mutableIntStateOf(initialTab.coerceIn(0, catalogTabs.size - 1)) }
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var artists by remember { mutableStateOf<List<Artist>>(emptyList()) }
    var albums by remember { mutableStateOf<List<Album>>(emptyList()) }
    var suggests by remember { mutableStateOf<List<String>>(emptyList()) }
    var hotWords by remember { mutableStateOf<List<String>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val tabs = catalogTabs

    LaunchedEffect(Unit) {
        val pending = SearchQueryBus.pending
        SearchQueryBus.pending = ""
        hotWords = NeteaseApi.hotSearch()
        if (pending.isNotBlank()) {
            query = pending
            submitted = pending
        }
    }

    LaunchedEffect(query, source) {
        suggests = emptyList()
        if (source != Source.NETEASE || query.isBlank() || query == submitted) return@LaunchedEffect
        kotlinx.coroutines.delay(250)
        if (query.isNotBlank() && query != submitted) {
            suggests = runCatching { NeteaseApi.searchSuggest(query) }.getOrDefault(emptyList())
        }
    }

    LaunchedEffect(submitted, tab, source) {
        songs = emptyList(); playlists = emptyList(); artists = emptyList(); albums = emptyList()
        error = null
        if (submitted.isBlank()) return@LaunchedEffect
        searching = true
        runCatching {
            when (source) {
                Source.NETEASE -> when (tab) {
                    0 -> songs = NeteaseApi.searchSongs(submitted).items
                    1 -> playlists = NeteaseApi.searchPlaylists(submitted).items
                    2 -> artists = NeteaseApi.searchArtists(submitted).items
                    else -> albums = NeteaseApi.searchAlbums(submitted).items
                }
                Source.KUGOU -> songs = KugouApi.searchSongs(submitted).items
                Source.BILIBILI -> songs = BiliApi.searchSongs(submitted).items
            }
        }.onFailure { error = it.message ?: "搜索失败" }
        searching = false
    }

    fun submit(term: String) {
        if (term.isBlank()) return
        query = term
        submitted = term
        keyboard?.hide()
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索歌曲、歌手、专辑") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit(query) }),
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "清空")
                            }
                        }
                    },
                )
            },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )

        // 数据源切换：酷狗 / B 站只提供歌曲搜索，切过去时回到「歌曲」页签
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Source.entries.forEach { s ->
                FilterChip(
                    selected = source == s,
                    onClick = {
                        source = s
                        if (s != Source.NETEASE) tab = 0
                    },
                    label = { Text(s.label) },
                )
            }
        }

        // 已提交且未改动关键词 → 展示结果；否则展示热搜/猜你想搜
        val browsing = submitted.isEmpty() || query != submitted
        if (browsing) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (source == Source.NETEASE && suggests.isNotEmpty()) {
                    item { Text("猜你想搜", style = MaterialTheme.typography.titleSmall) }
                    items(suggests) { s ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { submit(s) }
                                .padding(vertical = 8.dp),
                        ) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(s, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                } else if (source == Source.NETEASE && hotWords.isNotEmpty()) {
                    item { Text("热门搜索", style = MaterialTheme.typography.titleSmall) }
                    item {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            hotWords.forEach { w ->
                                SuggestionChip(onClick = { submit(w) }, label = { Text(w) })
                            }
                        }
                    }
                }
                if (source != Source.NETEASE) {
                    item {
                        Text(
                            "在${source.label}中搜索歌曲",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (suggests.isEmpty() && hotWords.isEmpty()) {
                    item { Text("输入关键词开始搜索", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        } else {
            if (source == Source.NETEASE) {
                PrimaryTabRow(selectedTabIndex = tab) {
                    tabs.forEachIndexed { i, name ->
                        Tab(
                            selected = tab == i,
                            onClick = { tab = i },
                            text = { Text(name) },
                        )
                    }
                }
            } else {
                PrimaryTabRow(selectedTabIndex = 0) {
                    Tab(selected = true, onClick = {}, text = { Text(catalogTabs[0]) })
                }
            }

            if (searching) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 220.dp),
                ) {
                    error?.let { msg ->
                        item { Text(msg, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error) }
                    }
                    when (tab) {
                        0 -> {
                            if (songs.isEmpty() && error == null) emptyItem()
                            itemsIndexed(songs, key = { _, s -> s.id }) { i, song ->
                                SongRow(
                                    song = song,
                                    onClick = {
                                        scope.launch {
                                            PlayerController.playQueue(context, songs, i)
                                        }
                                    },
                                    playing = playState.current?.id == song.id,
                                )
                            }
                        }
                        1 -> {
                            if (playlists.isEmpty() && error == null) emptyItem()
                            items(playlists, key = { it.id }) { p ->
                                PlaylistRow(p) { nav.navigate(Routes.playlist(p.source, p.id)) }
                            }
                        }
                        2 -> {
                            if (artists.isEmpty() && error == null) emptyItem()
                            items(artists, key = { it.id + it.name }) { a ->
                                ArtistRow(a) { if (a.id.isNotEmpty()) nav.navigate(Routes.artist(a.id)) }
                            }
                        }
                        else -> {
                            if (albums.isEmpty() && error == null) emptyItem()
                            items(albums, key = { it.id + it.name }) { a ->
                                AlbumRow(a) { if (a.id.isNotEmpty()) nav.navigate(Routes.album(a.id)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.emptyItem() {
    item {
        Text(
            "没有找到相关结果",
            Modifier.padding(24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PlaylistRow(p: Playlist, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = p.cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                buildString {
                    if (p.author.isNotEmpty()) append(p.author)
                    if (p.songCount > 0) append(" · ${p.songCount} 首")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ArtistRow(a: Artist, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = a.avatar,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(50)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                "单曲 ${a.songCount} · 专辑 ${a.albumCount}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AlbumRow(a: Album, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = a.cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                buildString {
                    if (a.artist.isNotEmpty()) append(a.artist)
                    if (a.publishDate.isNotEmpty()) append(" · ${a.publishDate}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
