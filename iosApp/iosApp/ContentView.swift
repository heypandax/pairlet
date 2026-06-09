import SwiftUI
import ComposeApp

// Hosts the shared Compose UI (MainViewController from the Kotlin framework).
struct ContentView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
