// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "FinexyCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "FinexyCore", targets: ["FinexyCore"])],
    targets: [
        .target(name: "FinexyCore"),
        .testTarget(name: "FinexyCoreTests", dependencies: ["FinexyCore"])
    ]
)
