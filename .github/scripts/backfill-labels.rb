#!/usr/bin/env ruby

require 'json'
require 'open3'
require 'cgi'
require 'yaml'

CONTENT_CONFIG_PATH = '.github/issue-labeler.yml'
PATH_CONFIG_PATH = '.github/pr-labeler.yml'
AUTOMATION_ACTOR = 'github-actions[bot]'
GLOB_FLAGS = File::FNM_DOTMATCH
UNCHECKED_CHECKBOX = /^\s*[-+*]\s+\[\s\]\s+/

def compile_pattern(value)
  match = value.match(%r{\A/(.*)/([im]*)\z}m)
  source = match ? match[1] : value
  flags = 0
  flags |= Regexp::IGNORECASE if match && match[2].include?('i')
  flags |= Regexp::MULTILINE if match && match[2].include?('m')
  Regexp.new(source, flags)
end

def labels_for(text, rules, filter_unchecked: true)
  text = text.each_line.reject { |line| UNCHECKED_CHECKBOX.match?(line) }.join if filter_unchecked
  rules.filter_map do |label, patterns|
    label if patterns.any? { |pattern| compile_pattern(pattern).match?(text) }
  end
end

def path_labels_for(files, rules)
  rules.filter_map do |label, conditions|
    globs = Array(conditions).flat_map do |condition|
      Array(condition['changed-files']).flat_map do |changed_files|
        Array(changed_files['any-glob-to-any-file'])
      end
    end
    label if globs.any? do |glob|
      files.any? { |file| File.fnmatch?(glob, file, GLOB_FLAGS) }
    end
  end
end

def labels_to_remove(existing:, desired:, managed:, automation_owned:)
  (existing & managed & automation_owned) - desired
end

def run(*args, input: nil)
  stdout, stderr, status = if input
                             Open3.capture3(*args, stdin_data: input)
                           else
                             Open3.capture3(*args)
                           end
  abort(stderr.empty? ? stdout : stderr) unless status.success?
  stdout
end

def paginated_json(endpoint)
  JSON.parse(run('gh', 'api', '--paginate', '--slurp', endpoint)).flatten
end

def pull_request_files(repository, number)
  paginated_json("repos/#{repository}/pulls/#{number}/files?per_page=100")
    .map { |file| file.fetch('filename') }
end

def automation_owned_labels(repository, number)
  ownership = {}
  paginated_json("repos/#{repository}/issues/#{number}/events?per_page=100").each do |event|
    label = event.dig('label', 'name')
    next unless label

    case event['event']
    when 'labeled'
      ownership[label] = event.dig('actor', 'login') == AUTOMATION_ACTOR
    when 'unlabeled'
      ownership.delete(label)
    end
  end
  ownership.filter_map { |label, automated| label if automated }
end

def remove_label(repository, number, label)
  encoded_label = CGI.escape(label).gsub('+', '%20')
  run('gh', 'api', '--method', 'DELETE',
      "repos/#{repository}/issues/#{number}/labels/#{encoded_label}")
end

content_rules = YAML.safe_load_file(CONTENT_CONFIG_PATH, aliases: false)
path_rules = YAML.safe_load_file(PATH_CONFIG_PATH, aliases: false)
managed_labels = (content_rules.keys | path_rules.keys)

if ARGV == ['--self-test']
  unchecked_text = "- [ ] 构建或依赖\n- [ ] 崩溃或无响应\n- [ ] 阅读文本与翻页"
  unchecked = labels_for(unchecked_text, content_rules)
  unexpected = ['area: build', 'area: reader', 'impact: crash']
  abort("unchecked labels matched: #{unchecked & unexpected}") unless (unchecked & unexpected).empty?
  raw_unchecked = labels_for(unchecked_text, content_rules, filter_unchecked: false)
  abort("raw unchecked labels matched: #{raw_unchecked & unexpected}") unless (raw_unchecked & unexpected).empty?

  checked_text = "- [x] 新功能\n- [x] Android\n- [x] 性能或耗电"
  checked = labels_for(checked_text, content_rules, filter_unchecked: false)
  expected = ['enhancement', 'platform: android', 'impact: performance']
  abort("missing checked labels: #{expected - checked}") unless (expected - checked).empty?

  labels = labels_for("漫画模式加载时掉帧\n- [x] Android", content_rules)
  expected = ['area: manga', 'impact: performance']
  abort("missing labels: #{expected - labels}") unless (expected - labels).empty?
  abort('missing historical Web label') unless labels_for('增加web端', content_rules).include?('area: web')
  abort('audio progress sync matched data') if labels_for('朗读进度和文字显示不同步', content_rules).include?('area: data')
  abort('backup sync missed data') unless labels_for('书架书籍备份同步', content_rules).include?('area: data')

  files = [
    'app/src/main/java/io/legado/app/ui/book/manga/ReadMangaActivity.kt',
    'app/src/test/java/io/legado/app/ui/book/read/ReadMangaOfflineActionsTest.kt'
  ]
  path_labels = path_labels_for(files, path_rules)
  expected = ['platform: android', 'area: manga', 'tests', 'kotlin']
  abort("missing path labels: #{expected - path_labels}") unless (expected - path_labels).empty?
  abort('unexpected build label') if path_labels.include?('area: build')

  removable = labels_to_remove(
    existing: ['impact: crash', 'impact: ui', 'question'],
    desired: [],
    managed: managed_labels,
    automation_owned: ['impact: crash']
  )
  abort("unsafe labels selected for removal: #{removable}") unless removable == ['impact: crash']

  puts 'historical label matching passed'
  exit
end

repository = ENV.fetch('GITHUB_REPOSITORY')
paginated_json("repos/#{repository}/issues?state=open&per_page=100").each do |item|
  text = [item['title'], item['body']].compact.join("\n")
  existing = item.fetch('labels', []).map { |label| label['name'] }
  desired = labels_for(text, content_rules)
  if item.key?('pull_request')
    desired |= path_labels_for(pull_request_files(repository, item['number']), path_rules)
  end

  stale_candidates = (existing & managed_labels) - desired
  stale = if stale_candidates.empty?
            []
          else
            stale_candidates & automation_owned_labels(repository, item['number'])
          end
  missing = desired - existing
  next if missing.empty? && stale.empty?

  stale.each { |label| remove_label(repository, item['number'], label) }

  unless missing.empty?
    endpoint = "repos/#{repository}/issues/#{item['number']}/labels"
    run('gh', 'api', '--method', 'POST', endpoint, '--input', '-',
        input: JSON.generate(labels: missing))
  end

  changes = []
  changes << "+#{missing.join(', ')}" unless missing.empty?
  changes << "-#{stale.join(', ')}" unless stale.empty?
  puts "##{item['number']}: #{changes.join('; ')}"
end
