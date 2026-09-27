import SwiftUI

/** 与安卓版一致的自绘图标（24×24 视口的 SVG path）。 */
enum AppIcon {
    /** 翻翻：一叠照片，每层之间留出缝隙。 */
    case flipStack
    /** 理理：2×2 圆角方格。 */
    case grid
    /** 评论：圆角气泡 + 小尾巴。 */
    case comment
    /** 喜欢：实心爱心（Material Favorite）。 */
    case favorite
    /** 删除：垃圾桶（Material DeleteOutline）。 */
    case deleteOutline
    /** 恢复：逆时针箭头时钟（Material Restore）。 */
    case restore

    var pathData: String {
        switch self {
        case .flipStack:
            return "M5.70,3.50L18.30,3.50A2.20,2.20 0.0 0,1 20.50,5.70L20.50,6.10L3.50,6.10L3.50,5.70A2.20,2.20 0.0 0,1 5.70,3.50Z"
                + "M5.70,7.10L18.30,7.10A2.20,2.20 0.0 0,1 20.50,9.30L20.50,9.70L3.50,9.70L3.50,9.30A2.20,2.20 0.0 0,1 5.70,7.10Z"
                + "M6.00,10.70L18.00,10.70A2.50,2.50 0.0 0,1 20.50,13.20L20.50,18.00A2.50,2.50 0.0 0,1 18.00,20.50L6.00,20.50A2.50,2.50 0.0 0,1 3.50,18.00L3.50,13.20A2.50,2.50 0.0 0,1 6.00,10.70Z"
        case .grid:
            return "M6.00,3.50L9.00,3.50A2.50,2.50 0.0 0,1 11.50,6.00L11.50,9.00A2.50,2.50 0.0 0,1 9.00,11.50L6.00,11.50A2.50,2.50 0.0 0,1 3.50,9.00L3.50,6.00A2.50,2.50 0.0 0,1 6.00,3.50Z"
                + "M15.00,3.50L18.00,3.50A2.50,2.50 0.0 0,1 20.50,6.00L20.50,9.00A2.50,2.50 0.0 0,1 18.00,11.50L15.00,11.50A2.50,2.50 0.0 0,1 12.50,9.00L12.50,6.00A2.50,2.50 0.0 0,1 15.00,3.50Z"
                + "M6.00,12.50L9.00,12.50A2.50,2.50 0.0 0,1 11.50,15.00L11.50,18.00A2.50,2.50 0.0 0,1 9.00,20.50L6.00,20.50A2.50,2.50 0.0 0,1 3.50,18.00L3.50,15.00A2.50,2.50 0.0 0,1 6.00,12.50Z"
                + "M15.00,12.50L18.00,12.50A2.50,2.50 0.0 0,1 20.50,15.00L20.50,18.00A2.50,2.50 0.0 0,1 18.00,20.50L15.00,20.50A2.50,2.50 0.0 0,1 12.50,18.00L12.50,15.00A2.50,2.50 0.0 0,1 15.00,12.50Z"
        case .comment:
            return "M6.2,4.5h11.6a3.7,3.7 0 0 1 3.7,3.7v4.6a3.7,3.7 0 0 1 -3.7,3.7h-5.1l-4,3.9a0.72,0.72 0 0 1 -1.23,-0.51v-3.39H6.2a3.7,3.7 0 0 1 -3.7,-3.7V8.2A3.7,3.7 0 0 1 6.2,4.5z"
        case .favorite:
            return "M12,21.35l-1.45,-1.32C5.4,15.36 2,12.28 2,8.5 2,5.42 4.42,3 7.5,3c1.74,0 3.41,0.81 4.5,2.09C13.09,3.81 14.76,3 16.5,3 19.58,3 22,5.42 22,8.5c0,3.78 -3.4,6.86 -8.55,11.54L12,21.35z"
        case .deleteOutline:
            return "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM8,9h8v10H8V9zm7.5,-5l-1,-1h-5l-1,1H5v2h14V4h-3.5z"
        case .restore:
            return "M14,12c0,-1.1 -0.9,-2 -2,-2s-2,0.9 -2,2 0.9,2 2,2 2,-0.9 2,-2zM12,3c-4.97,0 -9,4.03 -9,9H0l4,4 4,-4H5c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.51,0 -2.91,-0.49 -4.06,-1.3l-1.42,1.44C8.04,20.3 9.94,21 12,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9z"
        }
    }
}

/** 以指定颜色绘制 AppIcon 的视图。 */
struct AppIconImage: View {
    let icon: AppIcon
    let tint: Color

    var body: some View {
        IconShape(pathData: icon.pathData)
            .fill(tint)
            .aspectRatio(1, contentMode: .fit)
    }
}

/** 24×24 视口的 SVG path 形状。 */
struct IconShape: Shape {
    let pathData: String

    func path(in rect: CGRect) -> Path {
        let scale = rect.width / 24
        return SVGPathParser.parse(pathData)
            .applying(CGAffineTransform(scaleX: scale, y: scale))
    }
}

