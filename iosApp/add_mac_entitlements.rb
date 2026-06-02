#!/usr/bin/env ruby
# Wires KlardropMac.entitlements + a physical MacInfo.plist (with NSBonjourServices
# as a proper array) into the KlardropMac target's build configurations.
# Switches GENERATE_INFOPLIST_FILE -> NO and sets INFOPLIST_FILE = iosApp/MacInfo.plist.
# Run once (idempotent — checks existing values before overwriting).
#
# Usage: ruby iosApp/add_mac_entitlements.rb
require 'xcodeproj'

PROJECT_PATH      = File.join(__dir__, 'iosApp.xcodeproj')
ENTITLEMENTS_REL  = 'iosApp/KlardropMac.entitlements'  # relative to project root
INFOPLIST_REL     = 'iosApp/MacInfo.plist'              # relative to project root
MAC_TARGET_NAME   = 'KlardropMac'

project = Xcodeproj::Project.open(PROJECT_PATH)

mac_target = project.targets.find { |t| t.name == MAC_TARGET_NAME }
if mac_target.nil?
  puts "ERROR: Could not find target #{MAC_TARGET_NAME}"
  exit 1
end

puts "Configuring build settings for #{MAC_TARGET_NAME}..."

mac_target.build_configurations.each do |config|
  s = config.build_settings

  # --- Switch to physical Info.plist ---
  s['GENERATE_INFOPLIST_FILE'] = 'NO'
  s['INFOPLIST_FILE'] = INFOPLIST_REL
  # Remove INFOPLIST_KEY_* settings that are now in the physical plist
  %w[
    INFOPLIST_KEY_LSApplicationCategoryType
    INFOPLIST_KEY_NSHumanReadableCopyright
    INFOPLIST_KEY_NSLocalNetworkUsageDescription
    INFOPLIST_KEY_NSBonjourServices
    INFOPLIST_KEY_NSBluetoothAlwaysUsageDescription
  ].each { |k| s.delete(k) }

  # --- Entitlements ---
  s['CODE_SIGN_ENTITLEMENTS'] = ENTITLEMENTS_REL

  puts "  [#{config.name}] Done: INFOPLIST_FILE=#{INFOPLIST_REL}, CODE_SIGN_ENTITLEMENTS=#{ENTITLEMENTS_REL}"
end

# ---------------------------------------------------------------------------
# Add file refs to the project (for Xcode UI visibility)
# ---------------------------------------------------------------------------
iosapp_group = project.main_group.children.find { |c| c.respond_to?(:path) && c.path == 'iosApp' }

[
  { path: 'KlardropMac.entitlements', abs: File.join(File.dirname(PROJECT_PATH), ENTITLEMENTS_REL) },
  { path: 'MacInfo.plist',            abs: File.join(File.dirname(PROJECT_PATH), INFOPLIST_REL) },
].each do |entry|
  next unless iosapp_group
  existing_ref = project.files.find do |f|
    begin f.real_path.to_s == entry[:abs] rescue false end
  end
  if existing_ref
    puts "File ref already in project: #{entry[:path]}"
  else
    iosapp_group.new_file(entry[:path])
    puts "Added file ref: #{entry[:path]}"
  end
end

project.save
puts "\nProject saved. KlardropMac entitlements + physical MacInfo.plist configured."
