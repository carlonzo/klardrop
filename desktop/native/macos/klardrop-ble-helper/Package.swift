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
      path: "Sources/KlardropBleHelper",
      exclude: ["Info.plist"],
      linkerSettings: [
        // Embed Info.plist into the Mach-O __TEXT,__info_plist section so macOS
        // TCC can read NSBluetoothAlwaysUsageDescription. Without this, creating a
        // CBCentralManager / CBPeripheralManager triggers an abort with SIGABRT.
        .unsafeFlags([
          "-Xlinker", "-sectcreate",
          "-Xlinker", "__TEXT",
          "-Xlinker", "__info_plist",
          "-Xlinker", "Sources/KlardropBleHelper/Info.plist",
        ])
      ]
    )
  ]
)
