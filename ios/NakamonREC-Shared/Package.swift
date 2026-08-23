// swift-tools-version: 6.3
// The swift-tools-version declares the minimum version of Swift required to build this package.

import PackageDescription

let package = Package(
    name: "NakamonREC-Shared",
    platforms: [.iOS(.v17)],
    products: [
        .library(
            name: "NakamonREC-Shared",
            targets: ["NakamonREC-Shared"]
        ),
    ],
    targets: [
        .target(
            name: "NakamonREC-Shared",
            resources: [
                .process("Resources/monsters.json"),
                .process("Resources/grandprix_glyphs.json")
            ]
        ),
    ],
    swiftLanguageModes: [.v6]
)
