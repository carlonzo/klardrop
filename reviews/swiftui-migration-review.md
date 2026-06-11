## Summary

The migration successfully splits the UI layer: shared KMP business + state (new `:presentation` module using SKIE for Swift exposure of `DiscoveryController`, `DeviceChatViewModel`, `StateFlow`s, sealed types, etc.) consumed by native SwiftUI in a unified `iosApp` Xcode project (iOS + new `KlardropMac` macOS target sharing most `.swift` sources via manual target membership scripts). Android/Desktop retain Compose via `:compose-ui` (now depending on presentation). Old Compose bridges and the vestigial `:macos`/`:common-ui` Apple targets were removed; common module gained `macosArm64()` + platform cleanups (clipboard, deps, deployment targets bumped).

Approach is architecturally sound (single `KlardropBootstrap` owns the KMP graph + controllers for app lifetime; `@Observable` + `Task` + `.task`/`.onDisappear` for lifecycle; size-class routing for iPhone/iPad parity with old WideLayout). However, dominant risks realized in practice: the Swift sources do not compile for either Apple target today (missing `onEnum` in scope despite SKIE-generated interfaces); bridging is fragile/inconsistent (casts, KotlinBoolean, NSArray fallbacks now producing warnings or wrong paths); build/distribution has numerous hacks and duplication; several feature gaps (openFile) and lifecycle TODOs remain; error handling, ownership, and cross-cutting concerns have gaps. Overall verdict: substantial progress on the architectural split and SwiftUI reimplementation, but the changes are not yet in a buildable, parity-complete, low-risk state—requires fixes before merge to main.

## Issues

### Issue 1 -- Severity: bug
- File: iosApp/iosApp/Nav/KlardropNav.swift:250
- Description: `onEnum` (SKIE helper for sealed Kotlin interfaces such as `TrustStatus`) cannot be found in scope during `xcodebuild` for `KlardropMac` (and similarly for `iosApp` scheme, erroring in `AddDevicePickerSheet.swift:118`). The `import presentation` succeeds for other types (`DeviceUi`, `DiscoveryScreenState`), and macOS arm64 swiftinterfaces under the cocoapods framework do declare top-level `onEnum<__Sealed>(of: ... TrustStatus)`, but the compiler does not resolve the free function. This prevents the entire macOS (and iOS) target from building. The xcode invocations also surface targeting x86_64 vs. only arm64 modules in some cases.
- Suggestion: Ensure SKIE 0.10.12 + static framework export for macOS target emits `onEnum` visibly (may require explicit `import Skie` or `presentation.Skie.onEnum`, public visibility tweaks in build, or calling the qualified `Skie.Presentation.TrustStatus` form directly). Update all call sites uniformly once resolved. Add a compile-only CI step that builds both schemes with CODE_SIGNING_ALLOWED=NO.
- Status: open

### Issue 2 -- Severity: bug
- File: iosApp/iosApp/Views/Chat/DeviceChatScreen.swift:290
- Description: In `IncomingAuthBannerView`, `status.acceptTransfer` (from `ReceiveMessageStatus.PendingAuthorization`) is called with `KotlinBoolean(value: true/false)`. In the sibling `IncomingBannerStackView.swift:141` (and swipe actions), the identical callback is called with plain `true`/`false` (with comment claiming "Bool auto-bridges" / "PresentationBoolean"). Inconsistent bridging across files sharing the same Kotlin lambda type will cause type errors or runtime mismatches depending on SKIE mapping for the `(Boolean) -> Unit` param.
- Suggestion: Standardize on the form that actually compiles/links for the bridged signature (likely the plain Bool per the comment, or the explicit one). Remove the now-unnecessary `KotlinBoolean` wrapper if auto-bridging works. Add a test exercising accept from both banner and chat paths.
- Status: open

### Issue 3 -- Severity: bug
- File: iosApp/add_macos_target.rb:4
- Description: Hardcoded absolute path `PROJECT_PATH = '/Users/carlo/Projects/klardrop/iosApp/iosApp.xcodeproj'` (and `APP_DIR`). The script is checked in and referenced in migration notes/CI context; it will fail for any other developer or CI clone location. `add_mac_entitlements.rb` correctly uses `__dir__`, creating inconsistency.
- Suggestion: Make paths relative (e.g. using `File.expand_path` relative to `__dir__` or `File.dirname(__FILE__)`). Document that the scripts are one-time migration aids and should be removed or made idempotent+portable post-migration. Remove the dev username from repo.
- Status: open

