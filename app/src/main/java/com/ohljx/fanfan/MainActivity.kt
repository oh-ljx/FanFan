package com.ohljx.fanfan

import android.app.Activity
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.view.WindowCompat
import com.ohljx.fanfan.data.LibraryRepository
import com.ohljx.fanfan.flip.FlipSession
import com.ohljx.fanfan.flip.FlipSessionSnapshot
import com.ohljx.fanfan.media.MediaItem
import com.ohljx.fanfan.media.MediaStoreChangeObserver
import com.ohljx.fanfan.media.MediaStoreRepository
import com.ohljx.fanfan.ui.AppIcons
import com.ohljx.fanfan.ui.CollectionSource
import com.ohljx.fanfan.ui.CollectionViewer
import com.ohljx.fanfan.ui.FlipEasing
import com.ohljx.fanfan.ui.FlipScreen
import com.ohljx.fanfan.ui.InkColor
import com.ohljx.fanfan.ui.MediaPermissionGate
import com.ohljx.fanfan.ui.MintColor
import com.ohljx.fanfan.ui.PaperColor
import com.ohljx.fanfan.ui.PineColor
import com.ohljx.fanfan.ui.StageColor
import com.ohljx.fanfan.ui.WorkbenchScreen
import com.ohljx.fanfan.ui.theme.FanFanTheme
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val NavHeight = 48.dp
private const val MaxSystemMediaRequestItems = 2000

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FanFanTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = StageColor,
                ) {
                    MediaPermissionGate {
                        FanFanApp()
                    }
                }
            }
        }
    }
}

