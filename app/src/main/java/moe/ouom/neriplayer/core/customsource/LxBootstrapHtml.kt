package moe.ouom.neriplayer.core.customsource

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.core.customsource/LxBootstrapHtml
 * Created: 2026/7/26
 *
 * 构造注入 WebView 的引导 HTML,内含 LX Music 兼容运行时(globalThis.lx)。
 */

import org.json.JSONObject

/**
 * 生成引导 HTML。用户脚本以 <script> 文本形式内联,前面先建立 lx 运行时。
 * 使用 base64 内联脚本,避免脚本中的字符干扰 HTML 解析。
 */
internal fun buildBootstrapHtml(userScript: String): String {
    val meta = CustomSourceMetadataParser.parse(userScript)
    val infoJson = JSONObject().apply {
        put("name", meta.name)
        put("version", meta.version)
        put("author", meta.author)
        put("description", meta.description)
    }
    val infoJsonLiteral = JSONObject.quote(infoJson.toString())
    return buildBootstrapHtml(userScript, infoJsonLiteral)
}

private fun buildBootstrapHtml(userScript: String, infoJsonLiteral: String): String {
    val scriptJsonString = JSONObject.quote(userScript) // 安全转义为 JS 字符串字面量
    return """
<!DOCTYPE html>
<html>
<head><meta charset="utf-8"></head>
<body>
<script>
// 先注入脚本原文,供 currentScriptInfo.rawScript 使用(部分源脚本要对原文做 md5 完整性校验)
window.__NERI_RAW_SCRIPT = $scriptJsonString;
// 注入脚本头部元数据(name/version/author/description),供 currentScriptInfo 使用
try { window.__NERI_SCRIPT_INFO = JSON.parse($infoJsonLiteral); } catch (e) { window.__NERI_SCRIPT_INFO = {}; }
</script>
<script>
$LX_RUNTIME_JS
</script>
<script>
(function () {
  try {
    var __userScriptSource = window.__NERI_RAW_SCRIPT;
    // 以 Function 方式执行用户脚本,提供隔离作用域
    var __run = new Function(__userScriptSource);
    __run();
  } catch (e) {
    try { NeriBridge.onInitError('脚本执行异常: ' + (e && e.message ? e.message : e)); } catch (_) {}
  }
})();
</script>
</body>
</html>
    """.trimIndent()
}

/**
 * LX Music 自定义源兼容运行时。
 * 实现常用子集: EVENT_NAMES / on / send / request / utils(buffer, crypto) / env / version。
 */
