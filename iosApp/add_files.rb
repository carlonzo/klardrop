#!/usr/bin/env ruby
require 'xcodeproj'

PROJECT_PATH = '/Users/carlo/Projects/klardrop/iosApp/iosApp.xcodeproj'
APP_DIR = '/Users/carlo/Projects/klardrop/iosApp/iosApp'
TARGET_NAME = 'iosApp'

project = Xcodeproj::Project.open(PROJECT_PATH)
target = project.targets.find { |t| t.name == TARGET_NAME }

if target.nil?
  puts "ERROR: Could not find target #{TARGET_NAME}"
  exit 1
end

# Get all Swift files currently in the project
existing_paths = project.files.map { |f| f.real_path.to_s rescue nil }.compact

puts "Existing files in project (#{existing_paths.count}):"
existing_paths.select { |p| p.end_with?('.swift') }.sort.each { |p| puts "  #{p}" }
puts ""

# Find all Swift files in the app directory
all_swift_files = Dir.glob("#{APP_DIR}/**/*.swift").sort

puts "All Swift files on disk (#{all_swift_files.count}):"
all_swift_files.each { |p| puts "  #{p}" }
puts ""

# Find files not yet in the project
missing_files = all_swift_files.reject { |f| existing_paths.include?(f) }

puts "Missing files (#{missing_files.count}):"
missing_files.each { |p| puts "  #{p}" }
puts ""

if missing_files.empty?
  puts "All files already in project!"
  exit 0
end

# Helper: find or create group for a path
def find_or_create_group(project, app_dir, file_path)
  relative = file_path.sub("#{app_dir}/", '')
  parts = relative.split('/')[0..-2]  # directory parts (no filename)

  current_group = project.main_group
  # Navigate into iosApp group first
  iosapp_group = current_group.children.find { |c| c.respond_to?(:path) && c.path == 'iosApp' } ||
                 current_group.children.find { |c| c.display_name == 'iosApp' }

  if iosapp_group.nil?
    puts "  WARNING: Could not find iosApp group, using main group"
    iosapp_group = current_group
  end

  current_group = iosapp_group

  parts.each do |part|
    child = current_group.children.find { |c| c.respond_to?(:path) && c.path == part }
    if child.nil?
      puts "    Creating group: #{part}"
      child = current_group.new_group(part, part)
    end
    current_group = child
  end

  current_group
end

# Add missing files
missing_files.each do |file_path|
  puts "Adding: #{file_path}"
  group = find_or_create_group(project, APP_DIR, file_path)
  filename = File.basename(file_path)

  # Add file reference
  file_ref = group.new_file(filename)

  # Add to target's sources build phase
  target.source_build_phase.add_file_reference(file_ref)
  puts "  -> Added to group '#{group.display_name}' and target '#{TARGET_NAME}'"
end

project.save
puts "\nProject saved. Added #{missing_files.count} files."
