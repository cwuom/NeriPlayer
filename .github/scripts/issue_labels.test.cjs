'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const { collectIssueLabels, applyIssueLabels, FORM_FIELDS, KEYWORD_RULES } = require('./issue_labels.cjs');
const { syncLabels } = require('./sync_labels.cjs');

const areaHeading = '涉及模块 / Affected area';
const platformHeading = '涉及平台 / Affected platforms';
const bugBody = (area, platform = '不涉及在线平台 / Not related to online platforms') =>
  `### 问题描述 / Problem description\n\n无法完成操作\n\n### ${areaHeading}\n\n${area}\n\n### ${platformHeading}\n\n${platform}`;

test('bug 表单按明确选择补充类型、分诊、模块和平台标签', () => {
  assert.deepEqual(collectIssueLabels({ body: bugBody('播放与音频 / Playback and audio', '网易云音乐 / NetEase Cloud Music, Bilibili'), labels: [] }),
    ['bug', 'status: needs-triage', 'area: playback', 'platform: netease', 'platform: bilibili']);
});

test('功能与建议表单使用 enhancement', () => {
  for (const heading of ['功能背景与需求 / Motivation', '建议概述 / Suggestion overview']) {
    assert.deepEqual(collectIssueLabels({ body: `### ${heading}\n\n希望改进体验`, labels: [] }),
      ['enhancement', 'status: needs-triage']);
  }
});

test('精简功能请求不填平台和诊断信息时仍按多选模块分类', () => {
  const body = '### 功能背景与需求 / Motivation\n\n希望改进使用体验' +
    '\n\n### 期望的功能 / Proposed feature\n\n支持自定义显示方式' +
    `\n\n### ${areaHeading}\n\n歌词 / Lyrics, 界面与交互 / UI and interaction`;
  for (const platform of ['', `\n\n### ${platformHeading}\n\n_No response_`]) {
    assert.deepEqual(collectIssueLabels({ body: body + platform, labels: [] }),
      ['enhancement', 'status: needs-triage', 'area: lyrics', 'area: ui']);
    assert.deepEqual(collectIssueLabels({ body: body + platform, labels: ['enhancement', 'P1', 'status: confirmed'] }),
      ['area: lyrics', 'area: ui']);
  }
});

test('精简功能请求仍支持可选平台和正文平台识别', () => {
  const body = '### 功能背景与需求 / Motivation\n\n希望支持 Bilibili 视频播放' +
    `\n\n### ${areaHeading}\n\n播放与音频 / Playback and audio`;
  assert.deepEqual(collectIssueLabels({ body, labels: [] }),
    ['enhancement', 'status: needs-triage', 'area: playback', 'platform: bilibili']);
  assert.deepEqual(collectIssueLabels({ body: body + `\n\n### ${platformHeading}\n\nYouTube Music`, labels: [] }),
    ['enhancement', 'status: needs-triage', 'area: playback', 'platform: youtube']);
});

test('已有 question 标签阻止从其他表单标题添加 bug 或 enhancement', () => {
  for (const body of [bugBody('其他或不确定 / Other or unsure'), '### 功能背景与需求 / Motivation\n\n需要帮助']) {
    assert.deepEqual(collectIssueLabels({ body, labels: ['question', 'P1', 'status: confirmed'] }), []);
  }
});

test('未知选项、未回答和继承属性名不会生成标签', () => {
  assert.deepEqual(collectIssueLabels({ body: bugBody('P0, __proto__, constructor', '_No response_'), labels: [] }),
    ['bug', 'status: needs-triage']);
});

test('日志和分类字段中的关键词与恶意代码不会参与推断或执行', () => {
  global.issueLabelsInjected = false;
  const body = bugBody('${global.issueLabelsInjected = true}', 'platform: youtube') +
    '\n\n### 日志 / Logs\n\nP0 crash urgent area: auth\n```js\n### 涉及模块 / Affected area\n\n账号与登录 / Accounts and login\n```';
  assert.deepEqual(collectIssueLabels({ body, labels: [] }), ['bug', 'status: needs-triage']);
  assert.equal(global.issueLabelsInjected, false);
  delete global.issueLabelsInjected;
});

