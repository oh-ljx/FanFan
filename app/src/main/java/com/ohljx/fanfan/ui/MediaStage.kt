package com.ohljx.fanfan.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import androidx.media3.ui.AspectRatioFrameLayout
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Scale
import coil3.video.VideoFrameDecoder
import coil3.video.videoFrameMillis
import com.ohljx.fanfan.media.MediaItem
import com.ohljx.fanfan.media.MotionPhoto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

val StageColor = Color(0xFF070B0A)
val MintColor = Color(0xFF79D6B0)
val SparkColor = Color(0xFFF2C86B)
val LikeRed = Color(0xFFFF3B62)
val FlipEasing = CubicBezierEasing(0.2f, 0.78f, 0.2f, 1f)
internal val MotionPhotoIoDispatcher = Dispatchers.IO.limitedParallelism(1)

/**
 * 解析实况照片的播放源：规范 Container 目录格式直接把原图交给 Media3 播放；
 * 小米等 MicroVideoOffset / 无 XMP 的纯追加 MP4 格式 Media3 无法识别，先抽成临时文件再播。
 * 返回条目 id 与播放 Uri；仍在检测或检测不到可播放的内嵌视频时返回 null。
 */
internal suspend fun resolveMotionPhotoSource(context: Context, item: MediaItem): Pair<Long, Uri>? =
    withContext(MotionPhotoIoDispatcher) {
        val knownExtraction = item.legacyMotionPhotoHint ||
            (item.motionOffset != null && item.motionLength != null)
        if (knownExtraction || MotionPhoto.requiresExtraction(context.contentResolver, item.uri)) {
            MotionPhoto.extract(context, item)?.let { item.id to Uri.fromFile(it) }
        } else {
            item.id to item.uri
        }
    }

/** 普通视频与实况照片共用的播放器持有者；只在内存中预载垂直方向的下一项。 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class VideoPlayerController(context: Context) {
    private var currentPreloadRank = 0
    private val preloadControl =
        TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> {
            DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(1_800L)
        }
    private val preloadBuilder = DefaultPreloadManager.Builder(context, preloadControl)
    val player: ExoPlayer = preloadBuilder.buildExoPlayer()
    private val preloadManager = preloadBuilder.build()
    private val trackedItems = mutableMapOf<Long, ExoMediaItem>()
    private val trackedRanks = mutableMapOf<Long, Int>()

    var muted by mutableStateOf(false)
    val pausedIds = mutableStateListOf<Long>()
    val positions = mutableMapOf<Long, Long>()
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var feedbackFlash by mutableStateOf(false)
    var motionPlaybackActive by mutableStateOf(false)
        private set
    var showMotionSurface by mutableStateOf(false)
        private set
    var firstFrameReady by mutableStateOf(false)
        private set
    /** 当前视频宽高比（含旋转/像素比修正），0 表示未知。 */
    var videoAspectRatio by mutableFloatStateOf(0f)

    internal var boundItemId: Long? = null
    internal var boundIsMotionPhoto: Boolean = false

    /** 优先接管已预载的 MediaSource；未命中时仍走同一个播放器的正常准备路径。 */
    internal fun prepareSource(item: MediaItem, sourceUri: Uri, startPositionMs: Long) {
        val rank = trackedRanks[item.id] ?: if (boundItemId == null) currentPreloadRank else currentPreloadRank + 1
        val mediaItem = track(item.id, sourceUri, rank)
        currentPreloadRank = trackedRanks.getValue(item.id)
        preloadManager.setCurrentPlayingIndex(currentPreloadRank)
        val preloaded = preloadManager.getMediaSource(mediaItem)
        if (preloaded != null) {
            player.setMediaSource(preloaded, startPositionMs)
        } else {
            player.setMediaItem(mediaItem, startPositionMs)
        }
        player.prepare()
    }

    /** 窗口恒定为“当前可播放项 + 下一项”，不会随几万张相册线性增长。 */
    internal fun preloadNext(item: MediaItem?, sourceUri: Uri?) {
        if (item != null && sourceUri != null) track(item.id, sourceUri, currentPreloadRank + 1)
        val keep = setOfNotNull(boundItemId, item?.id?.takeIf { sourceUri != null })
        trackedItems.keys.filter { it !in keep }.forEach(::untrack)
        preloadManager.setCurrentPlayingIndex(currentPreloadRank)
        preloadManager.invalidate()
    }

    private fun track(id: Long, uri: Uri, rank: Int): ExoMediaItem {
        val mediaItem = ExoMediaItem.Builder().setMediaId(id.toString()).setUri(uri).build()
        val existing = trackedItems[id]
        if (existing == mediaItem) return existing
        if (existing != null) preloadManager.remove(existing)
        trackedItems[id] = mediaItem
        trackedRanks[id] = rank
        preloadManager.add(mediaItem, rank)
        preloadManager.invalidate()
        return mediaItem
    }

    private fun untrack(id: Long) {
        trackedItems.remove(id)?.let(preloadManager::remove)
        trackedRanks.remove(id)
    }

    internal fun release() {
        player.release()
        preloadManager.release()
    }

    /** 长按视频时 2 倍速播放；松开或切换媒体时必须恢复常速。 */
    fun setSpeedBoosted(boosted: Boolean) {
        player.setPlaybackSpeed(if (boosted) 2f else 1f)
    }

    fun isPaused(id: Long): Boolean = id in pausedIds

    fun togglePause(id: Long) {
        if (id in pausedIds) pausedIds.remove(id) else pausedIds.add(id)
        feedbackFlash = true
    }

    fun replayMotion(id: Long) {
        if (boundItemId != id || !boundIsMotionPhoto) return
        // TextureView 常驻且仍持有有效画面（finish 时已 seek 回 0 并渲染了首帧），
        // 同一媒体重播不会再触发 onRenderedFirstFrame，这里直接亮出画面。
        firstFrameReady = true
        showMotionSurface = true
        motionPlaybackActive = true
        player.seekTo(0L)
    }

    internal fun bind(item: MediaItem?) {
        boundItemId = item?.id
        boundIsMotionPhoto = item?.isMotionPhoto == true
        firstFrameReady = false
        motionPlaybackActive = boundIsMotionPhoto
        showMotionSurface = boundIsMotionPhoto
    }

    internal fun onFirstFrame() {
        firstFrameReady = true
    }

    internal fun finishMotionPlayback() {
        if (!boundIsMotionPhoto) return
        motionPlaybackActive = false
        showMotionSurface = false
        firstFrameReady = false
        player.pause()
        player.seekTo(0L)
    }
}

