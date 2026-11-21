/* overlay.js — 浮窗界面（依赖 overlay-core.js） */
(function () {
  'use strict';

  var C = window.OverlayCore;
  var STORE = 'df.overlay.v1';
  var state = load();

  function load() {
    var s = {};
    try { s = JSON.parse(localStorage.getItem(STORE) || '{}') || {}; } catch (e) { s = {}; }
    return {
      hand: Array.isArray(s.hand) ? s.hand : [],
      opp: Array.isArray(s.opp) ? s.opp : []
    };
  }

  function save() {
    try { localStorage.setItem(STORE, JSON.stringify(state)); } catch (e) {}
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

  function render() {
    var opp = $('opp');
    if (!state.opp.length) {
      opp.innerHTML = '<span class="empty">还没有识别到对方卡牌</span>';
    } else {
      opp.innerHTML = state.opp.map(function (c, i) {
        var atk = c.atk ? ' <b>' + c.atk + '</b>' : '';
        return '<span class="chip opp" data-opp="' + i + '">' + esc(c.name) + atk + '</span>';
      }).join('');
    }
    $('oppn').textContent = state.opp.length;

    var hand = $('hand');
    if (!state.hand.length) {
      hand.innerHTML = '<span class="empty">点下面输入框，把手里的牌加进来</span>';
    } else {
      hand.innerHTML = state.hand.map(function (n, i) {
        return '<span class="chip hand" data-hand="' + i + '">' + esc(n) + '</span>';
      }).join('');
    }
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

  function removeAt(kind, i) {
    if (kind === 'hand') state.hand.splice(i, 1);
    else state.opp.splice(i, 1);
    save(); render();
  }

  /* ---------------- 识别入口 ---------------- */

  function scan() {
    if (!hasNative()) {
      status('浮窗通道未连接：请从 App 里点「浮窗」开启');
      return;
    }
    status('正在截图识别…');
    callNative('capture');
  }

  function errorText(msg) {
    return msg + (hasNative() ? '' : '（浮窗通道未连接）');
  }

  window.__onOcr = function (lines, w, h) {
    var parsed = C.parse(lines || [], w, h);
    state.opp = parsed.opp;
    save(); render();
    var total = parsed.opp.length + parsed.my.length + parsed.other.length;
    if (!total) {
      status('没认出卡牌：把浮窗挪开，让卡面完整露出，再点一次');
    } else {
      status('识别到 ' + parsed.opp.length + ' 张对方卡 / ' + parsed.my.length + ' 张我方区域');
    }
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

    var hq = $('hq');
    hq.addEventListener('focus', function () { callNative('focus', true); });
    hq.addEventListener('blur', function () { callNative('focus', false); });
    hq.addEventListener('keydown', function (e) {
      if (e.key === 'Enter') {
        addHand(hq.value);
        hq.value = '';
        e.preventDefault();
      }
    });

    $('add').addEventListener('click', function () {
      addHand(hq.value);
      hq.value = '';
    });

    document.addEventListener('click', function (e) {
      var t = e.target;
      if (!t || !t.getAttribute) return;
      var o = t.getAttribute('data-opp');
      if (o != null) removeAt('opp', Number(o));
      var h = t.getAttribute('data-hand');
      if (h != null) removeAt('hand', Number(h));
    });
  }

  bind();
  render();
})();
