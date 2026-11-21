/* 浮窗 DOM 冒烟测试：用一个假的 WebView 桥（按 gin 的规则校验参数个数）跑 overlay.js。
   目的：确保「识别屏幕」真的会调用到原生 capture()，而不是被 try/catch 静默吞掉。
   跑法：node test/overlay.dom.test.js */
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const assets = path.join(__dirname, '..', 'app/src/main/assets');
const coreCode = fs.readFileSync(path.join(assets, 'overlay-core.js'), 'utf8');
const overlayCode = fs.readFileSync(path.join(assets, 'overlay.js'), 'utf8');

/* ---- 迷你 DOM ---- */
function makeEl(id) {
  const el = {
    id,
    textContent: '',
    innerHTML: '',
    value: '',
    listeners: {},
    addEventListener(type, fn) {
      (this.listeners[type] = this.listeners[type] || []).push(fn);
    },
    getAttribute() {
      return null;
    },
    click() {
      this.fire('click');
    },
    fire(type, ev) {
      (this.listeners[type] || []).forEach((fn) => fn(ev || { target: el }));
    }
  };
  return el;
}

function makeWindow() {
  const els = {};
  const doc = {
    getElementById(id) {
      if (!els[id]) els[id] = makeEl(id);
      return els[id];
    },
    addEventListener() {}
  };

  const calls = [];
  /* 按 Android WebView（gin java bridge）的规则：参数个数必须和 Java 方法一致，
     多传一个 undefined 也会抛 "called with N arguments, expected M"。
     桥的方法签名见 OverlayService.Bridge。 */
  const sig = { capture: 0, close: 0, focus: 1, copy: 1, toast: 1, snap: 0 };
  const DFNative = {};
  Object.keys(sig).forEach((name) => {
    DFNative[name] = function () {
      const got = arguments.length;
      const want = sig[name];
      if (got !== want) {
        throw new Error("Method '" + name + "' called with " + got + ' arguments, expected ' + want);
      }
      calls.push({ name, args: Array.prototype.slice.call(arguments) });
    };
  });

  const store = {};
  const win = {
    OverlayCore: null,
    DFNative,
    document: doc,
    localStorage: {
      getItem(k) {
        return Object.prototype.hasOwnProperty.call(store, k) ? store[k] : null;
      },
      setItem(k, v) {
        store[k] = String(v);
      }
    },
    setTimeout: (fn) => setTimeout(fn, 0),
    clearTimeout: (h) => clearTimeout(h),
    console
  };
  win.window = win;
  return { win, els, calls, store };
}

function boot() {
  const env = makeWindow();
  const ctx = vm.createContext(env.win);
  vm.runInContext(coreCode, ctx, { filename: 'overlay-core.js' });
  vm.runInContext(overlayCode, ctx, { filename: 'overlay.js' });
  return env;
}

let failed = 0;
function ok(cond, msg) {
  if (cond) {
    console.log('  ✓ ' + msg);
  } else {
    console.log('  ✗ ' + msg);
    failed++;
  }
}

console.log('浮窗 DOM 冒烟测试');

/* 1. 点「识别屏幕」必须真的调到原生 capture() */
{
  const { els, calls } = boot();
  els.scan.click();
  const cap = calls.filter((c) => c.name === 'capture');
  ok(cap.length === 1, '点识别屏幕调用了一次原生 capture()');
  ok(cap.length === 1 && cap[0].args.length === 0, 'capture() 不传多余参数（Java 方法是零参数）');
  ok(!/通道未连接/.test(els.status.textContent), '状态行没有误报「浮窗通道未连接」');
  ok(/正在截屏/.test(els.status.textContent), '状态行进入「正在截屏」');
}

/* 1b. 存图按钮走 snap() 而不是 capture() */
{
  const { els, calls } = boot();
  els.snap.click();
  ok(calls.some((c) => c.name === 'snap'), '存图按钮调用 snap()');
  ok(!calls.some((c) => c.name === 'capture'), '存图不重复触发 capture()');
}

/* 2. 原生阶段提示、卡图与文字两路结果都能落地 */
{
  const { els, win } = boot();
  els.scan.click();
  win.__onStatus('已截到 2712×1220，认卡中…');
  ok(/已截到 2712×1220/.test(els.status.textContent), '阶段提示写进状态行');

  win.__onCards([], 2712, 1220, 88, '', '');
  win.__onOcr([]);
  ok(/卡图 0 处/.test(els.status.textContent), '两路结果合并后显示统计');
  ok(!/通道未连接/.test(els.status.textContent), '结果出来后没有通道报错');

  /* 两路都到齐后能再次点识别 */
  els.scan.click();
  ok(/正在截屏/.test(els.status.textContent), '一次识别结束后可以再点识别');
}

/* 3. 原生报错要显示出来，并解除「进行中」 */
{
  const { els, win } = boot();
  els.scan.click();
  win.__onOcrError('没截到画面：请确认系统弹窗选的是「共享整个屏幕」');
  ok(/没截到画面/.test(els.status.textContent), '原生错误显示在状态行');
  els.scan.click();
  ok(/正在截屏/.test(els.status.textContent), '报错后还能再点识别（没有卡在 busy）');
}

/* 4b. 原生给了 side 就按 side 归位（魔陷区在 0.82H，不能按 y 猜成手牌） */
{
  const { els, win } = boot();
  els.scan.click();
  win.__onCards([
    { n: '赫之烙印', s: 0.9, x: 1360, y: 1000, w: 144, h: 210, side: 'mine' },
    { n: '白之圣女 艾克利西亚', s: 0.88, x: 900, y: 1130, w: 164, h: 240, side: 'hand' }
  ], 2712, 1220, 60, '', '');
  win.__onOcr([]);
  ok(els.minen.textContent === 1, 'side=mine 的盖牌归到我方场地');
  ok(els.handn.textContent === 1, 'side=hand 的手牌归到手牌');
}

/* 4. 卡牌落到对应区块 */
{
  const { els, win } = boot();
  els.scan.click();
  win.__onCards([
    { n: '白之圣女 艾克利西亚', s: 0.9, x: 100, y: 100, w: 60, h: 88 },
    { n: '阿不思的落胤', s: 0.88, x: 100, y: 1600, w: 60, h: 88 }
  ], 1220, 2712, 50, '', '');
  win.__onOcr([]);
  ok(els.oppn.textContent === 1, '上方命中算对方场地');
  ok(els.minen.textContent === 1, '下方命中算我方场地');
}

console.log(failed ? '\n失败 ' + failed + ' 项' : '\n全部通过');
process.exit(failed ? 1 : 0);
