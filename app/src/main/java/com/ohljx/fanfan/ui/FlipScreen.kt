package com.ohljx.fanfan.ui

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import com.ohljx.fanfan.data.LibraryRepository
import com.ohljx.fanfan.data.NoteEntity
import com.ohljx.fanfan.flip.FlipDirection
import com.ohljx.fanfan.flip.FlipSession
import com.ohljx.fanfan.media.MediaItem
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private enum class Axis { HORIZONTAL, VERTICAL }

private data class LastTap(val mediaId: Long, val uptime: Long, val position: Offset)

@Composable
fun FlipScreen(
    session: FlipSession,
    library: LibraryRepository,
    favorites: SnapshotStateMap<Long, Unit>,
    noteCounts: SnapshotStateMap<Long, Int>,
    onToggleFavorite: (MediaItem, Boolean) -> Unit,
    onLibraryChanged: () -> Unit,
    bottomInset: Dp,
    onNoteSheetOpenChange: (Boolean) -> Unit = {},
) {
    FlipStage(
        session,
        library,
        favorites,
        noteCounts,
        onToggleFavorite,
        onLibraryChanged,
        bottomInset,
        onNoteSheetOpenChange,
    )
}

@Composable
private fun FlipStage(
    session: FlipSession,
    library: LibraryRepository,
    favorites: SnapshotStateMap<Long, Unit>,
    noteCounts: SnapshotStateMap<Long, Int>,
    onToggleFavorite: (MediaItem, Boolean) -> Unit,
    onLibraryChanged: () -> Unit,
    bottomInset: Dp,
    onNoteSheetOpenChange: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    // 小点滑动条被触摸期间，主舞台不响应轻点/双击
    var dotStripTouch by remember { mutableStateOf(false) }

    var offset by remember { mutableFloatStateOf(0f) }
    var dragDirection by remember { mutableStateOf<FlipDirection?>(null) }
    var previewId by remember { mutableStateOf<Long?>(null) }
    var stageSize by remember { mutableStateOf(IntSize.Zero) }
    var settleJob by remember { mutableStateOf<Job?>(null) }

    // —— 评论 ——
    var notes by remember { mutableStateOf<List<NoteEntity>>(emptyList()) }
    var noteSheetOpen by remember { mutableStateOf(false) }
    val noteProgress by animateFloatAsState(
        targetValue = if (noteSheetOpen) 1f else 0f,
        animationSpec = tween(300, easing = FlipEasing),
        label = "note panel",
    )
    val noteLayerVisible = noteSheetOpen || noteProgress > 0.001f
    // 评论层可见期间通知外层隐藏底部导航
    LaunchedEffect(noteLayerVisible) { onNoteSheetOpenChange(noteLayerVisible) }
    var heartPulse by remember { mutableStateOf<Offset?>(null) }
    var pulseKey by remember { mutableIntStateOf(0) }

    // 删除：Android 11+ 走系统回收站（与系统相册打通，有系统确认弹窗）
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
        }
    }

    fun deleteCurrent() {
        val item = session.current ?: return
        if (Build.VERSION.SDK_INT >= 30) {
            pendingSystemTrashId = item.id
            try {
                val request = MediaStore.createTrashRequest(context.contentResolver, listOf(item.uri), true)
                systemTrashLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
            } catch (_: Exception) {
                pendingSystemTrashId = null
                onLibraryChanged()
            }
        } else {
            scope.launch { library.moveToTrash(item.id) }
            session.remove(item.id)
            onLibraryChanged()
        }
    }

    // —— 视频 / 实况照片播放（共享控制器） ——
    val currentItem = session.current
    val mediaById = remember(session.media) { session.media.associateBy { it.id } }
    val currentVideo = currentItem?.takeIf { it.isVideo || it.isMotionPhoto }
    val currentVideoState = rememberUpdatedState(currentVideo)
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

    val preloadVideo = remember(session.currentId, mediaById) {
        session.targetFor(FlipDirection.UP)
            ?.let(mediaById::get)
            ?.takeIf { it.isVideo || it.isMotionPhoto }
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
        // 评论面板打开不暂停播放：实况/视频在压缩后的舞台上继续播
        canPlay = !session.roundComplete && appVisible,
    )
    val paused = currentVideo?.takeIf { it.isVideo }?.let { controller.isPaused(it.id) } ?: true

    // 预取四个方向的下一组媒体封面，切换时直接命中内存缓存
    val neighborItems = remember(session.currentId, mediaById) {
        FlipDirection.entries.asSequence()
            .mapNotNull { session.targetFor(it) }
            .distinct()
            .mapNotNull(mediaById::get)
            .toList()
    }
    PrefetchSlides(neighborItems)

    // —— 手势 ——
    val lockThreshold = with(density) { 12.dp.toPx() }
    val lockDistance = with(density) { 22.dp.toPx() }
    val flingMin = with(density) { 38.dp.toPx() }
    val doubleTapSlop = with(density) { 24.dp.toPx() }

    var lastTap by remember { mutableStateOf<LastTap?>(null) }
    var tapJob by remember { mutableStateOf<Job?>(null) }

    fun isHorizontal(direction: FlipDirection) =
        direction == FlipDirection.LEFT || direction == FlipDirection.RIGHT

    fun dimensionOf(direction: FlipDirection): Float =
        (if (isHorizontal(direction)) stageSize.width else stageSize.height).toFloat()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // 评论面板打开时舞台保持全宽、只压缩高度：照片重新适应可见区域，
            // 横屏照片依然横屏展示，不会被缩成竖向小窗。
            .fillMaxHeight(1f - NotePanelHeightFraction * noteProgress)
            .onSizeChanged { stageSize = it }
            .pointerInput(session.roundComplete) {
                awaitEachGesture {
                    if (session.roundComplete) {
                        awaitFirstDown(requireUnconsumed = false)
                        return@awaitEachGesture
                    }
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val velocityTracker = VelocityTracker()
                    var dx = 0f
                    var dy = 0f
                    var axis: Axis? = null
                    var targets: Map<FlipDirection, Long?>? = null
                    var dragging = false
                    var longPressHandled = false
                    var videoSpeedBoost = false
                    val longPressDeadline = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis

                    try {
                        while (true) {
                            // 手指静止按住时不会再有触点事件，用超时等待保证长按时到点立即触发，
                            // 而不是等手指移动或松开才补判。
                            val canLongPress = axis == null && !longPressHandled &&
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
                            val delta = change.positionChange()
                            dx += delta.x
                            dy += delta.y
                            velocityTracker.addPosition(change.uptimeMillis, change.position)

                            if (longPressHandled) change.consume()

                            if (!longPressHandled && axis == null) {
                                val distance = hypot(dx, dy)
                                if (distance >= lockThreshold) {
                                    val major = max(abs(dx), abs(dy))
                                    val minor = max(1f, min(abs(dx), abs(dy)))
                                    if (major / minor >= 1.25f || distance >= lockDistance) {
                                        settleJob?.cancel()
                                        axis = if (abs(dx) > abs(dy)) Axis.HORIZONTAL else Axis.VERTICAL
                                        targets = mapOf(
                                            FlipDirection.UP to session.targetFor(FlipDirection.UP),
                                            FlipDirection.DOWN to session.targetFor(FlipDirection.DOWN),
                                            FlipDirection.LEFT to session.targetFor(FlipDirection.LEFT),
                                            FlipDirection.RIGHT to session.targetFor(FlipDirection.RIGHT),
                                        )
                                    }
                                }
                            }

                            val lockedAxis = axis
                            val lockedTargets = targets
                            if (!longPressHandled && lockedAxis != null && lockedTargets != null) {
                                change.consume()
                                val direction = when (lockedAxis) {
                                    Axis.HORIZONTAL -> if (dx < 0) FlipDirection.LEFT else FlipDirection.RIGHT
                                    Axis.VERTICAL -> if (dy < 0) FlipDirection.UP else FlipDirection.DOWN
                                }
                                val raw = if (lockedAxis == Axis.HORIZONTAL) dx else dy
                                val target = lockedTargets[direction]
                                if (dragDirection != direction) {
                                    dragDirection = direction
                                    previewId = target
                                }
                                offset = if (target != null) raw else raw * 0.18f
                                dragging = true
                            }
                        }
                    } finally {
                        // 松开或手势被打断都要恢复常速
                        if (videoSpeedBoost) controller.setSpeedBoosted(false)
                    }

                    // 轻点 / 双击
                    val direction = dragDirection
                    val lockedTargets = targets
                    if (!dragging || direction == null || lockedTargets == null) {
                        if (longPressHandled) return@awaitEachGesture
                        val tapItem = session.current
                        if (tapItem != null && !noteSheetOpen && !dotStripTouch) {
                            val last = lastTap
                            val isDouble = last != null &&
                                last.mediaId == tapItem.id &&
                                down.uptimeMillis - last.uptime <= 280 &&
                                (last.position - down.position).getDistance() <= doubleTapSlop
                            if (isDouble) {
                                tapJob?.cancel()
                                lastTap = null
                                onToggleFavorite(tapItem, true)
                                heartPulse = down.position
                                pulseKey += 1
                            } else {
                                lastTap = LastTap(tapItem.id, down.uptimeMillis, down.position)
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
                        return@awaitEachGesture
                    }

                    val velocity = velocityTracker.calculateVelocity()
                    val horizontal = isHorizontal(direction)
                    val delta = if (horizontal) dx else dy
                    val dimension = dimensionOf(direction)
                    if (dimension <= 0f) return@awaitEachGesture
                    val velocityMs = (if (horizontal) velocity.x else velocity.y) / 1000f
                    val projected = abs(delta + velocityMs * 150f)
                    val target = lockedTargets[direction]
                    val commit = target != null &&
                        (abs(delta) >= dimension * 0.24f ||
                            (abs(delta) >= flingMin && abs(velocityMs) >= 0.65f && projected >= dimension * 0.24f))

                    val progress = min(1f, abs(delta) / dimension)
                    val remaining = if (commit) 1f - progress else progress
                    val duration = (150f + remaining * 70f - min(30f, abs(velocityMs) * 24f))
                        .coerceIn(140f, 220f)
                        .toInt()

                    settleJob = scope.launch {
                        val animation = Animatable(offset)
                        if (commit) {
                            val targetId = requireNotNull(target)
                            val gapPx = with(density) { 16.dp.toPx() }
                            val exitOffset = if (direction == FlipDirection.UP || direction == FlipDirection.LEFT) {
                                -(dimension + gapPx)
                            } else {
                                dimension + gapPx
                            }
                            animation.animateTo(exitOffset, tween(duration, easing = FlipEasing)) {
                                offset = value
                            }
                            session.commit(direction, targetId)
                            offset = 0f
                        } else {
                            if (direction == FlipDirection.UP && target == null && session.roundFinished()) {
                                session.completeRound()
                            }
                            animation.animateTo(0f, tween(duration, easing = FlipEasing)) {
                                offset = value
                            }
                        }
                        dragDirection = null
                        previewId = null
                    }
                }
            },
    ) {
        val direction = dragDirection
        val horizontal = direction?.let { isHorizontal(it) } ?: true

        // 背景固定：当前模糊底保持，下一张模糊底随拖动进度淡入
        val ambientProgress = direction?.let {
            val dimension = dimensionOf(it)
            if (dimension > 0f) (abs(offset) / dimension).coerceIn(0f, 1f) else 0f
        } ?: 0f
        currentItem?.let { item ->
            AmbientBackground(item = item, alpha = 1f)
        }
        val previewItem = previewId?.let { id -> session.media.firstOrNull { it.id == id } }
        previewItem?.let { item ->
            AmbientBackground(item = item, alpha = ambientProgress)
        }

        val gapPx = with(density) { 16.dp.toPx() }
        if (previewItem != null && direction != null) {
            val dimension = dimensionOf(direction)
            val sign = if (direction == FlipDirection.UP || direction == FlipDirection.LEFT) -1f else 1f
            Slide(
                item = previewItem,
                offset = offset - sign * (dimension + gapPx),
                horizontal = horizontal,
            )
        }

        currentItem?.let { item ->
            val showPlayer = sourceUri != null &&
                (item.isVideo || (item.isMotionPhoto && controller.showMotionSurface))
            Slide(
                item = item,
                offset = offset,
                horizontal = horizontal,
                // Surface 常驻；首帧前继续露出已缓存的静态封面，
                // 避免切入实况时重建 View 卡顿。
                videoPlayer = controller.player,
                videoAspectRatio = controller.videoAspectRatio,
                showVideo = item.id == currentVideo?.id && showPlayer && controller.firstFrameReady,
            )
        }

        // 衬底渐变与所有悬浮控件：评论面板打开时整体淡出，缩小的舞台上只留照片
        if (noteProgress < 0.999f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 1f - noteProgress },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .align(Alignment.TopCenter)
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                listOf(Color(0xFF050907).copy(alpha = 0.5f), Color.Transparent),
                            ),
                        ),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                listOf(Color.Transparent, Color(0xFF050907).copy(alpha = 0.45f)),
                            ),
                        ),
                )

                if (currentVideo?.isVideo == true && (paused || controller.feedbackFlash)) {
                    VideoCenterIndicator(
                        paused = paused,
                        flash = controller.feedbackFlash,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                currentItem?.let { item ->
                    Text(
                        text = formatCaptureDate(item.dateTaken),
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .statusBarsPadding()
                            .padding(16.dp)
                            .align(Alignment.TopStart),
                    )
                    // 播放源解析完成（且确认有可播放的内嵌视频）后再亮实况角标
                    val liveBadgeReady = item.isMotionPhoto && motionVideoSource?.first == item.id
                    if (liveBadgeReady) {
                        LivePhotoBadge(
                            onClick = { controller.replayMotion(item.id) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .statusBarsPadding()
                                .padding(top = 4.dp, end = 6.dp),
                        )
                    }
                }

                currentItem?.let { item ->
                    ActionRail(
                        liked = item.id in favorites,
                        noteCount = noteCounts[item.id] ?: 0,
                        thirdIcon = Icons.Outlined.DeleteOutline,
                        thirdDescription = "删除",
                        onLike = { onToggleFavorite(item, false) },
                        onNote = {
                            notes = emptyList()
                            noteSheetOpen = true
                            scope.launch { notes = library.notesFor(item.id) }
                        },
                        onThird = { deleteCurrent() },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .navigationBarsPadding()
                            .padding(end = 7.dp, bottom = 104.dp + bottomInset),
                    )
                }

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
                            .padding(bottom = 34.dp + bottomInset)
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                    )
                }

                currentItem?.let { item ->
                    DayPager(
                        session = session,
                        item = item,
                        onTouchActiveChange = { dotStripTouch = it },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 18.dp + bottomInset),
                    )
                }
            }
        }

        heartPulse?.let { position ->
            HeartPulse(
                position = position,
                pulseKey = pulseKey,
                onDone = { heartPulse = null },
            )
        }

        if (session.roundComplete) {
            RoundCompleteOverlay(session)
        }
    }

    if (noteLayerVisible) {
        NoteSheet(
            notes = notes,
            progress = noteProgress,
            onDismiss = { noteSheetOpen = false },
            onSend = { text ->
                val item = session.current ?: return@NoteSheet
                scope.launch {
                    library.addNote(item.id, text)
                    notes = library.notesFor(item.id)
                    noteCounts[item.id] = notes.size
                }
            },
            onDelete = { note ->
                val item = session.current ?: return@NoteSheet
                scope.launch {
                    library.removeNote(note.id)
                    notes = library.notesFor(item.id)
                    noteCounts[item.id] = notes.size
                }
            },
        )
    }
}