/**
 * 为当前普通视频/实况照片建立并驱动一个 ExoPlayer。
 * 普通视频循环并记忆位置；实况播放抽取出的内嵌 MP4，每次进入只从头播放一次。
 */
@Composable
fun rememberVideoController(
    currentMedia: MediaItem?,
    sourceUri: Uri? = currentMedia?.uri,
    preloadMedia: MediaItem? = null,
    preloadSourceUri: Uri? = preloadMedia?.uri,
    canPlay: Boolean,
): VideoPlayerController {
    val context = LocalContext.current
    val controller = remember { VideoPlayerController(context.applicationContext) }
    val player = controller.player

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) controller.finishMotionPlayback()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (controller.boundIsMotionPhoto) controller.finishMotionPlayback()
            }

            override fun onRenderedFirstFrame() {
                controller.onFirstFrame()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val ratio = if (videoSize.width <= 0 || videoSize.height <= 0) {
                    0f
                } else {
                    videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
                // Media3 reports the display size after device rotation has been applied.
                controller.videoAspectRatio = ratio
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            controller.release()
        }
    }
    DisposableEffect(currentMedia?.id) {
        val outgoingVideo = currentMedia?.takeIf { it.isVideo }
        onDispose {
            outgoingVideo?.let {
                controller.positions[it.id] = player.currentPosition.coerceAtLeast(0L)
            }
        }
    }
    LaunchedEffect(currentMedia?.id, sourceUri) {
        controller.bind(currentMedia)
        controller.videoAspectRatio = 0f
        player.setPlaybackSpeed(1f)
        if (currentMedia == null || sourceUri == null) {
            player.stop()
            player.clearMediaItems()
            controller.positionMs = 0L
            controller.durationMs = 0L
            return@LaunchedEffect
        }
        val startPosition = if (currentMedia.isVideo) {
            controller.positions[currentMedia.id] ?: 0L
        } else {
            0L
        }
        player.repeatMode = if (currentMedia.isMotionPhoto) {
            Player.REPEAT_MODE_OFF
        } else {
            Player.REPEAT_MODE_ONE
        }
        controller.prepareSource(currentMedia, sourceUri, startPosition)
        controller.positionMs = startPosition
        controller.durationMs = if (currentMedia.isVideo) currentMedia.durationMs ?: 0L else 0L
    }
    LaunchedEffect(preloadMedia?.id, preloadSourceUri) {
        controller.preloadNext(preloadMedia, preloadSourceUri)
    }
    val paused = currentMedia?.takeIf { it.isVideo }?.let { controller.isPaused(it.id) } ?: true
    LaunchedEffect(canPlay, paused, currentMedia?.id, controller.motionPlaybackActive) {
        player.playWhenReady = when {
            currentMedia == null -> false
            currentMedia.isMotionPhoto -> canPlay && controller.motionPlaybackActive
            else -> canPlay && !paused
        }
    }
    LaunchedEffect(controller.muted) {
        player.volume = if (controller.muted) 0f else 1f
    }
    LaunchedEffect(currentMedia?.id) {
        if (currentMedia?.isVideo != true) return@LaunchedEffect
        while (true) {
            controller.positionMs = player.currentPosition.coerceAtLeast(0L)
            val reported = player.duration
            if (reported > 0) controller.durationMs = reported
            delay(120)
        }
    }
    LaunchedEffect(controller.feedbackFlash) {
        if (controller.feedbackFlash) {
            delay(380)
            controller.feedbackFlash = false
        }
    }
    return controller
}

