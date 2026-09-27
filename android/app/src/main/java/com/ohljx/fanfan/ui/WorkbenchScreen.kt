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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
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

enum class CollectionSource { ALL, FAVORITES, ALBUM, TRASH }
private enum class SubPage { ALL, FAVORITES, ALBUM, TRASH }

/** 理理页的相簿条目：按 MediaStore bucket 聚合出的展示模型。 */
private data class AlbumInfo(
    val bucketId: Long,
    val name: String,
    val count: Int,
    /** 拼贴封面（最多 4 张）。 */
    val covers: List<MediaItem>,
)

/**
 * MediaStore 的 BUCKET_DISPLAY_NAME 是英文目录名（Camera/Screenshots/WeiXin…），
 * 系统相册显示中文是 ROM 自己做的映射，没有公开 API，这里对齐常见相簿的中文名。
 */
internal fun localizedAlbumName(bucketName: String): String =
    AlbumNameLocalizations[bucketName.trim().lowercase()] ?: bucketName

private val AlbumNameLocalizations = mapOf(
    "camera" to "相机",
    "dcim" to "相机",
    "screenshot" to "截屏",
    "screenshots" to "截屏",
    "screenrecord" to "录屏",
    "screenrecords" to "录屏",
    "screenrecorder" to "录屏",
    "download" to "下载",
    "downloads" to "下载",
    "pictures" to "图片",
    "picture" to "图片",
    "photos" to "照片",
    "movies" to "视频",
    "videos" to "视频",
    "weixin" to "微信",
    "micromsg" to "微信",
    "wechat" to "微信",
    "qq" to "QQ",
    "qq_images" to "QQ",
    "qqfile_recv" to "QQ",
    "tencent" to "QQ",
    "tim" to "TIM",
    "wxwork" to "企业微信",
    "weibo" to "微博",
    "sina" to "微博",
    "douyin" to "抖音",
    "tiktok" to "TikTok",
    "xhs" to "小红书",
    "xiaohongshu" to "小红书",
    "capcut" to "剪映",
    "jianying" to "剪映",
    "meitu" to "美图秀秀",
    "taobao" to "淘宝",
    "alipay" to "支付宝",
    "dingtalk" to "钉钉",
    "bluetooth" to "蓝牙",
    "telegram" to "Telegram",
    "whatsapp" to "WhatsApp",
    "instagram" to "Instagram",
)

