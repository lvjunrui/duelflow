/* overlay.js — 浮窗界面（依赖 overlay-core.js） */
(function () {
  'use strict';

  var C = window.OverlayCore;
  var STORE = 'df.overlay.v2';
  var state = load();

  function load() {
    var s = {};
    try { s = JSON.parse(localStorage.getItem(STORE) || '{}') || {}; } catch (e) { s = {}; }
    return {
      hand: Array.isArray(s.hand) ? s.hand : [],
      opp: Array.isArray(s.opp) ? s.opp : [],
      mine: Array.isArray(s.mine) ? s.mine : [],
      cards: [],
      lines: [],
      w: 1080,
      h: 2400,
      ms: 0,
      note: '',
      unknown: 0
    };
  }

  function save() {
    try {
      localStorage.setItem(STORE, JSON.stringify({
        hand: state.hand, opp: state.opp, mine: state.mine
      }));
    } catch (e) {}
  }

  function $(id) { return document.getElementById(id); }

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  var lastBridgeError = '';

  /* 调原生方法。参数个数必须和 Java 侧完全一致：Android 的 WebView 桥在多传参数时
     会直接抛 "called with N arguments, expected M"，以前这里吞掉异常，导致
     「识别屏幕」根本没跑到原生代码（0.4.3 及之前一直识别不了就是这个原因）。 */
  function callNative(name) {
    var args = Array.prototype.slice.call(arguments, 1);
    try {
      var n = window.DFNative;
      if (!n || typeof n[name] !== 'function') {
        lastBridgeError = '找不到原生方法 ' + name;
        return false;
      }
      n[name].apply(n, args);
      return true;
    } catch (e) {
      lastBridgeError = name + '：' + (e && e.message ? e.message : e);
      note('ERR 桥接 ' + lastBridgeError);
      return false;
    }
  }

  function hasNative() {
    try { return !!(window.DFNative && window.DFNative.capture); } catch (e) { return false; }
  }

  function status(msg) { $('status').textContent = msg; }

  /* 阶段日志：native 每一步都会推状态，出问题时这些行会一起进诊断数据 */
  var log = [];
  function note(msg) {
    try {
      log.push(new Date().toISOString().slice(11, 19) + ' ' + msg);
      if (log.length > 40) log.shift();
    } catch (e) {}
  }

  /* native 回调里抛异常时，至少把错误显示在状态行，不再一动不动 */
  function safe(fn) {
    return function () {
      try {
        return fn.apply(this, arguments);
      } catch (e) {
        note('ERR ' + (e && e.message ? e.message : e));
        status('浮窗脚本出错：' + (e && e.message ? e.message : e));
      }
    };
  }

  /* ---------------- 渲染 ---------------- */

  function chip(c, kind, i) {
    var atk = c.atk ? ' <b>' + c.atk + '</b>' : '';
    var conf = c.conf ? ' · 匹配 ' + Math.round(c.conf * 100) + '%' : '';
    var src = c.src === 'ocr' ? '（文字识别）' : '';
    return '<span class="chip ' + kind + '" data-' + kind + '="' + i + '" title="' +
      esc(c.name + conf + src) + '">' + esc(c.name) + atk +
      (c.src === 'ocr' ? '<i class="src">文</i>' : '') + '</span>';
  }

  function render() {
    var opp = $('opp');
    opp.innerHTML = state.opp.length
      ? state.opp.map(function (c, i) { return chip(c, 'opp', i); }).join('')
      : '<span class="empty">还没有识别到对方卡牌</span>';
    $('oppn').textContent = state.opp.length;

    var mine = $('mine');
    mine.innerHTML = state.mine.length
      ? state.mine.map(function (c, i) { return chip(c, 'mine', i); }).join('')
      : '<span class="empty">没有识别到我方场地</span>';
    $('minen').textContent = state.mine.length;

    var hand = $('hand');
    hand.innerHTML = state.hand.length
      ? state.hand.map(function (n, i) {
        return '<span class="chip hand" data-hand="' + i + '">' + esc(n) + '</span>';
      }).join('')
      : '<span class="empty">点下面输入框，把手里的牌加进来</span>';
    $('handn').textContent = state.hand.length;

    var list = C.decide({ hand: state.hand, opp: state.opp });
    $('advn').textContent = list.length + ' 条';
    $('adv').innerHTML = list.map(function (a) {
      return '<div class="adv">' +
        (a.tag ? '<span class="tag">' + esc(a.tag) + '</span>' : '') +
        '<div class="t">' + esc(a.title) + '</div>' +
        '<div class="x">' + esc(a.text) + '</div></div>';
    }).join('');
  }

  /* ---------------- 数据操作 ---------------- */

  function addHand(name) {
    name = String(name || '').trim();
    if (!name) return;
    if (state.hand.length >= 8) return status('手牌最多 8 张');
    state.hand.push(name);
    save(); render();
  }

  function addOpp(name) {
    name = String(name || '').trim();
    if (!name) return;
    state.opp.push({ name: name, tag: '卡', atk: 0, conf: 0, src: 'man' });
    save(); render();
  }

  function removeAt(kind, i) {
    if (kind === 'hand') state.hand.splice(i, 1);
    else if (kind === 'my') state.mine.splice(i, 1);
    else state.opp.splice(i, 1);
    save(); render();
  }

  /* ---------------- 识别入口 ---------------- */

  var busy = false;
  var scanTimer = null;

  function scan() {
    if (!hasNative()) {
      status('浮窗通道未连接：请从 App 里点「浮窗」开启');
      return;
    }
    if (busy) {
      status('上一次识别还没结束，稍等一下');
      return;
    }
    busy = true;
    note('scan 开始');
    status('正在截屏：卡图模板匹配 + 分带 OCR…');
    clearTimeout(scanTimer);
    scanTimer = setTimeout(function () {
      if (!busy) return;
      busy = false;
      note('scan 超时');
      status('25 秒没有结果：确认录屏共享的是「整个屏幕」，或关掉浮窗重开');
    }, 25000);
    if (!callNative('capture')) {
      busy = false;
      status('调截屏失败（' + (lastBridgeError || '未知错误') + '）：请关掉浮窗，回 App 重新点「浮窗」');
    }
  }

  function endScan() {
    busy = false;
    clearTimeout(scanTimer);
  }

  function errorText(msg) {
    return msg + (hasNative() ? '' : '（浮窗通道未连接）');
  }

  /* 两条通道各自到达，合并后刷新 */
  function applyScan() {
    var merged = C.mergeScan({
      hits: state.cards, lines: state.lines, w: state.w, h: state.h
    });
    state.opp = merged.opp;
    state.mine = merged.mine;
    merged.hand.forEach(function (c) {
      if (state.hand.length < 8 && state.hand.indexOf(c.name) < 0) state.hand.push(c.name);
    });
    state.unknown = merged.unknown;
    save(); render();
    var bits = [];
    bits.push('卡图 ' + merged.hits + ' 处 / 文字 ' + merged.lines + ' 行');
    bits.push('对方 ' + merged.opp.length + ' 张');
    if (merged.mine.length) bits.push('我方 ' + merged.mine.length + ' 张');
    if (merged.unknown) bits.push('未识别 ' + merged.unknown + ' 处（可在下面手动补）');
    if (state.ms) bits.push(state.ms + 'ms');
    if (state.note) bits.push(state.note);
    status(bits.join(' · '));
    note('结果 ' + bits.join(' / '));
    endScan();
    saveDiag(merged);
  }

  /* 留一份最近一次识别的原始数据，出问题可以一键复制出来反馈 */
  function saveDiag(merged) {
    try {
      localStorage.setItem('df.diag.v1', JSON.stringify({
        at: Date.now(), w: state.w, h: state.h, ms: state.ms,
        hits: state.cards, lines: state.lines,
        merged: { opp: merged.opp, mine: merged.mine, hand: merged.hand, unknown: merged.unknown },
        log: log
      }));
    } catch (e) {}
  }

  window.__onStatus = safe(function (msg) {
    note('阶段 ' + msg);
    status(String(msg || ''));
  });

  window.__onCards = safe(function (cards, w, h, ms, matched, extra) {
    state.cards = cards || [];
    state.w = w || state.w;
    state.h = h || state.h;
    state.ms = ms || 0;
    state.note = extra || '';
    note('卡图 ' + state.cards.length + ' 处 ' + state.ms + 'ms ' + state.note);
    applyScan();
  });

  window.__onOcr = safe(function (lines) {
    state.lines = lines || [];
    note('文字 ' + state.lines.length + ' 行');
    applyScan();
  });

  window.__onOcrError = safe(function (msg) {
    note('ERR ' + msg);
    endScan();
    status(errorText(String(msg || '识别失败')));
  });

  /* ---------------- 事件 ---------------- */

  function bind() {
    var dl = $('dl');
    dl.innerHTML = C.DB.map(function (c) { return '<option value="' + esc(c.n) + '">'; }).join('');

    $('scan').addEventListener('click', scan);

    $('cls').addEventListener('click', function () {
      state.opp = [];
      save(); render();
      status('已清空对方场面');
    });

    ['hq', 'oq'].forEach(function (id) {
      var el = $(id);
      el.addEventListener('focus', function () { callNative('focus', true); });
      el.addEventListener('blur', function () { callNative('focus', false); });
      el.addEventListener('keydown', function (e) {
        if (e.key !== 'Enter') return;
        confirmInput(id);
        e.preventDefault();
      });
    });

    $('add').addEventListener('click', function () { confirmInput('hq'); });

    $('diag').addEventListener('click', function () {
      var raw = '';
      try { raw = localStorage.getItem('df.diag.v1') || ''; } catch (e) {}
      if (!raw) {
        raw = JSON.stringify({ at: Date.now(), noScan: true, status: $('status').textContent, log: log });
      }
      if (callNative('copy', raw)) status('已复制识别数据，可以直接粘贴给 AI');
      else status('复制失败：浮窗通道未连接');
    });
    $('oadd').addEventListener('click', function () { confirmInput('oq'); });

    document.addEventListener('click', function (e) {
      var t = e.target;
      if (!t || !t.getAttribute) return;
      var o = t.getAttribute('data-opp');
      if (o != null) removeAt('opp', Number(o));
      var m = t.getAttribute('data-mine');
      if (m != null) removeAt('my', Number(m));
      var h = t.getAttribute('data-hand');
      if (h != null) removeAt('hand', Number(h));
    });
  }

  function confirmInput(id) {
    var el = $(id);
    var v = el.value;
    el.value = '';
    if (id === 'oq') addOpp(v);
    else addHand(v);
  }

  bind();
  render();
})();
