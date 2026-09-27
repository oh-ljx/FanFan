import SwiftUI

/**
 * Live Photo 徽章。白色本体直接叠在照片上，背后垫一圈径向渐变的柔光晕
 * 保证浅色画面上可读。
 */
struct LivePhotoBadge: View {
    var compact = false
    var onClick: (() -> Void)? = nil

    var body: some View {
        let touchSize: CGFloat = compact ? 24 : 48
        let markSize: CGFloat = compact ? 14 : 27
        ZStack {
            RadialGradient(
                colors: [.black.opacity(0.4), .black.opacity(0.16), .clear],
                center: .center,
                startRadius: 0,
                endRadius: markSize * 0.7,
            )
            .frame(width: markSize * 1.4, height: markSize * 1.4)
            Image(systemName: "livephoto")
                .resizable()
                .scaledToFit()
                .foregroundStyle(.white)
                .frame(width: markSize, height: markSize)
        }
        .frame(width: touchSize, height: touchSize)
        .contentShape(Circle())
        .onTapGesture {
            onClick?()
        }
        .accessibilityLabel(onClick == nil ? "实况照片" : "实况照片，点按重播")
    }
}
