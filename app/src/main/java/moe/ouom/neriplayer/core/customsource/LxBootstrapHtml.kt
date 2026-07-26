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
    val scriptJsonString = JSONObject.quote(userScript) // 安全转义为 JS 字符串字面量
    return """
<!DOCTYPE html>
<html>
<head><meta charset="utf-8"></head>
<body>
<script>
$LX_RUNTIME_JS
</script>
<script>
(function () {
  try {
    var __userScriptSource = $scriptJsonString;
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

  var utils = {
    buffer: {
      from: function (data, encoding) {
        // 返回一个简化的 buffer-like 对象
        if (encoding === 'base64') {
          return { __raw: b64DecodeUnicode(data), toString: function (enc) {
            return enc === 'base64' ? b64EncodeUnicode(this.__raw) : this.__raw;
          }};
        }
        var raw = String(data);
        return { __raw: raw, toString: function (enc) {
          return enc === 'base64' ? b64EncodeUnicode(this.__raw) : this.__raw;
        }};
      },
      bufToString: function (buf, enc) {
        if (buf && typeof buf.toString === 'function') return buf.toString(enc);
        return String(buf);
      }
    },
    crypto: {
      // 交给脚本自带实现的场景较多,这里提供 base64
      randomBytes: function (n) {
        var arr = [];
        for (var i = 0; i < n; i++) arr.push(Math.floor(Math.random() * 256));
        return arr;
      }
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
    currentScriptInfo: { rawScript: '', name: 'NeriPlayer Custom Source' }
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