/** SVG path 解析：支持 M/L/H/V/C/S/A/Z 的绝对与相对形式。 */
enum SVGPathParser {
    static func parse(_ data: String) -> Path {
        var path = Path()
        var i = data.startIndex
        var cmd: Character = " "
        var cur = CGPoint.zero
        var start = CGPoint.zero
        var lastControl: CGPoint?

        func isCmd(_ c: Character) -> Bool { "MmLlHhVvCcSsAaZz".contains(c) }
        func skipSep() {
            while i < data.endIndex, data[i] == " " || data[i] == "," || data[i].isWhitespace {
                i = data.index(after: i)
            }
        }
        func number() -> CGFloat {
            skipSep()
            var s = ""
            if i < data.endIndex, data[i] == "-" || data[i] == "+" {
                s.append(data[i])
                i = data.index(after: i)
            }
            while i < data.endIndex, data[i].isNumber || data[i] == "." {
                s.append(data[i])
                i = data.index(after: i)
            }
            return CGFloat(Double(s) ?? 0)
        }
        func point(_ relative: Bool) -> CGPoint {
            let x = number()
            let y = number()
            return relative ? CGPoint(x: cur.x + x, y: cur.y + y) : CGPoint(x: x, y: y)
        }

        while i < data.endIndex {
            skipSep()
            if i >= data.endIndex { break }
            if isCmd(data[i]) {
                cmd = data[i]
                i = data.index(after: i)
            }
            let rel = cmd.isLowercase
            switch cmd {
            case "M", "m":
                cur = point(rel)
                start = cur
                path.move(to: cur)
                cmd = rel ? "l" : "L"
                lastControl = nil
            case "L", "l":
                cur = point(rel)
                path.addLine(to: cur)
                lastControl = nil
            case "H", "h":
                let x = number()
                cur = CGPoint(x: rel ? cur.x + x : x, y: cur.y)
                path.addLine(to: cur)
                lastControl = nil
            case "V", "v":
                let y = number()
                cur = CGPoint(x: cur.x, y: rel ? cur.y + y : y)
                path.addLine(to: cur)
                lastControl = nil
            case "C", "c":
                let c1 = point(rel)
                let c2 = point(rel)
                let end = point(rel)
                path.addCurve(to: end, control1: c1, control2: c2)
                cur = end
                lastControl = c2
            case "S", "s":
                let c1 = lastControl.map { CGPoint(x: 2 * cur.x - $0.x, y: 2 * cur.y - $0.y) } ?? cur
                let c2 = point(rel)
                let end = point(rel)
                path.addCurve(to: end, control1: c1, control2: c2)
                cur = end
                lastControl = c2
            case "A", "a":
                let rx = number()
                let ry = number()
                let rot = number()
                let largeArc = number() != 0
                let sweep = number() != 0
                let end = point(rel)
                appendArc(to: &path, from: cur, to: end, rx: rx, ry: ry,
                          rotationDeg: rot, largeArc: largeArc, sweep: sweep)
                cur = end
                lastControl = nil
            case "Z", "z":
                path.closeSubpath()
                cur = start
                lastControl = nil
            default:
                i = data.index(after: i)
            }
        }
        return path
    }

    /** 端点参数化 → 圆心参数化（W3C F.6.5）；本应用图标只用圆弧。 */
    private static func appendArc(
        to path: inout Path, from p1: CGPoint, to p2: CGPoint,
        rx: CGFloat, ry: CGFloat, rotationDeg: CGFloat, largeArc: Bool, sweep: Bool,
    ) {
        var rx = abs(rx)
        var ry = abs(ry)
        guard rx > 0, ry > 0, p1 != p2 else {
            path.addLine(to: p2)
            return
        }
        let phi = rotationDeg * .pi / 180
        let cosPhi = cos(phi)
        let sinPhi = sin(phi)
        let dx = (p1.x - p2.x) / 2
        let dy = (p1.y - p2.y) / 2
        let x1p = cosPhi * dx + sinPhi * dy
        let y1p = -sinPhi * dx + cosPhi * dy
        let lam = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry)
        if lam > 1 {
            let s = sqrt(lam)
            rx *= s
            ry *= s
        }
        let num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        let den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var coef = den == 0 ? 0 : sqrt(max(0, num / den))
        if largeArc == sweep { coef = -coef }
        let cxp = coef * rx * y1p / ry
        let cyp = -coef * ry * x1p / rx
        let cx = cosPhi * cxp - sinPhi * cyp + (p1.x + p2.x) / 2
        let cy = sinPhi * cxp + cosPhi * cyp + (p1.y + p2.y) / 2

        func angle(_ ux: CGFloat, _ uy: CGFloat, _ vx: CGFloat, _ vy: CGFloat) -> CGFloat {
            let dot = ux * vx + uy * vy
            let len = sqrt(ux * ux + uy * uy) * sqrt(vx * vx + vy * vy)
            var a = acos(max(-1, min(1, dot / len)))
            if ux * vy - uy * vx < 0 { a = -a }
            return a
        }
        let ux = (x1p - cxp) / rx
        let uy = (y1p - cyp) / ry
        let vx = (-x1p - cxp) / rx
        let vy = (-y1p - cyp) / ry
        let theta1 = angle(1, 0, ux, uy)
        var dTheta = angle(ux, uy, vx, vy)
        if !sweep, dTheta > 0 { dTheta -= 2 * .pi }
        if sweep, dTheta < 0 { dTheta += 2 * .pi }

        // 直接按 SVG 坐标系（y 向下）采样圆弧，约每 15° 一段，
        // 不依赖任何框架的弧度方向约定
        let segments = max(4, Int((abs(dTheta) * 12 / .pi).rounded(.up)))
        for step in 1...segments {
            let theta = theta1 + dTheta * CGFloat(step) / CGFloat(segments)
            path.addLine(to: CGPoint(
                x: cx + rx * cos(theta),
                y: cy + ry * sin(theta),
            ))
        }
    }
}