/** 理理：本轮进度 + 全部/喜欢 + 相簿管理 + 底部最近删除入口 + 各子页。 */
@Composable
fun WorkbenchScreen(
    session: FlipSession,
    library: LibraryRepository,
    favorites: SnapshotStateMap<Long, Unit>,
    allMedia: List<MediaItem>,
    trashItems: List<MediaItem>,
    /** 系统回收站里的全部 id（含在系统相册里删的）；相簿统计要与子页口径一致地排除它们。 */
    systemTrashedIds: Set<Long>,
    hiddenAlbumIds: Set<Long>,
    onRestartRound: () -> Unit,
    onOpenCollection: (CollectionSource, Long, Long?) -> Unit,
    onHideAlbum: (Long) -> Unit,
    onUnhideAlbum: (Long) -> Unit,
    onRestoreItems: (List<MediaItem>) -> Unit,
    onPermanentlyDeleted: (Set<Long>) -> Unit,
    bottomInset: Dp,
) {
    var subPageName by rememberSaveable { mutableStateOf<String?>(null) }
    var subPageBucketId by rememberSaveable { mutableStateOf<Long?>(null) }
    val subPage = subPageName?.let { name -> SubPage.entries.firstOrNull { it.name == name } }
    // 管理模式：相簿卡片显示隐藏/加回角标；离开理理页时自动退出（remember 不持久化）
    var managing by remember { mutableStateOf(false) }

    fun closeSubPage() {
        subPageName = null
        subPageBucketId = null
    }

    val activeIds = session.media.map { it.id }.toSet()
    val activeMedia = allMedia.filter { it.id in activeIds }
    val favoriteItems = activeMedia.filter { it.id in favorites }

    // 相簿按 bucket 聚合；统计口径与子页一致：排除整个系统回收站（含在系统相册里删的），
    // 否则被排除的照片仍会计入数量甚至成为封面，点进去却是空的。
    // 被隐藏的相簿仍列出（可加回），但不参与翻翻/全部/顶部计数。
    val trashedIds = remember(trashItems) { trashItems.mapTo(HashSet()) { it.id } }
    val albumInfos = remember(allMedia, trashedIds, systemTrashedIds) {
        allMedia.asSequence()
            .filter { it.id !in trashedIds && it.id !in systemTrashedIds }
            .groupBy { it.bucketId }
            .map { (bucketId, items) ->
                AlbumInfo(
                    bucketId = bucketId,
                    name = items.firstNotNullOfOrNull { item ->
                        item.bucketName?.takeIf { it.isNotBlank() }
                    }?.let(::localizedAlbumName) ?: "未命名相簿",
                    count = items.size,
                    // allMedia 按拍摄时间倒序，前 4 张即拼贴封面
                    covers = items.take(4),
                )
            }
            .sortedWith(compareByDescending<AlbumInfo> { it.count }.thenBy { it.name })
    }
    // 管理模式下把已隐藏相簿一并列出（置灰 + 加回角标），平时只显示可见相簿
    val gridAlbums = if (managing) albumInfos else albumInfos.filter { it.bucketId !in hiddenAlbumIds }

    BackHandler(subPage != null) { closeSubPage() }

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
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "照片",
                            color = InkColor,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (managing) "完成" else "管理",
                            color = PineColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.5.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { managing = !managing }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        AlbumTile(
                            title = "全部",
                            count = activeMedia.size,
                            items = activeMedia,
                            emptyIcon = Icons.Filled.PhotoLibrary,
                            emptyTint = PineColor,
                            onClick = { subPageName = SubPage.ALL.name },
                            modifier = Modifier.weight(1f),
                        )
                        AlbumTile(
                            title = "喜欢",
                            count = favoriteItems.size,
                            items = favoriteItems,
                            emptyIcon = Icons.Filled.Favorite,
                            emptyTint = LikeRed,
                            onClick = { subPageName = SubPage.FAVORITES.name },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // 相簿与全部/喜欢同一卡片样式，两列排在下面
                    gridAlbums.chunked(2).forEach { rowAlbums ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            rowAlbums.forEach { album ->
                                val hidden = album.bucketId in hiddenAlbumIds
                                AlbumTile(
                                    title = album.name,
                                    count = album.count,
                                    items = album.covers,
                                    emptyIcon = Icons.Filled.PhotoLibrary,
                                    emptyTint = PineColor,
                                    onClick = {
                                        subPageBucketId = album.bucketId
                                        subPageName = SubPage.ALBUM.name
                                    },
                                    modifier = Modifier.weight(1f),
                                    managing = managing,
                                    hidden = hidden,
                                    onToggleHidden = {
                                        if (hidden) {
                                            onUnhideAlbum(album.bucketId)
                                        } else {
                                            onHideAlbum(album.bucketId)
                                        }
                                    },
                                )
                            }
                            if (rowAlbums.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            item {
                TrashBar(
                    count = trashItems.size,
                    onClick = { subPageName = SubPage.TRASH.name },
                )
            }
        }

        // —— 全部子页 ——
        AnimatedVisibility(
            visible = subPage == SubPage.ALL,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
        ) {
            MediaGridPage(
                title = "全部",
                items = activeMedia,
                emptyText = "还没有照片",
                bottomInset = bottomInset,
                onOpen = { onOpenCollection(CollectionSource.ALL, it, null) },
                onBack = ::closeSubPage,
            )
        }

        // —— 喜欢子页 ——
        AnimatedVisibility(
            visible = subPage == SubPage.FAVORITES,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
        ) {
            MediaGridPage(
                title = "喜欢",
                items = favoriteItems,
                emptyText = "还没有喜欢的照片",
                bottomInset = bottomInset,
                onOpen = { onOpenCollection(CollectionSource.FAVORITES, it, null) },
                onBack = ::closeSubPage,
            )
        }

        // —— 相簿子页 ——
        AnimatedVisibility(
            visible = subPage == SubPage.ALBUM,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
        ) {
            val album = albumInfos.firstOrNull { it.bucketId == subPageBucketId }
            if (album == null) {
                // 相簿里的照片可能刚被清空，子页失去对象直接退回主页
                LaunchedEffect(Unit) { closeSubPage() }
            } else {
                MediaGridPage(
                    title = album.name,
                    items = activeMedia.filter { it.bucketId == album.bucketId },
                    emptyText = "这个相簿是空的",
                    bottomInset = bottomInset,
                    onOpen = { onOpenCollection(CollectionSource.ALBUM, it, album.bucketId) },
                    onBack = ::closeSubPage,
                )
            }
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
                onOpen = { onOpenCollection(CollectionSource.TRASH, it, null) },
                onRestoreItems = onRestoreItems,
                onPermanentlyDeleted = onPermanentlyDeleted,
                onBack = ::closeSubPage,
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
    managing: Boolean = false,
    hidden: Boolean = false,
    onToggleHidden: () -> Unit = {},
) {
    val hasPreview = items.isNotEmpty()
    Box(modifier = modifier.height(188.dp)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(20.dp))
                .background(AlbumPlaceholderColor)
                .clickable(
                    enabled = !managing,
                    onClickLabel = "打开$title",
                    onClick = onClick,
                )
                .graphicsLayer { alpha = if (hidden) 0.45f else 1f },
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
        if (managing) {
            // 管理角标：红色横杠 = 不显示这个相簿，绿色加号 = 加回来
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(9.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (hidden) PineColor else DangerColor)
                    .clickable(
                        onClickLabel = if (hidden) "加回相簿$title" else "不显示相簿$title",
                        onClick = onToggleHidden,
                    ),
            ) {
                Icon(
                    imageVector = if (hidden) Icons.Filled.Add else Icons.Filled.Remove,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp),
                )
            }
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

/** 底部最近删除横条：左侧名称，右侧数量 + 右箭头。 */
@Composable
private fun TrashBar(count: Int, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFFBFAF6))
            .clickable(onClickLabel = "打开最近删除", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Text(
            text = "最近删除",
            color = InkColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$count",
            color = Color(0xFF6D7871),
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Color(0xFF6D7871),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 全部 / 喜欢 / 相簿共用的网格子页。 */
@Composable
private fun MediaGridPage(
    title: String,
    items: List<MediaItem>,
    emptyText: String,
    bottomInset: Dp,
    onOpen: (Long) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(PaperColor)) {
        SubPageHeader(title = title, onBack = onBack)
        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(emptyText, color = Color(0xFF6D7871), fontSize = 15.sp)
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
