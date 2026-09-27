import Photos
import SwiftUI
import UIKit

private let albumPlaceholderColor = Color(hex: 0xE8EDE8)

/** 理理页的相簿条目：按相簿聚合出的展示模型。 */
private struct AlbumInfo {
    let albumId: String
    let name: String
    let count: Int
    /** 拼贴封面（最多 4 张）。 */
    let covers: [MediaItem]
}

private enum SubPage: String {
    case all, favorites, album
}

/** 理理：本轮进度 + 全部/喜欢 + 相簿管理 + 各子页。 */
struct WorkbenchScreen: View {
    @ObservedObject var app: AppState
    @ObservedObject var session: FlipSession
    let bottomInset: CGFloat
    let onRestartRound: () -> Void
    let onOpenCollection: (CollectionSource, String, String?) -> Void

    @State private var subPage: SubPage?
    @State private var subPageAlbumId: String?
    /** 管理模式：相簿卡片显示隐藏/加回角标；离开理理页时自动退出 */
    @State private var managing = false

    private var activeIds: Set<String> { Set(session.media.map(\.id)) }
    private var activeMedia: [MediaItem] { app.allMedia.filter { activeIds.contains($0.id) } }
    private var favoriteItems: [MediaItem] { activeMedia.filter { app.favorites.contains($0.id) } }

    // 相簿按归属聚合；被隐藏的相簿仍列出（可加回），但不参与翻翻/全部/顶部计数。
    private var albumInfos: [AlbumInfo] {
        let grouped = Dictionary(grouping: app.allMedia, by: \.albumId)
        return grouped.map { albumId, items in
            AlbumInfo(
                albumId: albumId,
                name: app.albumNames[albumId] ?? "未命名相簿",
                count: items.count,
                // allMedia 按拍摄时间倒序，前 4 张即拼贴封面
                covers: Array(items.prefix(4)),
            )
        }
        .sorted { lhs, rhs in
            lhs.count != rhs.count ? lhs.count > rhs.count : lhs.name < rhs.name
        }
    }

