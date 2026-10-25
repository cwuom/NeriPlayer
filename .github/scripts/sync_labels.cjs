'use strict';

const definitions = require('../labels.json');

// labels.json 只声明维护的标签，未列出的远端标签永远保留
// 新增表单或映射标签后，先运行 Sync labels，再验证 Issue 自动分类
async function syncLabels({ github, context, core }, labels = definitions) {
  const seen = new Set();
  if (!Array.isArray(labels)) throw new Error('Invalid labels configuration');
  for (const label of labels) {
    if (typeof label.name !== 'string' || !label.name.trim() || label.name.length > 50 ||
        typeof label.color !== 'string' || !/^[0-9a-f]{6}$/i.test(label.color) ||
        typeof label.description !== 'string' || label.description.length > 100 ||
        seen.has(label.name.toLowerCase())) {
      throw new Error('Invalid or duplicate label configuration');
    }
    seen.add(label.name.toLowerCase());
  }
  const target = context.repo;
  const existing = await github.paginate(github.rest.issues.listLabelsForRepo, { ...target, per_page: 100 });
  const byName = new Map(existing.map(label => [label.name.toLowerCase(), label]));
  const result = { created: [], updated: [], unchanged: [] };
  for (const label of labels) {
    const current = byName.get(label.name.toLowerCase());
    if (!current) {
      await github.rest.issues.createLabel({ ...target, name: label.name,
        color: label.color, description: label.description });
      result.created.push(label.name);
    } else if (current.name !== label.name || current.color.toLowerCase() !== label.color.toLowerCase() ||
               (current.description ?? '') !== label.description) {
      await github.rest.issues.updateLabel({ ...target, name: current.name, new_name: label.name,
        color: label.color, description: label.description });
      result.updated.push(label.name);
    } else {
      result.unchanged.push(label.name);
    }
  }
  core.info(`Labels: ${result.created.length} created, ${result.updated.length} updated, ${result.unchanged.length} unchanged`);
  return result;
}

module.exports = { syncLabels };
