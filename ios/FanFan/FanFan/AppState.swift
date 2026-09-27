import Photos
import Combine
import Foundation
import SwiftUI

/** 应用级状态：相册快照、喜欢/评论/隐藏相簿、翻翻会话。 */
@MainActor
final class AppState: ObservableObject {
    static let navHeight: CGFloat = 48

    let library = LibraryRepository.shared
    let photoRepo = PhotoRepository()

    @Published var permissionGranted = false
    @Published private(set) var loaded = false
    @Published private(set) var allMedia: [MediaItem] = []
    @Published private(set) var albumNames: [String: String] = [:]
    @Published private(set) var favorites: Set<String> = []
    @Published var noteCounts: [String: Int] = [:]
    @Published private(set) var hiddenAlbums: Set<String> = []
    @Published var loadingError: String?
    @Published var appVisible = true

    private(set) var session: FlipSession?

    /** 翻翻/全部/相簿统计的口径：排除隐藏相簿。 */
    var activeMedia: [MediaItem] {
        allMedia.filter { !hiddenAlbums.contains($0.albumId) }
    }

    // MARK: - 权限与启动

    func checkPermissionOnLaunch() {
        permissionGranted = photoRepo.hasAccess()
        if permissionGranted {
            startObservingLibrary()
            Task { await boot() }
        }
    }

    func requestPermission() async {
        _ = await photoRepo.requestAccess()
        permissionGranted = photoRepo.hasAccess()
        if permissionGranted {
            startObservingLibrary()
            await boot()
        }
    }

    /** 回到前台：重新检查权限并轻量刷新媒体快照。 */
    func handleResume() {
        appVisible = true
        permissionGranted = photoRepo.hasAccess()
        guard permissionGranted else { return }
        startObservingLibrary()
        if loaded {
            Task { await refresh() }
        } else {
            Task { await boot() }
        }
    }

    // MARK: - 加载

    func retryBoot() {
        loaded = false
        booting = false
        loadingError = nil
        Task { await boot() }
    }

    private var booting = false

    private func boot() async {
        guard !loaded, !booting else { return }
        booting = true
        defer { booting = false }
        loadingError = nil
        do {
            async let mediaTask = Task.detached { [photoRepo] in photoRepo.loadMedia() }.value
            async let favoritesTask = library.favoriteIds()
            async let notesTask = library.noteCounts()
            async let hiddenTask = library.hiddenAlbumIds()
            async let sessionTask = library.flipSessionBootState()

            let content = await mediaTask
            allMedia = content.media
            albumNames = content.albumNames
            favorites = try await favoritesTask
            noteCounts = try await notesTask
            hiddenAlbums = try await hiddenTask

            let sessionBoot = try await sessionTask
            session = FlipSession(
                media: activeMedia,
                initialSeen: sessionBoot.seenIds,
                initialSnapshot: sessionBoot.snapshot,
            ) { [library] update in
                library.enqueueFlipSessionUpdate(update)
            }
            loaded = true
        } catch {
            loadingError = error.localizedDescription
        }
    }

    /** 系统相册变化后重建快照；隐藏相簿/会话做对账，不打断当前轮。 */
    private func refresh() async {
        guard loaded else { return }
        let content = await Task.detached { [photoRepo] in photoRepo.loadMedia() }.value
        allMedia = content.media
        albumNames = content.albumNames
        session?.reconcileMedia(activeMedia)
    }

    private let observer = PhotoLibraryObserver()
    private var observingStarted = false
    private var refreshTask: Task<Void, Never>?

    private func startObservingLibrary() {
        guard !observingStarted else { return }
        observingStarted = true
        observer.onChange = { [weak self] in
            guard let self else { return }
            // 合并连续通知再做一次轻量快照刷新
            refreshTask?.cancel()
            refreshTask = Task { @MainActor in
                try? await Task.sleep(nanoseconds: 350_000_000)
                guard !Task.isCancelled else { return }
                await self.refresh()
            }
        }
        observer.start()
    }

    // MARK: - 喜欢

    func toggleFavorite(_ item: MediaItem, forceLike: Bool) {
        if favorites.contains(item.id) {
            if !forceLike {
                favorites.remove(item.id)
                library.removeFavorite(item.id)
            }
        } else {
            favorites.insert(item.id)
            library.addFavorite(item.id)
        }
    }

    /** 系统确认后从相册删除（进入系统“最近删除”），并清掉本地相关记录。 */
    func delete(_ targets: [MediaItem]) async {
        guard !targets.isEmpty else { return }
        do {
            let deleted = try await photoRepo.delete(targets)
            guard !deleted.isEmpty else { return }
            try await library.deleteMediaRecords(deleted)
            let deletedSet = Set(deleted)
            allMedia.removeAll { deletedSet.contains($0.id) }
            favorites.subtract(deletedSet)
            noteCounts = noteCounts.filter { !deletedSet.contains($0.key) }
            session?.reconcileMedia(activeMedia)
        } catch {
            NSLog("删除失败: \(error.localizedDescription)")
        }
    }

    // MARK: - 隐藏相簿

    func hideAlbum(_ albumId: String) {
        hiddenAlbums.insert(albumId)
        library.hideAlbum(albumId)
        session?.reconcileMedia(activeMedia)
    }

    func unhideAlbum(_ albumId: String) {
        hiddenAlbums.remove(albumId)
        library.unhideAlbum(albumId)
        session?.reconcileMedia(activeMedia)
    }
}