### Issue 4 -- Severity: bug
- File: iosApp/iosApp/Nav/KlardropNav.swift:144 (and surrounding iPadSidebar)
- Description: `SidebarView<Yours, Nearby>` (defined with generic section builders + LocalDeviceFooter) is never instantiated. The iPad regular layout manually reimplements the entire scrollable yours/nearby sections, trusted/nearby filtering, DeviceRowView construction, local footer button+rename sheet, and background styling (duplicating logic from `DiscoveryScreen.swift`'s `YourDevicesSectionView`/`NearbySectionView` + `SidebarView` itself). Comments claim reuse of SidebarView closures but the code does not.
- Suggestion: Either delete the unused `SidebarView.swift` (and its `LocalDeviceFooter`) or refactor `iPadSidebar` (and potentially compact) to use the component for the shared device-list cluster. This reduces duplication for future changes to rows/sections.
- Status: open

### Issue 5 -- Severity: bug
- File: common/src/macosMain/kotlin/com/carlom/klardrop/common/InternalPlatformDependencies.kt:94 (similarly iosMain:107)
- Description: `openFile(filePath:)` always returns `false` with comment "simplified implementation... proper would need platform-specific UI integration". `DeviceChatViewModel.openFileClicked` (and thus `MessageRowView` / chat "open" actions for received files) surfaces "Unable to open file. No suitable app found." on all Apple platforms. This is a feature regression vs. Android/desktop Compose parity and vs. the old common-ui expectations.
- Suggestion: Implement real open on macOS using `NSWorkspace.open` (or `NSWorkspace.openFile`) with the resolved path (respecting sandbox entitlements). For iOS use `UIDocumentInteractionController` or `UIApplication.open` + file coordination. Wire through `FileManager` / platform file system. At minimum, gate the UI action or improve the error.
- Status: open

### Issue 6 -- Severity: bug
- File: presentation/src/commonMain/kotlin/com/carlom/klardrop/DiscoveryController.kt:317
- Description: `dispose()` only contains `controllerScope.cancel()` + TODOs; it is never called from Swift side for the root controller (only `DiscoveryAppModel.stop()` cancels observer Tasks; `ChatModel.stop()` does call the per-VM `onDispose`). `DeviceChatViewModel` similarly has a TODO comment. The main `controllerScope` (launches for messengers, showDevicesHelper, pairing, notifier actions, etc.) lives for process lifetime, which is acceptable for single-bootstrap but leaves no clean teardown, risks in tests, or if bootstrap were recreated.
- Suggestion: Call `controller.dispose()` from `DiscoveryAppModel.stop()` (and any other owners). Implement full cleanup (e.g. unregister callbacks, cancel child jobs). Consider making controllers `Closeable` or using SKIE's native lifecycle hooks. Remove the TODOs or convert to tracked issues.
- Status: open

### Issue 7 -- Severity: bug
- File: iosApp/Klardrop Share/ShareViewController.swift:62
- Description: Share extension remains the pre-migration stub: uses `UserDefaults.standard` (not an App Group), has `fatalError("Impossible to save image")`, only handles images via kUTTypeData, and the main body is commented-out `SLComposeServiceViewController`. It is not integrated with the new presentation layer, `Filekit`, `OnDataToSend`, or SwiftUI flows. The "Klardrop Share" target is still in the project (Info.plist etc.) but will not deliver shares to the app's discovery/chat.
- Suggestion: Either fully implement a modern Share extension that resolves URLs/attachments into `PlatformFile`s and hands off via App Group + Darwin notification (or direct to `Klardrop` if possible), or remove the target + entitlements + build phases if share-extension support is deferred. Update `iosApp/Info.plist` and build settings accordingly.
- Status: open

### Issue 8 -- Severity: suggestion
- File: iosApp/iosApp/Observable/ChatModel.swift:52 (and similar in init + start Tasks; also DiscoveryModel.swift)
- Description: Heavy defensive casting after `.value` and `for await` (e.g. `as? ChatUiState ?? default`, `as? [Messages]`, `as? NSArray then compactMap`, `as? Reachability ?? Unknown()`, `as? ReceiveMessageUpdate`). Recent build logs show "conditional cast ... always succeeds" and "downcast ... does nothing" warnings. This indicates current SKIE is already emitting native Swift types for the StateFlows (no more opaque List/NSArray bridging needed for `Messages` etc.). The fallbacks and NSArray paths are now dead or misleading.
- Suggestion: Simplify to direct `vm.uiState.value` (or `as!` with comment, or trust the type), remove NSArray branches, and the `??` defaults (or keep only for safety with logging). Update comments. This will also make future type mismatches fail fast at compile time.
- Status: open

### Issue 9 -- Severity: suggestion
- File: presentation/build.gradle.kts:90 (and duplicated logic in common/build.gradle.kts: ~530 and compose-ui history)
- Description: Extensive fragile platform-specific linker hacks: manual `-F` for Bugsnag synthetic (Debug/Release, macos vs ios paths), `-lsqlite3`, `xcode-select` exec + SubFrameworks paths for UIUtilities, `isMacOsHost` guards, deployment target bumps, `isStatic=true`, SKIE version pin, and separate macOS sqlite3 stubs (`sqlite3_macos_stubs.c`). The cocoapods script phases + dummyFramework + podBuildBugsnag* in CI are order-sensitive. Small Xcode/Gradle/SKIE change will break Apple builds for both targets.
- Suggestion: Document all hacks with links to upstream issues (KT, SKIE, SQLDelight, Bugsnag KMP). Prefer Gradle configuration over post-processing ruby scripts where possible. Consider moving sqlite stubs into the KMP module's cinterop or a shared xcframework. Add a "build Apple frameworks" job in PR CI that exercises both schemes end-to-end.
- Status: open

### Issue 10 -- Severity: suggestion
- File: iosApp/iosApp/Observable/DiscoveryModel.swift:74 (and 99 etc.)
- Description: `backgroundDiscoveryEnabled = ctrl.backgroundDiscoveryEnabled.value.boolValue` (and similar for other Kotlin `Boolean` flows). This works because Kotlin `Boolean` bridges as `NSNumber`/`Bool?` requiring `.boolValue`, but is an interop smell scattered across the models. Also `KotlinBoolean(value:)` in one place.
- Suggestion: Centralize bridging extensions (e.g. `var boolValue: Bool { (self as? NSNumber)?.boolValue ?? false }` or rely on SKIE `Boolean` mapping if configured). Make the Kotlin `StateFlow<Boolean>` properties expose a cleaner Swift `Bool` if possible via SKIE annotations.
- Status: open

### Issue 11 -- Severity: nit
- File: iosApp/iosApp/Views/Discovery/DeviceUiMapping.swift:19 (and similar exhaustive switches in PermissionsPanelView, DiscoveryScreen, KlardropNav)
- Description: Several `switch onEnum(of: ...)` / `switch deviceType` have `default: return .xxx` that the compiler (and build logs) now flag as "default will never be executed" because the Kotlin enum/sealed cases are exhaustive in the bridged `__Sealed`. This is good (exhaustiveness) but the dead default adds noise.
- Suggestion: Remove the `default` arms (or replace with `fatalError("unreachable")` + comment) once all sealed cases are handled; let Swift enforce exhaustiveness.
- Status: open

### Issue 12 -- Severity: nit
- File: .github/workflows/release.yml:238 (and build_pr.yml, presentation/build.gradle.kts)
- Description: macOS native job + test matrix updates are correct in intent (new presentation + common macosArm64Test, removal of old :macos), but the prep steps (JDK 21 pin comment, podBuildBugsnagMacos + generateDummy, manual keychain, xcodebuild archive, separate notarize, macos-verified.txt gate for Homebrew) are complex and not exercised in the PR "build" job (only the macOS test job). A bad change here can silently produce unsigned artifacts or break Homebrew.
- Suggestion: Add an explicit "Build macOS native (unsigned)" step or matrix to the PR workflow using the same commands (with secrets absent path). Gate the Homebrew publish more explicitly.
- Status: open

## Review metadata
- Confidence: 75 (thorough reading of diff chunks + all critical post-change bridging/lifecycle/gradle/CI/Swift source files + targeted workspace greps + xcode/gradle invocations for evidence of build failures; some depth on every attention area requested; less on every leaf view or full pbxproj diff).
- Key files inspected: /tmp/grok-review-diff-45856514.diff, /tmp/grok-review-files-45856514.txt, presentation/build.gradle.kts, presentation/src/commonMain/kotlin/com/carlom/klardrop/{UiDependencies.kt,DiscoveryController.kt,UpdateBannerController.kt,chat/DeviceChatViewModel.kt,...}, presentation/src/{ios,macos}Main/kotlin/com/carlom/klardrop/KlardropBootstrap.kt, iosApp/Podfile, iosApp/iosApp/App/{iOSApp.swift,MacApp.swift,RootView.swift}, iosApp/iosApp/Observable/{DiscoveryModel.swift,ChatModel.swift}, iosApp/iosApp/Nav/{KlardropNav.swift,FilePicking.swift}, iosApp/iosApp/Views/Discovery/{DiscoveryScreen.swift,SidebarView.swift,DeviceUiMapping.swift}, multiple other Views/*.swift and Dialogs, common/build.gradle.kts + apple sources, .github/workflows/{build_pr.yml,release.yml}, iosApp/add_*.rb, iosApp/iosApp/{MacInfo.plist,KlardropMac.entitlements}, compose-ui/build.gradle.kts, settings.gradle.kts, and current workspace structure + build artifacts for interop verification. Also ran gradle/xcodebuild probes confirming compile failures and warnings.
