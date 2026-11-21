/* core.js — 纯逻辑层：浏览器与 Node 共用（无 DOM 依赖） */
(function (root) {
  'use strict';

  var KINDS = [
    { id: 'starter',  label: '抓手',   color: '#e8b84b' },
    { id: 'extender', label: '展开点', color: '#5aa9e6' },
    { id: 'handtrap', label: '手坑',   color: '#e05b6a' },
    { id: 'boss',     label: '终端',   color: '#b07ce8' },
    { id: 'other',    label: '其他',   color: '#8a94a6' }
  ];

  function kindLabel(id) {
    for (var i = 0; i < KINDS.length; i++) if (KINDS[i].id === id) return KINDS[i].label;
    return '其他';
  }

  function kindColor(id) {
    for (var i = 0; i < KINDS.length; i++) if (KINDS[i].id === id) return KINDS[i].color;
    return '#8a94a6';
  }

  function newId(prefix) {
    return (prefix || 'id') + '_' + Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
  }

  function totalCards(cards) {
    var n = 0;
    (cards || []).forEach(function (c) { n += Number(c.count) || 0; });
    return n;
  }

  function countsByKind(cards) {
    var out = {};
    KINDS.forEach(function (k) { out[k.id] = 0; });
    (cards || []).forEach(function (c) {
      var id = c.kind || 'other';
      out[id] = (out[id] || 0) + (Number(c.count) || 0);
    });
    return out;
  }

  /* 组合数 C(n, k) */
  function choose(n, k) {
    if (k < 0 || n < 0 || k > n) return 0;
    k = Math.min(k, n - k);
    var r = 1;
    for (var i = 1; i <= k; i++) r = r * (n - k + i) / i;
    return r;
  }

  /* 起手中至少 1 张某类卡的概率 */
  function probAtLeastOne(deckSize, groupSize, handSize) {
    handSize = handSize || 5;
    if (deckSize <= 0 || groupSize <= 0) return 0;
    if (groupSize >= deckSize) return 1;
    return 1 - choose(deckSize - groupSize, handSize) / choose(deckSize, handSize);
  }

  /* 起手中至少 k 张某类卡的概率 */
  function probAtLeast(deckSize, groupSize, k, handSize) {
    handSize = handSize || 5;
    k = k || 1;
    if (deckSize <= 0 || groupSize <= 0) return 0;
    if (k <= 0) return 1;
    var p = 0, top = Math.min(groupSize, handSize);
    for (var i = k; i <= top; i++) {
      p += choose(groupSize, i) * choose(deckSize - groupSize, handSize - i) / choose(deckSize, handSize);
    }
    return Math.max(0, Math.min(1, p));
  }

  /* 起手里每个分组都至少 1 张的概率（容斥，组数 <= 4） */
  function probAllGroupsPresent(deckSize, groups, handSize) {
    handSize = handSize || 5;
    var sizes = (groups || []).map(function (s) { return Number(s) || 0; });
    if (!sizes.length || deckSize <= 0) return 0;
    /* 任何一组数量为 0，就不可能出现「每组都有」 */
    if (sizes.some(function (s) { return s <= 0; })) return 0;
    var n = sizes.length, p = 1;
    for (var mask = 1; mask < (1 << n); mask++) {
      var sum = 0, bits = 0;
      for (var i = 0; i < n; i++) {
        if (mask & (1 << i)) { sum += sizes[i]; bits++; }
      }
      if (sum > deckSize) { /* 不可能事件，C 返回 0 */ }
      var q = choose(deckSize - sum, handSize) / choose(deckSize, handSize) || 0;
      p += (bits % 2 ? -1 : 1) * q;
    }
    return Math.max(0, Math.min(1, p));
  }

  function handCounts(hand) {
    var m = {};
    (hand || []).forEach(function (name) { m[name] = (m[name] || 0) + 1; });
    return m;
  }

  /* 手牌 vs 一条路线的需求 */
  function matchCombo(combo, handMap) {
    var reqs = (combo && combo.reqs) || [];
    var need = 0, have = 0, missing = [];
    reqs.forEach(function (r) {
      var n = Number(r.n) || 1;
      var h = Math.min(handMap[r.name] || 0, n);
      need += n;
      have += h;
      if (h < n) missing.push({ name: r.name, need: n, have: h });
    });
    var ratio = need ? have / need : 0;
    return { ratio: ratio, have: have, need: need, missing: missing, ok: missing.length === 0 };
  }

  function rankCombos(combos, hand) {
    var hm = handCounts(hand);
    return (combos || []).map(function (c) {
      var m = matchCombo(c, hm);
      return { combo: c, ratio: m.ratio, have: m.have, need: m.need, missing: m.missing, ok: m.ok };
    }).sort(function (a, b) {
      return (b.ratio - a.ratio) ||
        (a.missing.length - b.missing.length) ||
        String(a.combo.name).localeCompare(String(b.combo.name));
    });
  }

  /* 路线需求有多少能在当前卡组里找到 */
  function reqCoverage(combo, cards) {
    var names = {};
    (cards || []).forEach(function (c) { names[c.name] = true; });
    var reqs = (combo && combo.reqs) || [];
    if (!reqs.length) return 1;
    var covered = reqs.filter(function (r) { return names[r.name]; }).length;
    return covered / reqs.length;
  }

  function deckStats(cards) {
    var N = totalCards(cards);
    var byKind = countsByKind(cards);
    return {
      deckSize: N,
      byKind: byKind,
      starters: byKind.starter || 0,
      extender: byKind.extender || 0,
      handtrap: byKind.handtrap || 0,
      p5: probAtLeastOne(N, byKind.starter || 0, 5),
      p5StarterExt: probAllGroupsPresent(N, [byKind.starter || 0, byKind.extender || 0], 5),
      p5Handtrap: probAtLeastOne(N, byKind.handtrap || 0, 5)
    };
  }

  function pct(x) {
    var v = (Number(x) || 0) * 100;
    return (Math.round(v * 10) / 10);
  }

  function fmtPct(x) { return pct(x).toFixed(1) + '%'; }

  var api = {
    KINDS: KINDS,
    kindLabel: kindLabel,
    kindColor: kindColor,
    newId: newId,
    totalCards: totalCards,
    countsByKind: countsByKind,
    choose: choose,
    probAtLeastOne: probAtLeastOne,
    probAtLeast: probAtLeast,
    probAllGroupsPresent: probAllGroupsPresent,
    handCounts: handCounts,
    matchCombo: matchCombo,
    rankCombos: rankCombos,
    reqCoverage: reqCoverage,
    deckStats: deckStats,
    pct: pct,
    fmtPct: fmtPct
  };

  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.Core = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