@Composable
fun FanFanApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { MediaStoreRepository(context.applicationContext) }
    val library = remember { LibraryRepository.get(context.applicationContext) }

    // —— 启动加载：MediaStore 与 Room 并发读取；实况识别不再逐文件扫描 ——
    var allMedia by remember { mutableStateOf<List<MediaItem>?>(null) }
    var bootSeen by remember { mutableStateOf<Set<Long>?>(null) }
    var bootSession by remember { mutableStateOf<FlipSessionSnapshot?>(null) }
    var systemTrashed by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    val favorites = remember { mutableStateMapOf<Long, Unit>() }
    val noteCounts = remember { mutableStateMapOf<Long, Int>() }
    var bootTrash by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var loadingError by remember { mutableStateOf<String?>(null) }
    var bootAttempt by remember { mutableIntStateOf(0) }
    var mediaRefreshRequest by remember { mutableIntStateOf(0) }

    LaunchedEffect(bootAttempt) {
        loadingError = null
        try {
            coroutineScope {
                val activeTask = async { repository.loadMedia() }
                val systemTrashTask = async { repository.loadSystemTrashed() }
                val appTrashTask = async {
                    if (android.os.Build.VERSION.SDK_INT < 30) library.trashIds() else emptySet()
                }
                val sessionTask = async { library.flipSessionBootState() }
                val favoritesTask = async { library.favoriteIds() }
                val notesTask = async { library.noteCounts() }

                val active = activeTask.await()
                val trashed = systemTrashTask.await()
                val sessionBoot = sessionTask.await()
                val appTrash = appTrashTask.await()

                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    // MediaStore 是回收站成员关系的唯一真相；
                    // 仅清理明确查询为 active 的旧 Room 标记。
                    library.clearTrashForActive(active.mapTo(mutableSetOf()) { it.id })
                }

                systemTrashed = trashed
                bootTrash = if (android.os.Build.VERSION.SDK_INT >= 30) {
                    trashed.mapTo(mutableSetOf()) { it.id }
                } else {
                    appTrash
                }
                allMedia = active + trashed
                bootSeen = sessionBoot.seenIds
                bootSession = sessionBoot.snapshot
                favorites.clear()
                favoritesTask.await().forEach { favorites[it] = Unit }
                noteCounts.clear()
                noteCounts.putAll(notesTask.await())
            }
        } catch (error: Exception) {
            loadingError = error.message ?: "无法读取相册"
        }
    }

    // 系统相册里删除、恢复或新增媒体后，合并连续通知再做一次轻量快照刷新。
    val requestRefresh by rememberUpdatedState(newValue = { mediaRefreshRequest += 1 })
    DisposableEffect(repository) {
        val observer = MediaStoreChangeObserver(context.applicationContext) { requestRefresh() }
        onDispose { observer.close() }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && allMedia != null) requestRefresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val mediaReady = allMedia != null
    LaunchedEffect(mediaRefreshRequest, mediaReady) {
        if (mediaRefreshRequest == 0 || !mediaReady) return@LaunchedEffect
        delay(350)
        try {
            coroutineScope {
                val activeTask = async { repository.loadMedia() }
                val systemTrashTask = async { repository.loadSystemTrashed() }
                val appTrashTask = async {
                    if (android.os.Build.VERSION.SDK_INT < 30) library.trashIds() else emptySet()
                }
                val active = activeTask.await()
                val trashed = systemTrashTask.await()
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    library.clearTrashForActive(active.mapTo(mutableSetOf()) { it.id })
                }
                systemTrashed = trashed
                bootTrash = if (android.os.Build.VERSION.SDK_INT >= 30) {
                    trashed.mapTo(mutableSetOf()) { it.id }
                } else {
                    appTrashTask.await()
                }
                allMedia = active + trashed
            }
        } catch (_: Exception) {
            // 保留屏幕上最后一个成功快照；下一次 onResume/MediaStore 事件会重试。
        }
    }

    val toggleFavorite: (MediaItem, Boolean) -> Unit = { item, forceLike ->
        if (item.id in favorites) {
            if (!forceLike) {
                favorites.remove(item.id)
                scope.launch { library.removeFavorite(item.id) }
            }
        } else {
            favorites[item.id] = Unit
            scope.launch { library.addFavorite(item.id) }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(StageColor)) {
        val media = allMedia
        val seen = bootSeen
        when {
            loadingError != null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
            ) {
                Text(
                    "相册读取失败",
                    color = Color.White,
                    fontSize = 16.sp,
                )
                Text(
                    loadingError.orEmpty(),
                    color = Color(0xFF8FA598),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Button(
                    onClick = {
                        allMedia = null
                        bootSeen = null
                        bootAttempt += 1
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PineColor),
                    modifier = Modifier.padding(top = 18.dp),
                ) {
                    Text("重新读取")
                }
            }
            media == null || seen == null -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MintColor,
            )
            media.isEmpty() -> Text(
                "相册里还没有照片",
                color = Color(0xFF8FA598),
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.Center),
            )
            else -> FanFanTabs(
                allMedia = media,
                initialTrashed = systemTrashed,
                bootSeen = seen,
                initialSession = bootSession,
                bootTrash = bootTrash,
                favorites = favorites,
                noteCounts = noteCounts,
                toggleFavorite = toggleFavorite,
                library = library,
                onMediaPurged = { ids -> allMedia = allMedia?.filter { it.id !in ids } },
                onMediaStoreChanged = { mediaRefreshRequest += 1 },
            )
        }
    }
}