/** 当前窗口尺寸（px）；封面请求显式使用它，保证预取与展示的内存缓存键一致。 */
@Composable
private fun currentWindowSizePx(): IntSize = LocalWindowInfo.current.containerSize

/** 全屏媒体封面的 Coil 请求；[PrefetchSlides] 用同一个构建器，保证内存缓存键一致。 */
private fun slideImageRequest(context: Context, item: MediaItem, widthPx: Int, heightPx: Int): ImageRequest =
    ImageRequest.Builder(context)
        .data(item.uri)
        .size(widthPx, heightPx)
        .scale(Scale.FIT)
        .apply {
            if (item.isVideo) {
                decoderFactory(VideoFrameDecoder.Factory())
                videoFrameMillis(0)
            }
        }
        .build()

/** 环境光模糊背景的 Coil 请求（缩小尺寸 + Crop）。 */
private fun ambientImageRequest(context: Context, item: MediaItem, widthPx: Int, heightPx: Int): ImageRequest =
    ImageRequest.Builder(context)
        .data(item.uri)
        .size(widthPx, heightPx)
        .scale(Scale.FILL)
        .apply {
            if (item.isVideo) {
                decoderFactory(VideoFrameDecoder.Factory())
                videoFrameMillis(0)
            }
        }
        .build()

/** 小点滑动预览用的小尺寸缩略图请求。 */
internal fun thumbnailImageRequest(context: Context, item: MediaItem, sizePx: Int): ImageRequest =
    ImageRequest.Builder(context)
        .data(item.uri)
        .size(sizePx)
        .scale(Scale.FILL)
        .apply {
            if (item.isVideo) {
                decoderFactory(VideoFrameDecoder.Factory())
                videoFrameMillis(0)
            }
        }
        .build()

/**
 * 顺序预取最可能访问的少量邻居。execute 会随 LaunchedEffect 取消，快速翻动时不会
 * 留下不断累积的后台解码任务与当前页面争抢 IO/GPU。
 */
@Composable
fun PrefetchSlides(items: List<MediaItem>) {
    if (items.isEmpty()) return
    val context = LocalContext.current
    val imageLoader = remember { SingletonImageLoader.get(context) }
    val screenSize = currentWindowSizePx()
    val ambientWidth = (screenSize.width * 0.55f).toInt().coerceAtLeast(240)
    val ambientHeight = (screenSize.height * 0.55f).toInt().coerceAtLeast(320)
    val ids = items.map { it.id }
    LaunchedEffect(ids) {
        items.take(3).forEach { item ->
            imageLoader.execute(slideImageRequest(context, item, screenSize.width, screenSize.height))
            imageLoader.execute(ambientImageRequest(context, item, ambientWidth, ambientHeight))
            if (item.isMotionPhoto) {
                // 需要抽取的实况（小米等格式）提前抽好，翻到时直接命中缓存；标准格式不会额外抽文件。
                resolveMotionPhotoSource(context, item)
            }
        }
    }
}

