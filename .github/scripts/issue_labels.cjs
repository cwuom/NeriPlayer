'use strict';

// 维护约定：表单的分类标题和选项是解析协议，修改时同步更新映射与测试
// P0 为阻断性问题，P1 为严重问题，P2 为普通问题，P3 为低影响改进，均由维护者分诊
// 只处理首次打开，普通分组人工优先，编辑和重开后的标签由维护者管理
const FORM_FIELDS = {
  area: {
    title: '涉及模块 / Affected area',
    options: {
      '账号与登录 / Accounts and login': ['area: auth'],
      '播放与音频 / Playback and audio': ['area: playback'],
      '车载与 Android Auto / Automotive and Android Auto': ['area: automotive'],
      '蓝牙连接与控制 / Bluetooth connectivity and controls': ['area: bluetooth'],
      '自动换源 / Automatic source switching': ['area: source-switching'],
      '通知与锁屏控制 / Notifications and lock screen controls': ['area: media-controls'],
      '音频焦点与中断 / Audio focus and interruptions': ['area: audio-focus'],
      'USB 独占播放 / USB exclusive playback': ['area: usb-exclusive'],
      '网络与代理 / Network and proxy': ['area: network'],
      '歌词 / Lyrics': ['area: lyrics'],
      '下载与存储 / Downloads and storage': ['area: download'],
      '本地音乐与歌单 / Local music and playlists': ['area: local-media'],
      '云同步与备份 / Cloud sync and backup': ['area: sync'],
      '一起听 / Listen Together': ['area: listen-together'],
      '界面与交互 / UI and interaction': ['area: ui'],
      '性能与资源占用 / Performance and resource usage': ['area: performance'],
      '文档与翻译 / Documentation and translation': ['area: documentation', 'documentation'],
      '构建、CI 与发布 / Build and CI and release': ['area: ci'],
      '安装、启动与更新 / Installation and startup and update': ['area: startup'],
      '其他或不确定 / Other or unsure': [],
    },
  },
  platform: {
    title: '涉及平台 / Affected platforms',
    options: {
      '网易云音乐 / NetEase Cloud Music': ['platform: netease'],
      Bilibili: ['platform: bilibili'],
      'YouTube Music': ['platform: youtube'],
      '本地音频或下载文件 / Local audio or downloaded files': [],
      '不涉及在线平台 / Not related to online platforms': [],
      '其他或不确定 / Other or unsure': [],
    },
  },
};

// 设备与播放主题可与维护者选定的主模块并存，其余分类以已有标签和表单选择为准
const TOPIC_AREAS = new Set([
  'area: automotive', 'area: bluetooth', 'area: source-switching', 'area: media-controls', 'area: audio-focus',
]);
const NARRATIVE_SECTIONS = new Set([
  '问题描述 / Problem description', '复现步骤 / Steps to reproduce',
  '预期行为 / Expected behaviour', '实际行为 / Actual behaviour',
  'USB 音频设备与播放设置（如适用） / USB audio device and playback settings (if applicable)',
  '播放内容与相关设置（如适用） / Playback item and related settings (if applicable)',
  '补充说明（可选） / Additional information (optional)',
  '功能背景与需求 / Motivation', '期望的功能 / Proposed feature',
  '替代方案 / Alternatives considered', '补充材料 / Additional context',
  '建议概述 / Suggestion overview', '建议的背景与原因 / Background & Reason for Suggestion',
  '建议的具体实施方案 / Detailed Implementation Plan (Optional)', '预期效果 / Expected Effect',
  '关联的现有功能（可选） / Associated Existing Features (Optional)',
  '补充材料（可选） / Supplementary Materials (Optional)',
]);