@Composable
private fun FanFanTabs(
    allMedia: List<MediaItem>,
    initialTrashed: List<MediaItem>,
    bootSeen: Set<Long>,
    initialSession: FlipSessionSnapshot?,
    bootTrash: Set<Long>,
    favorites: androidx.compose.runtime.snapshots.SnapshotStateMap<Long, Unit>,
    noteCounts: androidx.compose.runtime.snapshots.SnapshotStateMap<Long, Int>,
    toggleFavorite: (MediaItem, Boolean) -> Unit,
    library: LibraryRepository,
    onMediaPurged: (Set<Long>) -> Unit,
    onMediaStoreChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val session = remember {
        FlipSession(
            media = allMedia.filter { it.id !in bootTrash },
            initialSeen = bootSeen,
            initialSnapshot = initialSession,
            onStateChanged = library::enqueueFlipSessionUpdate,
        )
    }

    // 外部系统相册删除/恢复后保留当前 deck 与回看栈，只对媒体集合做增量对账。
    LaunchedEffect(allMedia, bootTrash) {
        val active = allMedia.filter { it.id !in bootTrash }
        if (active != session.media) session.reconcileMedia(active)
    }

    var tab by rememberSaveable { mutableStateOf("flip") }
    var viewerSourceName by rememberSaveable { mutableStateOf<String?>(null) }
    var viewerStartId by rememberSaveable { mutableStateOf<Long?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var noteSheetOpen by remember { mutableStateOf(false) }
    fun notifyMediaChanged() {
        refreshTick += 1
        onMediaStoreChanged()
    }

    // 回收站列表：只显示翻翻自己删除的（Room 删除标记 ∩ 系统回收站现状）。
    // 系统相册删的、或已被外部恢复/清理的条目都不混入。
    var trashedMedia by remember { mutableStateOf(initialTrashed) }
    LaunchedEffect(initialTrashed, refreshTick, allMedia) {
        val deletedAt = library.trashEntries().associate { it.mediaId to it.deletedAt }
        val list = if (android.os.Build.VERSION.SDK_INT >= 30) {
            initialTrashed.filter { it.id in deletedAt.keys }
        } else {
            allMedia.filter { it.id in deletedAt.keys }
        }
        // 排序统一用系统回收站到期时间
        // （到期时间 = 删除时间 + 固定保留期，大小即删除先后）；
        // Room 的删除时间作兜底。越晚删除的排越上面，与系统相册一致。
        trashedMedia = list.sortedByDescending { item ->
            item.trashExpiresAtSeconds?.times(1000)
                ?: deletedAt[item.id]
                ?: item.dateTaken
        }
    }

    // 恢复：Android 11+ 统一由系统确认并修改 IS_TRASHED，回调成功后再更新本地状态。
    val context = LocalContext.current
    val view = LocalView.current
    SideEffect {
        val window = (context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            val lightChrome = tab != "flip" && viewerSourceName == null
            isAppearanceLightStatusBars = lightChrome
            // 导航条随页面换衬底（理理页是相纸色），系统图标颜色跟着走。
            isAppearanceLightNavigationBars = lightChrome
        }
    }
    var pendingRestoreIds by rememberSaveable { mutableStateOf<LongArray?>(null) }
    fun applyRestoredItems(targets: List<MediaItem>) {
        if (targets.isEmpty()) return
        scope.launch {
            library.restoreFromTrash(targets.map { it.id })
            val existingIds = session.media.mapTo(mutableSetOf()) { it.id }
            session.reconcileMedia(session.media + targets.filter { existingIds.add(it.id) })
            notifyMediaChanged()
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val ids = pendingRestoreIds?.toSet().orEmpty()
        pendingRestoreIds = null
        val targets = allMedia.filter { it.id in ids }
        if (result.resultCode == Activity.RESULT_OK && targets.isNotEmpty()) {
            applyRestoredItems(targets)
        }
    }

    val restoreItems: (List<MediaItem>) -> Unit = { targets ->
        val distinctTargets = targets.distinctBy { it.uri }.take(MaxSystemMediaRequestItems)
        if (distinctTargets.isNotEmpty() && android.os.Build.VERSION.SDK_INT >= 30) {
            pendingRestoreIds = distinctTargets.map { it.id }.toLongArray()
            try {
                val request = MediaStore.createTrashRequest(
                    context.contentResolver,
                    distinctTargets.map { it.uri },
                    false,
                )
                restoreLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
            } catch (_: Exception) {
                pendingRestoreIds = null
            }
        } else if (distinctTargets.isNotEmpty()) {
            applyRestoredItems(distinctTargets)
        }
    }

    fun closeViewer() {
        viewerSourceName = null
        viewerStartId = null
    }
    BackHandler(viewerSourceName != null) { closeViewer() }

    Box(modifier = Modifier.fillMaxSize()) {
        if (tab == "flip") {
            FlipScreen(
                session = session,
                library = library,
                favorites = favorites,
                noteCounts = noteCounts,
                onToggleFavorite = toggleFavorite,
                onLibraryChanged = { notifyMediaChanged() },
                bottomInset = NavHeight,
                onNoteSheetOpenChange = { noteSheetOpen = it },
            )
        } else {
            WorkbenchScreen(
                session = session,
                library = library,
                favorites = favorites,
                allMedia = allMedia,
                trashItems = trashedMedia,
                onRestartRound = {
                    session.restartRound()
                    tab = "flip"
                },
                onOpenCollection = { source, id ->
                    viewerSourceName = source.name
                    viewerStartId = id
                },
                onRestoreItems = restoreItems,
                onPermanentlyDeleted = { ids ->
                    ids.forEach(favorites::remove)
                    onMediaPurged(ids)
                    notifyMediaChanged()
                },
                bottomInset = NavHeight,
            )
        }

        AnimatedVisibility(
            visible = !noteSheetOpen,
            modifier = Modifier.align(Alignment.BottomCenter),
            // 与评论面板同一时长同一曲线，避免两套动画不同步导致的顿挫
            enter = slideInVertically(tween(300, easing = FlipEasing)) { it },
            exit = slideOutVertically(tween(300, easing = FlipEasing)) { it },
        ) {
            BottomNav(
                tab = tab,
                onTab = { tab = it },
            )
        }

        val viewerSource = viewerSourceName?.let { name ->
            CollectionSource.entries.firstOrNull { it.name == name }
        }
        val startId = viewerStartId
        if (viewerSource != null && startId != null) {
            CollectionViewer(
                source = viewerSource,
                startId = startId,
                allMedia = allMedia,
                trashMedia = trashedMedia,
                session = session,
                favorites = favorites,
                noteCounts = noteCounts,
                onToggleFavorite = toggleFavorite,
                library = library,
                onRestoreItems = restoreItems,
                onLibraryChanged = { notifyMediaChanged() },
                onClose = { closeViewer() },
            )
        }
    }
}

/** 全宽贴底导航：翻翻页用暗房衬底，理理页换成相纸色，与页面融为一体。 */
@Composable
private fun BottomNav(tab: String, onTab: (String) -> Unit, modifier: Modifier = Modifier) {
    val onPaper = tab == "workbench"
    val barColor = if (onPaper) PaperColor else Color(0xFF0B100E).copy(alpha = 0.97f)
    val dividerColor = if (onPaper) InkColor.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.08f)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(barColor)
            .navigationBarsPadding(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(dividerColor),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(NavHeight)
                .selectableGroup(),
        ) {
            NavItem(
                label = "翻翻",
                icon = AppIcons.FlipStack,
                active = tab == "flip",
                onPaper = onPaper,
                onClick = { onTab("flip") },
                modifier = Modifier.weight(1f),
            )
            NavItem(
                label = "理理",
                icon = AppIcons.Grid,
                active = tab == "workbench",
                onPaper = onPaper,
                onClick = { onTab("workbench") },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun NavItem(
    label: String,
    icon: ImageVector,
    active: Boolean,
    onPaper: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeTint = if (onPaper) PineColor else MintColor
    val activeLabel = if (onPaper) InkColor else Color.White
    val inactiveTint = if (onPaper) Color(0xFF7C8781) else Color(0xFF8F9A94)
    val iconTint by animateColorAsState(
        targetValue = if (active) activeTint else inactiveTint,
        label = "nav icon tint",
    )
    val labelColor by animateColorAsState(
        targetValue = if (active) activeLabel else inactiveTint,
        label = "nav label color",
    )
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(NavHeight)
            .selectable(
                selected = active,
                role = Role.Tab,
                onClick = onClick,
            ),
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
        Text(
            label,
            color = labelColor,
            fontSize = 15.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
