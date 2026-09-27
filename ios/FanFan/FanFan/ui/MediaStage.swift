import Combine
import AVKit
import Photos
import PhotosUI
import SwiftUI

/**
 * 视频播放控制器（对应安卓版 ExoPlayer + VideoPlayerController）。
 * 普通视频循环播放并记忆进度；实况照片走 [LivePhotoController]，不经过这里。
 */
@MainActor
final class VideoPlayerController: ObservableObject {
    let player = AVPlayer()

    @Published var muted = false {
        didSet { player.isMuted = muted }
    }
    @Published private(set) var pausedIds: Set<String> = []
    @Published private(set) var positionMs: Int64 = 0
    @Published private(set) var durationMs: Int64 = 0
    @Published var feedbackFlash = false

    private var positions: [String: Int64] = [:]
    private var timeObserver: Any?
    private var endObserver: NSObjectProtocol?
    private var boundItemId: String?
    private var boundIsVideo = false
    private var speedBoosted = false
    private var canPlay = true

    init() {
        player.automaticallyWaitsToMinimizeStalling = true
        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.12, preferredTimescale: 600),
            queue: .main,
        ) { [weak self] time in
            MainActor.assumeIsolated { self?.poll(time) }
        }
        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: nil,
            queue: .main,
        ) { [weak self] note in
            MainActor.assumeIsolated { self?.handleEnded(note) }
        }
    }

    deinit {
        if let timeObserver { player.removeTimeObserver(timeObserver) }
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
    }

    func bind(item: MediaItem?) {
        if let old = boundItemId, old != item?.id, boundIsVideo {
            positions[old] = positionMs
        }
        boundItemId = item?.id
        boundIsVideo = item?.isVideo == true
        speedBoosted = false
        positionMs = 0
        durationMs = item.flatMap { $0.isVideo ? $0.durationMs : nil } ?? 0
        player.replaceCurrentItem(with: nil)
        guard let item, item.isVideo else { return }
        let requestId = item.id
        let start = positions[item.id] ?? 0
        Task {
            let playerItem = await Self.requestPlayerItem(for: item.asset)
            // 等待期间用户可能已翻到别的媒体
            guard boundItemId == requestId, let playerItem else { return }
            player.replaceCurrentItem(with: playerItem)
            if start > 0 {
                await player.seek(to: CMTime(seconds: Double(start) / 1000, preferredTimescale: 600))
                positionMs = start
            }
            updatePlayback()
        }
    }

    private static func requestPlayerItem(for asset: PHAsset) async -> AVPlayerItem? {
        await withCheckedContinuation { cont in
            let options = PHVideoRequestOptions()
            options.isNetworkAccessAllowed = true
            options.deliveryMode = .highQualityFormat
            PHImageManager.default().requestPlayerItem(forVideo: asset, options: options) { playerItem, _ in
                cont.resume(returning: playerItem)
            }
        }
    }

    func setCanPlay(_ value: Bool) {
        guard value != canPlay else { return }
        canPlay = value
        updatePlayback()
    }

    func isPaused(_ id: String) -> Bool { pausedIds.contains(id) }

    func togglePause(_ id: String) {
        if pausedIds.contains(id) { pausedIds.remove(id) } else { pausedIds.insert(id) }
        feedbackFlash = true
        updatePlayback()
        Task {
            try? await Task.sleep(nanoseconds: 380_000_000)
            guard !Task.isCancelled else { return }
            self.feedbackFlash = false
        }
    }

    /** 长按视频时 2 倍速播放；松开或切换媒体时必须恢复常速。 */
    func setSpeedBoosted(_ boosted: Bool) {
        guard boosted != speedBoosted else { return }
        speedBoosted = boosted
        updatePlayback()
    }

    func seek(toMs ms: Int64) {
        player.seek(to: CMTime(seconds: Double(ms) / 1000, preferredTimescale: 600))
        positionMs = ms
    }

    func shutdown() {
        player.pause()
    }

    private func updatePlayback() {
        guard boundIsVideo, let id = boundItemId else {
            player.pause()
            return
        }
        if canPlay && !pausedIds.contains(id) {
            player.rate = speedBoosted ? 2 : 1
        } else {
            player.pause()
        }
    }

    private func poll(_ time: CMTime) {
        guard boundIsVideo, boundItemId != nil else { return }
        positionMs = max(0, Int64(time.seconds * 1000))
        if let duration = player.currentItem?.duration, duration.isNumeric {
            let ms = Int64(duration.seconds * 1000)
            if ms > 0 { durationMs = ms }
        }
    }

    private func handleEnded(_ note: Notification) {
        guard let item = note.object as? AVPlayerItem, item === player.currentItem else { return }
        // 普通视频循环播放
        player.seek(to: .zero)
        positionMs = 0
        updatePlayback()
    }
}