// 规则只声明固定词和目标标签，不包含优先级、确认状态或可执行正文
const KEYWORD_RULES = [
  { id: 'automotive', label: 'area: automotive', pattern: /车载|车机|\bandroid auto\b|\bandroid automotive\b|\bautomotive\b|\bcar (?:stereo|head unit|display|playback)\b/i },
  { id: 'bluetooth', label: 'area: bluetooth', pattern: /蓝牙|\bbluetooth\b|\bavrcp\b|\ba2dp\b|\bble audio\b/i },
  { id: 'source-switching', label: 'area: source-switching', pattern: /换源|音源(?:切换|回退)|\b(?:automatic|auto)[ -]source[ -]switching\b|\bsource[ -](?:switching|fallback)\b/i },
  { id: 'media-controls', label: 'area: media-controls', pattern: /通知(?:栏|控制|播放|媒体)|锁屏(?:控制|播放|媒体)|媒体(?:通知|控制)|\bmedia (?:notifications?|controls?)\b|\bnotification (?:controls?|playback)\b|\block[ -]screen (?:controls?|playback|media)\b/i },
  { id: 'audio-focus', label: 'area: audio-focus', pattern: /音频焦点|来电(?:打断|中断|暂停)|电话(?:打断|中断)|\baudio[ -]focus\b|\b(?:call|audio) interruptions?\b|\bducking\b/i },
  { id: 'auth', label: 'area: auth', pattern: /登录|登陆|账号|\blog[ -]?in\b|\bsign[ -]?in\b|\bauthentication\b|\boauth\b/i },
  { id: 'playback', label: 'area: playback', pattern: /播放|音频|音效|均衡器|淡入淡出|\bplayback\b|\bplayer\b|\baudio\b|\bequalizer\b|\bcrossfade\b/i },
  { id: 'usb-exclusive', label: 'area: usb-exclusive', pattern: /usb\s*(?:dac|独占|exclusive|audio)|\buac[12](?:\.0)?\b|比特完美|\bbit[- ]perfect\b/i },
  { id: 'network', label: 'area: network', pattern: /代理|网络|联网|\bproxy\b|\bnetwork\b|\bdns\b|\bvpn\b/i },
  { id: 'lyrics', label: 'area: lyrics', pattern: /歌词|逐字|\blyrics?\b|\blrc\b|\byrc\b|\bttml\b|\blrclib\b|\blyricon\b|\bsuperlyric\b/i },
  { id: 'download', label: 'area: download', pattern: /下载|续传|\bdownloads?\b|\bdownloading\b/i },
  { id: 'local-media', label: 'area: local-media', pattern: /本地(?:音乐|音频|曲库)|歌单|媒体库|\blocal (?:music|audio|library)\b|\bplaylists?\b|\bmedia library\b/i },
  { id: 'sync', label: 'area: sync', pattern: /云同步|歌单同步|github\s*同步|备份|\bwebdav\b|\bbackups?\b|\bsync(?:hronization)?\b/i },
  { id: 'listen-together', label: 'area: listen-together', pattern: /一起听|\blisten[ -]together\b/i },
  { id: 'ui', label: 'area: ui', pattern: /界面|主题|布局|手势|\buser interface\b|\bui\b|\blayout\b|\btheme\b/i },
  { id: 'performance', label: 'area: performance', pattern: /卡顿|耗电|内存|发热|掉帧|\bperformance\b|\bstutter(?:ing)?\b|\bbattery drain\b|\bmemory (?:usage|leak)\b/i },
  { id: 'documentation', label: 'area: documentation', pattern: /文档|界面翻译|本地化|\bdocumentation\b|\breadme\b|\blocalization\b/i },
  { id: 'ci', label: 'area: ci', pattern: /构建|编译|持续集成|\bci\b|\bgradle\b|\bgithub actions\b|\bworkflow\b/i },
  { id: 'startup', label: 'area: startup', pattern: /启动|安装|闪退|安全模式|\bstartup\b|\binstall(?:ation|ing)?\b|\blaunch(?:ing)?\b|\bsafe mode\b/i },
  { id: 'netease', label: 'platform: netease', pattern: /网易云|\bnetease(?:[ -]cloud[ -]music)?\b|\bcloudmusic\b/i },
  { id: 'bilibili', label: 'platform: bilibili', pattern: /哔哩哔哩|\bb\s*站|\bbilibili\b/i },
  { id: 'youtube', label: 'platform: youtube', pattern: /\byoutube(?:[ -]music)?\b|\bytmusic\b/i },
];