/** 环境光背景层：固定不动，过渡时通过 alpha 交叉淡入。 */
@Composable
fun AmbientBackground(item: MediaItem, modifier: Modifier = Modifier, alpha: Float = 1f) {
    val context = LocalContext.current
    val screenSize = currentWindowSizePx()
    val targetWidthPx = (screenSize.width * 0.55f).toInt().coerceAtLeast(240)
    val targetHeightPx = (screenSize.height * 0.55f).toInt().coerceAtLeast(320)
    val request = remember(item.id, targetWidthPx, targetHeightPx) {
        ambientImageRequest(context, item, targetWidthPx, targetHeightPx)
    }
    Box(modifier = modifier.fillMaxSize().alpha(alpha)) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .scale(1.12f)
                .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(26.dp) else Modifier),
        )
        Box(modifier = Modifier.fillMaxSize().background(StageColor.copy(alpha = 0.74f)))
    }
}

/**
 * 单张媒体内容层：只渲染照片本体（视频叠加播放器画面），
 * 背景由 [AmbientBackground] 提供。
 */
@Composable
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
fun Slide(
    item: MediaItem,
    offset: Float,
    horizontal: Boolean,
    videoPlayer: ExoPlayer? = null,
    videoAspectRatio: Float = 0f,
    showVideo: Boolean = videoPlayer != null,
) {
    val context = LocalContext.current
    val screenSize = currentWindowSizePx()
    val request = remember(item.id, screenSize) {
        slideImageRequest(context, item, screenSize.width, screenSize.height)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                if (horizontal) translationX = offset else translationY = offset
            },
    ) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        if (videoPlayer != null) {
            // 用 TextureView 而不是 PlayerView(SurfaceView)：TextureView 直接画在应用窗口里，
            // 首帧出来前保持透明，静帧不会被黑块盖住；
            // 且播放开始后才挂载也能正常出帧——
            // SurfaceView 在部分机型上晚挂 surface 会卡住不出画面，要 seek 一次才恢复。
            AndroidView(
                factory = { viewContext ->
                    AspectRatioFrameLayout(viewContext).apply {
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        addView(TextureView(viewContext))
                    }
                },
                update = { container ->
                    val textureView = container.getChildAt(0) as TextureView
                    if (container.tag !== videoPlayer) {
                        container.tag = videoPlayer
                        videoPlayer.setVideoTextureView(textureView)
                    }
                    container.setAspectRatio(videoAspectRatio)
                },
                onRelease = { container ->
                    (container.getChildAt(0) as? TextureView)?.let(videoPlayer::clearVideoTextureView)
                },
                modifier = Modifier.fillMaxSize().alpha(if (showVideo) 1f else 0f),
            )
        }
    }
}

/** 视频中央暂停/播放指示。 */
@Composable
fun VideoCenterIndicator(paused: Boolean, flash: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Color(0xFF0A100C).copy(alpha = 0.55f))
            .alpha(if (flash) 1f else 0.75f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(26.dp),
        )
    }
}

/** 视频控制条：播放/暂停、可拖进度、时间、静音。 */
@Composable
fun VideoControlsBar(
    positionMs: Long,
    durationMs: Long,
    paused: Boolean,
    muted: Boolean,
    onTogglePause: () -> Unit,
    onToggleMute: () -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction
        ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onTogglePause, modifier = Modifier.size(30.dp)) {
            Icon(
                imageVector = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                contentDescription = if (paused) "播放" else "暂停",
                tint = Color.White,
            )
        }
        Slider(
            value = fraction,
            onValueChange = { dragFraction = it },
            onValueChangeFinished = {
                dragFraction?.let(onSeek)
                dragFraction = null
            },
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.28f),
            ),
        )
        Text(
            text = "${formatVideoTime(positionMs)} / ${formatVideoTime(durationMs)}",
            color = Color.White.copy(alpha = 0.88f),
            fontSize = 13.sp,
        )
        IconButton(onClick = onToggleMute, modifier = Modifier.size(30.dp)) {
            Icon(
                imageVector = if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = if (muted) "取消静音" else "静音",
                tint = Color.White,
            )
        }
    }
}

