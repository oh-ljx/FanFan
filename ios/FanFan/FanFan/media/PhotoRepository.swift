import Photos

struct PhotoLibraryContent {
    /** 按拍摄时间倒序、id 倒序作为稳定次序。 */
    let media: [MediaItem]
    /** albumId → 展示名（含虚拟相机胶卷）。 */
    let albumNames: [String: String]
}

/**
 * PhotoKit 的轻量访问层。
 *
 * 启动查询只读取系统已经索引好的元数据；相簿归属通过遍历用户相簿一次性建立。
 */
struct PhotoRepository {
    /** 不在任何用户相簿里的媒体归入这个虚拟相簿。 */
    static let cameraRollAlbumId = "__camera_roll__"

    func authorizationStatus() -> PHAuthorizationStatus {
        PHPhotoLibrary.authorizationStatus(for: .readWrite)
    }

    func hasAccess() -> Bool {
        let status = authorizationStatus()
        return status == .authorized || status == .limited
    }

    func requestAccess() async -> PHAuthorizationStatus {
        await PHPhotoLibrary.requestAuthorization(for: .readWrite)
    }

    func loadMedia() -> PhotoLibraryContent {
        // 相簿归属：每个媒体只归一个相簿（按枚举顺序先到先得），与安卓 bucket 的单归属对齐。
        var albumNames: [String: String] = [Self.cameraRollAlbumId: "相机胶卷"]
        var albumOfAsset: [String: String] = [:]
        let collections = PHAssetCollection.fetchAssetCollections(with: .album, subtype: .any, options: nil)
        collections.enumerateObjects { collection, _, _ in
            albumNames[collection.localIdentifier] = collection.localizedTitle ?? "未命名相簿"
            let assets = PHAsset.fetchAssets(in: collection, options: nil)
            assets.enumerateObjects { asset, _, _ in
                if albumOfAsset[asset.localIdentifier] == nil {
                    albumOfAsset[asset.localIdentifier] = collection.localIdentifier
                }
            }
        }

        let options = PHFetchOptions()
        options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        options.includeAssetSourceTypes = [.typeUserLibrary, .typeCloudShared, .typeiTunesSynced]
        let result = PHAsset.fetchAssets(with: options)

        var media: [MediaItem] = []
        media.reserveCapacity(result.count)
        result.enumerateObjects { asset, _, _ in
            let isVideo = asset.mediaType == .video
            let taken = asset.creationDate ?? asset.modificationDate ?? Date.distantPast
            media.append(MediaItem(
                id: asset.localIdentifier,
                asset: asset,
                dateTaken: Int64(taken.timeIntervalSince1970 * 1000),
                isVideo: isVideo,
                durationMs: isVideo ? Int64(asset.duration * 1000) : nil,
                isLivePhoto: asset.mediaSubtypes.contains(.photoLive),
                albumId: albumOfAsset[asset.localIdentifier] ?? Self.cameraRollAlbumId,
            ))
        }
        // 与安卓一致：dateTaken 倒序，id 倒序兜底（fetch 顺序已按创建时间，补一个稳定次序）。
        media.sort { lhs, rhs in
            lhs.dateTaken != rhs.dateTaken ? lhs.dateTaken > rhs.dateTaken : lhs.id > rhs.id
        }
        return PhotoLibraryContent(media: media, albumNames: albumNames)
    }

    /**
     * 删除：系统弹出确认框（可能取消），删除后进入系统的“最近删除”。
     * 返回实际被删除的 id（通过重新查询确认，用户取消时不会被误清本地数据）。
     */
    func delete(_ items: [MediaItem]) async throws -> [String] {
        guard !items.isEmpty else { return [] }
        try await PHPhotoLibrary.shared().performChanges {
            PHAssetChangeRequest.deleteAssets(items.map(\.asset) as NSArray)
        }
        let remaining = PHAsset.fetchAssets(withLocalIdentifiers: items.map(\.id), options: nil)
        var remainingIds: Set<String> = []
        remaining.enumerateObjects { asset, _, _ in remainingIds.insert(asset.localIdentifier) }
        return items.map(\.id).filter { !remainingIds.contains($0) }
    }
}

/** 系统相册变更监听：删除、恢复或新增媒体后触发一次轻量快照刷新。 */
final class PhotoLibraryObserver: NSObject, PHPhotoLibraryChangeObserver {
    var onChange: () -> Void = {}

    func start() {
        PHPhotoLibrary.shared().register(self)
    }

    func stop() {
        PHPhotoLibrary.shared().unregisterChangeObserver(self)
    }

    func photoLibraryDidChange(_ changeInstance: PHChange) {
        DispatchQueue.main.async { [onChange] in onChange() }
    }
}