test('围栏内的伪表单和重复字段不会伪造自动分类', () => {
  assert.deepEqual(collectIssueLabels({ body: '```\n' + bugBody('歌词 / Lyrics') + '\n```', labels: [] }),
    ['status: needs-triage']);
  assert.deepEqual(collectIssueLabels({ body: bugBody('歌词 / Lyrics') + `\n\n### ${areaHeading}\n\n账号与登录 / Accounts and login`, labels: [] }),
    ['bug', 'status: needs-triage']);
});

test('保留已有人工分诊分组和优先级，不新增冲突分类', () => {
  assert.deepEqual(collectIssueLabels({ body: bugBody('歌词 / Lyrics', '网易云音乐 / NetEase Cloud Music'),
    labels: [{ name: 'enhancement' }, { name: 'status: confirmed' }, { name: 'area: ui' }, { name: 'platform: youtube' }, { name: 'P1' }, { name: 'custom' }] }), []);
});

test('已存在的标签不重复添加，未分诊分组仍可补齐', () => {
  assert.deepEqual(collectIssueLabels({ body: bugBody('歌词 / Lyrics'), labels: ['BUG', 'custom'] }),
    ['status: needs-triage', 'area: lyrics']);
});

test('多选模块去重并给文档选项添加现有 documentation 标签', () => {
  assert.deepEqual(collectIssueLabels({ body: bugBody('歌词 / Lyrics, 文档与翻译 / Documentation and translation, 歌词 / Lyrics'), labels: [] }),
    ['bug', 'status: needs-triage', 'area: lyrics', 'area: documentation', 'documentation']);
});

test('所有模块选项都有显式映射，优先级不由表单自动设置', () => {
  const cases = [
    ['账号与登录 / Accounts and login', ['area: auth']],
    ['播放与音频 / Playback and audio', ['area: playback']],
    ['车载与 Android Auto / Automotive and Android Auto', ['area: automotive']],
    ['蓝牙连接与控制 / Bluetooth connectivity and controls', ['area: bluetooth']],
    ['自动换源 / Automatic source switching', ['area: source-switching']],
    ['通知与锁屏控制 / Notifications and lock screen controls', ['area: media-controls']],
    ['音频焦点与中断 / Audio focus and interruptions', ['area: audio-focus']],
    ['USB 独占播放 / USB exclusive playback', ['area: usb-exclusive']],
    ['网络与代理 / Network and proxy', ['area: network']],
    ['歌词 / Lyrics', ['area: lyrics']],
    ['下载与存储 / Downloads and storage', ['area: download']],
    ['本地音乐与歌单 / Local music and playlists', ['area: local-media']],
    ['云同步与备份 / Cloud sync and backup', ['area: sync']],
    ['一起听 / Listen Together', ['area: listen-together']],
    ['界面与交互 / UI and interaction', ['area: ui']],
    ['性能与资源占用 / Performance and resource usage', ['area: performance']],
    ['文档与翻译 / Documentation and translation', ['area: documentation', 'documentation']],
    ['构建、CI 与发布 / Build and CI and release', ['area: ci']],
    ['安装、启动与更新 / Installation and startup and update', ['area: startup']],
    ['其他或不确定 / Other or unsure', []],
  ];
  for (const [choice, labels] of cases) {
    assert.deepEqual(collectIssueLabels({ body: bugBody(choice), labels: [] }), ['bug', 'status: needs-triage', ...labels], choice);
  }
  assert.equal(cases.length, Object.keys(FORM_FIELDS.area.options).length);
});

test('所有平台选项都有显式映射', () => {
  const cases = [
    ['网易云音乐 / NetEase Cloud Music', ['platform: netease']],
    ['Bilibili', ['platform: bilibili']],
    ['YouTube Music', ['platform: youtube']],
    ['本地音频或下载文件 / Local audio or downloaded files', []],
    ['不涉及在线平台 / Not related to online platforms', []],
    ['其他或不确定 / Other or unsure', []],
  ];
  for (const [choice, labels] of cases) {
    assert.deepEqual(collectIssueLabels({ body: bugBody('其他或不确定 / Other or unsure', choice), labels: [] }), ['bug', 'status: needs-triage', ...labels], choice);
  }
  assert.equal(cases.length, Object.keys(FORM_FIELDS.platform.options).length);
});

