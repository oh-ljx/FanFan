import Photos
import SwiftUI
import UIKit

private enum Axis { case horizontal, vertical }

private struct LastTap {
    let mediaId: String
    let time: Date
    let position: CGPoint
}

/** 一次拖动手势的私有状态（对应安卓 pointerInput 循环里的局部变量）。 */
private struct DragState {
    var axis: Axis?
    var targets: [FlipDirection: String?] = [:]
    var dragging = false
    var longPressHandled = false
    var videoSpeedBoost = false
    var samples: [(time: Date, x: CGFloat, y: CGFloat)] = []
    var started = false
}

/** 翻翻主舞台：四向翻卡 + 双击喜欢 + 评论 + 删除 + 视频/实况播放。 */
struct FlipScreen: View {
    @ObservedObject var app: AppState
    @ObservedObject var session: FlipSession
    let bottomInset: CGFloat
    let safeAreaInsets: EdgeInsets
    let onNoteSheetOpenChange: (Bool) -> Void

    @StateObject private var videoController = VideoPlayerController()
    @StateObject private var liveController = LivePhotoController()

    @State private var offset: CGFloat = 0
    @State private var dragDirection: FlipDirection?
    @State private var previewId: String?
    @State private var stageSize: CGSize = .zero
    @State private var drag = DragState()
    @State private var settleToken = UUID()

    // 小点滑动条 / 视频控制条被触摸期间，主舞台不响应翻页与轻点
    @State private var overlayTouch = false

    // 评论
    @State private var notes: [Note] = []
    @State private var noteSheetOpen = false
    @State private var noteProgress: CGFloat = 0

    // 双击爱心
    @State private var heartPulse: CGPoint?
    @State private var pulseKey = 0

    // 轻点 / 双击
    @State private var lastTap: LastTap?
    @State private var tapTask: Task<Void, Never>?
    @State private var longPressTask: Task<Void, Never>?

    private var currentItem: MediaItem? { session.current }
    private var currentVideo: MediaItem? {
        currentItem.flatMap { $0.isVideo || $0.isLivePhoto ? $0 : nil }
    }