/**
 * 实况照片播放控制：进入时自动从头播放一次，结束后回到静态封面；
 * 长按照片或点按右上角标志可重播。
 */
@MainActor
final class LivePhotoController: ObservableObject {
    @Published private(set) var livePhoto: PHLivePhoto?
    @Published private(set) var playing = false

    private var loadedForId: String?

    func load(for item: MediaItem?) async {
        guard let item, item.isLivePhoto else {
            loadedForId = nil
            livePhoto = nil
            playing = false
            return
        }
        if loadedForId == item.id { return }
        loadedForId = item.id
        livePhoto = nil
        playing = false
        let photo = await Self.requestLivePhoto(for: item.asset)
        guard loadedForId == item.id else { return }
        livePhoto = photo
        if photo != nil { playing = true }
    }

    func replay() {
        guard livePhoto != nil, !playing else { return }
        playing = true
    }

    func didEndPlayback() {
        playing = false
    }

    private static func requestLivePhoto(for asset: PHAsset) async -> PHLivePhoto? {
        await withCheckedContinuation { cont in
            let options = PHLivePhotoRequestOptions()
            options.deliveryMode = .highQualityFormat
            options.isNetworkAccessAllowed = true
            var resumed = false
            PHImageManager.default().requestLivePhoto(
                for: asset,
                targetSize: ScreenMetrics.sizePx,
                contentMode: .aspectFit,
                options: options,
            ) { photo, info in
                let degraded = (info?[PHImageResultIsDegradedKey] as? Bool) ?? false
                guard !degraded, !resumed else { return }
                resumed = true
                cont.resume(returning: photo)
            }
        }
    }
}

/** 播放中的实况照片层；播放结束由代理回调驱动视图卸载，露出底下的静态封面。 */
struct LivePhotoPlayerView: UIViewRepresentable {
    let livePhoto: PHLivePhoto
    let onEnd: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onEnd: onEnd) }

    func makeUIView(context: Context) -> PHLivePhotoView {
        let view = PHLivePhotoView()
        view.delegate = context.coordinator
        view.playbackGestureRecognizer.isEnabled = false
        view.isMuted = false
        view.contentMode = .scaleAspectFit
        view.livePhoto = livePhoto
        return view
    }

    func updateUIView(_ uiView: PHLivePhotoView, context: Context) {
        context.coordinator.onEnd = onEnd
        if !context.coordinator.started {
            context.coordinator.started = true
            // 等视图完成布局再开始播放，否则首帧可能不渲染
            DispatchQueue.main.async { uiView.startPlayback(with: .full) }
        }
    }

    final class Coordinator: NSObject, PHLivePhotoViewDelegate {
        var onEnd: () -> Void
        var started = false

        init(onEnd: @escaping () -> Void) { self.onEnd = onEnd }

        func livePhotoView(
            _ livePhotoView: PHLivePhotoView,
            didEndPlaybackWith playbackStyle: PHLivePhotoViewPlaybackStyle,
        ) {
            onEnd()
        }
    }
}

/** AVPlayerLayer 承载层：首帧渲染前保持透明，静帧不会被黑块盖住。 */
struct VideoPlayerView: UIViewRepresentable {
    let player: AVPlayer