/** 裸图标直接叠在照片上：先画一层柔化投影保证浅色画面上的可读性，再画本体。 */
@Composable
private fun ShadowedIcon(
    imageVector: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = Color.Black.copy(alpha = 0.45f),
            modifier = Modifier
                .matchParentSize()
                .offset(y = 1.2.dp)
                .blur(4.dp),
        )
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.matchParentSize(),
        )
    }
}

/** 短视频式的独立悬浮动作：裸图标 + 投影，不套圆形衬底。 */
@Composable
private fun RailActionButton(
    label: String,
    contentDescription: String,
    stateDescription: String? = null,
    onClick: () -> Unit,
    icon: @Composable (Modifier) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
                stateDescription?.let { this.stateDescription = it }
            }
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        icon(Modifier.size(31.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            style = TextStyle(
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.55f),
                    offset = Offset(0f, 1f),
                    blurRadius = 5f,
                ),
            ),
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/** 右侧操作栏：喜欢 / 评论 / 第三个操作（翻翻是删除，回收站查看页是恢复）。 */
@Composable
fun ActionRail(
    liked: Boolean,
    noteCount: Int,
    thirdIcon: ImageVector,
    thirdDescription: String,
    onLike: () -> Unit,
    onNote: () -> Unit,
    onThird: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isRestore = thirdDescription.contains("恢复")
    val likeScale by animateFloatAsState(
        targetValue = if (liked) 1.12f else 1f,
        animationSpec = tween(180, easing = FlipEasing),
        label = "like scale",
    )
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RailActionButton(
            label = "喜欢",
            contentDescription = if (liked) "取消喜欢" else "喜欢",
            stateDescription = if (liked) "已喜欢" else "未喜欢",
            onClick = onLike,
        ) { iconModifier ->
            ShadowedIcon(
                imageVector = Icons.Filled.Favorite,
                tint = if (liked) LikeRed else Color.White,
                modifier = iconModifier.scale(likeScale),
            )
        }
        RailActionButton(
            label = if (noteCount > 0) "${noteCount.coerceAtMost(99)} 条" else "评论",
            contentDescription = if (noteCount > 0) "查看评论，共 $noteCount 条" else "评论",
            stateDescription = if (noteCount > 0) "已有评论" else "还没有评论",
            onClick = onNote,
        ) { iconModifier ->
            ShadowedIcon(
                imageVector = AppIcons.Comment,
                tint = Color.White,
                modifier = iconModifier,
            )
        }
        RailActionButton(
            label = if (isRestore) "恢复" else "删除",
            contentDescription = thirdDescription,
            onClick = onThird,
        ) { iconModifier ->
            ShadowedIcon(
                imageVector = thirdIcon,
                tint = Color.White,
                modifier = iconModifier,
            )
        }
    }
}

/** 双击点赞：点击位置弹出带随机倾角的红爱心。 */
@Composable
fun HeartPulse(position: Offset, pulseKey: Int, onDone: () -> Unit) {
    val progress = remember(pulseKey) { Animatable(0f) }
    val tilt = remember(pulseKey) { Random.nextFloat() * 32f - 16f }
    LaunchedEffect(pulseKey) {
        progress.animateTo(1f, tween(500))
        onDone()
    }
    val p = progress.value
    val scale = when {
        p < 0.32f -> 0.4f + p / 0.32f * 0.68f
        p < 0.62f -> 1.08f - (p - 0.32f) / 0.3f * 0.14f
        else -> 0.94f + (p - 0.62f) / 0.38f * 0.06f
    }
    val alpha = when {
        p < 0.08f -> p / 0.08f
        p < 0.62f -> 1f
        else -> 1f - (p - 0.62f) / 0.38f
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Icon(
            imageVector = Icons.Filled.Favorite,
            contentDescription = null,
            tint = LikeRed,
            modifier = Modifier
                .offset {
                    IntOffset(
                        (position.x - 33.dp.toPx()).toInt(),
                        (position.y - 33.dp.toPx() - p * 18.dp.toPx()).toInt(),
                    )
                }
                .size(66.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    rotationZ = tilt
                    this.alpha = alpha
                },
        )
    }
}

fun formatCaptureDate(timestamp: Long): String =
    SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(timestamp))

fun formatVideoTime(ms: Long): String {
    val totalSeconds = max(0L, ms) / 1000
    return "${totalSeconds / 60}:${String.format(Locale.ROOT, "%02d", totalSeconds % 60)}"
}
