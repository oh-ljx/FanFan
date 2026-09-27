import Photos

/** 系统相册里的一条媒体（照片或视频）。id 为 PHAsset.localIdentifier。 */
struct MediaItem: Equatable, Identifiable {
    let id: String
    let asset: PHAsset
    /** 拍摄时间，毫秒时间戳。 */
    let dateTaken: Int64
    let isVideo: Bool
    /** 视频时长（毫秒），照片为 nil。 */
    let durationMs: Int64?
    /** 实况照片（Live Photo）。 */
    let isLivePhoto: Bool
    /** 所属相簿（用户相簿 localIdentifier；不在任何相簿时归入虚拟相机胶卷）。 */
    let albumId: String

    static func == (lhs: MediaItem, rhs: MediaItem) -> Bool {
        lhs.id == rhs.id &&
            lhs.dateTaken == rhs.dateTaken &&
            lhs.isVideo == rhs.isVideo &&
            lhs.durationMs == rhs.durationMs &&
            lhs.isLivePhoto == rhs.isLivePhoto &&
            lhs.albumId == rhs.albumId
    }
}

enum FlipDirection: CaseIterable {
    case up, down, left, right

    var isHorizontal: Bool { self == .left || self == .right }
}

enum CollectionSource: String {
    case all, favorites, album
}
