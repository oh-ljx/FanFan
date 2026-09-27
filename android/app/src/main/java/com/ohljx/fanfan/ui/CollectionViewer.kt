package com.ohljx.fanfan.ui

import android.app.Activity
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ohljx.fanfan.data.LibraryRepository
import com.ohljx.fanfan.data.NoteEntity
import com.ohljx.fanfan.flip.FlipSession
import com.ohljx.fanfan.media.MediaItem
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private data class ViewerTap(val mediaId: Long, val uptime: Long, val position: Offset)

/** 全部 / 喜欢 / 相簿 / 最近删除共用的集合查看页：上下滑动按列表顺序切换。 */
@Composable
fun CollectionViewer(
    source: CollectionSource,
    startId: Long,
    albumBucketId: Long?,
    allMedia: List<MediaItem>,
    trashMedia: List<MediaItem>,
    session: FlipSession,
    favorites: SnapshotStateMap<Long, Unit>,
    noteCounts: SnapshotStateMap<Long, Int>,
    onToggleFavorite: (MediaItem, Boolean) -> Unit,
    library: LibraryRepository,
    onRestoreItems: (List<MediaItem>) -> Unit,
    onLibraryChanged: () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current

    var items by remember { mutableStateOf<List<MediaItem>?>(null) }
    var index by remember { mutableIntStateOf(0) }
    val favoriteIds = favorites.keys.toSet()
    val activeIds = session.media.map { it.id }.toSet()

    LaunchedEffect(source, startId, albumBucketId, allMedia, trashMedia, favoriteIds, activeIds) {
        val previousId = items?.getOrNull(index)?.id
        val list = when (source) {
            CollectionSource.FAVORITES -> allMedia.filter { it.id in favoriteIds && it.id in activeIds }
            CollectionSource.TRASH -> trashMedia
            CollectionSource.ALL -> allMedia.filter { it.id in activeIds }
            CollectionSource.ALBUM -> allMedia.filter { it.bucketId == albumBucketId && it.id in activeIds }
        }
        items = list
        if (list.isEmpty()) {
            index = 0
        } else {
            val preferredId = previousId?.takeIf { id -> list.any { it.id == id } } ?: startId
            val preferredIndex = list.indexOfFirst { it.id == preferredId }
            index = if (preferredIndex >= 0) preferredIndex else index.coerceIn(0, list.lastIndex)
        }
    }

    val list = items
    if (list == null) return
    if (list.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val current = list[index.coerceIn(0, list.lastIndex)]

    // —— 视频 / 实况照片 ——
    val currentVideo = current.takeIf { it.isVideo || it.isMotionPhoto }
    val currentVideoState = rememberUpdatedState(currentVideo)
    var noteSheetOpen by remember { mutableStateOf(false) }
    val noteProgress by animateFloatAsState(
        targetValue = if (noteSheetOpen) 1f else 0f,
        animationSpec = tween(300, easing = FlipEasing),
        label = "note panel",
    )
    val noteLayerVisible = noteSheetOpen || noteProgress > 0.001f
    var notes by remember { mutableStateOf<List<NoteEntity>>(emptyList()) }
    val context = LocalContext.current
    // 实况播放源异步解析：标准格式直接交给 Media3；小米等格式先抽取（见 resolveMotionPhotoSource）。
    val motionVideoSource by produceState<Pair<Long, Uri>?>(initialValue = null, currentVideo?.id) {
        value = null
        currentVideo?.takeIf { it.isMotionPhoto }?.let { item ->
            value = resolveMotionPhotoSource(context, item)
        }
    }
    val sourceUri = when {
        currentVideo == null -> null
        currentVideo.isVideo -> currentVideo.uri
        else -> motionVideoSource
            ?.takeIf { it.first == currentVideo.id }
            ?.second
    }
    val preloadVideo = remember(list, index) {
        list.getOrNull(index + 1)?.takeIf { it.isVideo || it.isMotionPhoto }
    }
    val preloadMotionSource by produceState<Pair<Long, Uri>?>(initialValue = null, preloadVideo?.id) {
        value = null
        preloadVideo?.takeIf { it.isMotionPhoto }?.let { item ->
            value = resolveMotionPhotoSource(context, item)
        }
    }
    val preloadSourceUri = when {
        preloadVideo == null -> null
        preloadVideo.isVideo -> preloadVideo.uri
        else -> preloadMotionSource
            ?.takeIf { it.first == preloadVideo.id }
            ?.second
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var appVisible by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { source, _ ->
            appVisible = source.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val controller = rememberVideoController(
        currentMedia = currentVideo,
        sourceUri = sourceUri,
        preloadMedia = preloadVideo,
        preloadSourceUri = preloadSourceUri,
        // 评论面板打开不暂停播放
        canPlay = appVisible,
    )
    val paused = currentVideo?.takeIf { it.isVideo }?.let { controller.isPaused(it.id) } ?: true

    // 预取前后两张封面，上下滑动切换时直接命中内存缓存
    PrefetchSlides(
        remember(list, index) {
            listOfNotNull(list.getOrNull(index - 1), list.getOrNull(index + 1))
        },
    )

    // 删除（喜欢来源）：Android 11+ 走系统回收站
    fun removeItem(mediaId: Long) {
        val newList = list.filterNot { it.id == mediaId }
        items = newList
        if (newList.isEmpty()) {
            onClose()
            return
        }
        if (index > newList.lastIndex) index = newList.lastIndex
    }

    var pendingSystemTrashId by rememberSaveable { mutableStateOf<Long?>(null) }
    val systemTrashLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val mediaId = pendingSystemTrashId
        pendingSystemTrashId = null
        if (mediaId != null && result.resultCode == Activity.RESULT_OK) {
            scope.launch { library.moveToTrash(mediaId) }
            session.remove(mediaId)
            onLibraryChanged()
            removeItem(mediaId)
        }
    }

    // —— 手势状态 ——
    var offset by remember { mutableFloatStateOf(0f) }
    var dragDirection by remember { mutableStateOf<Int?>(null) } // -1 下一张(上滑) / +1 上一张(下滑)
    var stageSize by remember { mutableStateOf(IntSize.Zero) }
    var heartPulse by remember { mutableStateOf<Offset?>(null) }
    var pulseKey by remember { mutableIntStateOf(0) }
    var lastTap by remember { mutableStateOf<ViewerTap?>(null) }
    var tapJob by remember { mutableStateOf<Job?>(null) }
    var settleJob by remember { mutableStateOf<Job?>(null) }

    val lockThreshold = with(density) { 10.dp.toPx() }
    val flingMin = with(density) { 38.dp.toPx() }
    val doubleTapSlop = with(density) { 24.dp.toPx() }

    fun neighbor(dir: Int): Int? {
        val next = index - dir // dir=-1（上滑）→ index+1
        return if (next in list.indices) next else null
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // 评论面板打开时舞台保持全宽、只压缩高度：横屏照片依然横屏展示
            .fillMaxHeight(1f - NotePanelHeightFraction * noteProgress)
            .background(StageColor)
            .onSizeChanged { stageSize = it }
            .pointerInput(list) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val velocityTracker = VelocityTracker()
                    var dy = 0f
                    var dragging = false
                    var longPressHandled = false
                    var videoSpeedBoost = false
                    val longPressDeadline = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis

                    try {
                        while (true) {
                            // 手指静止按住时不会再有触点事件，用超时等待保证长按时到点立即触发
                            val canLongPress = !dragging && !longPressHandled &&
                                currentVideoState.value?.let { it.isMotionPhoto || it.isVideo } == true
                            val event = if (canLongPress) {
                                val waitMs = (longPressDeadline - SystemClock.uptimeMillis()).coerceAtLeast(0L)
                                withTimeoutOrNull(waitMs) { awaitPointerEvent() }
                            } else {
                                awaitPointerEvent()
                            }
                            if (event == null) {
                                // 长按触发：震动提示开始播放；实况从头重播，视频 2 倍速
                                val media = currentVideoState.value ?: break
                                longPressHandled = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (media.isMotionPhoto) {
                                    controller.replayMotion(media.id)
                                } else {
                                    controller.setSpeedBoosted(true)
                                    videoSpeedBoost = true
                                }
                                continue
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            dy += change.positionChange().y
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            if (longPressHandled) change.consume()
                            if (!longPressHandled && !dragging && abs(dy) >= lockThreshold) {
                                settleJob?.cancel()
                                dragging = true
                            }
                            if (!longPressHandled && dragging) {
                                change.consume()
                                val dir = if (dy < 0) -1 else 1
                                if (dragDirection != dir) dragDirection = dir
                                val target = neighbor(dir)
                                offset = if (target != null) dy else dy * 0.18f
                            }
                        }
                    } finally {
                        // 松开或手势被打断都要恢复常速
                        if (videoSpeedBoost) controller.setSpeedBoosted(false)
                    }

                    val dir = dragDirection
                    if (!dragging || dir == null) {
                        if (longPressHandled) {
                            dragDirection = null
                            return@awaitEachGesture
                        }
                        // 轻点/双击
                        val tapItem = currentVideoState.value?.let { current } ?: current
                        val last = lastTap
                        val isDouble = last != null &&
                            last.mediaId == tapItem.id &&
                            down.uptimeMillis - last.uptime <= 280 &&
                            (last.position - down.position).getDistance() <= doubleTapSlop
                        if (!noteSheetOpen) {
                            if (isDouble) {
                                tapJob?.cancel()
                                lastTap = null
                                onToggleFavorite(tapItem, true)
                                heartPulse = down.position
                                pulseKey += 1
                            } else {
                                lastTap = ViewerTap(tapItem.id, down.uptimeMillis, down.position)
                                tapJob?.cancel()
                                tapJob = scope.launch {
                                    delay(285)
                                    if (
                                        lastTap?.mediaId == tapItem.id &&
                                        currentVideoState.value?.isVideo == true
                                    ) {
                                        controller.togglePause(tapItem.id)
                                    }
                                    lastTap = null
                                }
                            }
                        }
                        dragDirection = null
                        return@awaitEachGesture
                    }

                    val dimension = stageSize.height.toFloat()
                    if (dimension <= 0f) return@awaitEachGesture
                    val velocityMs = velocityTracker.calculateVelocity().y / 1000f
                    val projected = abs(dy + velocityMs * 150f)
                    val target = neighbor(dir)
                    val commit = target != null &&
                        (abs(dy) >= dimension * 0.24f ||
                            (abs(dy) >= flingMin && abs(velocityMs) >= 0.65f && projected >= dimension * 0.24f))
                    val progress = min(1f, abs(dy) / dimension)
                    val remaining = if (commit) 1f - progress else progress
                    val duration = (150f + remaining * 70f - min(30f, abs(velocityMs) * 24f))
                        .coerceIn(140f, 220f)
                        .toInt()

                    settleJob = scope.launch {
                        val animation = Animatable(offset)
                        if (commit) {
                            val targetIndex = requireNotNull(target)
                            val gapPx = with(density) { 16.dp.toPx() }
                            animation.animateTo(
                                if (dir < 0) -(dimension + gapPx) else dimension + gapPx,
                                tween(duration, easing = FlipEasing),
                            ) { offset = value }
                            index = targetIndex
                            offset = 0f
                        } else {
                            animation.animateTo(0f, tween(duration, easing = FlipEasing)) {
                                offset = value
                            }
                        }
                        dragDirection = null
                    }
                }
            },
    ) {
        // 背景固定 + 交叉淡入
        val dimensionNow = stageSize.height.toFloat()
        val ambientProgress = if (dragDirection != null && dimensionNow > 0f) {
            (abs(offset) / dimensionNow).coerceIn(0f, 1f)
        } else {
            0f
        }
        AmbientBackground(item = current, alpha = 1f)
        val dir = dragDirection
        if (dir != null) {
            val targetIndex = neighbor(dir)
            if (targetIndex != null) {
                AmbientBackground(item = list[targetIndex], alpha = ambientProgress)
            }
        }

        // 预览滑块
        val gapPx = with(density) { 16.dp.toPx() }
        if (dir != null) {
            val targetIndex = neighbor(dir)
            if (targetIndex != null) {
                Slide(
                    item = list[targetIndex],
                    offset = offset - dir * (stageSize.height + gapPx),
                    horizontal = false,
                )
            }
        }

        val showPlayer = sourceUri != null &&
            (current.isVideo || (current.isMotionPhoto && controller.showMotionSurface))
        Slide(
            item = current,
            offset = offset,
            horizontal = false,
            videoPlayer = controller.player,
            videoAspectRatio = controller.videoAspectRatio,
            showVideo = currentVideo != null && showPlayer && controller.firstFrameReady,
        )

        heartPulse?.let { position ->
            HeartPulse(position = position, pulseKey = pulseKey, onDone = { heartPulse = null })
        }

        // 所有悬浮控件：评论面板打开时整体淡出，缩小的舞台上只留照片
        if (noteProgress < 0.999f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 1f - noteProgress },
            ) {
                if (currentVideo?.isVideo == true && (paused || controller.feedbackFlash)) {
                    VideoCenterIndicator(
                        paused = paused,
                        flash = controller.feedbackFlash,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                // 顶部：返回 + 日期
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .align(Alignment.TopStart),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回列表",
                        tint = Color.White,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onClose)
                            .padding(4.dp)
                            .align(Alignment.CenterStart),
                    )
                    Text(
                        text = formatCaptureDate(current.dateTaken),
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 44.dp),
                    )
                    // 播放源解析完成（且确认有可播放的内嵌视频）后再亮实况角标
                    val liveBadgeReady = current.isMotionPhoto && motionVideoSource?.first == current.id
                    if (liveBadgeReady) {
                        LivePhotoBadge(
                            onClick = { controller.replayMotion(current.id) },
                            modifier = Modifier.align(Alignment.CenterEnd),
                        )
                    }
                }

                // 操作栏
                ActionRail(
                    liked = current.id in favorites,
                    noteCount = noteCounts[current.id] ?: 0,
                    thirdIcon = if (source == CollectionSource.TRASH) {
                        Icons.Filled.Restore
                    } else {
                        Icons.Outlined.DeleteOutline
                    },
                    thirdDescription = if (source == CollectionSource.TRASH) "恢复照片" else "删除照片",
                    onLike = { onToggleFavorite(current, false) },
                    onNote = {
                        notes = emptyList()
                        noteSheetOpen = true
                        scope.launch { notes = library.notesFor(current.id) }
                    },
                    onThird = {
                        if (source == CollectionSource.TRASH) {
                            onRestoreItems(listOf(current))
                        } else if (android.os.Build.VERSION.SDK_INT >= 30) {
                            pendingSystemTrashId = current.id
                            try {
                                val request = MediaStore.createTrashRequest(
                                    context.contentResolver,
                                    listOf(current.uri),
                                    true,
                                )
                                systemTrashLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                            } catch (_: Exception) {
                                pendingSystemTrashId = null
                                onLibraryChanged()
                            }
                        } else {
                            scope.launch { library.moveToTrash(current.id) }
                            session.remove(current.id)
                            onLibraryChanged()
                            removeItem(current.id)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 7.dp, bottom = 104.dp),
                )

                // 位置指示
                Text(
                    text = "${index + 1} / ${list.size}",
                    color = Color.White.copy(alpha = 0.88f),
                    fontSize = 13.sp,
                    letterSpacing = 1.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp),
                )

                if (currentVideo?.isVideo == true) {
                    VideoControlsBar(
                        positionMs = controller.positionMs,
                        durationMs = controller.durationMs,
                        paused = paused,
                        muted = controller.muted,
                        onTogglePause = { controller.togglePause(currentVideo.id) },
                        onToggleMute = { controller.muted = !controller.muted },
                        onSeek = { fraction ->
                            if (controller.durationMs > 0) {
                                val target = (fraction * controller.durationMs).toLong()
                                controller.player.seekTo(target)
                                controller.positionMs = target
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 34.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                    )
                }
            }
        }
    }

    if (noteLayerVisible) {
        NoteSheet(
            notes = notes,
            progress = noteProgress,
            onDismiss = { noteSheetOpen = false },
            onSend = { text, parentId ->
                scope.launch {
                    library.addNote(current.id, text, parentId)
                    notes = library.notesFor(current.id)
                    noteCounts[current.id] = notes.size
                }
            },
            onDelete = { note ->
                scope.launch {
                    library.removeNote(note.id)
                    notes = library.notesFor(current.id)
                    noteCounts[current.id] = notes.size
                }
            },
        )
    }
}
