// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "Twinotify",
    platforms: [.macOS(.v26)],
    products: [
        .library(name: "TwinotifyKit", targets: ["TwinotifyKit"]),
        .executable(name: "TwinotifyMac", targets: ["TwinotifyMac"])
    ],
    dependencies: [
        .package(url: "https://github.com/jedisct1/swift-sodium.git",
                 revision: "cfd195c76882aa9b997560ca7cb95d72fbf5db00")
    ],
    targets: [
        .systemLibrary(name: "CSQLite"),
        .target(name: "TwinotifyKit", dependencies: ["CSQLite", .product(name: "Sodium", package: "swift-sodium")]),
        .executableTarget(name: "TwinotifyMac", dependencies: ["TwinotifyKit"]),
        .testTarget(name: "TwinotifyKitTests", dependencies: ["TwinotifyKit"])
    ]
)
