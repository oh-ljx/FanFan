import SwiftUI
import UIKit

private struct ViewerTap {
    let mediaId: String
    let time: Date
    let position: CGPoint
}

/** 查看页拖动手势的私有状态。 */
private struct ViewerDrag {
    var started = false
    var dragging = false
    var direction: Int?  // -1 下一张(上滑) / +1 上一张(下滑)
    var longPressHandled = false
    var videoSpeedBoost = false
    var samples: [(time: Date, y: CGFloat)] = []
}

/** 全部 / 喜欢 / 相簿共用的集合查看页：上下滑动按列表顺序切换。 */
struct CollectionViewer: View {
    @ObservedObject var app: AppState
    @ObservedObject var session: FlipSession
    let source: CollectionSource
    let startId: String
    let albumId: String?
    let safeAreaInsets: EdgeInsets
    let onClose: () -> Void

    @StateObject private var videoController = VideoPlayerController()
    @StateObject private var liveController = LivePhotoController()

    @State private var items: [MediaItem]?
    @State private var index = 0

    @State private var offset: CGFloat = 0
    @State private var drag = ViewerDrag()
    @State private var settleToken = UUID()

    @State private var notes: [Note] = []
    @State private var noteSheetOpen = false
    @State private var noteProgress: CGFloat = 0

    @State private var heartPulse: CGPoint?
    @State private var pulseKey = 0
    @State private var lastTap: ViewerTap?
    @State private var tapTask: Task<Void, Never>?
    @State private var longPressTask: Task<Void, Never>?

    // 视频控制条被触摸期间，舞台不响应拖动/轻点
    @State private var overlayTouch = false
    @State private var stageHeight: CGFloat = 0

    private let lockThreshold: CGFloat = 10
    private let flingMin: CGFloat = 38
    private let doubleTapSlop: CGFloat = 24

    private var current: MediaItem? {
        guard let items, !items.isEmpty else { return nil }
        return items[min(max(0, index), items.count - 1)]
    }

