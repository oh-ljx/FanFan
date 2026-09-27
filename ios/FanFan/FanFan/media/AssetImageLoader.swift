import Photos
import SwiftUI
import UIKit

/**
 * 照片缩略图/封面加载。基于 PHCachingImageManager：
 * opportunistic 模式下先回调低清图再回调高清图，流式交给 SwiftUI 视图。
 */
final class AssetImageLoader {
    static let shared = AssetImageLoader()

    let cachingManager = PHCachingImageManager()

    private init() {
        cachingManager.allowsCachingHighQualityImages = true
    }

    /** 每个元素是一次回调的图片；高清图（或失败）到达后流结束。 */
    func images(
        for asset: PHAsset,
        targetSize: CGSize,
        contentMode: PHImageContentMode,
    ) -> AsyncStream<UIImage?> {
        AsyncStream { continuation in
            let options = PHImageRequestOptions()
            options.isNetworkAccessAllowed = true
            options.deliveryMode = .opportunistic
            options.resizeMode = .fast
            let requestId = cachingManager.requestImage(
                for: asset,
                targetSize: targetSize,
                contentMode: contentMode,
                options: options,
            ) { image, info in
                let cancelled = (info?[PHImageCancelledKey] as? Bool) ?? false
                if cancelled { return }
                let degraded = (info?[PHImageResultIsDegradedKey] as? Bool) ?? false
                let failed = info?[PHImageErrorKey] != nil || image == nil
                continuation.yield(image)
                if !degraded || failed {
                    continuation.finish()
                }
            }
            continuation.onTermination = { _ in
                self.cachingManager.cancelImageRequest(requestId)
            }
        }
    }

    /** 预取最可能访问的少量邻居，切换时直接命中缓存。 */
    func prefetch(_ assets: [PHAsset], stageSizePx: CGSize) {
        guard !assets.isEmpty else { return }
        let ambient = CGSize(
            width: max(240, stageSizePx.width * 0.55),
            height: max(320, stageSizePx.height * 0.55),
        )
        let options = PHImageRequestOptions()
        options.isNetworkAccessAllowed = true
        options.deliveryMode = .opportunistic
        options.resizeMode = .fast
        cachingManager.startCachingImages(
            for: assets,
            targetSize: stageSizePx,
            contentMode: .aspectFit,
            options: options,
        )
        cachingManager.startCachingImages(
            for: assets,
            targetSize: ambient,
            contentMode: .aspectFill,
            options: options,
        )
    }
}

/** 当前屏幕的像素尺寸；封面请求显式使用它，保证预取与展示命中同一份缓存。 */
enum ScreenMetrics {
    static var sizePx: CGSize {
        let bounds = UIScreen.main.bounds
        let scale = UIScreen.main.scale
        return CGSize(width: bounds.width * scale, height: bounds.height * scale)
    }

    static var scale: CGFloat { UIScreen.main.scale }
}

/** 按 item 加载并展示一张照片/视频封面；item 变化时重新请求。 */
struct MediaImageView: View {
    let item: MediaItem
    /** 目标尺寸（像素）。 */
    let targetSize: CGSize
    let contentMode: PHImageContentMode

    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: contentMode == .aspectFill ? .fill : .fit)
            } else {
                Color.clear
            }
        }
        .task(id: item.id) {
            image = nil
            for await result in AssetImageLoader.shared.images(
                for: item.asset,
                targetSize: targetSize,
                contentMode: contentMode,
            ) {
                guard !Task.isCancelled else { return }
                image = result
            }
        }
    }
}