private val LX_RUNTIME_JS = """
(function () {
  'use strict';

  var EVENT_NAMES = {
    request: 'request',
    inited: 'inited',
    updateAlert: 'updateAlert'
  };

  var requestHandlers = [];
  var httpCallbacks = {};
  var httpSeq = 0;

  function nativeLog(msg) {
    try { NeriBridge.log(String(msg)); } catch (e) {}
  }
  function nativeErr(msg) {
    try { NeriBridge.onScriptError(String(msg)); } catch (e) {}
  }

  // ---- 紧凑 MD5 实现(返回小写 hex),供 lx.utils.crypto.md5 使用 ----
  function __md5(str) {
    function rl(n, c) { return (n << c) | (n >>> (32 - c)); }
    function au(x, y) {
      var l = (x & 0xFFFF) + (y & 0xFFFF);
      var m = (x >> 16) + (y >> 16) + (l >> 16);
      return (m << 16) | (l & 0xFFFF);
    }
    function cmn(q, a, b, x, s, t) { return au(rl(au(au(a, q), au(x, t)), s), b); }
    function ff(a, b, c, d, x, s, t) { return cmn((b & c) | (~b & d), a, b, x, s, t); }
    function gg(a, b, c, d, x, s, t) { return cmn((b & d) | (c & ~d), a, b, x, s, t); }
    function hh(a, b, c, d, x, s, t) { return cmn(b ^ c ^ d, a, b, x, s, t); }
    function ii(a, b, c, d, x, s, t) { return cmn(c ^ (b | ~d), a, b, x, s, t); }
    function sb(s) {
      var i, b = [];
      for (i = 0; i < s.length * 8; i += 8) b[i >> 5] |= (s.charCodeAt(i / 8) & 0xFF) << (i % 32);
      return b;
    }
    function bh(bin) {
      var hex = '0123456789abcdef', s = '', i;
      for (i = 0; i < bin.length * 4; i++) {
        s += hex.charAt((bin[i >> 2] >> ((i % 4) * 8 + 4)) & 0xF) + hex.charAt((bin[i >> 2] >> ((i % 4) * 8)) & 0xF);
      }
      return s;
    }
    function u8(s) { return unescape(encodeURIComponent(s)); }
    var x = sb(u8(str)), len = u8(str).length * 8;
    x[len >> 5] |= 0x80 << (len % 32);
    x[(((len + 64) >>> 9) << 4) + 14] = len;
    var a = 1732584193, b = -271733879, c = -1732584194, d = 271733878, i;
    for (i = 0; i < x.length; i += 16) {
      var oa = a, ob = b, oc = c, od = d;
      a = ff(a, b, c, d, x[i], 7, -680876936); d = ff(d, a, b, c, x[i + 1], 12, -389564586);
      c = ff(c, d, a, b, x[i + 2], 17, 606105819); b = ff(b, c, d, a, x[i + 3], 22, -1044525330);
      a = ff(a, b, c, d, x[i + 4], 7, -176418897); d = ff(d, a, b, c, x[i + 5], 12, 1200080426);
      c = ff(c, d, a, b, x[i + 6], 17, -1473231341); b = ff(b, c, d, a, x[i + 7], 22, -45705983);
      a = ff(a, b, c, d, x[i + 8], 7, 1770035416); d = ff(d, a, b, c, x[i + 9], 12, -1958414417);
      c = ff(c, d, a, b, x[i + 10], 17, -42063); b = ff(b, c, d, a, x[i + 11], 22, -1990404162);
      a = ff(a, b, c, d, x[i + 12], 7, 1804603682); d = ff(d, a, b, c, x[i + 13], 12, -40341101);
      c = ff(c, d, a, b, x[i + 14], 17, -1502002290); b = ff(b, c, d, a, x[i + 15], 22, 1236535329);
      a = gg(a, b, c, d, x[i + 1], 5, -165796510); d = gg(d, a, b, c, x[i + 6], 9, -1069501632);
      c = gg(c, d, a, b, x[i + 11], 14, 643717713); b = gg(b, c, d, a, x[i], 20, -373897302);
      a = gg(a, b, c, d, x[i + 5], 5, -701558691); d = gg(d, a, b, c, x[i + 10], 9, 38016083);
      c = gg(c, d, a, b, x[i + 15], 14, -660478335); b = gg(b, c, d, a, x[i + 4], 20, -405537848);
      a = gg(a, b, c, d, x[i + 9], 5, 568446438); d = gg(d, a, b, c, x[i + 14], 9, -1019803690);
      c = gg(c, d, a, b, x[i + 3], 14, -187363961); b = gg(b, c, d, a, x[i + 8], 20, 1163531501);
      a = gg(a, b, c, d, x[i + 13], 5, -1444681467); d = gg(d, a, b, c, x[i + 2], 9, -51403784);
      c = gg(c, d, a, b, x[i + 7], 14, 1735328473); b = gg(b, c, d, a, x[i + 12], 20, -1926607734);
      a = hh(a, b, c, d, x[i + 5], 4, -378558); d = hh(d, a, b, c, x[i + 8], 11, -2022574463);
      c = hh(c, d, a, b, x[i + 11], 16, 1839030562); b = hh(b, c, d, a, x[i + 14], 23, -35309556);
      a = hh(a, b, c, d, x[i + 1], 4, -1530992060); d = hh(d, a, b, c, x[i + 4], 11, 1272893353);
      c = hh(c, d, a, b, x[i + 7], 16, -155497632); b = hh(b, c, d, a, x[i + 10], 23, -1094730640);
      a = hh(a, b, c, d, x[i + 13], 4, 681279174); d = hh(d, a, b, c, x[i], 11, -358537222);
      c = hh(c, d, a, b, x[i + 3], 16, -722521979); b = hh(b, c, d, a, x[i + 6], 23, 76029189);
      a = hh(a, b, c, d, x[i + 9], 4, -640364487); d = hh(d, a, b, c, x[i + 12], 11, -421815835);
      c = hh(c, d, a, b, x[i + 15], 16, 530742520); b = hh(b, c, d, a, x[i + 2], 23, -995338651);
      a = ii(a, b, c, d, x[i], 6, -198630844); d = ii(d, a, b, c, x[i + 7], 10, 1126891415);
      c = ii(c, d, a, b, x[i + 14], 15, -1416354905); b = ii(b, c, d, a, x[i + 5], 21, -57434055);
      a = ii(a, b, c, d, x[i + 12], 6, 1700485571); d = ii(d, a, b, c, x[i + 3], 10, -1894986606);
      c = ii(c, d, a, b, x[i + 10], 15, -1051523); b = ii(b, c, d, a, x[i + 1], 21, -2054922799);
      a = ii(a, b, c, d, x[i + 8], 6, 1873313359); d = ii(d, a, b, c, x[i + 15], 10, -30611744);
      c = ii(c, d, a, b, x[i + 6], 15, -1560198380); b = ii(b, c, d, a, x[i + 13], 21, 1309151649);
      a = ii(a, b, c, d, x[i + 4], 6, -145523070); d = ii(d, a, b, c, x[i + 11], 10, -1120210379);
      c = ii(c, d, a, b, x[i + 2], 15, 718787259); b = ii(b, c, d, a, x[i + 9], 21, -343485551);
      a = au(a, oa); b = au(b, ob); c = au(c, oc); d = au(d, od);
    }
    return bh([a, b, c, d]);
  }

  // 捕获脚本内部的 console 输出与未捕获错误,便于诊断
  try {
    var _c = window.console || {};
    window.console = {
      log: function () { nativeLog(Array.prototype.join.call(arguments, ' ')); },
      info: function () { nativeLog(Array.prototype.join.call(arguments, ' ')); },
      warn: function () { nativeLog('WARN ' + Array.prototype.join.call(arguments, ' ')); },
      error: function () { nativeErr(Array.prototype.join.call(arguments, ' ')); },
      debug: function () { nativeLog(Array.prototype.join.call(arguments, ' ')); }
    };
  } catch (e) {}
  window.addEventListener('error', function (ev) {
    nativeErr('window.onerror: ' + (ev && ev.message ? ev.message : ev));
  });
  window.addEventListener('unhandledrejection', function (ev) {
    var r = ev && ev.reason;
    nativeErr('unhandledrejection: ' + (r && r.message ? r.message : r));
  });

  // ---- HTTP 桥接 ----
  // lx.request(url, options, callback) -> callback(err, resp, body)
  function lxRequest(url, options, callback) {
    options = options || {};
    var id = 'h' + (++httpSeq);
    httpCallbacks[id] = { cb: callback, done: false };

    var opt = {
      method: options.method || 'GET',
      headers: options.headers || {},
      body: undefined
    };
    // body 处理: 支持 form / json / 原始字符串
    if (options.form) {
      opt.body = Object.keys(options.form).map(function (k) {
        return encodeURIComponent(k) + '=' + encodeURIComponent(options.form[k]);
      }).join('&');
      if (!opt.headers['Content-Type']) opt.headers['Content-Type'] = 'application/x-www-form-urlencoded';
    } else if (options.body !== undefined && options.body !== null) {
      if (typeof options.body === 'object') {
        opt.body = JSON.stringify(options.body);
        if (!opt.headers['Content-Type']) opt.headers['Content-Type'] = 'application/json';
      } else {
        opt.body = String(options.body);
      }
    }

    try {
      NeriBridge.httpRequest(id, url, JSON.stringify(opt));
    } catch (e) {
      var entry = httpCallbacks[id];
      if (entry && !entry.done) {
        entry.done = true;
        delete httpCallbacks[id];
        if (callback) callback(e.message || 'bridge error', null, null);
      }
    }

    // 返回一个可 abort 的句柄(部分脚本会用到)
    return {
      abort: function () { try { NeriBridge.httpAbort(id); } catch (e) {} }
    };
  }

  window.__neri_httpCallback = function (payloadStr) {
    var payload;
    try { payload = JSON.parse(payloadStr); } catch (e) { return; }
    var entry = httpCallbacks[payload.requestId];
    if (!entry || entry.done) return;
    entry.done = true;
    delete httpCallbacks[payload.requestId];

    if (payload.error) {
      if (entry.cb) entry.cb(payload.error, null, null);
      return;
    }
    var resp = payload.response || {};
    var body = resp.body;
    // 尝试把 JSON body 解析成对象,兼容 LX 脚本对 resp.body 直接取字段
    var parsedBody = body;
    try {
      var ct = (resp.headers && (resp.headers['content-type'] || resp.headers['Content-Type'])) || '';
      if (ct.indexOf('json') >= 0 || (typeof body === 'string' && (body.charAt(0) === '{' || body.charAt(0) === '['))) {
        parsedBody = JSON.parse(body);
      }
    } catch (e) { parsedBody = body; }

    var lxResp = {
      statusCode: resp.statusCode,
      headers: resp.headers || {},
      body: parsedBody,
      raw: body
    };
    if (entry.cb) entry.cb(null, lxResp, parsedBody);
  };

  // ---- utils ----
  function b64EncodeUnicode(str) {
    try {
      return btoa(unescape(encodeURIComponent(str)));
    } catch (e) { return btoa(str); }
  }
  function b64DecodeUnicode(str) {
    try {
      return decodeURIComponent(escape(atob(str)));
    } catch (e) { return atob(str); }
  }
  // hex 编解码,按 UTF-8 字节处理(LX 脚本常用 buffer.from(x).toString('hex') 生成签名)
  function hexEncode(str) {
    var bytes = unescape(encodeURIComponent(str)), out = '';
    for (var i = 0; i < bytes.length; i++) {
      var h = bytes.charCodeAt(i).toString(16);
      out += h.length === 1 ? '0' + h : h;
    }
    return out;
  }
  function hexDecode(hex) {
    var bytes = '';
    for (var i = 0; i < hex.length; i += 2) {
      bytes += String.fromCharCode(parseInt(hex.substr(i, 2), 16));
    }
    try { return decodeURIComponent(escape(bytes)); } catch (e) { return bytes; }
  }

  function makeBuf(raw) {
    return {
      __raw: raw,
      length: raw.length,
      toString: function (enc) {
        if (enc === 'base64') return b64EncodeUnicode(this.__raw);
        if (enc === 'hex') return hexEncode(this.__raw);
        return this.__raw;
      }
    };
  }

  var utils = {
    buffer: {
      from: function (data, encoding) {
        if (data && typeof data === 'object' && data.__raw !== undefined) return makeBuf(data.__raw);
        if (encoding === 'base64') return makeBuf(b64DecodeUnicode(String(data)));
        if (encoding === 'hex') return makeBuf(hexDecode(String(data)));
        return makeBuf(String(data));
      },
      bufToString: function (buf, enc) {
        if (buf && typeof buf.toString === 'function') return buf.toString(enc);
        if (typeof buf === 'string') return makeBuf(buf).toString(enc);
        return String(buf);
      }
    },
    crypto: {
      md5: function (s) { return __md5(String(s)); },
      randomBytes: function (n) {
        var arr = [];
        for (var i = 0; i < n; i++) arr.push(Math.floor(Math.random() * 256));
        return arr;
      },
      // 网易云自签名脚本可能需要 aes/rsa,这里暂不支持,报明确错误便于诊断
      aesEncrypt: function () { throw new Error('lx.utils.crypto.aesEncrypt 暂不支持,请改用基于服务器接口的音源脚本'); },
      rsaEncrypt: function () { throw new Error('lx.utils.crypto.rsaEncrypt 暂不支持,请改用基于服务器接口的音源脚本'); }
    },
    str2b64: b64EncodeUnicode,
    b642str: b64DecodeUnicode
  };

  // ---- lx 对象 ----
  var lx = {
    EVENT_NAMES: EVENT_NAMES,
    version: '2.0.0',
    env: 'android',
    on: function (name, handler) {
      if (name === EVENT_NAMES.request) {
        requestHandlers.push(handler);
      }
    },
    send: function (name, payload) {
      if (name === EVENT_NAMES.inited) {
        try {
          var sources = (payload && payload.sources) || {};
          NeriBridge.onInited(JSON.stringify(sources));
        } catch (e) {
          try { NeriBridge.onInitError(String(e && e.message || e)); } catch (_) {}
        }
      }
    },
    request: lxRequest,
    utils: utils,
    currentScriptInfo: (function () {
      var info = (typeof window !== 'undefined' && window.__NERI_SCRIPT_INFO) ? window.__NERI_SCRIPT_INFO : {};
      var raw = (typeof window !== 'undefined' && window.__NERI_RAW_SCRIPT) ? window.__NERI_RAW_SCRIPT : '';
      return {
        rawScript: raw,
        name: info.name || 'NeriPlayer Custom Source',
        description: info.description || '',
        version: (info.version != null ? String(info.version) : '1.0.0'),
        author: info.author || ''
      };
    })()
  };

  globalThis.lx = lx;
  window.lx = lx;

  // ---- Java -> JS: 调用某个 request 处理器 ----
  window.__neri_invoke = function (payloadStr) {
    var payload;
    try { payload = JSON.parse(payloadStr); } catch (e) {
      return;
    }
    var callId = payload.callId;
    if (!requestHandlers.length) {
      NeriBridge.onRequestResult(callId, JSON.stringify({ ok: false, error: '脚本未注册 request 处理器' }));
      return;
    }
    var arg = { source: payload.source, action: payload.action, info: payload.info };
    var handler = requestHandlers[0];
    var settled = false;
    function resolve(url) {
      if (settled) return; settled = true;
      var u = url;
      if (u && typeof u === 'object') { u = u.url || u['url'] || ''; }
      NeriBridge.onRequestResult(callId, JSON.stringify({ ok: !!u, url: u ? String(u) : '', error: u ? '' : '空 URL' }));
    }
    function reject(err) {
      if (settled) return; settled = true;
      NeriBridge.onRequestResult(callId, JSON.stringify({ ok: false, error: String(err && err.message || err || '未知错误') }));
    }
    try {
      var ret = handler(arg);
      if (ret && typeof ret.then === 'function') {
        ret.then(resolve, reject);
      } else if (ret !== undefined) {
        resolve(ret);
      }
      // 若 handler 使用回调式(部分老脚本),超时由 Java 侧兜底
    } catch (e) {
      reject(e);
    }
  };
})();
""".trimIndent()