    var body: some View {
        ZStack {
            Color.paper.ignoresSafeArea()

            // —— 主页 ——
            ScrollView {
                LazyVStack(spacing: 20) {
                    ContactSheetHero(
                        seen: session.seenCount,
                        total: session.media.count,
                        onRestart: onRestartRound,
                    )

                    VStack(spacing: 12) {
                        HStack {
                            Text("照片")
                                .font(.system(size: 18, weight: .bold))
                                .foregroundStyle(Color.ink)
                            Spacer()
                            Text(managing ? "完成" : "管理")
                                .font(.system(size: 14, weight: .semibold))
                                .tracking(0.5)
                                .foregroundStyle(Color.pine)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 4)
                                .contentShape(RoundedRectangle(cornerRadius: 6))
                                .onTapGesture { managing.toggle() }
                        }

                        HStack(spacing: 12) {
                            AlbumTile(
                                title: "全部",
                                count: activeMedia.count,
                                items: activeMedia,
                                emptyIcon: "photo.on.rectangle.angled",
                                emptyTint: .pine,
                                onClick: { openSubPage(.all) },
                            )
                            AlbumTile(
                                title: "喜欢",
                                count: favoriteItems.count,
                                items: favoriteItems,
                                emptyIcon: "heart.fill",
                                emptyTint: .likeRed,
                                onClick: { openSubPage(.favorites) },
                            )
                        }

                        let gridAlbums = managing
                            ? albumInfos
                            : albumInfos.filter { !app.hiddenAlbums.contains($0.albumId) }
                        ForEach(gridAlbums.chunked(into: 2), id: \.first?.albumId) { row in
                            HStack(spacing: 12) {
                                ForEach(row, id: \.albumId) { album in
                                    let hidden = app.hiddenAlbums.contains(album.albumId)
                                    AlbumTile(
                                        title: album.name,
                                        count: album.count,
                                        items: album.covers,
                                        emptyIcon: "photo.on.rectangle.angled",
                                        emptyTint: .pine,
                                        onClick: {
                                            subPageAlbumId = album.albumId
                                            openSubPage(.album)
                                        },
                                        managing: managing,
                                        hidden: hidden,
                                        onToggleHidden: {
                                            if hidden {
                                                app.unhideAlbum(album.albumId)
                                            } else {
                                                app.hideAlbum(album.albumId)
                                            }
                                        },
                                    )
                                }
                                if row.count == 1 {
                                    Spacer()
                                        .frame(maxWidth: .infinity)
                                }
                            }
                        }
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 20)
                .padding(.bottom, bottomInset + 28)
            }

            // —— 全部子页 ——
            if subPage == .all {
                MediaGridPage(
                    title: "全部",
                    items: activeMedia,
                    emptyText: "还没有照片",
                    bottomInset: bottomInset,
                    onOpen: { onOpenCollection(.all, $0, nil) },
                    onBack: closeSubPage,
                )
                .transition(.move(edge: .trailing))
            }

            // —— 喜欢子页 ——
            if subPage == .favorites {
                MediaGridPage(
                    title: "喜欢",
                    items: favoriteItems,
                    emptyText: "还没有喜欢的照片",
                    bottomInset: bottomInset,
                    onOpen: { onOpenCollection(.favorites, $0, nil) },
                    onBack: closeSubPage,
                )
                .transition(.move(edge: .trailing))
            }

            // —— 相簿子页 ——
            if subPage == .album {
                if let album = albumInfos.first(where: { $0.albumId == subPageAlbumId }) {
                    MediaGridPage(
                        title: album.name,
                        items: activeMedia.filter { $0.albumId == album.albumId },
                        emptyText: "这个相簿是空的",
                        bottomInset: bottomInset,
                        onOpen: { onOpenCollection(.album, $0, album.albumId) },
                        onBack: closeSubPage,
                    )
                    .transition(.move(edge: .trailing))
                } else {
                    // 相簿里的照片可能刚被清空，子页失去对象直接退回主页
                    Color.clear.onAppear { closeSubPage() }
                }
            }
        }
    }

    private func openSubPage(_ page: SubPage) {
        withAnimation(flipTiming(0.3)) { subPage = page }
    }

    private func closeSubPage() {
        withAnimation(flipTiming(0.3)) {
            subPage = nil
            subPageAlbumId = nil
        }
    }
}

private extension Array {
    func chunked(into size: Int) -> [[Element]] {
        stride(from: 0, to: count, by: size).map { Array(self[$0..<Swift.min($0 + size, count)]) }
    }
}

/** 顶部进度卡：本轮翻看的完成度。 */
private struct ContactSheetHero: View {
    let seen: Int
    let total: Int
    let onRestart: () -> Void

    var body: some View {
        let progress = total > 0 ? min(1, max(0, Double(seen) / Double(total))) : 0
        let percent = Int((progress * 100).rounded())
        let litSegments = seen > 0 && total > 0 ? min(12, max(1, Int((progress * 12).rounded()))) : 0
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("本轮翻看")
                    .font(.system(size: 13, weight: .bold))
                    .tracking(1)
                    .foregroundStyle(Color.paper.opacity(0.78))
                Spacer()
                Text("\(percent)%")
                    .font(.system(size: 13, weight: .bold, design: .monospaced))
                    .foregroundStyle(Color.mint)
            }
            Text("\(seen) / \(total)")
                .font(.system(size: 34, weight: .bold, design: .monospaced))
                .foregroundStyle(Color.paper)
                .padding(.top, 15)
            HStack(spacing: 5) {
                ForEach(0..<12, id: \.self) { index in
                    RoundedRectangle(cornerRadius: 2)
                        .fill(index < litSegments ? Color.mint : Color.paper.opacity(0.15))
                        .frame(height: 8)
                }
            }
            .padding(.top, 15)
            HStack {
                Text(
                    total == 0
                        ? "还没有照片可以整理"
                        : seen >= total ? "这一轮已经全部翻完" : "还有 \(total - seen) 张没翻到过",
                )
                .font(.system(size: 14))
                .foregroundStyle(Color.paper.opacity(0.72))
                Spacer()
                Text("重新开始")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(Color.pine)
                    .padding(.horizontal, 15)
                    .frame(minHeight: 48)
                    .background(Color.mint)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                    .contentShape(RoundedRectangle(cornerRadius: 14))
                    .onTapGesture(perform: onRestart)
                    .accessibilityLabel("重新开始本轮翻看")
            }
            .padding(.top, 17)
        }
        .padding(20)
        .background(Color.pine)
        .clipShape(RoundedRectangle(cornerRadius: 24))
    }
}