const HEADING = /^ {0,3}#{1,6}\s+(.+?)\s*#*\s*$/;
const LOG_HEADING = /日志|堆栈|诊断|\blogs?\b|\blogcat\b|\bstack traces?\b|\bdiagnostics?\b/i;
const LOG_LINE = /^\s*(?:\d{4}-\d{2}-\d{2}[T ]|\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}|(?:TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\b|[VDIWEF]\/|[\w.$]+(?:Exception|Error)\b|at\s+[\w.$]+\(|Caused by:)/i;

function narrativeLines(body) {
  const lines = [];
  let fence = null;
  let quote = false;
  const markdown = String(body ?? '').replace(/<!--[\s\S]*?(?:-->|$)/g, '')
    .replace(/<(pre|code|details)\b[^>]*>[\s\S]*?<\/\1>/gi, '');
  for (const line of markdown.split(/\r?\n/)) {
    if (/^\s*>/.test(line)) { quote = true; continue; }
    if (quote) {
      if (!line.trim()) quote = false;
      continue;
    }
    const marker = line.match(/^ {0,3}(`{3,}|~{3,})/);
    if (marker) {
      if (!fence) fence = marker[1];
      else if (marker[1][0] === fence[0] && marker[1].length >= fence.length) fence = null;
      continue;
    }
    if (fence) continue;
    if (/^(?: {4}|\t)/.test(line) || /^\s*[-*+]\s+\[[ xX]\]/.test(line)) continue;
    lines.push(line.replace(/(`+)[^`]*?\1/g, '').replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1')
      .replace(/https?:\/\/\S+/gi, ''));
  }
  return lines;
}

function readSections(body) {
  let current = [];
  const sections = new Map([['', [current]]]);
  for (const line of narrativeLines(body)) {
    const heading = line.match(HEADING);
    if (heading) {
      const values = sections.get(heading[1]) ?? [];
      current = [];
      values.push(current);
      sections.set(heading[1], values);
    } else if (current) {
      current.push(line);
    }
  }
  return sections;
}

function keywordNarrative(issue, sections) {
  const isForm = [...NARRATIVE_SECTIONS, ...Object.values(FORM_FIELDS).map(field => field.title)]
    .some(heading => sections.has(heading));
  const content = narrativeLines(issue.title).filter(line => !HEADING.test(line) && !LOG_LINE.test(line));
  for (const [heading, values] of sections) {
    if (values.length !== 1 || LOG_HEADING.test(heading) || (isForm && !NARRATIVE_SECTIONS.has(heading))) continue;
    content.push(...values[0].filter(line => !LOG_LINE.test(line)));
  }
  return content.join('\n');
}

function collectIssueLabelDecisions(issue) {
  const current = new Set((issue.labels ?? []).map(label =>
    (typeof label === 'string' ? label : label.name).toLowerCase()));
  const hasGroup = prefix => [...current].some(name => name.startsWith(prefix));
  const sections = readSections(issue.body);
  const wanted = new Map();
  const add = (label, rule) => { if (!wanted.has(label)) wanted.set(label, rule); };
  if (!current.has('bug') && !current.has('enhancement') && !current.has('question')) {
    const types = [
      ['问题描述 / Problem description', 'bug'],
      ['功能背景与需求 / Motivation', 'enhancement'],
      ['建议概述 / Suggestion overview', 'enhancement'],
    ].filter(([heading]) => sections.get(heading)?.length === 1);
    if (types.length === 1) add(types[0][1], 'form:type');
  }
  if (!hasGroup('status: ')) add('status: needs-triage', 'default:triage');
  for (const [group, field] of Object.entries(FORM_FIELDS)) {
    const values = sections.get(field.title);
    if (values?.length !== 1) continue;
    for (const choice of values[0].join('\n').split(/,\s*|\n/).map(value => value.trim())) {
      if (!Object.hasOwn(field.options, choice)) continue;
      for (const label of field.options[choice]) {
        if (!hasGroup(`${group}: `) || (group === 'area' && TOPIC_AREAS.has(label))) add(label, `form:${group}`);
      }
    }
  }
  const hasArea = hasGroup('area: ') || [...wanted.keys()].some(label => label.startsWith('area: '));
  const hasPlatform = hasGroup('platform: ') || [...wanted.keys()].some(label => label.startsWith('platform: '));
  const narrative = keywordNarrative(issue, sections);
  for (const rule of KEYWORD_RULES) {
    if ((rule.label.startsWith('area: ') && hasArea && !TOPIC_AREAS.has(rule.label)) ||
        (rule.label.startsWith('platform: ') && hasPlatform) || !rule.pattern.test(narrative)) continue;
    add(rule.label, `keyword:${rule.id}`);
    if (rule.label === 'area: documentation') add('documentation', `keyword:${rule.id}`);
  }
  return [...wanted].filter(([label]) => !current.has(label.toLowerCase())).map(([label, rule]) => ({ label, rule }));
}

function collectIssueLabels(issue) {
  return collectIssueLabelDecisions(issue).map(decision => decision.label);
}

async function applyIssueLabels({ github, context, core }) {
  const { action, issue } = context.payload;
  if (action !== 'opened' || !issue || issue.pull_request) return [];
  const target = { ...context.repo, issue_number: issue.number };
  // 读取当前标签，避免排队期间已完成的人工分诊被旧事件覆盖
  const { data: latest } = await github.rest.issues.get(target);
  const decisions = collectIssueLabelDecisions(latest);
  const labels = decisions.map(decision => decision.label);
  if (labels.length) await github.rest.issues.addLabels({ ...target, labels });
  core.info(`Issue #${issue.number}: added ${labels.length} labels; ${decisions.map(item => `${item.label} [${item.rule}]`).join(', ')}`);
  return labels;
}

module.exports = { FORM_FIELDS, KEYWORD_RULES, collectIssueLabels, applyIssueLabels };