function dropdownFields(source) {
  return source.split(/\n(?=  - type: )/).filter(block => block.startsWith('  - type: dropdown')).map(block => ({
    title: block.match(/^      label: (.+)$/m)?.[1],
    choices: [...block.matchAll(/^        - (.+)$/gm)].map(match => match[1]),
  }));
}

test('三个表单的分类字段与脚本协议一致，所有自动标签存在于清单', () => {
  for (const filename of ['bug-report.yml', 'feature-request.yml', 'suggestion-submission.yml']) {
    const source = fs.readFileSync(path.join(__dirname, '..', 'ISSUE_TEMPLATE', filename), 'utf8');
    const fields = dropdownFields(source);
    for (const field of Object.values(FORM_FIELDS)) {
      const matching = fields.filter(item => item.title === field.title);
      assert.equal(matching.length, 1, `${filename}: ${field.title}`);
      assert.deepEqual(matching[0].choices, Object.keys(field.options), filename);
    }
  }
  const definitions = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'labels.json'), 'utf8'));
  const names = new Set(definitions.map(label => label.name));
  for (const field of Object.values(FORM_FIELDS)) {
    for (const labels of Object.values(field.options)) {
      for (const name of labels) assert.ok(names.has(name), name);
    }
  }
  for (const name of ['bug', 'enhancement', 'question', 'status: needs-triage', 'P0', 'P1', 'P2', 'P3']) assert.ok(names.has(name), name);
  for (const rule of KEYWORD_RULES) assert.ok(names.has(rule.label), rule.label);
});

test('同一车载蓝牙报告在播放分类上补充两个设备子类', () => {
  const body = bugBody('播放与音频 / Playback and audio').replace('无法完成操作', 'Android Auto 车机蓝牙断开后无法恢复');
  assert.deepEqual(collectIssueLabels({ title: '[BUG] 车载连接问题', body, labels: [] }),
    ['bug', 'status: needs-triage', 'area: playback', 'area: automotive', 'area: bluetooth']);
});

test('英文实际叙述识别换源、通知控制和音频焦点', () => {
  const body = bugBody('播放与音频 / Playback and audio').replace('无法完成操作',
    'Automatic source switching stops working. Notification controls disappear; audio focus does not recover after a call.');
  assert.deepEqual(collectIssueLabels({ body, labels: [] }),
    ['bug', 'status: needs-triage', 'area: playback', 'area: source-switching', 'area: media-controls', 'area: audio-focus']);
});

test('无表单时标题与实际正文可识别明确模块和平台', () => {
  assert.deepEqual(collectIssueLabels({ title: 'Bilibili download tasks stay pending', body: 'Downloads never finish.', labels: [] }),
    ['status: needs-triage', 'area: download', 'platform: bilibili']);
  assert.deepEqual(collectIssueLabels({ title: 'WebDAV backup fails with NetEase Cloud Music', body: '', labels: [] }),
    ['status: needs-triage', 'area: sync', 'platform: netease']);
});

test('表单选择其他或不确定时仍从实际叙述补充模块和平台', () => {
  const body = bugBody('其他或不确定 / Other or unsure', '其他或不确定 / Other or unsure')
    .replace('无法完成操作', 'Bilibili 下载任务始终没有完成');
  assert.deepEqual(collectIssueLabels({ body, labels: [] }),
    ['bug', 'status: needs-triage', 'area: download', 'platform: bilibili']);
});