    func makeUIView(context: Context) -> PlayerLayerView {
        let view = PlayerLayerView()
        view.playerLayer.player = player
        view.playerLayer.videoGravity = .resizeAspect
        view.backgroundColor = .clear
        return view
    }

    func updateUIView(_ uiView: PlayerLayerView, context: Context) {
        if uiView.playerLayer.player !== player {
            uiView.playerLayer.player = player
        }
    }
}

final class PlayerLayerView: UIView {
    override static var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
}

/**
 * 单张媒体内容层：只渲染照片本体（视频叠加播放器画面），
 * 背景由 [AmbientBackground] 提供。
 */
struct Slide: View {
    let item: MediaItem
    let offset: CGFloat
    let horizontal: Bool
    var videoPlayer: AVPlayer? = nil
    var showVideo = false

    var body: some View {
        ZStack {
            MediaImageView(item: item, targetSize: ScreenMetrics.sizePx, contentMode: .aspectFit)
            if let videoPlayer, showVideo {
                VideoPlayerView(player: videoPlayer)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .offset(x: horizontal ? offset : 0, y: horizontal ? 0 : offset)
    }
}

/** 环境光背景层：固定不动，过渡时通过 alpha 交叉淡入。 */
struct AmbientBackground: View {
    let item: MediaItem
    var alpha: CGFloat = 1

    var body: some View {
        let size = ScreenMetrics.sizePx
        Color.clear
            .overlay(
                MediaImageView(
                    item: item,
                    targetSize: CGSize(
                        width: max(240, size.width * 0.55),
                        height: max(320, size.height * 0.55),
                    ),
                    contentMode: .aspectFill,
                ),
            )
            .scaleEffect(1.12)
            .blur(radius: 26)
            .overlay(Color.stage.opacity(0.74))
            .opacity(alpha)
            .clipped()
    }
}

/** 视频中央暂停/播放指示。 */
struct VideoCenterIndicator: View {
    let paused: Bool
    let flash: Bool

    var body: some View {
        ZStack {
            Circle()
                .fill(Color(hex: 0x0A100C).opacity(0.55))
            Image(systemName: paused ? "play.fill" : "pause.fill")
                .font(.system(size: 26))
                .foregroundStyle(.white)
        }
        .frame(width: 56, height: 56)
        .opacity(flash ? 1 : 0.75)
    }
}

/** 视频控制条：播放/暂停、可拖进度、时间、静音。 */
struct VideoControlsBar: View {
    let positionMs: Int64
    let durationMs: Int64
    let paused: Bool
    let muted: Bool
    let onTogglePause: () -> Void
    let onToggleMute: () -> Void
    let onSeek: (Double) -> Void

    @State private var dragFraction: Double?

    var body: some View {
        let fraction = dragFraction
            ?? (durationMs > 0
                ? min(1, max(0, Double(positionMs) / Double(durationMs)))
                : 0)
        HStack(spacing: 0) {
            Button(action: onTogglePause) {
                Image(systemName: paused ? "play.fill" : "pause.fill")
                    .font(.system(size: 17))
                    .foregroundStyle(.white)
                    .frame(width: 30, height: 30)
            }
            .accessibilityLabel(paused ? "播放" : "暂停")
            Slider(
                value: Binding(
                    get: { fraction },
                    set: { dragFraction = $0 },
                ),
                onEditingChanged: { editing in
                    if !editing, let dragFraction {
                        onSeek(dragFraction)
                        self.dragFraction = nil
                    }
                },
            )
            .tint(.white)
            .padding(.horizontal, 6)
            Text("\(formatVideoTime(positionMs)) / \(formatVideoTime(durationMs))")
                .font(.system(size: 13))
                .foregroundStyle(.white.opacity(0.88))
                .fixedSize()
            Button(action: onToggleMute) {
                Image(systemName: muted ? "speaker.slash.fill" : "speaker.wave.2.fill")
                    .font(.system(size: 15))
                    .foregroundStyle(.white)
                    .frame(width: 30, height: 30)
            }
            .accessibilityLabel(muted ? "取消静音" : "静音")
        }
    }
}

/** 裸图标直接叠在照片上：先画一层柔化投影保证浅色画面上的可读性，再画本体。 */
struct ShadowedIcon: View {
    let icon: AppIcon
    let tint: Color

    var body: some View {
        AppIconImage(icon: icon, tint: tint)
            .background(
                AppIconImage(icon: icon, tint: .black.opacity(0.45))
                    .blur(radius: 4)
                    .offset(y: 1.2),
            )
    }
}

/** 短视频式的独立悬浮动作：裸图标 + 投影，不套圆形衬底。 */
struct RailActionButton<Icon: View>: View {
    let label: String
    let contentDescription: String
    let action: () -> Void
    @ViewBuilder let icon: () -> Icon

    var body: some View {
        Button(action: action) {
            VStack(spacing: 3) {
                icon()
                    .frame(width: 31, height: 31)
                Text(label)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(.white)
                    .shadow(color: .black.opacity(0.55), radius: 5, x: 0, y: 1)
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 5)
            .contentShape(RoundedRectangle(cornerRadius: 12))
        }
        .accessibilityLabel(contentDescription)
    }
}

/** 右侧操作栏：喜欢 / 评论 / 删除。 */
struct ActionRail: View {
    let liked: Bool
    let noteCount: Int
    let thirdIcon: AppIcon
    let thirdLabel: String
    let thirdDescription: String
    let onLike: () -> Void
    let onNote: () -> Void
    let onThird: () -> Void

    var body: some View {
        VStack(spacing: 14) {
            RailActionButton(
                label: "喜欢",
                contentDescription: liked ? "取消喜欢" : "喜欢",
                action: onLike,
            ) {
                ShadowedIcon(icon: .favorite, tint: liked ? .likeRed : .white)
                    .scaleEffect(liked ? 1.12 : 1)
                    .animation(flipTiming(0.18), value: liked)
            }
            RailActionButton(
                label: noteCount > 0 ? "\(min(noteCount, 99)) 条" : "评论",
                contentDescription: noteCount > 0 ? "查看评论，共 \(noteCount) 条" : "评论",
                action: onNote,
            ) {
                ShadowedIcon(icon: .comment, tint: .white)
            }
            RailActionButton(
                label: thirdLabel,
                contentDescription: thirdDescription,
                action: onThird,
            ) {
                ShadowedIcon(icon: thirdIcon, tint: .white)
            }
        }
    }
}

/** 双击点赞：点击位置弹出带随机倾角的红爱心。 */
struct HeartPulse: View {
    let position: CGPoint
    let pulseKey: Int
    let onDone: () -> Void

    @State private var progress: CGFloat = 0
    @State private var tilt = Double.random(in: -16...16)

    var body: some View {
        let p = progress
        let scale: CGFloat = if p < 0.32 {
            0.4 + p / 0.32 * 0.68
        } else if p < 0.62 {
            1.08 - (p - 0.32) / 0.3 * 0.14
        } else {
            0.94 + (p - 0.62) / 0.38 * 0.06
        }
        let alpha: CGFloat = if p < 0.08 {
            p / 0.08
        } else if p < 0.62 {
            1
        } else {
            1 - (p - 0.62) / 0.38
        }
        AppIconImage(icon: .favorite, tint: .likeRed)
            .frame(width: 66, height: 66)
            .scaleEffect(scale)
            .rotationEffect(.degrees(tilt))
            .opacity(alpha)
            .position(x: position.x, y: position.y - 18 * p)
            .onAppear {
                withAnimation(.linear(duration: 0.5)) { progress = 1 }
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { onDone() }
            }
    }
}

private let captureDateFormatter: DateFormatter = {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "zh_CN")
    formatter.dateFormat = "yyyy年M月d日"
    return formatter
}()

func formatCaptureDate(_ timestampMs: Int64) -> String {
    captureDateFormatter.string(from: Date(timeIntervalSince1970: TimeInterval(timestampMs) / 1000))
}

func formatVideoTime(_ ms: Int64) -> String {
    let totalSeconds = max(0, ms) / 1000
    return "\(totalSeconds / 60):\(String(format: "%02d", totalSeconds % 60))"
}
