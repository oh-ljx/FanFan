import SwiftUI
import UIKit

enum MainTab { case flip, workbench }

struct ViewerContext: Equatable {
    let source: CollectionSource
    let startId: String
    let albumId: String?
}

struct RootView: View {
    @ObservedObject var app: AppState

    @State private var tab: MainTab = .flip
    @State private var viewer: ViewerContext?
    @State private var noteSheetVisible = false

    var body: some View {
        ZStack {
            Color.stage.ignoresSafeArea()
            content
        }
        .onAppear {
            StatusBarStyleManager.shared.lightContent = tab == .workbench && viewer == nil
            app.checkPermissionOnLaunch()
        }
        .onChange(of: tab == .workbench && viewer == nil) { light in
            StatusBarStyleManager.shared.lightContent = light
        }
    }

    @ViewBuilder
    private var content: some View {
        if !app.permissionGranted {
            PermissionGate(app: app)
        } else if let error = app.loadingError {
            VStack {
                Text("相册读取失败")
                    .font(.system(size: 16))
                    .foregroundStyle(.white)
                Text(error)
                    .font(.system(size: 13))
                    .foregroundStyle(Color(hex: 0x8FA598))
                    .padding(.top, 8)
                Button {
                    app.retryBoot()
                } label: {
                    Text("重新读取")
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 22)
                        .padding(.vertical, 10)
                        .background(Color.pine)
                        .clipShape(RoundedRectangle(cornerRadius: 20))
                }
                .padding(.top, 18)
            }
            .padding(.horizontal, 32)
        } else if !app.loaded {
            ProgressView()
                .tint(.mint)
        } else if app.allMedia.isEmpty {
            Text("相册里还没有照片")
                .font(.system(size: 14))
                .foregroundStyle(Color(hex: 0x8FA598))
        } else if let session = app.session {
            tabs(session: session)
        }
    }

    private func tabs(session: FlipSession) -> some View {
        GeometryReader { geo in
            let controlsInset = AppState.navHeight + geo.safeAreaInsets.bottom
            ZStack {
                if tab == .flip {
                    FlipScreen(
                        app: app,
                        session: session,
                        bottomInset: controlsInset,
                        safeAreaInsets: geo.safeAreaInsets,
                        onNoteSheetOpenChange: { noteSheetVisible = $0 },
                    )
                    .ignoresSafeArea()
                } else {
                    WorkbenchScreen(
                        app: app,
                        session: session,
                        bottomInset: controlsInset,
                        onRestartRound: {
                            session.restartRound()
                            tab = .flip
                        },
                        onOpenCollection: { source, id, albumId in
                            viewer = ViewerContext(source: source, startId: id, albumId: albumId)
                        },
                    )
                }

                BottomNav(tab: $tab)
                    .frame(maxHeight: .infinity, alignment: .bottom)
                    .ignoresSafeArea(edges: .bottom)
                    .offset(y: noteSheetVisible ? AppState.navHeight + 60 : 0)
                    .animation(flipTiming(0.3), value: noteSheetVisible)

                if let viewer {
                    CollectionViewer(
                        app: app,
                        session: session,
                        source: viewer.source,
                        startId: viewer.startId,
                        albumId: viewer.albumId,
                        safeAreaInsets: geo.safeAreaInsets,
                        onClose: { self.viewer = nil },
                    )
                    .ignoresSafeArea()
                }
            }
        }
    }
}

/** 全宽贴底导航：翻翻页用暗房衬底，理理页换成相纸色，与页面融为一体。 */
private struct BottomNav: View {
    @Binding var tab: MainTab

    var body: some View {
        let onPaper = tab == .workbench
        VStack(spacing: 0) {
            Rectangle()
                .fill(onPaper ? Color.ink.opacity(0.08) : Color.white.opacity(0.08))
                .frame(height: 1)
            HStack(spacing: 0) {
                NavItem(
                    label: "翻翻",
                    icon: .flipStack,
                    active: tab == .flip,
                    onPaper: onPaper,
                ) { tab = .flip }
                NavItem(
                    label: "理理",
                    icon: .grid,
                    active: tab == .workbench,
                    onPaper: onPaper,
                ) { tab = .workbench }
            }
            .frame(height: AppState.navHeight)
            .padding(.bottom, bottomSafeArea)
        }
        .background(onPaper ? Color.paper : Color(hex: 0x0B100E).opacity(0.97))
    }

    private var bottomSafeArea: CGFloat {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first { $0.isKeyWindow }?
            .safeAreaInsets.bottom ?? 0
    }
}

private struct NavItem: View {
    let label: String
    let icon: AppIcon
    let active: Bool
    let onPaper: Bool
    let onClick: () -> Void

    var body: some View {
        let activeTint = onPaper ? Color.pine : Color.mint
        let activeLabel = onPaper ? Color.ink : Color.white
        let inactiveTint = onPaper ? Color(hex: 0x7C8781) : Color(hex: 0x8F9A94)
        HStack(spacing: 8) {
            AppIconImage(icon: icon, tint: active ? activeTint : inactiveTint)
                .frame(width: 24, height: 24)
            Text(label)
                .font(.system(size: 15, weight: active ? .bold : .medium))
                .foregroundStyle(active ? activeLabel : inactiveTint)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .contentShape(Rectangle())
        .onTapGesture(perform: onClick)
    }
}