test('已有模块的明确叙述词可回退分类', () => {
  for (const [title, label] of [
    ['登录二维码失效', 'area: auth'], ['播放音效失效', 'area: playback'],
    ['USB DAC 没有输出', 'area: usb-exclusive'], ['代理无法连接', 'area: network'],
    ['逐字歌词时间错误', 'area: lyrics'], ['本地音乐歌单缺失', 'area: local-media'],
    ['Listen Together room disconnected', 'area: listen-together'], ['界面布局不正确', 'area: ui'],
    ['内存占用过高', 'area: performance'], ['README 文档缺少说明', 'area: documentation'],
    ['Gradle 编译失败', 'area: ci'], ['安装后无法启动', 'area: startup'],
    ['YouTube Music request fails', 'platform: youtube'],
  ]) assert.ok(collectIssueLabels({ title, body: '', labels: [] }).includes(label), `${title}: ${label}`);
});

test('模板固定标题、关闭状态、安装来源和确认项不会推断功能标签', () => {
  const body = bugBody('其他或不确定 / Other or unsure') +
    '\n\n### 是否开启自动换源 / Automatic source switching enabled\n\n关闭 / Disabled' +
    '\n\n### 是否开启 USB 独占播放 / Is USB exclusive playback enabled\n\n未开启 / Disabled' +
    '\n\n### 复现时的音频输出设备 / Audio output during reproduction\n\n蓝牙耳机或音箱 / Bluetooth device' +
    '\n\n### 安装来源 / Distribution\n\nGitHub Actions CI' +
    '\n\n### 提交前确认 / Confirmation\n\n- [x] 我已在最新 Release 或 CI 构建中复现问题';
  assert.deepEqual(collectIssueLabels({ title: '[BUG] ', body, labels: [] }), ['bug', 'status: needs-triage']);
});

test('围栏、注释、引用、内联代码和日志行不触发关键词', () => {
  const body = bugBody('其他或不确定 / Other or unsure').replace('无法完成操作', [
    '```text', 'Bluetooth Android Auto WebDAV', '```',
    '<!-- YouTube lyrics -->', '> 自动换源、音频焦点、蓝牙', '',
    '`USB DAC`', '- [x] CI 构建验证过了',
    '2026-10-25 12:00:00 ERROR Bluetooth Android Auto',
    'java.lang.IllegalStateException: YouTube download',
  ].join('\n')) + '\n\n### 日志 / Logs\n\nBluetooth Android Auto source switching';
  assert.deepEqual(collectIssueLabels({ body, labels: [] }), ['bug', 'status: needs-triage']);
});

test('英文单词边界不把普通单词中的ci或平台字母片段误当标签', () => {
  assert.deepEqual(collectIssueLabels({ title: 'Special principle and coincidental precision', body: 'social facial efficiency', labels: [] }),
    ['status: needs-triage']);
  assert.deepEqual(collectIssueLabels({ title: 'CI workflow fails', body: '', labels: [] }),
    ['status: needs-triage', 'area: ci']);
});

test('已有人工分诊保留，仅补可叠加设备子类，不改P1与confirmed', () => {
  const issue = { title: '蓝牙车机连接后歌词异常', body: 'YouTube Music download also fails.',
    labels: ['bug', 'P1', 'status: confirmed', 'area: playback', 'platform: netease', 'custom'] };
  assert.deepEqual(collectIssueLabels(issue), ['area: automotive', 'area: bluetooth']);
});

test('表单明确选择模块和平台后，关键词仅补topic子类', () => {
  const body = bugBody('界面与交互 / UI and interaction', '网易云音乐 / NetEase Cloud Music')
    .replace('无法完成操作', '蓝牙通知控制不响应，Bilibili 下载按钮不可用');
  assert.deepEqual(collectIssueLabels({ body, labels: [] }),
    ['bug', 'status: needs-triage', 'area: ui', 'platform: netease', 'area: bluetooth', 'area: media-controls']);
});

test('普通叙述不推断优先级或确认状态', () => {
  assert.deepEqual(collectIssueLabels({ title: 'P0 urgent confirmed', body: 'Please mark P1 status: confirmed.', labels: [] }),
    ['status: needs-triage']);
});