    private let lockThreshold: CGFloat = 12
    private let lockDistance: CGFloat = 22
    private let flingMin: CGFloat = 38
    private let doubleTapSlop: CGFloat = 24

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .top) {
                stage(in: geo.size, safeAreaTop: safeAreaInsets.top)
                    .frame(width: geo.size.width, height: geo.size.height * (1 - 0.44 * noteProgress))
                    .clipped()

                if noteSheetOpen || noteProgress > 0.001 {
                    NoteSheet(
                        notes: notes,
                        progress: noteProgress,
                        screenSize: geo.size,
                        onDismiss: { noteSheetOpen = false },
                        onSend: { text, parentId in
                            guard let item = session.current else { return }
                            Task {
                                await app.library.addNote(item.id, text: text, parentId: parentId)
                                let updated = (try? await app.library.notesFor(item.id)) ?? []
                                notes = updated
                                app.noteCounts[item.id] = updated.count
                            }
                        },
                        onDelete: { note in
                            guard let item = session.current else { return }
                            Task {
                                await app.library.removeNote(note.id)
                                let updated = (try? await app.library.notesFor(item.id)) ?? []
                                notes = updated
                                app.noteCounts[item.id] = updated.count
                            }
                        },
                    )
                }
            }
        }
        .onChange(of: noteSheetOpen) { open in
            withAnimation(flipTiming(0.3)) { noteProgress = open ? 1 : 0 }
        }
        .onChange(of: noteSheetOpen || noteProgress > 0.001) { visible in
            onNoteSheetOpenChange(visible)
        }
    }

    // MARK: - 舞台

    @ViewBuilder
    private func stage(in size: CGSize, safeAreaTop: CGFloat) -> some View {
        ZStack {
            let mediaById = Dictionary(uniqueKeysWithValues: session.media.map { ($0.id, $0) })

            // 背景固定：当前模糊底保持，下一张模糊底随拖动进度淡入
            let ambientProgress: CGFloat = {
                guard let direction = dragDirection else { return 0 }
                let dimension = dimension(of: direction, in: size)
                return dimension > 0 ? min(1, abs(offset) / dimension) : 0
            }()
            if let item = currentItem {
                AmbientBackground(item: item, alpha: 1)
            }
            let previewItem = previewId.flatMap { mediaById[$0] }
            if let previewItem {
                AmbientBackground(item: previewItem, alpha: ambientProgress)
            }

            let gap: CGFloat = 16
            if let previewItem, let direction = dragDirection {
                let dimension = dimension(of: direction, in: size)
                let sign: CGFloat = direction == .up || direction == .left ? -1 : 1
                Slide(
                    item: previewItem,
                    offset: offset - sign * (dimension + gap),
                    horizontal: direction.isHorizontal,
                )
            }

            if let item = currentItem {
                Slide(
                    item: item,
                    offset: offset,
                    horizontal: dragDirection?.isHorizontal ?? true,
                    videoPlayer: videoController.player,
                    showVideo: item.isVideo,
                )
                // 实况照片：播放时叠一层 PHLivePhotoView，结束露出静态封面
                if item.isLivePhoto, liveController.playing, let livePhoto = liveController.livePhoto {
                    LivePhotoPlayerView(livePhoto: livePhoto) {
                        liveController.didEndPlayback()
                    }
                    .offset(
                        x: (dragDirection?.isHorizontal ?? true) ? offset : 0,
                        y: (dragDirection?.isHorizontal ?? true) ? 0 : offset,
                    )
                }
            }

            // 衬底渐变与所有悬浮控件：评论面板打开时整体淡出，缩小的舞台上只留照片
            if noteProgress < 0.999 {
                overlays(safeAreaTop: safeAreaTop)
                    .opacity(1 - noteProgress)
            }

            if let position = heartPulse {
                HeartPulse(position: position, pulseKey: pulseKey, onDone: { heartPulse = nil })
                    .id(pulseKey)
            }

            if session.roundComplete {
                RoundCompleteOverlay(session: session)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .onSizeChange { stageSize = $0 }
        .simultaneousGesture(stageGesture, including: overlayTouch ? .subviews : .all)
        .onAppear {
            stageSize = stageSize == .zero ? UIScreen.main.bounds.size : stageSize
            videoController.bind(item: currentVideo?.isVideo == true ? currentVideo : nil)
            updateCanPlay()
            prefetchNeighbors()
        }
        .onChange(of: currentItem?.id) { _ in
            videoController.bind(item: currentVideo?.isVideo == true ? currentVideo : nil)
            prefetchNeighbors()
        }
        .task(id: currentItem?.id) {
            await liveController.load(for: currentItem)
        }
        .onChange(of: !session.roundComplete && app.appVisible) { _ in updateCanPlay() }
        .onDisappear { videoController.shutdown() }
    }

    private func updateCanPlay() {
        videoController.setCanPlay(!session.roundComplete && app.appVisible)
    }

    private func prefetchNeighbors() {
        let assets = FlipDirection.allCases
            .compactMap { session.targetFor($0) }
            .uniqued()
            .compactMap { id in session.media.first { $0.id == id } }
            .map(\.asset)
        AssetImageLoader.shared.prefetch(Array(assets.prefix(3)), stageSizePx: ScreenMetrics.sizePx)
    }

    private func dimension(of direction: FlipDirection, in size: CGSize) -> CGFloat {
        direction.isHorizontal ? size.width : size.height
    }

    // MARK: - 悬浮控件

    @ViewBuilder
    private func overlays(safeAreaTop: CGFloat) -> some View {
        ZStack {
            // 顶部 / 底部衬底渐变
            VStack {
                LinearGradient(
                    colors: [Color(hex: 0x050907).opacity(0.5), .clear],
                    startPoint: .top,
                    endPoint: .bottom,
                )
                .frame(height: 120)
                Spacer()
                LinearGradient(
                    colors: [.clear, Color(hex: 0x050907).opacity(0.45)],
                    startPoint: .top,
                    endPoint: .bottom,
                )
                .frame(height: 140)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            if let video = currentVideo, video.isVideo,
               videoController.isPaused(video.id) || videoController.feedbackFlash {
                VideoCenterIndicator(
                    paused: videoController.isPaused(video.id),
                    flash: videoController.feedbackFlash,
                )
            }

            if let item = currentItem {
                Text(formatCaptureDate(item.dateTaken))
                    .font(.system(size: 16, weight: .medium, design: .monospaced))
                    .foregroundStyle(.white)
                    .padding(16)
                    .padding(.top, safeAreaTop)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)

                if item.isLivePhoto, liveController.livePhoto != nil {
                    LivePhotoBadge {
                        liveController.replay()
                    }
                    .padding(.top, 4 + safeAreaTop)
                    .padding(.trailing, 6)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                }
            }

            if let item = currentItem {
                ActionRail(
                    liked: app.favorites.contains(item.id),
                    noteCount: app.noteCounts[item.id] ?? 0,
                    thirdIcon: .deleteOutline,
                    thirdLabel: "删除",
                    thirdDescription: "删除",
                    onLike: { app.toggleFavorite(item, forceLike: false) },
                    onNote: {
                        notes = []
                        noteSheetOpen = true
                        Task {
                            notes = (try? await app.library.notesFor(item.id)) ?? []
                        }
                    },
                    onThird: { Task { await app.delete([item]) } },
                )
                .padding(.trailing, 7)
                .padding(.bottom, 104 + bottomInset)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
            }

            if let video = currentVideo, video.isVideo {
                VideoControlsBar(
                    positionMs: videoController.positionMs,
                    durationMs: videoController.durationMs,
                    paused: videoController.isPaused(video.id),
                    muted: videoController.muted,
                    onTogglePause: { videoController.togglePause(video.id) },
                    onToggleMute: { videoController.muted.toggle() },
                    onSeek: { fraction in
                        guard videoController.durationMs > 0 else { return }
                        videoController.seek(toMs: Int64(fraction * Double(videoController.durationMs)))
                    },
                )
                .padding(.bottom, 34 + bottomInset)
                .padding(.horizontal, 12)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
                .simultaneousGesture(touchTracker)
            }

            if let item = currentItem {
                DayPager(
                    session: session,
                    item: item,
                    onTouchActiveChange: { active in
                        if active {
                            overlayTouch = true
                        } else {
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
                                overlayTouch = false
                            }
                        }
                    },
                )
                .padding(.bottom, 18 + bottomInset)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
            }
        }
    }

    /** 跟踪视频控制条触摸，拖动进度条时不触发翻页。 */
    private var touchTracker: some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { _ in overlayTouch = true }
            .onEnded { _ in
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
                    overlayTouch = false
                }
            }
    }

    // MARK: - 手势

    private var stageGesture: some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                if session.roundComplete { return }
                if !drag.started {
                    drag = DragState(started: true)
                    startLongPressTimer()
                }
                let dx = value.translation.width
                let dy = value.translation.height
                drag.samples.append((value.time, dx, dy))
                if drag.samples.count > 12 { drag.samples.removeFirst() }

                if drag.longPressHandled { return }

                if drag.axis == nil {
                    let distance = hypot(dx, dy)
                    if distance >= lockThreshold {
                        let major = max(abs(dx), abs(dy))
                        let minor = max(1, min(abs(dx), abs(dy)))
                        if major / minor >= 1.25 || distance >= lockDistance {
                            settleToken = UUID()  // 取消进行中的回弹/飞出
                            longPressTask?.cancel()
                            drag.axis = abs(dx) > abs(dy) ? .horizontal : .vertical
                            drag.targets = [
                                .up: session.targetFor(.up),
                                .down: session.targetFor(.down),
                                .left: session.targetFor(.left),
                                .right: session.targetFor(.right),
                            ]
                        }
                    }
                }

                guard let axis = drag.axis else { return }
                let direction: FlipDirection = switch axis {
                case .horizontal: dx < 0 ? .left : .right
                case .vertical: dy < 0 ? .up : .down
                }
                let raw = axis == .horizontal ? dx : dy
                let target = drag.targets[direction] ?? nil
                if dragDirection != direction {
                    dragDirection = direction
                    previewId = target
                }
                offset = target != nil ? raw : raw * 0.18
                drag.dragging = true
            }
            .onEnded { value in
                longPressTask?.cancel()
                if drag.videoSpeedBoost { videoController.setSpeedBoosted(false) }
                let wasLongPress = drag.longPressHandled
                let gesture = drag
                drag = DragState()
                if session.roundComplete { return }

                let dx = value.translation.width
                let dy = value.translation.height

                // 轻点 / 双击
                guard gesture.dragging, let direction = dragDirection else {
                    if wasLongPress { return }
                    handleTap(at: value.startLocation)
                    return
                }

                let horizontal = direction.isHorizontal
                let delta = horizontal ? dx : dy
                let dimension = dimension(of: direction, in: stageSize)
                guard dimension > 0 else { return }
                let velocity = velocityAtEnd(samples: gesture.samples, end: (value.time, delta), horizontal: horizontal)
                let velocityMs = velocity / 1000  // pt/ms
                let projected = abs(delta + velocityMs * 150)
                let target = gesture.targets[direction] ?? nil
                let commit = target != nil &&
                    (abs(delta) >= dimension * 0.24 ||
                        (abs(delta) >= flingMin && abs(velocityMs) >= 0.65 && projected >= dimension * 0.24))

                let progress = min(1, abs(delta) / dimension)
                let remaining = commit ? 1 - progress : progress
                let duration = Double((150 + remaining * 70 - min(30, abs(velocityMs) * 24))
                    .clamped(to: 140...220)) / 1000

                let token = UUID()
                settleToken = token
                let exitOffset: CGFloat = if commit {
                    (direction == .up || direction == .left) ? -(dimension + 16) : dimension + 16
                } else {
                    0
                }
                if !commit, direction == .up, target == nil, session.roundFinished() {
                    session.completeRound()
                }
                withAnimation(flipTiming(duration)) {
                    offset = exitOffset
                }
                DispatchQueue.main.asyncAfter(deadline: .now() + duration) {
                    guard settleToken == token else { return }
                    if commit, let target {
                        session.commit(direction, targetId: target)
                    }
                    var transaction = Transaction()
                    transaction.disablesAnimations = true
                    withTransaction(transaction) {
                        offset = 0
                        dragDirection = nil
                        previewId = nil
                    }
                }
            }
    }

    private func startLongPressTimer() {
        longPressTask?.cancel()
        longPressTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 500_000_000)
            guard !Task.isCancelled, drag.axis == nil, !drag.longPressHandled else { return }
            guard let media = currentVideo else { return }
            drag.longPressHandled = true
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            if media.isLivePhoto {
                liveController.replay()
            } else {
                videoController.setSpeedBoosted(true)
                drag.videoSpeedBoost = true
            }
        }
    }

    private func velocityAtEnd(
        samples: [(time: Date, x: CGFloat, y: CGFloat)],
        end: (time: Date, delta: CGFloat),
        horizontal: Bool,
    ) -> CGFloat {
        // 取 100ms 前的采样估算末速度（pt/s）
        guard let reference = samples.last(where: { end.time.timeIntervalSince($0.time) >= 0.08 })
            ?? samples.first else { return 0 }
        let dt = end.time.timeIntervalSince(reference.time)
        guard dt > 0.01 else { return 0 }
        let from = horizontal ? reference.x : reference.y
        return (end.delta - from) / CGFloat(dt)
    }

    private func handleTap(at position: CGPoint) {
        guard let tapItem = session.current, !noteSheetOpen, !overlayTouch else { return }
        let now = Date()
        let isDouble = lastTap.map {
            $0.mediaId == tapItem.id &&
                now.timeIntervalSince($0.time) <= 0.28 &&
                hypot($0.position.x - position.x, $0.position.y - position.y) <= doubleTapSlop
        } ?? false
        if isDouble {
            tapTask?.cancel()
            lastTap = nil
            app.toggleFavorite(tapItem, forceLike: true)
            heartPulse = position
            pulseKey += 1
        } else {
            lastTap = LastTap(mediaId: tapItem.id, time: now, position: position)
            tapTask?.cancel()
            tapTask = Task { @MainActor in
                try? await Task.sleep(nanoseconds: 285_000_000)
                guard !Task.isCancelled else { return }
                if lastTap?.mediaId == tapItem.id, currentVideo?.isVideo == true {
                    videoController.togglePause(tapItem.id)
                }
                lastTap = nil
            }
        }
    }
}

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}

