// swift-tools-version:5.9
import PackageDescription

let package = Package(
  name: "KlardropBleHelper",
  platforms: [
    .macOS(.v11),
  ],
  targets: [
    .executableTarget(
      name: "KlardropBleHelper",
      path: "Sources/KlardropBleHelper"
    )
  ]
)
