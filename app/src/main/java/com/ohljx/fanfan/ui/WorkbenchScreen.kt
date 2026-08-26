package com.ohljx.fanfan.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.video.VideoFrameDecoder
import coil3.video.videoFrameMillis
import com.ohljx.fanfan.data.LibraryRepository
import com.ohljx.fanfan.flip.FlipSession
import com.ohljx.fanfan.media.MediaItem
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val PaperColor = Color(0xFFF6F3EC)
val InkColor = Color(0xFF13251E)
val PineColor = Color(0xFF204C3D)
val DangerColor = Color(0xFFD84C58)
private val AlbumPlaceholderColor = Color(0xFFE8EDE8)
private const val MAX_SYSTEM_MEDIA_REQUEST_ITEMS = 2_000

enum class CollectionSource { FAVORITES, TRASH }
private enum class SubPage { FAVORITES, TRASH }

/** 理理：本轮进度 + 喜欢/回收站入口 + 两个子页。 */
@Composable
fun WorkbenchScreen(
    session: FlipSession,
    library: LibraryRepository,
    favorites: SnapshotStateMap<Long, Unit>,
    allMedia: List<MediaItem>,
    trashItems: List<MediaItem>,
    onRestartRound: () -> Unit,
    onOpenCollection: (CollectionSource, Long) -> Unit,
    onRestoreItems: (List<MediaItem>) -> Unit,
    onPermanentlyDeleted: (Set<Long>) -> Unit,
    bottomInset: Dp,
) {
    var subPageName by rememberSaveable { mutableStateOf<String?>(null) }
    val subPage = subPageName?.let { name -> SubPage.entries.firstOrNull { it.name == name } }

    val activeIds = session.media.map { it.id }.toSet()
    val favoriteItems = allMedia.filter { it.id in favorites && it.id in activeIds }

    BackHandler(subPage != null) { subPageName = null }

    Box(modifier = Modifier.fillMaxSize().background(PaperColor)) {
        // —— 主页 ——
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentPadding = PaddingValues(
                start = 20.dp,
                top = 20.dp,
                end = 20.dp,
                bottom = bottomInset + 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                ContactSheetHero(
                    seen = session.seenCount,
                    total = session.media.size,
                    onRestart = onRestartRound,
                )
            }
            item {
                Text(
                    text = "你的照片",
                    color = InkColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    AlbumTile(
                        title = "喜欢的照片",
                        count = favoriteItems.size,
                        items = favoriteItems,
                        emptyIcon = Icons.Filled.Favorite,
                        emptyTint = LikeRed,
                        onClick = { subPageName = SubPage.FAVORITES.name },
                        modifier = Modifier.weight(1f),
                    )
                    AlbumTile(
                        title = "最近删除",
                        count = trashItems.size,
                        items = trashItems,
                        emptyIcon = Icons.Filled.DeleteOutline,
                        emptyTint = PineColor,
                        onClick = { subPageName = SubPage.TRASH.name },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // —— 喜欢子页 ——
        AnimatedVisibility(
            visible = subPage == SubPage.FAVORITES,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
        ) {
            FavoritesPage(
                items = favoriteItems,
                bottomInset = bottomInset,
                onOpen = { onOpenCollection(CollectionSource.FAVORITES, it) },
                onBack = { subPageName = null },
            )
        }

        // —— 回收站子页 ——
        AnimatedVisibility(
            visible = subPage == SubPage.TRASH,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
        ) {
            TrashPage(
                items = trashItems,
                library = library,
                bottomInset = bottomInset,
                onOpen = { onOpenCollection(CollectionSource.TRASH, it) },
                onRestoreItems = onRestoreItems,
                onPermanentlyDeleted = onPermanentlyDeleted,
                onBack = { subPageName = null },
            )
        }
    }
}

@Composable
private fun ContactSheetHero(seen: Int, total: Int, onRestart: () -> Unit) {
    val progress = if (total > 0) (seen.toFloat() / total).coerceIn(0f, 1f) else 0f
    val percent = (progress * 100).roundToInt()
    val litSegments = if (seen > 0 && total > 0) {
        (progress * 12).roundToInt().coerceIn(1, 12)
    } else {
        0
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(PineColor)
            .padding(20.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "本轮翻看",
                color = PaperColor.copy(alpha = 0.78f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            Text(
                text = "$percent%",
                color = MintColor,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
        Text(
            text = "$seen / $total",
            color = PaperColor,
            fontSize = 34.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .padding(top = 15.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 15.dp),
        ) {
            repeat(12) { index ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (index < litSegments) MintColor else PaperColor.copy(alpha = 0.15f),
                        ),
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 17.dp),
        ) {
            Text(
                text = when {
                    total == 0 -> "还没有照片可以整理"
                    seen >= total -> "这一轮已经全部翻完"
                    else -> "还有 ${total - seen} 张没翻到过"
                },
                color = PaperColor.copy(alpha = 0.72f),
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MintColor)
                    .clickable(onClickLabel = "重新开始本轮翻看", onClick = onRestart)
                    .padding(horizontal = 15.dp),
            ) {
                Text(
                    text = "重新开始",
                    color = PineColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun AlbumTile(
    title: String,
    count: Int,
    items: List<MediaItem>,
    emptyIcon: ImageVector,
    emptyTint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasPreview = items.isNotEmpty()
    Box(
        modifier = modifier
            .height(188.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(AlbumPlaceholderColor)
            .clickable(onClickLabel = "打开$title", onClick = onClick),
    ) {
        AlbumMosaic(
            items = items.take(4),
            emptyIcon = emptyIcon,
            emptyTint = emptyTint,
            modifier = Modifier.fillMaxSize(),
        )
        if (hasPreview) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(104.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0xFF07100C).copy(alpha = 0.88f)),
                        ),
                    ),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 13.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = if (hasPreview) Color.White else InkColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "›",
                    color = if (hasPreview) Color.White.copy(alpha = 0.7f) else PineColor.copy(alpha = 0.55f),
                    fontSize = 19.sp,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            Text(
                text = "$count 张",
                color = if (hasPreview) Color.White.copy(alpha = 0.72f) else PineColor.copy(alpha = 0.72f),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
private fun AlbumMosaic(
    items: List<MediaItem>,
    emptyIcon: ImageVector,
    emptyTint: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(AlbumPlaceholderColor)) {
        when (items.size) {
            0 -> Icon(
                imageVector = emptyIcon,
                contentDescription = null,
                tint = emptyTint.copy(alpha = 0.78f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .size(28.dp),
            )
            1 -> MosaicThumb(items[0], Modifier.fillMaxSize())
            2 -> {
                MosaicThumb(
                    items[0],
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .fillMaxWidth(0.5f)
                        .padding(end = 1.dp),
                )
                MosaicThumb(
                    items[1],
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .fillMaxWidth(0.5f)
                        .padding(start = 1.dp),
                )
            }
            3 -> {
                MosaicThumb(
                    items[0],
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .fillMaxWidth(0.5f)
                        .padding(end = 1.dp),
                )
                MosaicThumb(
                    items[1],
                    Modifier
                        .align(Alignment.TopEnd)
                        .fillMaxSize(0.5f)
                        .padding(start = 1.dp, bottom = 1.dp),
                )
                MosaicThumb(
                    items[2],
                    Modifier
                        .align(Alignment.BottomEnd)
                        .fillMaxSize(0.5f)
                        .padding(start = 1.dp, top = 1.dp),
                )
            }
            else -> {
                val alignments = listOf(
                    Alignment.TopStart,
                    Alignment.TopEnd,
                    Alignment.BottomStart,
                    Alignment.BottomEnd,
                )
                items.take(4).forEachIndexed { index, item ->
                    MosaicThumb(
                        item,
                        Modifier
                            .align(alignments[index])
                            .fillMaxSize(0.5f)
                            .padding(
                                start = if (index % 2 == 1) 1.dp else 0.dp,
                                top = if (index >= 2) 1.dp else 0.dp,
                                end = if (index % 2 == 0) 1.dp else 0.dp,
                                bottom = if (index < 2) 1.dp else 0.dp,
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun MosaicThumb(item: MediaItem, modifier: Modifier) {
    MediaThumb(
        item = item,
        square = false,
        showBadge = false,
        modifier = modifier,
    )
}

@Composable
private fun SubPageHeader(title: String, onBack: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回",
            tint = InkColor,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(0xFFEDF1EC))
                .clickable(onClick = onBack)
                .padding(12.dp),
        )
        Text(
            title,
            color = InkColor,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 12.dp),
        )
        trailing?.let {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { it() }
        }
    }
}

@Composable
private fun MediaThumb(
    item: MediaItem,
    modifier: Modifier = Modifier,
    square: Boolean = true,
    showBadge: Boolean = true,
) {
    val context = LocalContext.current
    val request = remember(item.id) {
        ImageRequest.Builder(context)
            .data(item.uri)
            .apply {
                if (item.isVideo) {
                    decoderFactory(VideoFrameDecoder.Factory())
                    videoFrameMillis(0)
                }
            }
            .size(360)
            .build()
    }
    Box(modifier = if (square) modifier.aspectRatio(1f) else modifier) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (showBadge && item.isVideo) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(7.dp),
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(10.dp),
                )
                Text(
                    formatVideoTime(item.durationMs ?: 0),
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 3.dp),
                )
            }
        } else if (showBadge && item.isMotionPhoto && !item.legacyMotionPhotoHint) {
            LivePhotoBadge(
                compact = true,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(7.dp),
            )
        }
    }
}

@Composable
private fun FavoritesPage(
    items: List<MediaItem>,
    bottomInset: Dp,
    onOpen: (Long) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(PaperColor)) {
        SubPageHeader(title = "喜欢的照片", onBack = onBack)
        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("还没有喜欢的照片", color = Color(0xFF6D7871), fontSize = 15.sp)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 26.dp + bottomInset),
            ) {
                items(items, key = { it.id }) { item ->
                    MediaThumb(
                        item = item,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onOpen(item.id) },
                    )
                }
            }
        }
    }
}

/** 回收站：网格 + 长按多选 + 滑动连选 + 全选 + 批量恢复/彻底删除。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrashPage(
    items: List<MediaItem>,
    library: LibraryRepository,
    bottomInset: Dp,
    onOpen: (Long) -> Unit,
    onRestoreItems: (List<MediaItem>) -> Unit,
    onPermanentlyDeleted: (Set<Long>) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Long>() }
    val cellRects = remember { mutableStateMapOf<Long, Rect>() }
    var purgeArmed by remember { mutableStateOf(false) }
    var pendingPurgeIds by rememberSaveable { mutableStateOf<LongArray?>(null) }
    var pendingLegacyPurgeIds by rememberSaveable { mutableStateOf<LongArray?>(null) }
    val selectableCount = minOf(items.size, MAX_SYSTEM_MEDIA_REQUEST_ITEMS)

    fun addSelection(id: Long) {
        if (id !in selected && selected.size < MAX_SYSTEM_MEDIA_REQUEST_ITEMS) selected.add(id)
    }

    fun toggleSelection(id: Long) {
        if (id in selected) selected.remove(id) else addSelection(id)
    }

    // 两步确认的“武装”状态 2.6 秒后自动还原
    LaunchedEffect(purgeArmed) {
        if (purgeArmed) {
            kotlinx.coroutines.delay(2600)
            purgeArmed = false
        }
    }

    fun exitSelection() {
        selecting = false
        selected.clear()
        purgeArmed = false
    }

    val purgeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val ids = pendingPurgeIds?.toSet().orEmpty()
        pendingPurgeIds = null
        if (result.resultCode == Activity.RESULT_OK && ids.isNotEmpty()) {
            scope.launch {
                library.permanentlyDelete(ids.toList())
                onPermanentlyDeleted(ids)
            }
        }
    }

    fun performLegacyPurge(ids: Set<Long>) {
        if (ids.isEmpty()) return
        val targets = items.filter { it.id in ids }
        scope.launch {
            val deletedIds = withContext(Dispatchers.IO) {
                targets.mapNotNull { item ->
                    try {
                        item.id.takeIf { context.contentResolver.delete(item.uri, null, null) > 0 }
                    } catch (_: Exception) {
                        null
                    }
                }.toSet()
            }
            if (deletedIds.isNotEmpty()) {
                library.permanentlyDelete(deletedIds.toList())
                onPermanentlyDeleted(deletedIds)
            }
        }
    }

    val legacyWriteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val ids = pendingLegacyPurgeIds?.toSet().orEmpty()
        pendingLegacyPurgeIds = null
        if (granted) performLegacyPurge(ids)
    }

    fun requestPurge(targets: List<MediaItem>) {
        val boundedTargets = targets.take(MAX_SYSTEM_MEDIA_REQUEST_ITEMS)
        if (boundedTargets.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 30) {
            pendingPurgeIds = boundedTargets.map { it.id }.toLongArray()
            try {
                val request = MediaStore.createDeleteRequest(
                    context.contentResolver,
                    boundedTargets.map { it.uri },
                )
                purgeLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
            } catch (_: Exception) {
                pendingPurgeIds = null
            }
        } else {
            val ids = boundedTargets.map { it.id }.toSet()
            val canWrite = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
            if (canWrite) {
                performLegacyPurge(ids)
            } else {
                pendingLegacyPurgeIds = ids.toLongArray()
                legacyWriteLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    BackHandler(selecting) { exitSelection() }

    Box(modifier = Modifier.fillMaxSize().background(PaperColor)) {
        Column(modifier = Modifier.fillMaxSize()) {
            SubPageHeader(
                title = if (selecting) {
                    if (items.size > MAX_SYSTEM_MEDIA_REQUEST_ITEMS && selected.size == selectableCount) {
                        "已选 ${selected.size} 张（最多 2000 张）"
                    } else {
                        "已选 ${selected.size} 张"
                    }
                } else {
                    "最近删除"
                },
                onBack = { if (selecting) exitSelection() else onBack() },
                trailing = if (selecting) {
                    {
                        Text(
                            if (selected.size == selectableCount && selectableCount > 0) {
                                "取消全选"
                            } else if (items.size > MAX_SYSTEM_MEDIA_REQUEST_ITEMS) {
                                "全选（最多 2000）"
                            } else {
                                "全选"
                            },
                            color = PineColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(99.dp))
                                .background(Color(0xFFEDF1EC))
                                .clickable {
                                    if (selected.size == selectableCount) selected.clear()
                                    else {
                                        selected.clear()
                                        selected.addAll(items.take(MAX_SYSTEM_MEDIA_REQUEST_ITEMS).map { it.id })
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                } else null,
            )

            if (items.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("最近删除是空的", color = Color(0xFF6D7871), fontSize = 15.sp)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .pointerInput(selecting) {
                            if (!selecting) return@pointerInput
                            var dragModeAdd = true
                            var lastId: Long? = null
                            detectDragGestures(
                                onDragStart = { start ->
                                    lastId = null
                                    val hit = cellRects.entries.firstOrNull { it.value.contains(start) }?.key
                                    dragModeAdd = hit == null || hit !in selected
                                    if (hit != null && hit != lastId) {
                                        lastId = hit
                                        if (dragModeAdd) addSelection(hit) else selected.remove(hit)
                                    }
                                },
                                onDrag = { change, _ ->
                                    val hit = cellRects.entries
                                        .firstOrNull { it.value.contains(change.position) }?.key
                                    if (hit != null && hit != lastId) {
                                        lastId = hit
                                        if (dragModeAdd) {
                                            addSelection(hit)
                                        } else {
                                            selected.remove(hit)
                                        }
                                    }
                                },
                            )
                        },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        bottom = (if (selecting) 100.dp else 26.dp) + bottomInset,
                    ),
                ) {
                    items(items, key = { it.id }) { item ->
                        val isSelected = item.id in selected
                        Box {
                            MediaThumb(
                                item = item,
                                modifier = Modifier
                                    .onGloballyPositioned { cellRects[item.id] = it.boundsInParent() }
                                    .clip(RoundedCornerShape(12.dp))
                                    .graphicsLayer {
                                        alpha = if (selecting && !isSelected) 1f else if (isSelected) 0.55f else 1f
                                        val s = if (isSelected) 0.92f else 1f
                                        scaleX = s
                                        scaleY = s
                                    }
                                    .combinedClickable(
                                        onClick = {
                                            if (selecting) {
                                                toggleSelection(item.id)
                                            } else {
                                                onOpen(item.id)
                                            }
                                        },
                                        onLongClick = {
                                            if (!selecting) {
                                                selecting = true
                                                addSelection(item.id)
                                            }
                                        },
                                    ),
                            )
                            if (selecting) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(6.dp)
                                        .size(21.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isSelected) PineColor else Color(0xFF0A100C).copy(alpha = 0.28f),
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(12.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 多选操作条
        if (selecting) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp + bottomInset)
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFFFBFAF6))
                    .padding(10.dp),
            ) {
                Text(
                    "恢复",
                    color = PineColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(13.dp))
                        .background(MintColor.copy(alpha = 0.22f))
                        .clickable(enabled = selected.isNotEmpty()) {
                            onRestoreItems(items.filter { it.id in selected })
                            exitSelection()
                        }
                        .padding(vertical = 12.dp)
                        .graphicsLayer { alpha = if (selected.isEmpty()) 0.38f else 1f },
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Text(
                    if (purgeArmed) "确认删除 ${selected.size} 张" else "彻底删除",
                    color = if (purgeArmed) Color.White else DangerColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(13.dp))
                        .background(if (purgeArmed) DangerColor else DangerColor.copy(alpha = 0.12f))
                        .clickable(enabled = selected.isNotEmpty()) {
                            if (!purgeArmed) {
                                purgeArmed = true
                            } else {
                                val targets = items.filter { it.id in selected }
                                exitSelection()
                                requestPurge(targets)
                            }
                        }
                        .padding(vertical = 12.dp)
                        .graphicsLayer { alpha = if (selected.isEmpty()) 0.38f else 1f },
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}
