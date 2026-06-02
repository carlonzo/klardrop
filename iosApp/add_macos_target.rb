#!/usr/bin/env ruby
require 'xcodeproj'

PROJECT_PATH = '/Users/carlo/Projects/klardrop/iosApp/iosApp.xcodeproj'
APP_DIR      = '/Users/carlo/Projects/klardrop/iosApp/iosApp'
MAC_TARGET_NAME = 'KlardropMac'
DEVELOPMENT_TEAM = 'D7T5425WSW'

project = Xcodeproj::Project.open(PROJECT_PATH)

# Check if target already exists
if project.targets.any? { |t| t.name == MAC_TARGET_NAME }
  puts "Target #{MAC_TARGET_NAME} already exists. Skipping creation."
  exit 0
end

ios_target = project.targets.find { |t| t.name == 'iosApp' }
if ios_target.nil?
  puts "ERROR: Could not find iosApp target"
  exit 1
end

puts "Creating macOS target: #{MAC_TARGET_NAME}"
mac_target = project.new_target(:application, MAC_TARGET_NAME, :osx, '14.0')

puts "Setting build settings..."
mac_target.build_configurations.each do |config|
  s = config.build_settings

  # Identity
  s['PRODUCT_BUNDLE_IDENTIFIER']    = 'com.carlom.Klardrop'
  s['PRODUCT_NAME']                 = 'Klardrop'

  # Platform
  s['MACOSX_DEPLOYMENT_TARGET']     = '14.0'
  s['SDKROOT']                      = 'macosx'
  s['SUPPORTED_PLATFORMS']          = 'macosx'
  s['SUPPORTS_MACCATALYST']         = 'NO'

  # Swift
  s['SWIFT_VERSION']                = '5.0'
  s['SWIFT_EMIT_LOC_STRINGS']       = 'YES'

  # Signing
  s['CODE_SIGN_STYLE']              = 'Automatic'
  s['DEVELOPMENT_TEAM']             = DEVELOPMENT_TEAM
  s['CODE_SIGN_IDENTITY']           = 'Apple Development'
  s['ENABLE_HARDENED_RUNTIME']      = 'YES'

  # Sandbox/scripts
  s['ENABLE_USER_SCRIPT_SANDBOXING'] = 'NO'

  # Info.plist (synthesized)
  s['GENERATE_INFOPLIST_FILE']                        = 'YES'
  s['INFOPLIST_KEY_LSApplicationCategoryType']         = 'public.app-category.utilities'
  s['INFOPLIST_KEY_NSHumanReadableCopyright']          = ''

  # Previews
  s['ENABLE_PREVIEWS']              = 'YES'

  # Linker
  s['LD_RUNPATH_SEARCH_PATHS']      = ['$(inherited)', '@executable_path/../Frameworks']
  s['OTHER_LDFLAGS']                = ['$(inherited)', '-lsqlite3']

  # macOS specifics
  s['COMBINE_HIDPI_IMAGES']         = 'YES'

  if config.name == 'Debug'
    s['ONLY_ACTIVE_ARCH']                       = 'YES'
    s['SWIFT_ACTIVE_COMPILATION_CONDITIONS']     = 'DEBUG'
    s['SWIFT_OPTIMIZATION_LEVEL']               = '-Onone'
    s['DEBUG_INFORMATION_FORMAT']               = 'dwarf'
  elsif config.name == 'Release'
    s['SWIFT_COMPILATION_MODE']                 = 'wholemodule'
    s['SWIFT_OPTIMIZATION_LEVEL']               = '-O'
  end
end

# ---------------------------------------------------------------------------
# File membership — SHARED FILES
# Add the same PBXFileReference that the iOS target already has, so no
# duplicate refs are created.
# ---------------------------------------------------------------------------

