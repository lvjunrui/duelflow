/* 纯逻辑回归测试：node test/core.test.js */
const assert = require('assert');
const path = require('path');
const C = require(path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'core.js'));

let passed = 0;
function t(name, fn) { fn(); passed++; console.log('✓ ' + name); }

t('组合数', () => {
  assert.strictEqual(C.choose(5, 0), 1);
  assert.strictEqual(C.choose(5, 5), 1);
  assert.strictEqual(C.choose(40, 5), 658008);
  assert.strictEqual(C.choose(3, 5), 0);
});

t('40 张卡组、3 张抓手，起手至少 1 张 ≈ 33.8%', () => {
  const p = C.probAtLeastOne(40, 3, 5);
  assert.ok(Math.abs(p - 0.33755) < 0.002, 'got ' + p);
});

t('起手 5 张的期望张数 = 5*s/N（用至少 k 张反推单调性）', () => {
  const N = 40, s = 10;
  const p1 = C.probAtLeast(N, s, 1, 5);
  const p2 = C.probAtLeast(N, s, 2, 5);
  assert.ok(p1 > p2 && p2 > 0, 'P(>=1) 应大于 P(>=2)');
});

t('两组同时上手 = 1 - P(A缺) - P(B缺) + P(AB都缺)', () => {
  const N = 40, a = 9, b = 12, h = 5;
  const inc = 1 - C.choose(N - a, h) / C.choose(N, h) - C.choose(N - b, h) / C.choose(N, h)
    + C.choose(N - a - b, h) / C.choose(N, h);
  const got = C.probAllGroupsPresent(N, [a, b], h);
  assert.ok(Math.abs(got - inc) < 1e-9, `${got} vs ${inc}`);
});

t('0 张抓手时概率为 0', () => {
  assert.strictEqual(C.probAtLeastOne(40, 0, 5), 0);
  assert.strictEqual(C.probAllGroupsPresent(40, [0, 12], 5), 0);
});

t('卡组统计', () => {
  const cards = [
    { name: 'A', kind: 'starter', count: 3 },
    { name: 'B', kind: 'starter', count: 2 },
    { name: 'C', kind: 'handtrap', count: 9 }
  ];
  const s = C.deckStats(cards);
  assert.strictEqual(s.deckSize, 14);
  assert.strictEqual(s.starters, 5);
  assert.strictEqual(s.handtrap, 9);
});

t('手牌匹配：命中/缺卡/排序', () => {
  const combos = [
    { name: '双卡线', reqs: [{ name: 'A', n: 1 }, { name: 'B', n: 1 }] },
    { name: '单卡线', reqs: [{ name: 'A', n: 1 }] },
    { name: '三卡线', reqs: [{ name: 'A', n: 1 }, { name: 'B', n: 1 }, { name: 'C', n: 1 }] }
  ];
  const ranked = C.rankCombos(combos, ['A', 'B']);
  assert.deepStrictEqual(ranked.map(r => r.combo.name), ['单卡线', '双卡线', '三卡线']);
  assert.strictEqual(ranked[0].ok, true);
  assert.strictEqual(ranked[2].missing.length, 1);
  assert.strictEqual(ranked[2].ratio, 2 / 3);
});

t('同名多张需求（如 2 张同名卡）', () => {
  const m = C.matchCombo({ reqs: [{ name: 'A', n: 2 }] }, { A: 2 });
  assert.strictEqual(m.ok, true);
  const m2 = C.matchCombo({ reqs: [{ name: 'A', n: 2 }] }, { A: 1 });
  assert.strictEqual(m2.ok, false);
  assert.strictEqual(m2.missing[0].have, 1);
});

t('卡组覆盖度', () => {
  const combo = { reqs: [{ name: 'A', n: 1 }, { name: 'Z', n: 1 }] };
  assert.strictEqual(C.reqCoverage(combo, [{ name: 'A' }]), 0.5);
  assert.strictEqual(C.reqCoverage({}, []), 1);
});

t('百分比格式化', () => {
  assert.strictEqual(C.fmtPct(0.33755), '33.8%');
  assert.strictEqual(C.fmtPct(0), '0.0%');
});

console.log(`\n${passed} 项全部通过`);