private extension Array where Element: Hashable {
    func uniqued() -> [Element] {
        var seen: Set<Element> = []
        return filter { seen.insert($0).inserted }
    }
}

/** 小点滑动条：点按直接跳到对应照片，按住拖动则逐张快速翻阅。 */
private struct DayPager: View {
    @ObservedObject var session: FlipSession
    let item: MediaItem
    let onTouchActiveChange: (Bool) -> Void

    @State private var stripWidth: CGFloat = 0
    @State private var scrubbing = false
    @State private var scrubIndex = 0
    @State private var lastScrubIndex = 0
    private let selectionHaptic = UISelectionFeedbackGenerator()

    private var items: [MediaItem] { session.sameDayItems(item) }
    private var currentIndex: Int {
        items.firstIndex { $0.id == item.id } ?? 0
    }

    var body: some View {
        let items = self.items
        if items.count >= 2 {
            let maxDots = 7
            let start = items.count > maxDots
                ? min(max(0, currentIndex - maxDots / 2), items.count - maxDots)
                : 0
            let visible = Array(items[start..<min(start + maxDots, items.count)])

            VStack(spacing: 10) {
                if scrubbing {
                    ScrubThumbnailStrip(items: items, index: scrubIndex)
                }
                HStack(spacing: 5) {
                    ForEach(Array(visible.enumerated()), id: \.element.id) { index, dayItem in
                        let absolute = start + index
                        let isActive = absolute == currentIndex
                        let isEdge = (index == 0 && start > 0) ||
                            (index == visible.count - 1 && start + visible.count < items.count)
                        RoundedRectangle(cornerRadius: 5)
                            .fill(isActive ? Color.white : Color.white.opacity(isEdge ? 0.25 : 0.4))
                            .frame(width: isActive ? 16 : (isEdge ? 3 : 5), height: 5)
                    }
                }
                // 小点本身只有 5pt 高，上下各扩 8pt 方便按住拖动
                .padding(.vertical, 8)
                .onSizeChange { stripWidth = $0.width }
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { value in
                            guard stripWidth > 0 else { return }
                            if !scrubbing {
                                onTouchActiveChange(true)
                                scrubbing = true
                                lastScrubIndex = items.firstIndex { $0.id == session.currentId } ?? 0
                                scrubIndex = lastScrubIndex
                            }
                            scrubTo(value.location.x, items: items)
                        }
                        .onEnded { _ in
                            scrubbing = false
                            // 主舞台的轻点判定在同一个抬起事件之后执行，延迟复位避免误触发暂停/点赞
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
                                onTouchActiveChange(false)
                            }
                        },
                )
            }
        }
    }

    private func scrubTo(_ x: CGFloat, items: [MediaItem]) {
        let targetIndex = min(max(0, Int(x / stripWidth * CGFloat(items.count))), items.count - 1)
        guard targetIndex != lastScrubIndex else { return }
        lastScrubIndex = targetIndex
        scrubIndex = targetIndex
        selectionHaptic.selectionChanged()
        commitTo(targetIndex, items: items)
    }

    /** 逐步提交到目标位置；方向与手势翻看一致：索引更大 = 屏幕右侧 = 时间更晚。 */
    private func commitTo(_ targetIndex: Int, items: [MediaItem]) {
        var guardCount = 0
        while guardCount < items.count {
            guardCount += 1
            guard let curId = session.currentId,
                  let curIdx = items.firstIndex(where: { $0.id == curId }),
                  curIdx != targetIndex else { return }
            let direction: FlipDirection = targetIndex > curIdx ? .left : .right
            guard let target = session.targetFor(direction) else { return }
            session.commit(direction, targetId: target)
        }
    }
}

