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
        .target(name: "PhonetixSafari"),
    ]
)
