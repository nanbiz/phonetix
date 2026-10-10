// swift-tools-version: 6.0

// The iPhone app: the browser extension as a Safari extension, carried by an app of its own,
// which is the only way iOS installs one. Built and installed with xtool (ios/build.sh).
import PackageDescription

let package = Package(
    name: "Phonetix",
    platforms: [.iOS(.v17)],
    products: [
        .library(name: "Phonetix", targets: ["Phonetix"]),
        .library(name: "PhonetixSafari", targets: ["PhonetixSafari"]),
    ],
    targets: [
        .target(name: "Phonetix"),
        // Safari finds the handler by its name in Info.plist and nothing in the code refers to
        // it, so without -ObjC the linker leaves it out, and the extension is a binary with no
        // code that Safari cannot start (and that the signer refuses).
        .target(
            name: "PhonetixSafari",
            linkerSettings: [.unsafeFlags(["-Xlinker", "-ObjC"])]
        ),
    ]
)
