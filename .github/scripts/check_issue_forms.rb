require 'json'
require 'yaml'

root = File.expand_path('../..', __dir__)
labels = JSON.parse(File.read(File.join(root, '.github/labels.json')))
names = labels.map { |label| label.fetch('name') }
abort 'Label names must be unique' unless names.uniq == names
labels.each do |label|
  abort "Invalid label color: #{label['name']}" unless /\A[0-9a-fA-F]{6}\z/.match?(label.fetch('color'))
  abort "Missing label description: #{label['name']}" if label.fetch('description').strip.empty?
end
%w[P0 P1 P2 P3].each { |name| abort "Missing priority label: #{name}" unless names.include?(name) }

config = YAML.safe_load(File.read(File.join(root, '.github/ISSUE_TEMPLATE/config.yml')))
abort 'Blank issues must be disabled' unless config.fetch('blank_issues_enabled') == false

Dir.glob(File.join(root, '.github/ISSUE_TEMPLATE/*.yml')).sort.each do |path|
  next if File.basename(path) == 'config.yml'
  form = YAML.safe_load(File.read(path))
  %w[name description body labels].each { |key| abort "#{path}: missing #{key}" unless form.key?(key) }
  abort "#{path}: unknown static label" unless (form.fetch('labels') - names).empty?
  ids = form.fetch('body').map { |field| field['id'] }.compact
  abort "#{path}: duplicate field IDs" unless ids.uniq == ids
  if File.basename(path) == 'feature-request.yml'
    fields = form.fetch('body').to_h { |field| [field['id'], field] }
    diagnostic_ids = %w[app-version build-uuid distribution]
    abort "#{path}: feature requests must not collect diagnostic fields" unless (ids & diagnostic_ids).empty?
    area = fields.fetch('affected-area')
    unless area['type'] == 'dropdown' && area.fetch('attributes')['multiple'] == true &&
           area.fetch('validations')['required'] == true
      abort "#{path}: affected-area must be a required multi-select dropdown"
    end
    platform = fields.fetch('affected-platforms')
    abort "#{path}: affected-platforms must be optional" unless platform.fetch('validations')['required'] == false
  end
  form.fetch('body').each do |field|
    next unless field['type'] == 'dropdown'
    attributes = field.fetch('attributes')
    options = attributes.fetch('options')
    abort "#{path}: empty dropdown" if options.empty?
    abort "#{path}: duplicate dropdown options" unless options.uniq == options
    next if attributes['multiple'] == true
    unless options.first == '请选择 / Please select' && attributes['default'].eql?(0)
      abort "#{path}: single-select dropdown #{field['id']} must default to the neutral prompt"
    end
  end
end
puts 'Issue forms and label catalog are valid'