/** 滑动小点时浮在小点上方的缩略图预览：目标照片居中放大，两侧为相邻照片。 */
private struct ScrubThumbnailStrip: View {
    let items: [MediaItem]
    let index: Int

    var body: some View {
        HStack(spacing: 8) {
            ForEach((index - 1)...(index + 1), id: \.self) { position in
                if items.indices.contains(position) {
                    let target = items[position]
                    let isCurrent = position == index
                    let size: CGFloat = isCurrent ? 88 : 60
                    MediaImageView(
                        item: target,
                        targetSize: CGSize(
                            width: size * ScreenMetrics.scale,
                            height: size * ScreenMetrics.scale,
                        ),
                        contentMode: .aspectFill,
                    )
                    .frame(width: size, height: size)
                    .clipShape(RoundedRectangle(cornerRadius: isCurrent ? 14 : 10))
                    .overlay(
                        RoundedRectangle(cornerRadius: isCurrent ? 14 : 10)
                            .strokeBorder(.white, lineWidth: isCurrent ? 2 : 0),
                    )
                }
            }
        }
    }
}

/** 本轮翻完后的全屏遮罩。 */
private struct RoundCompleteOverlay: View {
    @ObservedObject var session: FlipSession

    var body: some View {
        ZStack {
            Color.stage.ignoresSafeArea()
            VStack {
                Circle()
                    .fill(Color.spark)
                    .frame(width: 38, height: 38)
                Text("这一轮翻完了")
                    .font(.system(size: 24))
                    .foregroundStyle(Color(hex: 0xEEF3EC))
                    .padding(.top, 24)
                Text("\(session.media.count) 张照片，都重新见过了")
                    .font(.system(size: 13))
                    .foregroundStyle(Color(hex: 0x8FA598))
                    .padding(.top, 10)
                Button {
                    session.restartRound()
                } label: {
                    Text("再翻一遍")
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(Color(hex: 0x3D2C0C))
                        .padding(.horizontal, 24)
                        .padding(.vertical, 12)
                        .background(Color.spark)
                        .clipShape(RoundedRectangle(cornerRadius: 24))
                }
                .padding(.top, 30)
            }
        }
        // 拦截触摸，防止穿透到下面的操作栏/分页点
        .contentShape(Rectangle())
        .onTapGesture {}
    }
}