@Composable
private fun DayPager(
    session: FlipSession,
    item: MediaItem,
    onTouchActiveChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = session.sameDayItems(item)
    if (items.size < 2) return
    val currentIndex = items.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
    val maxDots = 7
    val start = if (items.size > maxDots) {
        (currentIndex - maxDots / 2).coerceIn(0, items.size - maxDots)
    } else {
        0
    }
    val visible = items.subList(start, minOf(start + maxDots, items.size))

    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var stripWidthPx by remember { mutableIntStateOf(0) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubIndex by remember { mutableIntStateOf(currentIndex) }
    // 滑动条手势期间照片随 commit 不断变化，用最新引用避免 pointerInput 因 items 重建而中断手势
    val currentItems by rememberUpdatedState(items)

    /** 逐步提交到目标位置；方向与手势翻看一致：索引更大 = 屏幕右侧 = 时间更晚。 */
    fun commitTo(targetIndex: Int) {
        var guard = 0
        while (guard++ < currentItems.size) {
            val curId = session.currentId ?: return
            val curIdx = currentItems.indexOfFirst { it.id == curId }
            if (curIdx < 0 || curIdx == targetIndex) return
            val direction = if (targetIndex > curIdx) FlipDirection.LEFT else FlipDirection.RIGHT
            val target = session.targetFor(direction) ?: return
            session.commit(direction, target)
        }
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (scrubbing) {
            ScrubThumbnailStrip(items = items, index = scrubIndex)
            Spacer(Modifier.height(10.dp))
        }
        Row(
            modifier = Modifier
                // 小点本身只有 5dp 高，上下各扩 8dp 方便按住拖动
                .padding(vertical = 8.dp)
                .onSizeChanged { stripWidthPx = it.width }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val width = stripWidthPx.takeIf { it > 0 } ?: return@awaitEachGesture
                        onTouchActiveChange(true)
                        scrubbing = true
                        fun indexAt(x: Float): Int =
                            ((x / width) * currentItems.size).toInt().coerceIn(0, currentItems.lastIndex)
                        var lastIndex = currentItems
                            .indexOfFirst { it.id == session.currentId }
                            .coerceAtLeast(0)
                        scrubIndex = lastIndex

                        fun scrubTo(x: Float) {
                            val targetIndex = indexAt(x)
                            if (targetIndex == lastIndex) return
                            lastIndex = targetIndex
                            scrubIndex = targetIndex
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            commitTo(targetIndex)
                        }

                        try {
                            // 点按小点直接跳到对应照片，拖动则逐张快速翻阅
                            scrubTo(down.position.x)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                scrubTo(change.position.x)
                                change.consume()
                                if (!change.pressed) break
                            }
                        } finally {
                            scrubbing = false
                            // 主舞台的轻点判定在同一个抬起事件之后执行，延迟复位避免误触发暂停/点赞
                            scope.launch { delay(150); onTouchActiveChange(false) }
                        }
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            visible.forEachIndexed { index, dayItem ->
                val absolute = start + index
                val isActive = absolute == currentIndex
                val isEdge = (index == 0 && start > 0) ||
                    (index == visible.lastIndex && start + visible.size < items.size)
                Box(
                    modifier = Modifier
                        .height(5.dp)
                        .width(if (isActive) 16.dp else if (isEdge) 3.dp else 5.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(
                            when {
                                isActive -> Color.White
                                isEdge -> Color.White.copy(alpha = 0.25f)
                                else -> Color.White.copy(alpha = 0.4f)
                            },
                        ),
                )
            }
        }
    }
}

/** 滑动小点时浮在小点上方的缩略图预览：目标照片居中放大，两侧为相邻照片。 */
@Composable
private fun ScrubThumbnailStrip(items: List<MediaItem>, index: Int) {
    val context = LocalContext.current
    val density = LocalDensity.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (position in index - 1..index + 1) {
            if (position !in items.indices) continue
            val target = items[position]
            val isCurrent = position == index
            val size = if (isCurrent) 88.dp else 60.dp
            val corner = if (isCurrent) 14.dp else 10.dp
            val sizePx = with(density) { size.roundToPx() }
            val request = remember(target.id, sizePx) { thumbnailImageRequest(context, target, sizePx) }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(corner))
                    .then(
                        if (isCurrent) {
                            Modifier.border(2.dp, Color.White, RoundedCornerShape(corner))
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}

@Composable
private fun RoundCompleteOverlay(session: FlipSession) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StageColor)
            // 拦截触摸，防止穿透到下面的操作栏/分页点
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(SparkColor),
            )
            Text(
                "这一轮翻完了",
                color = Color(0xFFEEF3EC),
                fontSize = 24.sp,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text(
                "${session.media.size} 张照片，都重新见过了",
                color = Color(0xFF8FA598),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
            Button(
                onClick = { session.restartRound() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = SparkColor,
                    contentColor = Color(0xFF3D2C0C),
                ),
                modifier = Modifier.padding(top = 30.dp),
            ) {
                Text("再翻一遍")
            }
        }
    }
}