SHARED_FILES = [
  'iosApp/App/RootView.swift',
  'iosApp/Design/Color+Hex.swift',
  'iosApp/Design/KdColors.swift',
  'iosApp/Design/KdMotion.swift',
  'iosApp/Design/KdRadii.swift',
  'iosApp/Design/KdSpacing.swift',
  'iosApp/Design/KdType.swift',
  'iosApp/Nav/FilePicking.swift',
  'iosApp/Nav/KlardropNav.swift',
  'iosApp/Nav/NavRoutes.swift',
  'iosApp/Observable/ChatModel.swift',
  'iosApp/Observable/DiscoveryModel.swift',
  'iosApp/Views/Chat/ChatHeaderView.swift',
  'iosApp/Views/Chat/ChatTimeFormat.swift',
  'iosApp/Views/Chat/DeviceChatScreen.swift',
  'iosApp/Views/Chat/FileCardView.swift',
  'iosApp/Views/Chat/MessageInputView.swift',
  'iosApp/Views/Chat/MessageRowView.swift',
  'iosApp/Views/Chat/TextMessageViewerView.swift',
  'iosApp/Views/Components/BannerView.swift',
  'iosApp/Views/Components/BubbleQuickActionsView.swift',
  'iosApp/Views/Components/BubbleView.swift',
  'iosApp/Views/Components/DateChipView.swift',
  'iosApp/Views/Components/DeviceAvatarView.swift',
  'iosApp/Views/Components/SectionHeadView.swift',
  'iosApp/Views/Components/VisibilityPillView.swift',
  'iosApp/Views/DeviceUiHelpers.swift',
  'iosApp/Views/Dialogs/AddDevicePickerSheet.swift',
  'iosApp/Views/Dialogs/PairingApprovalSheet.swift',
  'iosApp/Views/Dialogs/PairingDialogView.swift',
  'iosApp/Views/Dialogs/PermissionsChecklistView.swift',
  'iosApp/Views/Dialogs/RenameSheet.swift',
  'iosApp/Views/Dialogs/SettingsSheet.swift',
  'iosApp/Views/Dialogs/ShareSheetView.swift',
  'iosApp/Views/Dialogs/TrustDialogs.swift',
  'iosApp/Views/Discovery/DeviceRowView.swift',
  'iosApp/Views/Discovery/DeviceUiMapping.swift',
  'iosApp/Views/Discovery/DiscoveryScreen.swift',
  'iosApp/Views/Discovery/IncomingBannerStackView.swift',
  'iosApp/Views/Discovery/IncomingTransferCardView.swift',
  'iosApp/Views/Discovery/PermissionsPanelView.swift',
  'iosApp/Views/Discovery/SidebarView.swift',
  'iosApp/Views/Discovery/SystemNotificationCardView.swift',
  'iosApp/Views/Discovery/UpdateBannerView.swift',
  'iosApp/Views/DiscoveryView.swift',
  'iosApp/Views/StatusDotView.swift',
]

# Build a lookup: basename -> file_ref from existing project files
file_ref_by_path = {}
project.files.each do |f|
  begin
    real = f.real_path.to_s
    file_ref_by_path[real] = f
  rescue
  end
end

# Helper: build absolute path from relative (relative to the iosApp workspace root)
WORKSPACE_ROOT = File.dirname(PROJECT_PATH)

added_count = 0
SHARED_FILES.each do |rel|
  abs = File.join(WORKSPACE_ROOT, rel)
  ref = file_ref_by_path[abs]
  if ref.nil?
    puts "  WARNING: could not find file ref for #{rel} (#{abs}) — skipping"
    next
  end
  mac_target.source_build_phase.add_file_reference(ref)
  puts "  Shared: #{File.basename(rel)}"
  added_count += 1
end

# ---------------------------------------------------------------------------
# MacApp.swift — macOS-only entry point
# Create a new file ref in the App group and add ONLY to macOS target.
# ---------------------------------------------------------------------------
mac_app_path = File.join(APP_DIR, 'App', 'MacApp.swift')

# Find the App group in the iosApp group
iosapp_group = project.main_group.children.find { |c| c.respond_to?(:path) && c.path == 'iosApp' }
app_group = iosapp_group&.children&.find { |c| c.respond_to?(:path) && c.path == 'App' }

if app_group.nil?
  puts "  WARNING: Could not find App group, using main group for MacApp.swift"
  app_group = project.main_group
end

# Only add if not already in project
existing_mac_ref = file_ref_by_path[mac_app_path]
if existing_mac_ref.nil?
  mac_app_ref = app_group.new_file('MacApp.swift')
  puts "  Created file ref: MacApp.swift"
else
  mac_app_ref = existing_mac_ref
  puts "  Reusing existing file ref: MacApp.swift"
end

mac_target.source_build_phase.add_file_reference(mac_app_ref)
puts "  MacApp.swift added to #{MAC_TARGET_NAME} only"

# ---------------------------------------------------------------------------
# Create shared scheme
# ---------------------------------------------------------------------------
puts "Creating shared scheme: #{MAC_TARGET_NAME}"
scheme = Xcodeproj::XCScheme.new
scheme.add_build_target(mac_target)
scheme.set_launch_target(mac_target)
scheme.save_as(PROJECT_PATH, MAC_TARGET_NAME, true)
puts "  Scheme saved."

# ---------------------------------------------------------------------------
# Save project
# ---------------------------------------------------------------------------
project.save
puts "\nProject saved. Added #{added_count} shared files + MacApp.swift to #{MAC_TARGET_NAME}."
puts "Targets in project:"
project.targets.each { |t| puts "  - #{t.name}" }
