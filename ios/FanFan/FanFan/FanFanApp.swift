import AVFAudio
import SwiftUI
import UIKit

/** 按页面切换状态栏颜色（翻翻/查看页深色底浅色图标，理理页相纸底深色图标）。 */
final class StatusBarStyleManager {
    static let shared = StatusBarStyleManager()

    weak var viewController: UIViewController?

    var lightContent = false {
        didSet {
            guard oldValue != lightContent else { return }
            viewController?.setNeedsStatusBarAppearanceUpdate()
        }
    }
}

final class ThemedHostingController<Content: View>: UIHostingController<Content> {
    override var preferredStatusBarStyle: UIStatusBarStyle {
        StatusBarStyleManager.shared.lightContent ? .darkContent : .lightContent
    }
}

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil,
    ) -> Bool {
        // 视频与实况照片在静音键下也出声，与系统照片应用一致
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        return true
    }

    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions,
    ) -> UISceneConfiguration {
        let config = UISceneConfiguration(name: "Default Configuration", sessionRole: connectingSceneSession.role)
        config.delegateClass = SceneDelegate.self
        return config
    }
}

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?
    private let appState = AppState()

    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions,
    ) {
        guard let windowScene = scene as? UIWindowScene else { return }
        let controller = ThemedHostingController(rootView: RootView(app: appState))
        controller.view.backgroundColor = UIColor(Color.stage)
        StatusBarStyleManager.shared.viewController = controller
        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = controller
        window.makeKeyAndVisible()
        self.window = window
    }

    func sceneWillEnterForeground(_ scene: UIScene) {
        appState.handleResume()
    }

    func sceneDidEnterBackground(_ scene: UIScene) {
        appState.appVisible = false
    }
}