/** 全部/喜欢/相簿共用的卡片：拼贴封面 + 名称 + 数量。 */
private struct AlbumTile: View {
    let title: String
    let count: Int
    let items: [MediaItem]
    let emptyIcon: String
    let emptyTint: Color
    let onClick: () -> Void
    var managing = false
    var hidden = false
    var onToggleHidden: () -> Void = {}

    var body: some View {
        let hasPreview = !items.isEmpty
        ZStack {
            ZStack(alignment: .bottomLeading) {
                AlbumMosaic(items: Array(items.prefix(4)), emptyIcon: emptyIcon, emptyTint: emptyTint)
                if hasPreview {
                    LinearGradient(
                        colors: [.clear, Color(hex: 0x07100C).opacity(0.88)],
                        startPoint: .top,
                        endPoint: .bottom,
                    )
                    .frame(height: 104)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
                }
                VStack(alignment: .leading, spacing: 3) {
                    HStack {
                        Text(title)
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(hasPreview ? .white : Color.ink)
                            .lineLimit(1)
                        Text("›")
                            .font(.system(size: 19))
                            .foregroundStyle(
                                hasPreview ? .white.opacity(0.7) : Color.pine.opacity(0.55),
                            )
                    }
                    Text("\(count) 张")
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundStyle(
                            hasPreview ? .white.opacity(0.72) : Color.pine.opacity(0.72),
                        )
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 13)
            }
            .background(albumPlaceholderColor)
            .clipShape(RoundedRectangle(cornerRadius: 20))
            .opacity(hidden ? 0.45 : 1)
            .contentShape(RoundedRectangle(cornerRadius: 20))
            .onTapGesture {
                if !managing { onClick() }
            }
            .accessibilityLabel("打开\(title)")

            if managing {
                // 管理角标：红色横杠 = 不显示这个相簿，绿色加号 = 加回来
                Button(action: onToggleHidden) {
                    Image(systemName: hidden ? "plus" : "minus")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: 22, height: 22)
                        .background(hidden ? Color.pine : Color.danger)
                        .clipShape(Circle())
                }
                .accessibilityLabel(hidden ? "加回相簿\(title)" : "不显示相簿\(title)")
                .padding(9)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
            }
        }
        .frame(height: 188)
        .frame(maxWidth: .infinity)
    }
}

/** 拼贴封面：1 张铺满，2 张左右对半，3 张左半 + 右上下，4 张四宫格。 */
private struct AlbumMosaic: View {
    let items: [MediaItem]
    let emptyIcon: String
    let emptyTint: Color

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let h = geo.size.height
            ZStack(alignment: .topLeading) {
                albumPlaceholderColor
                switch items.count {
                case 0:
                    Image(systemName: emptyIcon)
                        .font(.system(size: 28))
                        .foregroundStyle(emptyTint.opacity(0.78))
                        .padding(16)
                case 1:
                    thumb(items[0], x: 0, y: 0, w: w, h: h)
                case 2:
                    thumb(items[0], x: 0, y: 0, w: w / 2 - 0.5, h: h)
                    thumb(items[1], x: w / 2 + 0.5, y: 0, w: w / 2 - 0.5, h: h)
                case 3:
                    thumb(items[0], x: 0, y: 0, w: w / 2 - 0.5, h: h)
                    thumb(items[1], x: w / 2 + 0.5, y: 0, w: w / 2 - 0.5, h: h / 2 - 0.5)
                    thumb(items[2], x: w / 2 + 0.5, y: h / 2 + 0.5, w: w / 2 - 0.5, h: h / 2 - 0.5)
                default:
                    thumb(items[0], x: 0, y: 0, w: w / 2 - 0.5, h: h / 2 - 0.5)
                    thumb(items[1], x: w / 2 + 0.5, y: 0, w: w / 2 - 0.5, h: h / 2 - 0.5)
                    thumb(items[2], x: 0, y: h / 2 + 0.5, w: w / 2 - 0.5, h: h / 2 - 0.5)
                    thumb(items[3], x: w / 2 + 0.5, y: h / 2 + 0.5, w: w / 2 - 0.5, h: h / 2 - 0.5)
                }
            }
        }
    }

    private func thumb(_ item: MediaItem, x: CGFloat, y: CGFloat, w: CGFloat, h: CGFloat) -> some View {
        MediaThumb(item: item, square: false, showBadge: false)
            .frame(width: w, height: h)
            .clipped()
            .position(x: x + w / 2, y: y + h / 2)
    }
}

