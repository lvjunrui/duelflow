/* app.js — 界面与交互（依赖 core.js） */
(function () {
  'use strict';

  var C = window.Core;
  var STORE = 'duelflow.v1';
  var MAX_HAND = 6;

  var state = load();
  var ui = { modal: null, editor: null, player: null };

  /* ---------------- 存取 ---------------- */

  function load() {
    var s = {};
    try {
      var raw = localStorage.getItem(STORE);
      if (raw) s = JSON.parse(raw) || {};
    } catch (e) { s = {}; }
    return {
      cards: Array.isArray(s.cards) ? s.cards : [],
      combos: Array.isArray(s.combos) ? s.combos : [],
      hand: Array.isArray(s.hand) ? s.hand : [],
      tab: s.tab === 'deck' || s.tab === 'combos' ? s.tab : 'live'
    };
  }

  function save() {
    try { localStorage.setItem(STORE, JSON.stringify(state)); } catch (e) {}
  }

  /* ---------------- 工具 ---------------- */

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function toast(msg) {
    var el = document.getElementById('toast');
    el.textContent = msg;
    el.classList.remove('hidden');
    clearTimeout(toast._t);
    toast._t = setTimeout(function () { el.classList.add('hidden'); }, 1700);
  }

  function cardByName(name) {
    for (var i = 0; i < state.cards.length; i++) if (state.cards[i].name === name) return state.cards[i];
    return null;
  }

  function inHandCount(name) {
    var n = 0;
    state.hand.forEach(function (x) { if (x === name) n++; });
    return n;
  }

  /* ---------------- 数据操作 ---------------- */

  function addCard(name, kind, count) {
    name = String(name || '').trim();
    if (!name) return toast('卡名不能为空');
    count = Math.max(1, Math.min(3, Number(count) || 1));
    var existed = cardByName(name);
    if (existed) {
      existed.count = Math.min(3, existed.count + count);
      existed.kind = kind;
      toast('已更新「' + name + '」数量');
    } else {
      state.cards.push({ name: name, kind: kind || 'other', count: count });
      toast('已添加「' + name + '」');
    }
    save(); render();
  }

  function incCard(name, d) {
    var c = cardByName(name);
    if (!c) return;
    c.count = Math.max(0, Math.min(3, c.count + d));
    if (c.count === 0) {
      state.cards = state.cards.filter(function (x) { return x.name !== name; });
      state.hand = state.hand.filter(function (x) { return x !== name; });
    }
    save(); render();
  }

  function setKind(name, kind) {
    var c = cardByName(name);
    if (!c) return;
    c.kind = kind; save(); render();
  }

  function delCard(name) {
    if (!confirm('删除「' + name + '」？')) return;
    state.cards = state.cards.filter(function (x) { return x.name !== name; });
    state.hand = state.hand.filter(function (x) { return x !== name; });
    save(); render();
  }

  function addHand(name) {
    if (state.hand.length >= MAX_HAND) return toast('手牌最多 ' + MAX_HAND + ' 张');
    var c = cardByName(name);
    if (!c) return;
    if (inHandCount(name) >= c.count) return toast('「' + name + '」带的不够多');
    state.hand.push(name);
    save(); render();
  }

  function removeHand(i) {
    state.hand.splice(i, 1);
    save(); render();
  }

  function clearHand() { state.hand = []; save(); render(); }

  function loadPreset() {
    if (state.cards.length && !confirm('会替换当前卡组（路线保留），继续？')) return;
    state.cards = PRESET.map(function (p) {
      return { name: p[0], kind: p[1], count: p[2] };
    });
    state.hand = [];
    save(); render();
    toast('已载入「圣徒百臂」40 张，分类可自行调整');
  }

  function seedDemo() {
    if (state.cards.length && !confirm('已有卡组数据，示例会追加进去，继续？')) return;
    var demo = [
      ['示例·单卡抓手', 'starter', 3],
      ['示例·检索点', 'starter', 6],
      ['示例·展开点', 'extender', 9],
      ['示例·手坑', 'handtrap', 9],
      ['示例·终端', 'boss', 3]
    ];
    demo.forEach(function (d) {
      if (!cardByName(d[0])) state.cards.push({ name: d[0], kind: d[1], count: d[2] });
    });
    if (!state.combos.length) {
      state.combos.push({
        id: C.newId('combo'),
        name: '示例：两卡展开线',
        reqs: [{ name: '示例·单卡抓手', n: 1 }, { name: '示例·展开点', n: 1 }],
        steps: [
          '通常召唤「示例·单卡抓手」',
          '发动效果检索「示例·展开点」',
          '特殊召唤「示例·展开点」',
          '同调 / 连接召唤 → 示例·终端',
          '确认场面：终端 1 只 + 后场盖 1 张'
        ]
      });
    }
    save(); render();
    toast('示例数据已就绪，去「卡组」改成你自己的');
  }

  /* 预置卡组：用户现有「圣徒百臂」主卡组 40 张（分类可按自己理解改） */
  var PRESET = [
    ['百臂无形手 伊卜揶尔', 'starter', 3],
    ['神之百臂无形手', 'starter', 3],
    ['罪宝的欺瞒', 'starter', 3],
    ['圣徒蓟花', 'starter', 2],
    ['百臂无形手 雅多揶尔', 'extender', 2],
    ['百臂无形手 马基卜揶尔', 'extender', 2],
    ['百臂无形幽狱 塔尔塔洛撕', 'extender', 1],
    ['百臂无形诱惑手', 'extender', 1],
    ['百臂无形招引手', 'extender', 1],
    ['圣徒妖主', 'extender', 1],
    ['黑魔女 迪亚贝尔斯塔', 'extender', 1],
    ['圣徒・负疚', 'extender', 1],
    ['白森之罪宝', 'extender', 2],
    ['觉醒的罪宝', 'extender', 1],
    ['圣徒・棘路', 'extender', 1],
    ['幻惑见习魔术使', 'extender', 2],
    ['狩猎罪宝的魔鬼', 'extender', 1],
    ['灰流丽', 'handtrap', 3],
    ['效果遮蒙者', 'handtrap', 2],
    ['多多迷宝・软绵雀精', 'handtrap', 2],
    ['无限泡影', 'handtrap', 2],
    ['超融合', 'handtrap', 2],
    ['增殖的Z', 'handtrap', 1]
  ];

  /* ---------------- 视图 ---------------- */

  function metric(label, value, sub, tone) {
    return '<div class="metric' + (tone ? ' ' + tone : '') + '">' +
      '<div class="m-val">' + value + '</div>' +
      '<div class="m-lab">' + label + '</div>' +
      (sub ? '<div class="m-sub">' + sub + '</div>' : '') +
      '</div>';
  }

  function viewLive() {
    var st = C.deckStats(state.cards);
    var ranked = C.rankCombos(state.combos, state.hand);
    var hm = C.handCounts(state.hand);

    var handHtml = state.hand.length
      ? state.hand.map(function (n, i) {
          return '<button class="chip" data-act="unhand" data-i="' + i + '">' + esc(n) + '<i>×</i></button>';
        }).join('')
      : '<div class="empty">手牌为空。点下面的按钮，从你的卡组里选起手。</div>';

    var routeHtml;
    if (!state.combos.length) {
      routeHtml = '<div class="empty">还没有展开路线。去「路线」建一条，之后这里会按手牌自动匹配。</div>';
    } else {
      routeHtml = ranked.map(function (r) { return routeItem(r, hm); }).join('');
    }

    return section(
      '<div class="panel-head"><h2>手牌</h2><span class="muted">' + state.hand.length + ' / ' + MAX_HAND + '</span>' +
      (state.hand.length ? '<button class="link" data-act="clearHand">清空</button>' : '') + '</div>' +
      '<div class="chips">' + handHtml + '</div>' +
      '<button class="btn primary block" data-act="openPicker">＋ 从卡组选牌</button>'
    ) +
    section(
      '<div class="panel-head"><h2>可走路线</h2><span class="muted">' + (state.combos.length ? '按匹配度排序' : '') + '</span></div>' +
      routeHtml
    ) +
    section(
      '<div class="panel-head"><h2>起手概率</h2><span class="muted">先手 5 张</span></div>' +
      '<div class="metrics">' +
        metric('至少 1 抓手', C.fmtPct(st.p5), st.starters + ' 张', st.starters ? '' : 'dim') +
        metric('抓手 + 展开点', C.fmtPct(st.p5StarterExt), '同时上手', st.p5StarterExt > 0 ? '' : 'dim') +
        metric('至少 1 手坑', C.fmtPct(st.p5Handtrap), st.handtrap + ' 张', st.handtrap ? '' : 'dim') +
      '</div>' +
      (st.deckSize === 0 ? '<div class="empty">卡组为空，先去「卡组」录入你的牌。</div>' : '')
    );
  }

  function routeItem(r, hm) {
    var combo = r.combo;
    var eq = combo.reqs || [];
    var reqHtml = eq.length
      ? eq.map(function (q) {
          var need = Number(q.n) || 1;
          var have = Math.min(hm[q.name] || 0, need);
          var hit = have >= need;
          var uncatalogued = !cardByName(q.name);
          return '<span class="req ' + (hit ? 'hit' : 'miss') + '">' +
            esc(q.name) + (need > 1 ? ' ×' + need : '') +
            (hit ? '' : ' <b>缺' + (need - have) + '</b>') +
            (uncatalogued ? ' <em>未收录</em>' : '') +
            '</span>';
        }).join('')
      : '<span class="req hit">任意手牌</span>';

    var steps = (combo.steps || []).length;
    return '<div class="route">' +
      '<div class="route-top">' +
        '<div class="route-name">' + esc(combo.name) + '</div>' +
        '<div class="route-pct' + (r.ratio === 1 ? ' ok' : '') + '">' + Math.round(r.ratio * 100) + '%</div>' +
      '</div>' +
      '<div class="bar"><div style="width:' + Math.round(r.ratio * 100) + '%"></div></div>' +
      '<div class="reqs">' + reqHtml + '</div>' +
      '<div class="route-foot">' +
        '<span class="muted">' + steps + ' 步</span>' +
        '<button class="btn small' + (steps ? ' primary' : '') + '" data-act="play" data-id="' + esc(combo.id) + '">' +
          (steps ? '开始展开' : '无步骤') + '</button>' +
      '</div>' +
    '</div>';
  }

  function viewDeck() {
    var st = C.deckStats(state.cards);
    var total = st.deckSize;
    var warn = total > 60 ? ' over' : (total === 0 ? ' dim' : '');

    var rows = state.cards.length ? state.cards.map(function (c) {
      var color = C.kindColor(c.kind);
      return '<div class="card-row">' +
        '<span class="kdot" style="background:' + color + '"></span>' +
        '<span class="cname">' + esc(c.name) + '</span>' +
        '<select class="kinds" data-act="setkind" data-name="' + esc(c.name) + '">' +
          C.KINDS.map(function (k) {
            return '<option value="' + k.id + '"' + (k.id === c.kind ? ' selected' : '') + '>' + k.label + '</option>';
          }).join('') +
        '</select>' +
        '<button class="stepper" data-act="inc" data-name="' + esc(c.name) + '" data-d="-1">−</button>' +
        '<span class="cnt">' + c.count + '</span>' +
        '<button class="stepper" data-act="inc" data-name="' + esc(c.name) + '" data-d="1">＋</button>' +
        '<button class="del" data-act="delCard" data-name="' + esc(c.name) + '">✕</button>' +
      '</div>';
    }).join('') : '<div class="empty">还没有卡牌。用下面的表单输入卡名，或者先看看示例数据。</div>';

    var kindOpts = C.KINDS.map(function (k) {
      return '<option value="' + k.id + '"' + (k.id === 'starter' ? ' selected' : '') + '>' + k.label + '</option>';
    }).join('');

    return section(
      '<div class="metrics compact">' +
        metric('卡组总数', String(total), '标准 40–60', warn) +
        metric('抓手', String(st.starters), '起手 ' + C.fmtPct(st.p5), '') +
        metric('手坑', String(st.handtrap), '起手 ' + C.fmtPct(st.p5Handtrap), '') +
      '</div>'
    ) +
    section(
      '<div class="panel-head"><h2>卡组</h2><span class="muted">' + state.cards.length + ' 种</span></div>' +
      '<div class="cards">' + rows + '</div>'
    ) +
    section(
      '<form class="add-form" id="addCardForm">' +
        '<input class="inp" name="name" placeholder="卡名，例如 灰流丽" maxlength="40" autocomplete="off" required>' +
        '<div class="add-row">' +
          '<select class="inp sel" name="kind">' + kindOpts + '</select>' +
          '<input class="inp num" name="count" type="number" min="1" max="3" value="1" inputmode="numeric">' +
          '<button class="btn primary" type="submit">添加</button>' +
        '</div>' +
      '</form>'
    ) +
    section(
      '<div class="panel-head"><h2>数据</h2></div>' +
      '<div class="io-row">' +
        '<button class="btn small primary" data-act="loadPreset">载入「圣徒百臂」</button>' +
        '<button class="btn small" data-act="seedDemo">填充示例数据</button>' +
        '<button class="btn small" data-act="openIO" data-mode="export">导出</button>' +
        '<button class="btn small" data-act="openIO" data-mode="import">导入</button>' +
        '<button class="btn small danger" data-act="resetAll">清空数据</button>' +
      '</div>' +
      '<div class="muted small-pad">数据只存在这台手机的本地存储里，不上传任何地方。</div>'
    );
  }

  function viewCombos() {
    var list = state.combos.length ? state.combos.map(function (c) {
      var reqTxt = (c.reqs || []).map(function (r) {
        return esc(r.name) + (r.n > 1 ? ' ×' + r.n : '');
      }).join('、') || '无需求';
      return '<div class="combo-row">' +
        '<button class="combo-main" data-act="openEditor" data-id="' + esc(c.id) + '">' +
          '<div class="combo-name">' + esc(c.name) + '</div>' +
          '<div class="combo-sub">' + reqTxt + ' · ' + (c.steps || []).length + ' 步</div>' +
        '</button>' +
        '<button class="del" data-act="delCombo" data-id="' + esc(c.id) + '">✕</button>' +
      '</div>';
    }).join('') : '<div class="empty">还没有路线。一条路线 = 需要哪些卡 + 每一步做什么。</div>';

    return section(
      '<div class="panel-head"><h2>展开路线</h2><span class="muted">' + state.combos.length + ' 条</span></div>' +
      '<div class="combos">' + list + '</div>' +
      '<button class="btn primary block" data-act="openEditor" data-id="">＋ 新建路线</button>'
    ) +
    section(
      '<div class="panel-head"><h2>怎么用</h2></div>' +
      '<div class="hint">' +
        '<p>1. 「卡组」里录入你实际在用的牌，标好抓手 / 展开点 / 手坑。</p>' +
        '<p>2. 这里把常用的展开线写下来：需要哪几张卡、每步点什么。</p>' +
        '<p>3. 实战时在「实战」里点出手牌，工具会算出哪条线能走、缺哪张。</p>' +
      '</div>'
    );
  }

  function section(inner, extra) {
    return '<section class="panel' + (extra ? ' ' + extra : '') + '">' + inner + '</section>';
  }

  /* ---------------- 弹层 ---------------- */

  function viewPicker() {
    if (!state.cards.length) {
      return '<div class="sheet-head"><h3>从卡组选牌</h3><button class="del" data-act="closeModal">✕</button></div>' +
        '<div class="empty">卡组还是空的。先去「卡组」里添加卡牌。</div>';
    }
    var rows = state.cards.map(function (c) {
      var left = c.count - inHandCount(c.name);
      var color = C.kindColor(c.kind);
      return '<button class="pick-row" data-act="pick" data-name="' + esc(c.name) + '"' + (left <= 0 ? ' disabled' : '') + '>' +
        '<span class="kdot" style="background:' + color + '"></span>' +
        '<span class="cname">' + esc(c.name) + '</span>' +
        '<span class="kindtag">' + C.kindLabel(c.kind) + '</span>' +
        '<span class="left">剩 ' + Math.max(0, left) + '</span>' +
      '</button>';
    }).join('');
    return '<div class="sheet-head"><h3>从卡组选牌</h3><span class="muted">' + state.hand.length + '/' + MAX_HAND + '</span>' +
      '<button class="del" data-act="closeModal">✕</button></div>' +
      '<div class="pick-list">' + rows + '</div>';
  }

  function viewEditor() {
    var ed = ui.editor;
    var deckRows = state.cards.map(function (c) {
      var cur = 0;
      (ed.reqs || []).forEach(function (r) { if (r.name === c.name) cur = r.n; });
      return '<div class="req-row">' +
        '<span class="cname">' + esc(c.name) + '</span>' +
        '<button class="stepper" data-act="reqInc" data-name="' + esc(c.name) + '" data-d="-1">−</button>' +
        '<span class="cnt">' + cur + '</span>' +
        '<button class="stepper" data-act="reqInc" data-name="' + esc(c.name) + '" data-d="1">＋</button>' +
      '</div>';
    }).join('');

    return '<div class="sheet-head"><h3>' + (ed.id ? '编辑路线' : '新建路线') + '</h3>' +
      '<button class="del" data-act="closeModal">✕</button></div>' +
      '<div class="sheet-body">' +
        '<label class="lab">路线名称</label>' +
        '<input class="inp" id="edName" value="' + esc(ed.name) + '" placeholder="例如：单抓手 → 银金线" maxlength="40">' +
        '<label class="lab">需要的手牌（可选）</label>' +
        (state.cards.length ? '<div class="req-list">' + deckRows + '</div>' : '<div class="empty small">卡组为空，先录卡再标需求。</div>') +
        '<label class="lab">步骤（一行一步）</label>' +
        '<textarea class="inp area" id="edSteps" rows="7" placeholder="通常召唤……&#10;检索……&#10;特召……">' + esc(ed.stepsText) + '</textarea>' +
      '</div>' +
      '<div class="sheet-foot">' +
        (ed.id ? '<button class="btn small danger" data-act="delCombo" data-id="' + esc(ed.id) + '">删除</button>' : '') +
        '<button class="btn primary" data-act="saveCombo">保存</button>' +
      '</div>';
  }

  function viewPlayer() {
    var combo = null;
    for (var i = 0; i < state.combos.length; i++) if (state.combos[i].id === ui.player.id) combo = state.combos[i];
    if (!combo) { return '<div class="empty">路线已不存在。</div>'; }
    var steps = combo.steps || [];
    if (!steps.length) {
      return '<div class="sheet-head"><h3>' + esc(combo.name) + '</h3><button class="del" data-act="closeModal">✕</button></div>' +
        '<div class="empty">这条路线还没有步骤，去「路线」里补上。</div>';
    }
    var idx = Math.max(0, Math.min(ui.player.idx, steps.length - 1));
    var pctv = Math.round(((idx + 1) / steps.length) * 100);
    var dots = steps.map(function (s, i) {
      return '<button class="dot' + (i === idx ? ' cur' : '') + (i < idx ? ' done' : '') + '" data-act="gotoStep" data-i="' + i + '">' + (i + 1) + '</button>';
    }).join('');

    return '<div class="player">' +
      '<div class="player-head">' +
        '<div><div class="player-name">' + esc(combo.name) + '</div>' +
        '<div class="muted">第 ' + (idx + 1) + ' / ' + steps.length + ' 步</div></div>' +
        '<button class="del" data-act="closeModal">✕</button>' +
      '</div>' +
      '<div class="bar big"><div style="width:' + pctv + '%"></div></div>' +
      '<div class="step-body">' + esc(steps[idx]) + '</div>' +
      '<div class="dots">' + dots + '</div>' +
      '<div class="player-actions">' +
        '<button class="btn" data-act="stepPrev"' + (idx === 0 ? ' disabled' : '') + '>上一步</button>' +
        '<button class="btn primary" data-act="stepNext"' + (idx === steps.length - 1 ? ' disabled' : '') + '>' +
          (idx === steps.length - 1 ? '已完成' : '下一步') + '</button>' +
      '</div>' +
    '</div>';
  }

  function viewIO() {
    var mode = ui.ioMode || 'export';
    var json = JSON.stringify({ cards: state.cards, combos: state.combos }, null, 2);
    if (mode === 'export') {
      return '<div class="sheet-head"><h3>导出数据</h3><button class="del" data-act="closeModal">✕</button></div>' +
        '<div class="sheet-body">' +
          '<textarea class="inp area mono" id="ioText" rows="10" readonly>' + esc(json) + '</textarea>' +
        '</div>' +
        '<div class="sheet-foot"><button class="btn" data-act="copyIO">复制</button>' +
        '<button class="btn primary" data-act="closeModal">完成</button></div>';
    }
    return '<div class="sheet-head"><h3>导入数据</h3><button class="del" data-act="closeModal">✕</button></div>' +
      '<div class="sheet-body">' +
        '<div class="muted small-pad">粘贴之前导出的 JSON。会覆盖当前卡组和路线。</div>' +
        '<textarea class="inp area mono" id="ioText" rows="10" placeholder="{' + '"cards":[...],"combos":[...]}"></textarea>' +
      '</div>' +
      '<div class="sheet-foot"><button class="btn primary" data-act="applyIO">导入</button></div>';
  }

  /* ---------------- 渲染 ---------------- */

  function render() {
    var tabs = document.querySelectorAll('#tabs .tab');
    for (var i = 0; i < tabs.length; i++) {
      var on = tabs[i].getAttribute('data-tab') === state.tab;
      tabs[i].classList.toggle('on', on);
    }
    document.getElementById('deckBadge').textContent = C.totalCards(state.cards) + ' 张';

    var html = state.tab === 'deck' ? viewDeck() : state.tab === 'combos' ? viewCombos() : viewLive();
    document.getElementById('view').innerHTML = html;

    var modal = document.getElementById('modal');
    var sheet = document.getElementById('sheet');
    if (!ui.modal) {
      modal.classList.add('hidden');
      sheet.innerHTML = '';
      sheet.className = 'sheet';
      return;
    }
    modal.classList.remove('hidden');
    sheet.className = 'sheet' + (ui.modal === 'player' ? ' full' : '');
    sheet.innerHTML =
      ui.modal === 'picker' ? viewPicker() :
      ui.modal === 'editor' ? viewEditor() :
      ui.modal === 'player' ? viewPlayer() : viewIO();
    document.getElementById('view').scrollTop = 0;
  }

  function closeModal() {
    ui.modal = null; ui.editor = null; ui.player = null;
    render();
  }

  /* ---------------- 编辑路线 ---------------- */

  function openEditor(id) {
    var combo = null;
    for (var i = 0; i < state.combos.length; i++) if (state.combos[i].id === id) combo = state.combos[i];
    ui.editor = {
      id: combo ? combo.id : null,
      name: combo ? combo.name : '',
      reqs: combo ? JSON.parse(JSON.stringify(combo.reqs || [])) : [],
      stepsText: combo ? (combo.steps || []).join('\n') : ''
    };
    ui.modal = 'editor';
    render();
  }

  function syncEditor() {
    if (!ui.editor) return;
    var n = document.getElementById('edName');
    var s = document.getElementById('edSteps');
    if (n) ui.editor.name = n.value;
    if (s) ui.editor.stepsText = s.value;
  }

  function reqInc(name, d) {
    syncEditor();
    var c = cardByName(name);
    var max = c ? c.count : 3;
    var req = null;
    ui.editor.reqs.forEach(function (r) { if (r.name === name) req = r; });
    var next = (req ? req.n : 0) + d;
    if (next <= 0) {
      ui.editor.reqs = ui.editor.reqs.filter(function (r) { return r.name !== name; });
    } else if (req) {
      req.n = Math.min(max, next);
    } else {
      ui.editor.reqs.push({ name: name, n: Math.min(max, next) });
    }
    render();
  }

  function saveCombo() {
    syncEditor();
    var ed = ui.editor;
    var name = String(ed.name || '').trim();
    if (!name) return toast('给路线起个名字');
    var steps = String(ed.stepsText || '').split('\n')
      .map(function (s) { return s.trim(); })
      .filter(function (s) { return s.length > 0; });
    var reqs = (ed.reqs || []).filter(function (r) { return r.n > 0; })
      .map(function (r) { return { name: r.name, n: r.n }; });

    if (ed.id) {
      state.combos.forEach(function (c) {
        if (c.id === ed.id) { c.name = name; c.reqs = reqs; c.steps = steps; }
      });
    } else {
      state.combos.push({ id: C.newId('combo'), name: name, reqs: reqs, steps: steps });
    }
    save();
    toast(ed.id ? '已保存' : '路线已创建');
    closeModal();
  }

  function delCombo(id) {
    if (!confirm('删除这条路线？')) return;
    state.combos = state.combos.filter(function (c) { return c.id !== id; });
    save(); closeModal(); toast('已删除');
  }

  /* ---------------- 导入导出 ---------------- */

  function copyIO() {
    var ta = document.getElementById('ioText');
    if (!ta) return;
    ta.select();
    ta.setSelectionRange(0, ta.value.length);
    var ok = false;
    try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(ta.value).then(function () { toast('已复制'); }, function () {});
    }
    toast(ok ? '已复制' : '已全选，长按复制');
  }

  function applyIO() {
    var ta = document.getElementById('ioText');
    if (!ta) return;
    var data;
    try {
      data = JSON.parse(ta.value);
    } catch (e) {
      return toast('JSON 格式不对');
    }
    if (!data || !Array.isArray(data.cards)) return toast('缺少 cards 字段');
    state.cards = data.cards.map(function (c) {
      return { name: String(c.name || '').slice(0, 40), kind: c.kind || 'other', count: Math.max(1, Math.min(3, Number(c.count) || 1)) };
    }).filter(function (c) { return c.name; });
    state.combos = (Array.isArray(data.combos) ? data.combos : []).map(function (c) {
      return {
        id: c.id || C.newId('combo'),
        name: String(c.name || '未命名'),
        reqs: Array.isArray(c.reqs) ? c.reqs.map(function (r) { return { name: String(r.name || ''), n: Math.max(1, Number(r.n) || 1) }; }) : [],
        steps: Array.isArray(c.steps) ? c.steps.map(String) : []
      };
    });
    state.hand = [];
    save(); closeModal(); toast('导入完成');
  }

  function resetAll() {
    if (!confirm('清空所有卡组、路线和手牌？')) return;
    state = { cards: [], combos: [], hand: [], tab: state.tab };
    save(); render(); toast('已清空');
  }

  /* ---------------- 事件 ---------------- */

  document.addEventListener('click', function (e) {
    var el = e.target.closest ? e.target.closest('[data-act]') : null;
    if (!el) return;
    var act = el.getAttribute('data-act');
    var name = el.getAttribute('data-name');
    var id = el.getAttribute('data-id');

    switch (act) {
      case 'tab':
        state.tab = el.getAttribute('data-tab'); save(); render(); break;
      case 'unhand': removeHand(Number(el.getAttribute('data-i'))); break;
      case 'clearHand': clearHand(); break;
      case 'openPicker': ui.modal = 'picker'; render(); break;
      case 'pick': addHand(name); break;
      case 'closeModal': closeModal(); break;
      case 'play':
        ui.player = { id: id, idx: 0 }; ui.modal = 'player'; render(); break;
      case 'stepNext':
        if (ui.player) { ui.player.idx++; render(); } break;
      case 'stepPrev':
        if (ui.player) { ui.player.idx = Math.max(0, ui.player.idx - 1); render(); } break;
      case 'gotoStep':
        if (ui.player) { ui.player.idx = Number(el.getAttribute('data-i')); render(); } break;
      case 'inc': incCard(name, Number(el.getAttribute('data-d'))); break;
      case 'delCard': delCard(name); break;
      case 'openEditor': openEditor(id || null); break;
      case 'saveCombo': saveCombo(); break;
      case 'delCombo': delCombo(id); break;
      case 'reqInc': reqInc(name, Number(el.getAttribute('data-d'))); break;
      case 'seedDemo': seedDemo(); break;
      case 'loadPreset': loadPreset(); break;
      case 'resetAll': resetAll(); break;
      case 'openIO': ui.ioMode = el.getAttribute('data-mode'); ui.modal = 'io'; render(); break;
      case 'copyIO': copyIO(); break;
      case 'applyIO': applyIO(); break;
    }
  });

  document.addEventListener('change', function (e) {
    var el = e.target;
    if (el && el.getAttribute && el.getAttribute('data-act') === 'setkind') {
      setKind(el.getAttribute('data-name'), el.value);
    }
  });

  document.addEventListener('submit', function (e) {
    var form = e.target;
    if (!form || form.id !== 'addCardForm') return;
    e.preventDefault();
    var name = form.elements.name.value;
    var kind = form.elements.kind.value;
    var count = Number(form.elements.count.value) || 1;
    addCard(name, kind, count);
    form.elements.name.value = '';
    form.elements.count.value = 1;
    form.elements.name.focus();
  });

  /* Android 返回键：先关弹层 */
  window.__onAndroidBack = function () {
    if (ui.modal) { closeModal(); return true; }
    return false;
  };

  render();
})();
