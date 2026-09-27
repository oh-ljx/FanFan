import SwiftUI

extension Color {
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
        )
    }

    static let stage = Color(hex: 0x070B0A)
    static let mint = Color(hex: 0x79D6B0)
    static let spark = Color(hex: 0xF2C86B)
    static let likeRed = Color(hex: 0xFF3B62)
    static let paper = Color(hex: 0xF6F3EC)
    static let ink = Color(hex: 0x13251E)
    static let pine = Color(hex: 0x204C3D)
    static let danger = Color(hex: 0xD84C58)
}

/** 安卓版 CubicBezierEasing(0.2, 0.78, 0.2, 1)。 */
func flipTiming(_ duration: Double) -> Animation {
    .timingCurve(0.2, 0.78, 0.2, 1, duration: duration)
}

private struct ViewSizePreferenceKey: PreferenceKey {
    static var defaultValue: CGSize = .zero

    static func reduce(value: inout CGSize, nextValue: () -> CGSize) {
        value = nextValue()
    }
}

extension View {
    /** iOS 15 兼容的视图尺寸监听。 */
    func onSizeChange(_ action: @escaping (CGSize) -> Void) -> some View {
        background {
            GeometryReader { proxy in
                Color.clear.preference(key: ViewSizePreferenceKey.self, value: proxy.size)
            }
        }
        .onPreferenceChange(ViewSizePreferenceKey.self, perform: action)
    }
}