/** 子页标题栏：返回圆钮 + 大标题。 */
private struct SubPageHeader<Trailing: View>: View {
    let title: String
    let onBack: () -> Void
    @ViewBuilder let trailing: () -> Trailing

    var body: some View {
        HStack {
            Button(action: onBack) {
                Image(systemName: "arrow.left")
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(Color.ink)
                    .frame(width: 48, height: 48)
                    .background(Color(hex: 0xEDF1EC))
                    .clipShape(Circle())
            }
            .accessibilityLabel("返回")
            Text(title)
                .font(.system(size: 22, weight: .bold))
                .foregroundStyle(Color.ink)
                .padding(.leading, 12)
            Spacer()
            trailing()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }
}

private extension SubPageHeader where Trailing == EmptyView {
    init(title: String, onBack: @escaping () -> Void) {
        self.init(title: title, onBack: onBack, trailing: { EmptyView() })
    }
}

/** 网格缩略图：视频左下角带时长角标，实况照片带 Live 角标。 */
struct MediaThumb: View {
    let item: MediaItem
    var square = true
    var showBadge = true

    var body: some View {
        Group {
            if square {
                Color.clear.aspectRatio(1, contentMode: .fit)
            } else {
                Color.clear
            }
        }
        .overlay(
            ZStack(alignment: .bottomLeading) {
                    MediaImageView(
                        item: item,
                        targetSize: CGSize(width: 480, height: 480),
                        contentMode: .aspectFill,
                    )
                    if showBadge, item.isVideo {
                        HStack(spacing: 3) {
                            Image(systemName: "play.fill")
                                .font(.system(size: 8))
                                .foregroundStyle(.white)
                            Text(formatVideoTime(item.durationMs ?? 0))
                                .font(.system(size: 11))
                                .foregroundStyle(.white)
                        }
                        .padding(7)
                    } else if showBadge, item.isLivePhoto {
                        LivePhotoBadge(compact: true)
                            .padding(7)
                    }
                },
            )
            .clipped()
            .contentShape(Rectangle())
    }
}

/** 全部 / 喜欢 / 相簿共用的网格子页。 */
private struct MediaGridPage: View {
    let title: String
    let items: [MediaItem]
    let emptyText: String
    let bottomInset: CGFloat
    let onOpen: (String) -> Void
    let onBack: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            SubPageHeader(title: title, onBack: onBack)
            if items.isEmpty {
                Text(emptyText)
                    .font(.system(size: 15))
                    .foregroundStyle(Color(hex: 0x6D7871))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVGrid(
                        columns: Array(repeating: GridItem(.flexible(), spacing: 3), count: 3),
                        spacing: 3,
                    ) {
                        ForEach(items) { item in
                            MediaThumb(item: item)
                                .clipShape(RoundedRectangle(cornerRadius: 12))
                                .onTapGesture { onOpen(item.id) }
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, 26 + bottomInset)
                }
            }
        }
        .background(Color.paper.ignoresSafeArea())
    }
}
