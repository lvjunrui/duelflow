/* overlay-core.js — 浮窗的纯逻辑：OCR 文本 → 卡牌识别 → 决策建议
   无 DOM 依赖，可在 Node 里测试。 */
(function (root) {
  'use strict';

  /* 卡库：本卡组 43 张 + 常见对局卡。n=中文名，t=类型标签 */
  var DB = [
    { n: '灰流丽', t: '手坑' },
    { n: '多多迷宝・软绵雀精', t: '手坑' },
    { n: '抽牌小丑与封锁鸟', t: '手坑' },
    { n: '增殖的Z', t: '手坑' },
    { n: '惨绝戏之导化 阿卢绯尔', t: '启动' },
    { n: '烙印融合', t: '启动' },
    { n: '烙印之气炎', t: '启动' },
    { n: '天底的使徒', t: '启动' },
    { n: '落胤与圣女', t: '启动' },
    { n: '守宝团・姬特', t: '展开' },
    { n: '白之圣女 艾克利西亚', t: '展开' },
    { n: '白之落胤', t: '展开' },
    { n: '阿尔白斯之落胤', t: '展开' },
    { n: '引导之圣女 库妸㽗', t: '展开' },
    { n: '赫之圣女 卡尔塔西亚', t: '展开' },
    { n: '黑衣龙 阿尔比昂', t: '展开' },
    { n: '原初之神 法拉', t: '展开' },
    { n: '超重禽属 鸡蛇矿', t: '展开' },
    { n: '铁兽鸟 墨丘利耶', t: '展开' },
    { n: '轩辕的相剑师', t: '展开' },
    { n: '烙印开幕', t: '展开' },
    { n: '白之烙印', t: '展开' },
    { n: '小世界', t: '展开' },
    { n: '超融合', t: '解场' },
    { n: '禁忌的一滴', t: '解场' },
    { n: '烙印断罪', t: '陷阱' },
    { n: '教导之圣女 艾克利西亚', t: '展开' },
    { n: '烙印龙 阿尔比昂', t: '融合怪' },
    { n: '冰剑龙 镜翠幻种', t: '融合怪' },
    { n: '神炎龙 卢绯里昂', t: '融合怪' },
    { n: '灰烬龙 落尘劣种', t: '融合怪' },
    { n: '吞喰圣痕之龙', t: '融合怪' },
    { n: '赫焉龙 格兰吉尼奥尔', t: '融合怪' },
    { n: '惨绝戏子・汝寻何人', t: '融合怪' },
    { n: '击铁龙 猎宝机装', t: '融合怪' },
    { n: '深渊龙 阿尔白・雷纳图斯', t: '融合怪' },
    { n: '空隙原星龙', t: '融合怪' },
    { n: '至爱－英雄 闪耀光焰翼人', t: '融合怪' },
    { n: '黑衣龙之艾克利西亚', t: '同步怪' },
    { n: '三英的相剑师', t: '同步怪' },
    { n: '效果遮蒙者', t: '手坑' },
    { n: '无限泡影', t: '陷阱' },
    { n: '墓穴的指名者', t: '陷阱' },
    { n: '原始生命态 尼比鲁', t: '手坑' }
  ];

  /* 8 条路线：id / 名称 / 需求卡 / 一句提示 */
  var LINES = [
    { id: 'L1', name: '白之落胤单卡 · 同步场', reqs: ['白之落胤'], hint: '①送烙印龙进墓特召自身 → ②拉白之圣女 → 解放圣女出轩辕 → 同步黑衣龙 → ①出卡尔塔西亚。' },
    { id: 'L2', name: '阿卢绯尔单卡 · 灰烬龙', reqs: ['惨绝戏之导化 阿卢绯尔'], hint: 'NS 阿卢绯尔找烙印融合 → 卡组融合灰烬龙（3700）→ 结束阶段起跳白之落胤+圣女。' },
    { id: 'L3', name: '气炎单卡 · 免费用', reqs: ['烙印之气炎'], hint: '展示龙族送灰烬龙进墓 → 可选丢牌找阿尔白斯 → 结束阶段灰烬龙起跳。' },
    { id: 'L4', name: '白之圣女 + 三英', reqs: ['白之圣女 艾克利西亚'], hint: '对方怪多时特召 → 解放出白之落胤 → 同步三英找法拉，②无效并破坏。' },
    { id: 'L5', name: '落胤与圣女 · 解场', reqs: ['落胤与圣女'], hint: '①送融合怪进墓破坏 1 张表侧卡；有艾克莉西娅时②复活墓地怪兽。' },
    { id: 'L6', name: '气炎 + 法拉 · 抢怪', reqs: ['烙印之气炎', '原初之神 法拉'], hint: '展示法拉送赫焉龙进墓 → 丢法拉找阿尔白斯 → 法拉②特召 → ③抢对方最高攻击力。' },
    { id: 'L7', name: '超融合 · 吃场', reqs: ['超融合'], hint: '不能响应；龙族用空隙原星龙，融合怪用至爱英雄。' },
    { id: 'L8', name: '天底 · 后置检索', reqs: ['天底的使徒'], hint: '先做完场最后发；送灰烬龙进墓去找白之落胤，结束阶段起跳。' }
  ];

  function norm(s) {
    return String(s == null ? '' : s)
      .replace(/[\s\u3000]/g, '')
      .replace(/[「」『』\[\]()（）·・.,，。:：;；!！?？"'"'\-—/]/g, '')
      .toUpperCase();
  }

  function lev(a, b) {
    if (a === b) return 0;
    if (!a.length) return b.length;
    if (!b.length) return a.length;
    var prev = new Array(b.length + 1);
    var cur = new Array(b.length + 1);
    for (var j = 0; j <= b.length; j++) prev[j] = j;
    for (var i = 1; i <= a.length; i++) {
      cur[0] = i;
      for (var k = 1; k <= b.length; k++) {
        var cost = a.charAt(i - 1) === b.charAt(k - 1) ? 0 : 1;
        cur[k] = Math.min(prev[k] + 1, cur[k - 1] + 1, prev[k - 1] + cost);
      }
      var tmp = prev; prev = cur; cur = tmp;
    }
    return prev[b.length];
  }

  /* OCR 一行文本 → 最可能的卡名 */
  function match(text) {
    var t = norm(text);
    if (!t) return null;
    var best = null;
    for (var i = 0; i < DB.length; i++) {
      var n = norm(DB[i].n);
      if (n.length < 2) continue;
      if (t.indexOf(n) >= 0) {
        if (!best || n.length > best.score) best = { name: DB[i].n, tag: DB[i].t, score: n.length };
        continue;
      }
      if (n.length >= 4) {
        var win = n.length;
        var sim = 0;
        for (var s = 0; s + win <= t.length && s < 8; s++) {
          var piece = t.substr(s, win);
          sim = Math.max(sim, 1 - lev(piece, n) / win);
        }
        if (sim >= 0.62 && (!best || sim * 10 > best.score)) {
          best = { name: DB[i].n, tag: DB[i].t, score: sim * 10 };
        }
      }
    }
    return best;
  }

  function atkOf(text) {
    var t = String(text || '');
    var m = /ATK[\s:：]*(\d{1,5})/i.exec(t);
    if (m) return Number(m[1]);
    m = /(\d{3,5})\s*\/\s*(\d{3,5})/.exec(t);
    if (m) return Number(m[1]);
    /* MD 场上怪兽卡下沿只显示一个裸数字（如 3000） */
    m = /^\s*(\d{3,5})\s*$/.exec(t);
    return m ? Number(m[1]) : 0;
  }

  /* OCR 行 → 双方场面。y 比例 < 0.44 当对方，> 0.6 当我方 */
  function parse(lines, W, H) {
    var opp = [], mine = [], other = [];
    var height = H || 1;
    (lines || []).forEach(function (l) {
      var m = match(l.t);
      if (!m) return;
      var item = { name: m.name, tag: m.tag, atk: atkOf(l.t), y: l.y };
      var r = (l.y || 0) / height;
      if (r < 0.44) opp.push(item);
      else if (r > 0.6) mine.push(item);
      else other.push(item);
    });
    return { opp: dedupe(opp), mine: dedupe(mine), other: dedupe(other) };
  }

  function dedupe(list) {
    var seen = {}, out = [];
    list.forEach(function (c) {
      var k = c.name;
      if (seen[k] != null) {
        if ((c.atk || 0) > (out[seen[k]].atk || 0)) out[seen[k]].atk = c.atk;
        return;
      }
      seen[k] = out.length;
      out.push(c);
    });
    return out;
  }

  function names(list) {
    return (list || []).map(function (c) { return c.name; });
  }

  function hasAny(hand, arr) {
    for (var i = 0; i < arr.length; i++) if (hand.indexOf(arr[i]) >= 0) return true;
    return false;
  }

  function count(hand, name) {
    var n = 0;
    hand.forEach(function (x) { if (x === name) n++; });
    return n;
  }

  /* 决策：输入 {hand:[卡名], opp:[{name,atk}], my:[...] } */
  function decide(input) {
    var hand = (input && input.hand) || [];
    var opp = (input && input.opp) || [];
    var out = [];
    var oppNames = names(opp);
    var oppHasFusion = opp.some(function (c) { return c.tag === '融合怪'; });
    var oppMaxAtk = opp.reduce(function (a, c) { return Math.max(a, c.atk || 0); }, 0);

    if (count(hand, '超融合') > 0 && oppHasFusion) {
      out.push({
        p: 98,
        title: '超融合：吃对方的融合怪',
        text: '对方场上有融合怪兽 → 超融合（不能响应）把它和另一只表侧怪做「至爱－英雄 闪耀光焰翼人」。',
        tag: 'L7'
      });
    }
    if (count(hand, '烙印之气炎') > 0 && count(hand, '原初之神 法拉') > 0) {
      out.push({
        p: 96,
        title: '气炎 + 法拉：抢对方最大怪',
        text: '展示法拉送赫焉龙进墓 → 丢法拉找阿尔白斯 → 法拉②自己站起来 → ③抢对方攻击力最高的怪兽。',
        tag: 'L6'
      });
    }
    if (count(hand, '禁忌的一滴') > 0 && opp.length >= 2) {
      out.push({
        p: 92,
        title: '一滴：无效对方的压制点',
        text: '丢掉几张换对方同样数量的效果怪兽无效并减半。注意丢法拉不会触发它②（费用不算效果）。',
        tag: '通用'
      });
    }
    if (count(hand, '天底的使徒') > 0) {
      out.push({
        p: 90,
        title: '天底放到最后再发',
        text: '天底发动后本回合不能从额外特召怪兽。先把同步/融合做完，最后发它去找白之落胤。',
        tag: 'L8'
      });
    }
    if (count(hand, '白之落胤') > 0) {
      out.push({
        p: 88,
        title: '白之落胤：同步主线路',
        text: '①送烙印龙进墓特召自身 → ②拉白之圣女 → 解放圣女出轩辕的相剑师 → 同步黑衣龙 → ①出卡尔塔西亚 → 卡尔塔西亚融合赫焉龙。',
        tag: 'L1'
      });
    }
    if (count(hand, '惨绝戏之导化 阿卢绯尔') > 0) {
      out.push({
        p: 86,
        title: '阿卢绯尔：找烙印融合',
        text: 'NS 阿卢绯尔找烙印融合 → 卡组融合出灰烬龙（3700）→ 结束阶段灰烬龙③拉白之落胤。',
        tag: 'L2'
      });
    }
    if (count(hand, '烙印之气炎') > 0 && count(hand, '原初之神 法拉') === 0) {
      out.push({
        p: 82,
        title: '气炎：不吃费用的一换二',
        text: '展示手里的龙族，送灰烬龙进墓；不丢牌也能检索，结束阶段灰烬龙起跳白之落胤。',
        tag: 'L3'
      });
    }
    if (count(hand, '白之圣女 艾克利西亚') > 0) {
      out.push({
        p: 78,
        title: '白之圣女：同步三英找法拉',
        text: '对方场上有怪时特召 → 解放出白之落胤 → 白之落胤②拉圣女（调整）→ 同步三英 → ①找法拉，②无效并破坏。',
        tag: 'L4'
      });
    }
    if (count(hand, '落胤与圣女') > 0 && opp.length > 0) {
      out.push({
        p: 74,
        title: '落胤与圣女：速攻解场',
        text: '送「烙印龙 阿尔比昂」进墓 + 破坏对方 1 张表侧卡；烙印龙结束阶段再找一张烙印魔陷。',
        tag: 'L5'
      });
    }
    if (oppMaxAtk >= 3000 && !hasAny(names(hand), ['超融合', '禁忌的一滴', '阿尔白斯之落胤'])) {
      out.push({
        p: 60,
        title: '对方有 3000+ 打点，别硬撞',
        text: '优先用手坑拖到下一回合，或靠灰烬龙（3700）与之对换；没有解场牌就别把场面交出去。',
        tag: '通用'
      });
    }
    if (count(hand, '烙印融合') > 0) {
      out.push({
        p: 55,
        title: '烙印融合只有 1 张',
        text: '优先在「阿卢绯尔/姬特」之后用，先骗掉对方的灰；卡组素材选阿尔白斯 + 法拉（灰烬龙）。',
        tag: '通用'
      });
    }
    if (!out.length) {
      out.push({
        p: 40,
        title: '没有识别到手牌路线',
        text: '把对方场上的怪和你的手牌补进面板，或先用「识别屏幕」再扫一次；只有手坑就先过牌等下一回合。',
        tag: '通用'
      });
    }
    out.sort(function (a, b) { return b.p - a.p; });
    return out.slice(0, 5);
  }


  /* ---------------- 屏幕识别结果合并 ---------------- */

  /* 命中框的上下位置 → 归属：对方场地 / 我方场地 / 手牌 */
  function sideOf(cy, h) {
    var r = (cy || 0) / (h || 1);
    if (r < 0.52) return 'opp';
    if (r < 0.80) return 'mine';
    return 'hand';
  }

  function inBox(line, hit) {
    var lx = (line.x || 0) + (line.w || 0) / 2;
    var ly = (line.y || 0) + (line.h || 0) / 2;
    var hw = (hit.w || 0) / 2, hh = (hit.h || 0) / 2;
    return Math.abs(lx - hit.x) <= hw * 1.15 && ly >= hit.y - hh && ly <= hit.y + hh * 1.45;
  }

  /* 命中框附近（卡面下沿）的攻击力数字 */
  function atkNear(hit, lines) {
    var best = 0;
    (lines || []).forEach(function (l) {
      if (!inBox(l, hit)) return;
      var a = atkOf(l.t);
      if (a > best) best = a;
    });
    return best;
  }

  function tagOf(name) {
    for (var i = 0; i < DB.length; i++) if (DB[i].n === name) return DB[i].t;
    return '卡';
  }

  /**
   * 合并两条识别通道：
   *   hits  = 卡图模板匹配（方案二）[{n,s,x,y,w,h}]，n 为空表示有卡但没认出来
   *   lines = 分带放大 OCR（方案一）[{t,x,y,w,h}]
   * 返回 { opp, mine, hand, unknown, hits, lines }
   */
  function mergeScan(input) {
    var hits = (input && input.hits) || [];
    var lines = (input && input.lines) || [];
    var W = (input && input.w) || 1080;
    var H = (input && input.h) || 1920;
    var buckets = { opp: [], mine: [], hand: [] };
    var unknown = 0;
    var unknownBoxes = [];

    hits.forEach(function (hit) {
      if (!hit.n) {
        if ((hit.s || 0) >= 0.66 && unknownBoxes.length < 12) {
          unknown++;
          unknownBoxes.push({ x: hit.x, y: hit.y, w: hit.w, h: hit.h, s: hit.s });
        }
        return;
      }
      var side = sideOf(hit.y, H);
      buckets[side].push({
        name: hit.n,
        tag: tagOf(hit.n),
        atk: atkNear(hit, lines),
        conf: hit.s || 0,
        src: 'art',
        y: hit.y
      });
    });

    /* 模板没认出来、但文字里能读到的卡名，补一层（置信度低） */
    var covered = hits.filter(function (h) { return h.n; });
    lines.forEach(function (l) {
      var m = match(l.t);
      if (!m) return;
      var cy = (l.y || 0) + (l.h || 0) / 2;
      var near = covered.some(function (hit) {
        return Math.abs(hit.y - cy) < (hit.h || 100) * 0.6 && Math.abs(hit.x - ((l.x || 0) + (l.w || 0) / 2)) < (hit.w || 100) * 0.6;
      });
      if (near) return;
      buckets[sideOf(cy, H)].push({
        name: m.name,
        tag: m.tag,
        atk: atkOf(l.t),
        conf: 0.4,
        src: 'ocr',
        y: cy
      });
    });

    return {
      opp: dedupe(buckets.opp),
      mine: dedupe(buckets.mine),
      hand: dedupe(buckets.hand),
      unknown: unknown,
      unknownBoxes: unknownBoxes,
      hits: hits.length,
      lines: lines.length
    };
  }

  var api = {
    DB: DB,
    LINES: LINES,
    norm: norm,
    lev: lev,
    match: match,
    atkOf: atkOf,
    parse: parse,
    decide: decide,
    mergeScan: mergeScan,
    sideOf: sideOf
  };

  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.OverlayCore = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
