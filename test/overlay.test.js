/* overlay-core 的纯逻辑测试 */
const assert = require('assert');
const C = require('../app/src/main/assets/overlay-core.js');

let n = 0;
function t(name, fn) { n++; try { fn(); console.log('ok  ' + name); } catch (e) { console.log('FAIL ' + name + ' → ' + e.message); process.exitCode = 1; } }

t('norm 去空白与标点', () => {
  assert.strictEqual(C.norm(' 白之落胤 '), '白之落胤');
  assert.strictEqual(C.norm('烙印融合'), '烙印融合');
});

t('精确匹配卡名', () => {
  assert.strictEqual(C.match('灰流丽 ATK 0').name, '灰流丽');
  assert.strictEqual(C.match('烙印融合').name, '烙印融合');
  assert.strictEqual(C.match('原初之神 法拉').name, '原初之神 法拉');
});

t('容忍 OCR 错字', () => {
  const m = C.match('白之落{胤');
  assert.ok(m && m.name === '白之落胤', JSON.stringify(m));
});

t('ATK 解析', () => {
  assert.strictEqual(C.atkOf('冰剑龙 ATK 3000'), 3000);
  assert.strictEqual(C.atkOf('3000/2500'), 3000);
  assert.strictEqual(C.atkOf('没有数字'), 0);
});

t('按 y 分边 + 去重', () => {
  const lines = [
    { t: '冰剑龙 镜翠幻种 ATK 3000', y: 120 },
    { t: '灰流丽 ATK 0', y: 140 },
    { t: '烙印融合', y: 1200 },
    { t: '烙印融合', y: 1240 },
    { t: '无关文字', y: 600 }
  ];
  const p = C.parse(lines, 1080, 1920);
  assert.deepStrictEqual(p.opp.map(x => x.name), ['冰剑龙 镜翠幻种', '灰流丽']);
  assert.strictEqual(p.mine.length, 1);
  assert.strictEqual(p.opp[0].atk, 3000);
});

t('超融合优先吃融合怪', () => {
  const out = C.decide({ hand: ['超融合'], opp: [{ name: '冰剑龙 镜翠幻种', tag: '融合怪', atk: 3000 }] });
  assert.strictEqual(out[0].tag, 'L7');
});

t('气炎+法拉 排第一位时给出抢怪', () => {
  const out = C.decide({ hand: ['烙印之气炎', '原初之神 法拉'], opp: [] });
  assert.ok(out.some(x => x.tag === 'L6'));
});

t('天底会提示最后发', () => {
  const out = C.decide({ hand: ['天底的使徒', '白之落胤'], opp: [] });
  const s = out.map(x => x.title).join('|');
  assert.ok(s.includes('天底'), s);
  assert.ok(s.includes('白之落胤'), s);
});

t('无手牌也有兜底建议', () => {
  const out = C.decide({ hand: [], opp: [] });
  assert.strictEqual(out.length, 1);
  assert.ok(out[0].text.length > 5);
});

t('所有路线需求卡都在卡库或卡组里', () => {
  const names = new Set(C.DB.map(c => c.n));
  C.LINES.forEach(l => l.reqs.forEach(r => assert.ok(names.has(r), l.id + ' 缺 ' + r)));
});

console.log('\n' + n + ' 项完成');
