import SwiftUI

/// The app the Safari extension comes in. Everything Phonetix does happens in Safari; this is
/// what iOS installs it with, and what Safari's extension settings name it after.
@main
struct PhonetixApp: App {
    var body: some Scene {
        WindowGroup {
            VStack(spacing: 16) {
                Image(systemName: "character.bubble")
                    .font(.system(size: 56))
                    .foregroundStyle(.orange)
                Text("Phonetix")
                    .font(.largeTitle.bold())
            }
            .padding()
        }
    }
}