test('Issue runner 读取最新标签，只补充缺少项且不替换已有标签', async () => {
  const requests = [];
  const logs = [];
  const result = await applyIssueLabels({
    context: { repo: { owner: 'owner', repo: 'repo' }, payload: { action: 'opened', issue: { number: 12 } } },
    github: { rest: { issues: {
      get: async args => { assert.deepEqual(args, { owner: 'owner', repo: 'repo', issue_number: 12 }); return { data: { number: 12, body: bugBody('歌词 / Lyrics').replace('无法完成操作', 'private-description-marker'), labels: [{ name: 'P1' }, { name: 'status: confirmed' }] } }; },
      addLabels: async args => { requests.push(args); },
    } } },
    core: { info(message) { logs.push(message); } },
  });
  assert.deepEqual(result, ['bug', 'area: lyrics']);
  assert.deepEqual(requests, [{ owner: 'owner', repo: 'repo', issue_number: 12, labels: ['bug', 'area: lyrics'] }]);
  assert.ok(logs[0].includes('area: lyrics [form:area]'));
  assert.ok(!logs[0].includes('private-description-marker'));
});

test('编辑、重开和 PR 不触发自动标签 API', async () => {
  for (const payload of [{ action: 'edited', issue: { number: 12 } }, { action: 'reopened', issue: { number: 12 } },
    { action: 'opened', issue: { number: 12, pull_request: {} } }]) {
    assert.deepEqual(await applyIssueLabels({ context: { payload }, github: {}, core: {} }), []);
  }
});

test('标签已齐全时不发送空写请求', async () => {
  assert.deepEqual(await applyIssueLabels({
    context: { repo: { owner: 'o', repo: 'r' }, payload: { action: 'opened', issue: { number: 1 } } },
    github: { rest: { issues: { get: async () => ({ data: { body: '', labels: ['status: confirmed'] } }) } } },
    core: { info() {} },
  }), []);
});

test('同步标签只创建或更新清单中的项，未知远端标签被保留', async () => {
  const writes = [];
  const api = {
    listLabelsForRepo() {},
    createLabel: async args => { writes.push(['create', args]); },
    updateLabel: async args => { writes.push(['update', args]); },
  };
  const result = await syncLabels({
    context: { repo: { owner: 'o', repo: 'r' } },
    github: { rest: { issues: api }, paginate: async (endpoint, args) => {
      assert.equal(endpoint, api.listLabelsForRepo);
      assert.deepEqual(args, { owner: 'o', repo: 'r', per_page: 100 });
      return [{ name: 'bug', color: 'd73a4a', description: 'existing' }, { name: 'P1', color: 'ffffff', description: '' }, { name: 'custom', color: '123456', description: 'keep' }];
    } },
    core: { info() {} },
  }, [{ name: 'bug', color: 'd73a4a', description: 'existing' }, { name: 'P1', color: 'b60205', description: 'high' }, { name: 'P2', color: 'fbca04', description: 'normal' }]);
  assert.deepEqual(result, { created: ['P2'], updated: ['P1'], unchanged: ['bug'] });
  assert.deepEqual(writes, [
    ['update', { owner: 'o', repo: 'r', name: 'P1', new_name: 'P1', color: 'b60205', description: 'high' }],
    ['create', { owner: 'o', repo: 'r', name: 'P2', color: 'fbca04', description: 'normal' }],
  ]);
});

test('无效或重复标签配置在调用 GitHub 前拒绝', async () => {
  for (const labels of [[{ name: 'bad', color: 'red' }], [{ name: 'P1', color: '123456' }, { name: 'p1', color: '654321' }]]) {
    await assert.rejects(syncLabels({ github: {}, context: {}, core: {} }, labels), /label/i);
  }
});

test('标签配置的额外属性不能改变同步目标仓库', async () => {
  const writes = [];
  await syncLabels({
    context: { repo: { owner: 'o', repo: 'r' } },
    github: { paginate: async () => [], rest: { issues: {
      listLabelsForRepo() {}, createLabel: async args => { writes.push(args); },
    } } },
    core: { info() {} },
  }, [{ name: 'P1', color: 'b60205', description: 'high', owner: 'other', repo: 'other' }]);
  assert.deepEqual(writes, [{ owner: 'o', repo: 'r', name: 'P1', color: 'b60205', description: 'high' }]);
});
