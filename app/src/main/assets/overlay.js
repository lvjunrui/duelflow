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

  function callNative(name, arg) {
    try {
      var n = window.DFNative;
      if (!n || typeof n[name] !== 'function') return false;
      n[name](arg);
      return true;
    } catch (e) {
      return false;
    }
  }

  function hasNative() {
    try { return !!(window.DFNative && window.DFNative.capture); } catch (e) { return false; }
  }

  function status(msg) { $('status').textContent = msg; }

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

  function scan() {
    if (!hasNative()) {
      status('浮窗通道未连接：请从 App 里点「浮窗」开启');
      return;
    }
    status('正在截屏：卡图模板匹配 + 分带 OCR…');
    callNative('capture');
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
  }

  window.__onCards = function (cards, w, h, ms, note) {
    state.cards = cards || [];
    state.w = w || state.w;
    state.h = h || state.h;
    state.ms = ms || 0;
    state.note = note || '';
    applyScan();
  };

  window.__onOcr = function (lines) {
    state.lines = lines || [];
    applyScan();
  };

  window.__onOcrError = function (msg) {
    status(errorText(String(msg || '识别失败')));
  };

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
