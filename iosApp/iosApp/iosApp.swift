import SwiftUI
import Termish

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

@main
struct TermishIosApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView()
                // Ignore all safe areas: Compose handles the status bar / Home indicator bar and keyboard insets itself,
                // Otherwise, SwiftUI will automatically wrap the content within a safe area by default, exposing the top status bar and bottom indicator bar.
                // The white background of UIWindow (the header/footer appears white in dark mode)
                .ignoresSafeArea()
        }
    }
}
