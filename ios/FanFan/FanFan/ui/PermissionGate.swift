import SwiftUI
import UIKit

/** 权限门：已授权则显示主内容，否则显示授权引导页。从系统设置返回时会自动重新检查。 */
struct PermissionGate: View {
    @ObservedObject var app: AppState

    var body: some View {
        VStack {
            Text("翻翻需要访问你的照片")
                .font(.system(size: 20))
                .foregroundStyle(Color(hex: 0xEEF3EC))
            Text("所有浏览和整理都在本机完成，不会上传任何内容。")
                .font(.system(size: 13))
                .foregroundStyle(Color(hex: 0x8FA598))
                .multilineTextAlignment(.center)
                .padding(.top, 12)
            Button {
                Task { await app.requestPermission() }
            } label: {
                Text("授权访问相册")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(Color(hex: 0xF2F6F1))
                    .padding(.horizontal, 22)
                    .padding(.vertical, 12)
                    .background(Color(hex: 0x2D4A3E))
                    .clipShape(RoundedRectangle(cornerRadius: 22))
            }
            .padding(.top, 28)
            Button {
                guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
                UIApplication.shared.open(url)
            } label: {
                Text("去系统设置开启")
                    .font(.system(size: 13))
                    .foregroundStyle(Color(hex: 0x7CC7AB))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
            }
        }
        .padding(.horizontal, 40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
