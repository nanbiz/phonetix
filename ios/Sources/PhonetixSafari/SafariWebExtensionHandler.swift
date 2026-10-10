import Foundation

/// What Safari calls when the extension sends a message to its app. The extension asks the
/// app nothing: everything it needs is in its own bundle. The class has to exist, because
/// Safari starts the extension through it.
@objc(SafariWebExtensionHandler)
final class SafariWebExtensionHandler: NSObject, NSExtensionRequestHandling {
    func beginRequest(with context: NSExtensionContext) {
        context.completeRequest(returningItems: [], completionHandler: nil)
    }
}
