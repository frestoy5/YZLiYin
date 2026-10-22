package top.aryun.yzliyin.ui.screen

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import top.aryun.yzliyin.R
import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.core.model.Album
import top.aryun.yzliyin.core.model.Artist
import top.aryun.yzliyin.core.model.Playlist
import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.core.model.Source
import top.aryun.yzliyin.core.net.BiliApi
import top.aryun.yzliyin.core.net.KugouApi
import top.aryun.yzliyin.core.net.NeteaseApi
import top.aryun.yzliyin.core.net.YouTubeApi
import top.aryun.yzliyin.player.PlayerController
import top.aryun.yzliyin.ui.Routes
import top.aryun.yzliyin.ui.components.SongRow
import top.aryun.yzliyin.ui.components.labelText

/** 结果分类页签：label 存资源 id，使用处再 stringResource（同 ui/App.kt 的 TopTab）。 */
private data class CatalogTab(@StringRes val label: Int)

private val catalogTabs = listOf(
    CatalogTab(R.string.srch_tab_songs),
    CatalogTab(R.string.srch_tab_playlists),
    CatalogTab(R.string.srch_tab_artists),
    CatalogTab(R.string.srch_tab_albums),
)

/** 「全部源」聚合视图里每个源最多预览几首，想看完整列表点「查看全部」进入单源视图。 */
private const val GROUP_PREVIEW = 6

/**
 * 单个数据源的搜索超时。
 *
 * 聚合视图是四源并行的，若不给单源设超时，最慢那个会拖住整体刷新——
 * 一个源连不上（典型是 YouTube 国内直连不上）就表现为「四个源一起转圈」。
 *
 * 放到 20s 是因为现在已经「到达即渲染」：慢源不再阻塞别的源，这里只需兜住
 * 「永远不回来」这一种情况。取值要高于 HTTP 层 15s 的 connect 超时，
 * 好让更具体的原生错误信息先浮出来，而不是被一句笼统的超时盖掉。
 */
