#!/usr/bin/env ruby

require 'json'
require 'open3'
require 'yaml'

CONFIG_PATH = '.github/issue-labeler.yml'

def compile_pattern(value)
  match = value.match(%r{\A/(.*)/([im]*)\z}m)
  source = match ? match[1] : value
  flags = 0
  flags |= Regexp::IGNORECASE if match && match[2].include?('i')
  flags |= Regexp::MULTILINE if match && match[2].include?('m')
  Regexp.new(source, flags)
end

def labels_for(text, rules)
  rules.filter_map do |label, patterns|
    label if patterns.any? { |pattern| compile_pattern(pattern).match?(text) }
  end
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

rules = YAML.safe_load_file(CONFIG_PATH, aliases: false)

if ARGV == ['--self-test']
  labels = labels_for("漫画模式加载时掉帧\n- [x] Android", rules)
  expected = ['area: manga', 'impact: performance']
  abort("missing labels: #{expected - labels}") unless (expected - labels).empty?
  abort('missing historical Web label') unless labels_for('增加web端', rules).include?('area: web')
  puts 'historical label matching passed'
  exit
end

repository = ENV.fetch('GITHUB_REPOSITORY')
pages = JSON.parse(
  run('gh', 'api', '--paginate', '--slurp',
      "repos/#{repository}/issues?state=open&per_page=100")
)

pages.flatten.each do |item|
  text = [item['title'], item['body']].compact.join("\n")
  existing = item.fetch('labels', []).map { |label| label['name'] }
  missing = labels_for(text, rules) - existing
  next if missing.empty?

  endpoint = "repos/#{repository}/issues/#{item['number']}/labels"
  run('gh', 'api', '--method', 'POST', endpoint, '--input', '-',
      input: JSON.generate(labels: missing))
  puts "##{item['number']}: #{missing.join(', ')}"
end
