// swift-tools-version: 5.9
//
// SwiftPM dependencies for the Klardrop Apple apps.
//
// sentry-cocoa must match the version sentry-kmp's cinterop was built against,
// or the app fails to link with undefined symbols (SentryDependencyContainer,
// SentryId, ...) — a failure that reads like a broken Xcode setup rather than
// version skew. The pairing lived in the Podfile before; it lives here now.
// To update: read the value out of the plugin jar Gradle actually resolved
// rather than trusting release notes:
//
//   javap -p -constants -cp \
//     "$(find ~/.gradle/caches -name 'sentry-kotlin-multiplatform-gradle-plugin-*.jar' | head -1)" \
//     io.sentry.BuildConfig
//
// sentry-kmp version -> sentry-cocoa version:
//   0.27.0 -> 8.58.2

import PackageDescription

let package = Package(
    name: "KlardropDependencies",
    platforms: [
        .iOS(.v17),
        .macOS(.v14),
    ],
    products: [],
    dependencies: [
        .package(url: "https://github.com/getsentry/sentry-cocoa.git", exact: "8.58.2"),
    ],
    targets: []
)