private const val SOURCE_SEARCH_TIMEOUT_MS = 20_000L

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

    // 搜索现场用 rememberSaveable 持久化：从搜索页进播放器/歌单/详情再返回时
    // （那些页面都是 popBackStack 回来、本页 composable 重建），关键词、已提交词、
    // 选中的源与页签都还在，结果列表由下方 LaunchedEffect 自动重新拉取——
    // 否则一回来就掉回热搜初始页，用户要重新输一遍词。
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var allSources by rememberSaveable { mutableStateOf(true) }
    var source by rememberSaveable { mutableStateOf(Source.NETEASE) }
    var tab by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, catalogTabs.size - 1)) }

    // 搜索结果不落保存（返回后随 submitted 自动重拉），只用普通 remember
    var songsBySource by remember { mutableStateOf<Map<Source, List<Song>>>(emptyMap()) }
    var sourceErrors by remember { mutableStateOf<Map<Source, String>>(emptyMap()) }
    var sourcesLoading by remember { mutableStateOf(false) }
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var artists by remember { mutableStateOf<List<Artist>>(emptyList()) }
    var albums by remember { mutableStateOf<List<Album>>(emptyList()) }
    var suggests by remember { mutableStateOf<List<String>>(emptyList()) }
    var hotWords by remember { mutableStateOf<List<String>>(emptyList()) }
    // 搜索历史：进页面读一次，之后每次提交 / 删除都重读（存 SessionStore，跨启动保留）
    var history by remember { mutableStateOf(AppGraph.store.searchHistory()) }
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

    LaunchedEffect(query, allSources, source) {
        suggests = emptyList()
        if (!(allSources || source == Source.NETEASE)) return@LaunchedEffect
        if (query.isBlank() || query == submitted) return@LaunchedEffect
        kotlinx.coroutines.delay(250)
        if (query.isNotBlank() && query != submitted) {
            suggests = runCatching { NeteaseApi.searchSuggest(query) }.getOrDefault(emptyList())
        }
    }

    // 提交一次搜索，四个数据源并行各搜各的：聚合视图直接分组展示，
    // 单源视图从同一份结果里取，不再重复请求。
    //
    // 这里有两处缺一不可，否则就会变成「四个源一起转圈」：
    //  1. 逐源超时（[SOURCE_SEARCH_TIMEOUT_MS]）。YouTube 走 Innertube、还要跑 EJS
    //     challenge 的 WebView 回退求解，直连不上时会长时间空转；不设超时的话
    //     协程会一直等它，而界面上网易云/酷狗/B 站明明早就搜完了。
    //  2. 到达即渲染。原本要等四个源全部回来才整体刷新，等于让快的等慢的；
    //     现在每个源一回来就并进 songsBySource，快的先显示。
    LaunchedEffect(submitted) {
        songsBySource = emptyMap()
        sourceErrors = emptyMap()
        if (submitted.isBlank()) return@LaunchedEffect
        sourcesLoading = true
        val term = submitted
        try {
            coroutineScope {
                Source.online.forEach { s ->
                    async {
                        val result = runCatching {
                            val items = withTimeoutOrNull(SOURCE_SEARCH_TIMEOUT_MS) {
                                when (s) {
                                    Source.NETEASE -> NeteaseApi.searchSongs(term).items
                                    Source.KUGOU -> KugouApi.searchSongs(term).items
                                    Source.BILIBILI -> BiliApi.searchSongs(term).items
                                    Source.YOUTUBE -> YouTubeApi.searchSongs(term).items
                                    // 本机音乐不参与网络搜索，这里只是让 when 覆盖到它
                                    Source.LOCAL -> emptyList<Song>()
                                }
                            }
                            // 超时要能落到该源自己的分组标题上，不能静默当成「没结果」——
                            // 否则用户会以为是搜不到。这里刻意不用 error()：
                            // 本作用域里有个叫 error 的局部状态变量，会撞名
                            if (items == null) {
                                throw IllegalStateException(context.getString(R.string.srch_source_timeout))
                            }
                            items
                        }
                        val ok = result.getOrNull()
                        if (ok != null) {
                            songsBySource = songsBySource + (s to ok)
                        } else {
                            val reason = result.exceptionOrNull()?.message
                                ?: context.getString(R.string.srch_fail)
                            sourceErrors = sourceErrors + (s to reason)
                        }
                    }
                }
            }
        } finally {
            // 置 false 放 finally：中途被取消（改关键词重搜）也不会把转圈留在屏幕上
            sourcesLoading = false
        }
    }

    // 网易云的歌单/歌手/专辑分类结果：切到对应页签才拉
    LaunchedEffect(submitted, tab) {
        playlists = emptyList(); artists = emptyList(); albums = emptyList()
        error = null
        if (submitted.isBlank() || tab == 0 || allSources || source != Source.NETEASE) return@LaunchedEffect
        searching = true
        runCatching {
            when (tab) {
                1 -> playlists = NeteaseApi.searchPlaylists(submitted).items
                2 -> artists = NeteaseApi.searchArtists(submitted).items
                else -> albums = NeteaseApi.searchAlbums(submitted).items
            }
        }.onFailure { error = it.message ?: context.getString(R.string.srch_fail) }
        searching = false
    }

    fun submit(term: String) {
        if (term.isBlank()) return
        query = term
        submitted = term
        // 记进历史：热搜词、猜你想搜、历史条目点出来的搜索一样算数，
        // 下次进来就能直接点，不用再输一遍
        AppGraph.store.addSearchHistory(term)
        history = AppGraph.store.searchHistory()
        keyboard?.hide()
    }

    /** 进入单源视图：只看某个源的完整结果。 */
    fun focus(s: Source) {
        allSources = false
        source = s
        tab = 0
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.srch_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit(query) }),
                    trailingIcon = {
                        Row {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.srch_clear))
                                }
                            }
                            // 搜索框右侧的搜索按钮：不想按键盘「搜索」键时点它提交
                            IconButton(onClick = { submit(query) }) {
                                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.srch_action))
                            }
                        }
                    },
                )
            },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.srch_back))
                }
            },
        )

        // 数据源切换：「全部」聚合四个源的结果；选中单个源则只看该源
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = allSources,
                onClick = { allSources = true; tab = 0 },
                label = { Text(stringResource(R.string.srch_all_sources)) },
            )
            Source.online.forEach { s ->
                FilterChip(
                    selected = !allSources && source == s,
                    onClick = { focus(s) },
                    label = { Text(s.labelText()) },
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
                // 搜索历史排最前，只在还没输入关键词时展示：
                // 输字过程中正被猜你想搜顶着，把历史挤走反而碍事
                if (query.isBlank() && history.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.srch_history),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    AppGraph.store.clearSearchHistory()
                                    history = emptyList()
                                },
                            ) { Text(stringResource(R.string.srch_history_clear)) }
                        }
                    }
                    item {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            history.forEach { term ->
                                HistoryChip(
                                    text = term,
                                    onClick = { submit(term) },
                                    onRemove = {
                                        AppGraph.store.removeSearchHistory(term)
                                        history = AppGraph.store.searchHistory()
                                    },
                                )
                            }
                        }
                    }
                    item {
                        Text(
                            stringResource(R.string.srch_history_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if ((allSources || source == Source.NETEASE) && suggests.isNotEmpty()) {
                    item { Text(stringResource(R.string.srch_suggest), style = MaterialTheme.typography.titleSmall) }
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
                } else if ((allSources || source == Source.NETEASE) && hotWords.isNotEmpty()) {
                    item { Text(stringResource(R.string.srch_hot), style = MaterialTheme.typography.titleSmall) }
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
                } else if (!allSources && source != Source.NETEASE) {
                    item {
                        Text(
                            stringResource(R.string.srch_in_source, source.labelText()),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    item { Text(stringResource(R.string.srch_prompt), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        } else {
            // 页签：只有「网易云单源」才展示歌单/歌手/专辑分类，其余情况只有歌曲
            val showNeteaseTabs = !allSources && source == Source.NETEASE
            PrimaryTabRow(selectedTabIndex = if (showNeteaseTabs) tab else 0) {
                if (showNeteaseTabs) {
                    tabs.forEachIndexed { i, tabItem ->
                        Tab(
                            selected = tab == i,
                            onClick = { tab = i },
                            text = { Text(stringResource(tabItem.label)) },
                        )
                    }
                } else {
                    Tab(selected = true, onClick = {}, text = { Text(stringResource(catalogTabs[0].label)) })
                }
            }

            // 只有「一个源都还没回来」时才转圈；任何一源到位就先渲染，
            // 其余分组随后逐个补齐——不再让快的等慢的
            val nothingArrived = songsBySource.isEmpty() && sourceErrors.isEmpty()
            if (tab == 0 && sourcesLoading && nothingArrived) {
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
                    when {
                        tab > 0 -> when (tab) {
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
                        allSources -> Source.online.forEach { s ->
                            val list = songsBySource[s] ?: emptyList()
                            item(key = "hdr_${s.id}") {
                                SourceGroupHeader(
                                    source = s,
                                    count = list.size,
                                    error = sourceErrors[s],
                                    loading = sourcesLoading &&
                                        songsBySource[s] == null &&
                                        sourceErrors[s] == null,
                                    onClick = { focus(s) },
                                )
                            }
                            itemsIndexed(list.take(GROUP_PREVIEW), key = { _, song -> song.key }) { i, song ->
                                SongRow(
                                    song = song,
                                    onClick = {
                                        scope.launch {
                                            PlayerController.playQueue(context, list, i)
                                        }
                                    },
                                    playing = playState.current?.key == song.key,
                                )
                            }
                            if (list.size > GROUP_PREVIEW) {
                                item(key = "more_${s.id}") {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable { focus(s) }
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                    ) {
                                        Text(
                                            stringResource(R.string.srch_view_all, list.size),
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                            }
                        }
                        else -> {
                            val songs = songsBySource[source] ?: emptyList()
                            val sourceError = sourceErrors[source]
                            sourceError?.let { msg ->
                                item { Text(msg, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error) }
                            }
                            if (songs.isEmpty() && sourceError == null) emptyItem()
                            itemsIndexed(songs, key = { _, s -> s.key }) { i, song ->
                                SongRow(
                                    song = song,
                                    onClick = {
                                        scope.launch {
                                            PlayerController.playQueue(context, songs, i)
                                        }
                                    },
                                    playing = playState.current?.key == song.key,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 「全部源」聚合视图里每个数据源的分组标题：源名 + 结果数（或错误原因），点击进入该源完整列表。 */
@Composable
private fun SourceGroupHeader(
    source: Source,
    count: Int,
    error: String?,
    loading: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            source.labelText(),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        if (loading) {
            // 还没回来：给个小转圈而不是「0 首」。到达即渲染之后，
            // 慢源会先以空列表出现，显示 0 会被误读成「这个源搜不到」
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = error ?: stringResource(R.string.srch_count_unit, count),
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.emptyItem() {
    item {
        Text(
            stringResource(R.string.srch_empty),
            Modifier.padding(24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 搜索历史里的一条：点一下直接搜，**长按删掉这一条**。
 *
 * 没用 SuggestionChip 是因为它只认 onClick，塞不进长按删除；
 * 自己画一个圆角描边胶囊，和 SuggestionChip 长得差不多。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryChip(text: String, onClick: () -> Unit, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .combinedClickable(onClick = onClick, onLongClick = onRemove)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
                    if (p.songCount > 0) {
                        append(" · ")
                        append(stringResource(R.string.srch_count_unit, p.songCount))
                    }
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
                stringResource(R.string.srch_artist_meta, a.songCount, a.albumCount),
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