    private var currentVideo: MediaItem? {
        current.flatMap { $0.isVideo || $0.isLivePhoto ? $0 : nil }
    }

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .top) {
                stage(in: geo.size, safeAreaTop: safeAreaInsets.top, safeAreaBottom: safeAreaInsets.bottom)
                    .frame(width: geo.size.width, height: geo.size.height * (1 - 0.44 * noteProgress))
                    .clipped()


                if noteSheetOpen || noteProgress > 0.001 {
                    NoteSheet(
                        notes: notes,
                        progress: noteProgress,
                        screenSize: geo.size,
                        onDismiss: { noteSheetOpen = false },
                        onSend: { text, parentId in
                            guard let item = current else { return }
                            Task {
                                await app.library.addNote(item.id, text: text, parentId: parentId)
                                let updated = (try? await app.library.notesFor(item.id)) ?? []
                                notes = updated
                                app.noteCounts[item.id] = updated.count
                            }
                        },
                        onDelete: { note in
                            guard let item = current else { return }
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
        .background(Color.stage.ignoresSafeArea())
        .onChange(of: noteSheetOpen) { open in
            withAnimation(flipTiming(0.3)) { noteProgress = open ? 1 : 0 }
        }
        .onAppear { refreshList() }
        .onChange(of: app.favorites) { _ in refreshList() }
        .onChange(of: app.allMedia) { _ in refreshList() }
        .onDisappear { videoController.shutdown() }
    }

    private func computeList() -> [MediaItem] {
        let favoriteIds = app.favorites
        let activeIds = Set(session.media.map(\.id))
        switch source {
        case .favorites:
            return app.allMedia.filter { favoriteIds.contains($0.id) && activeIds.contains($0.id) }
        case .all:
            return app.allMedia.filter { activeIds.contains($0.id) }
        case .album:
            return app.allMedia.filter { $0.albumId == albumId && activeIds.contains($0.id) }
        }
    }

    private func refreshList() {
        let previousId = items.flatMap { $0.indices.contains(index) ? $0[index].id : nil }
        let list = computeList()
        items = list
        if list.isEmpty {
            index = 0
        } else {
            let preferredId = previousId.flatMap { id in list.contains { $0.id == id } ? id : nil } ?? startId
            index = list.firstIndex { $0.id == preferredId } ?? min(index, list.count - 1)
        }
    }

    private func neighbor(_ dir: Int) -> Int? {
        guard let items else { return nil }
        // dir=-1（上滑）→ index+1
        let next = index - dir
        return items.indices.contains(next) ? next : nil
    }

    // MARK: - 舞台

    @ViewBuilder
    private func stage(in size: CGSize, safeAreaTop: CGFloat, safeAreaBottom: CGFloat) -> some View {
        ZStack {
            let ambientProgress: CGFloat = {
                guard drag.direction != nil, size.height > 0 else { return 0 }
                return min(1, abs(offset) / size.height)
            }()
            if let item = current {
                AmbientBackground(item: item, alpha: 1)
            }
            if let dir = drag.direction, let targetIndex = neighbor(dir), let items {
                AmbientBackground(item: items[targetIndex], alpha: ambientProgress)
            }

            let gap: CGFloat = 16
            if let dir = drag.direction, let targetIndex = neighbor(dir), let items {
                Slide(
                    item: items[targetIndex],
                    offset: offset - CGFloat(dir) * (size.height + gap),
                    horizontal: false,
                )
            }

            if let item = current {
                Slide(
                    item: item,
                    offset: offset,
                    horizontal: false,
                    videoPlayer: videoController.player,
                    showVideo: item.isVideo,
                )
                if item.isLivePhoto, liveController.playing, let livePhoto = liveController.livePhoto {
                    LivePhotoPlayerView(livePhoto: livePhoto) {
                        liveController.didEndPlayback()
                    }
                    .offset(y: offset)
                }
            }

            if let position = heartPulse {
                HeartPulse(position: position, pulseKey: pulseKey, onDone: { heartPulse = nil })
                    .id(pulseKey)
            }

            // 所有悬浮控件：评论面板打开时整体淡出，缩小的舞台上只留照片
            if noteProgress < 0.999 {
                overlays(safeAreaTop: safeAreaTop, safeAreaBottom: safeAreaBottom)
                    .opacity(1 - noteProgress)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .contentShape(Rectangle())
        .onSizeChange { stageHeight = $0.height }
        .simultaneousGesture(viewerGesture, including: overlayTouch ? .subviews : .all)
        .onAppear {
            videoController.bind(item: currentVideo?.isVideo == true ? currentVideo : nil)
            updateCanPlay()
        }
        .onChange(of: current?.id) { _ in
            videoController.bind(item: currentVideo?.isVideo == true ? currentVideo : nil)
        }
        .task(id: current?.id) {
            await liveController.load(for: current)
        }
        .onChange(of: app.appVisible) { _ in updateCanPlay() }
    }

    private func updateCanPlay() {
        videoController.setCanPlay(app.appVisible)
    }

    // MARK: - 悬浮控件

    @ViewBuilder
    private func overlays(safeAreaTop: CGFloat, safeAreaBottom: CGFloat) -> some View {
        ZStack {
            if let video = currentVideo, video.isVideo,
               videoController.isPaused(video.id) || videoController.feedbackFlash {
                VideoCenterIndicator(
                    paused: videoController.isPaused(video.id),
                    flash: videoController.feedbackFlash,
                )
            }

            // 顶部：返回 + 日期
            if let item = current {
                HStack {
                    Button(action: onClose) {
                        Image(systemName: "arrow.left")
                            .font(.system(size: 20, weight: .medium))
                            .foregroundStyle(.white)
                            .frame(width: 34, height: 34)
                            .contentShape(Circle())
                    }
                    .accessibilityLabel("返回列表")
                    Text(formatCaptureDate(item.dateTaken))
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(.white)
                        .padding(.leading, 10)
                    Spacer()
                    if item.isLivePhoto, liveController.livePhoto != nil {
                        LivePhotoBadge {
                            liveController.replay()
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 12 + safeAreaTop)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            }

            // 操作栏
            if let item = current {
                ActionRail(
                    liked: app.favorites.contains(item.id),
                    noteCount: app.noteCounts[item.id] ?? 0,
                    thirdIcon: .deleteOutline,
                    thirdLabel: "删除",
                    thirdDescription: "删除照片",
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
                .padding(.bottom, 104 + safeAreaBottom)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
            }

            // 位置指示
            if let items {
                Text("\(min(index + 1, items.count)) / \(items.count)")
                    .font(.system(size: 13))
                    .tracking(1)
                    .foregroundStyle(.white.opacity(0.88))
                    .padding(.bottom, 16 + safeAreaBottom)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
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
                .padding(.bottom, 34 + safeAreaBottom)
                .padding(.horizontal, 12)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
                .simultaneousGesture(touchTracker)
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

    private var viewerGesture: some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                if !drag.started {
                    drag = ViewerDrag(started: true)
                    startLongPressTimer()
                }
                let dy = value.translation.height
                drag.samples.append((value.time, dy))
                if drag.samples.count > 12 { drag.samples.removeFirst() }

                if drag.longPressHandled { return }

                if !drag.dragging, abs(dy) >= lockThreshold {
                    settleToken = UUID()
                    longPressTask?.cancel()
                    drag.dragging = true
                }
                guard drag.dragging else { return }
                let dir = dy < 0 ? -1 : 1
                if drag.direction != dir { drag.direction = dir }
                offset = neighbor(dir) != nil ? dy : dy * 0.18
            }
            .onEnded { value in
                longPressTask?.cancel()
                if drag.videoSpeedBoost { videoController.setSpeedBoosted(false) }
                let wasLongPress = drag.longPressHandled
                let gesture = drag
                drag = ViewerDrag()

                guard gesture.dragging, let dir = gesture.direction else {
                    if !wasLongPress {
                        handleTap(at: value.startLocation)
                    }
                    return
                }

                let dimension = stageHeight
                guard dimension > 0 else { return }
                let dy = value.translation.height
                let velocityMs = velocityAtEnd(samples: gesture.samples, end: (value.time, dy)) / 1000
                let projected = abs(dy + velocityMs * 150)
                let target = neighbor(dir)
                let commit = target != nil &&
                    (abs(dy) >= dimension * 0.24 ||
                        (abs(dy) >= flingMin && abs(velocityMs) >= 0.65 && projected >= dimension * 0.24))

                let progress = min(1, abs(dy) / dimension)
                let remaining = commit ? 1 - progress : progress
                let duration = Double((150 + remaining * 70 - min(30, abs(velocityMs) * 24))
                    .clamped(to: 140...220)) / 1000

                let token = UUID()
                settleToken = token
                withAnimation(flipTiming(duration)) {
                    offset = commit ? (dir < 0 ? -(dimension + 16) : dimension + 16) : 0
                }
                DispatchQueue.main.asyncAfter(deadline: .now() + duration) {
                    guard settleToken == token else { return }
                    if let target {
                        index = target
                    }
                    var transaction = Transaction()
                    transaction.disablesAnimations = true
                    withTransaction(transaction) {
                        offset = 0
                    }
                }
            }
    }


    private func startLongPressTimer() {
        longPressTask?.cancel()
        longPressTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 500_000_000)
            guard !Task.isCancelled, !drag.dragging, !drag.longPressHandled else { return }
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
        samples: [(time: Date, y: CGFloat)],
        end: (time: Date, delta: CGFloat),
    ) -> CGFloat {
        guard let reference = samples.last(where: { end.time.timeIntervalSince($0.time) >= 0.08 })
            ?? samples.first else { return 0 }
        let dt = end.time.timeIntervalSince(reference.time)
        guard dt > 0.01 else { return 0 }
        return (end.delta - reference.y) / CGFloat(dt)
    }

    private func handleTap(at position: CGPoint) {
        guard let tapItem = current, !noteSheetOpen, !overlayTouch else { return }
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
            lastTap = ViewerTap(mediaId: tapItem.id, time: now, position: position)
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
