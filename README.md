# DuelFlow 展开助手

游戏王大师决斗（Master Duel）的**离线展开辅助工具**。装到手机上，实战时点出手牌，工具直接告诉你哪条展开线能走、缺哪张卡、下一步该点什么。

## 功能

- **实战**：从卡组点出手牌 → 所有展开路线按匹配度自动排序（命中率条 + 缺哪张），点「开始展开」进入逐步播放器，一步一步跟着做。
- **卡组**：录入自己的牌，标成 抓手 / 展开点 / 手坑 / 终端；实时算起手概率（至少 1 抓手、抓手与展开点同时上手、至少 1 手坑）。
- **路线**：把常用展开线写下来——需要哪几张卡、每一步操作。支持编辑、删除、导入导出 JSON。
- **浮窗**（0.4）：悬浮窗 + 截屏识别，实战时不用手动点手牌。**录屏必须授权「整个屏幕」**（Android 14+ 的系统弹窗默认是「共享单个应用」，那样切到 MD 就截不到画面；0.4.3 起 App 会主动申请整个屏幕）。两条通道同时跑：
  - **卡图模板匹配**：内置本卡组 49 张卡的小图模板，按画框做归一化互相关，认卡比 OCR 稳，还会给出匹配度；
  - **分带放大 OCR**：整屏切成上下两条带、放大约 1.5 倍再识别，卡面小字更容易读；
  - 两路结果合并成「对方场地 / 我方场地 / 手牌」，认错的点一下就能删，认漏的可以手动补。
  - 状态行会显示识别走到哪一步（截屏 → 认卡 → 文字），20 秒没结果会自己报超时，不会一直卡着不动。
  - 0.4.6：手牌改成**按版面卡位识别**。真机帧实测：MD 手牌永远是屏幕底部居中一排，卡宽 0.0605×屏宽、间距 0.0557×屏宽、顶边 0.855×屏高；按这个几何定位每个卡位，再在卡位附近做 ±16px/±3% 的对齐搜索，卡图区仍取 0.085~0.915 × 0.14~0.65。真帧上 5 张手牌全部认对（0.75~0.96，与第二名差 0.25~0.55，132ms）。全屏搜索会把左右两侧的火焰/石纹当成卡（一次 81 个假命中，0.55~0.70），所以场上只在中间 0.28~0.74 × 0.05~0.80 区域搜，且只报认出名字的。
  - 0.4.5：匹配尺度放宽到屏宽 4.5%~21.5%（原来只搜 9%~21%，手牌只有屏宽 5.5% 左右，根本不在搜索范围内），工作分辨率从 540 提到 1080；合成场景召回 86%→97%。浮窗加「存图」，把 App 实际截到的那一帧存到「下载/DSH-Go/duelflow-shots/」，用来离线调阈值。
  - 0.4.4 修掉了「识别屏幕点了没反应」的真凶：JS 调原生 `capture()` 时多传了一个参数，Android 的 WebView 桥会直接抛错，而旧代码把异常吞了——所以 0.3~0.4.3 的浮窗识别其实一次都没跑到原生代码。`test/overlay.dom.test.js` 用模拟桥（按 gin 的规则校验参数个数）守着这个契约。
- 数据全部存在手机本地（localStorage），**不联网、无网络权限**，不上传任何东西。

## 安装（推荐）

在手机浏览器打开：

```
https://github.com/<你的账号>/duelflow/releases/latest/download/DuelFlow.apk
```

下载后点安装（首次需要允许「安装未知来源应用」）。APK 是调试签名，个人自用足够。

## 构建

GitHub Actions 自动构建：推送到 `main` 或在 Actions 里手动触发，产物会挂到 Release。

本地构建需要 JDK 17 + Android SDK（aarch64 设备上官方 SDK 的 aapt2 跑不起来，建议用云端）：

```bash
gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 测试

```bash
node test/core.test.js           # 概率/匹配逻辑
node test/overlay.test.js        # 浮窗识别结果合并逻辑
node .devtest/ui.test.js         # 主界面（需先 npm i jsdom）
node .devtest/overlay.test.js    # 浮窗界面

# 卡图模板匹配（纯 Java，桌面 JVM 直接跑）
javac -d /tmp/dfout app/src/main/java/com/duelflow/app/CardMatcher.java test/java/MatcherTest.java
java -cp /tmp/dfout MatcherTest app/src/main/assets/cards

# 拿真实对局截图量耗时/命中（调阈值用，不进 CI）
javac -d /tmp/dfout app/src/main/java/com/duelflow/app/CardMatcher.java test/java/RealShotTest.java
java -cp /tmp/dfout RealShotTest app/src/main/assets/cards 截图.jpg
```

## 结构

```
app/src/main/assets/    H5 应用（index.html / app.js / core.js / style.css）
app/src/main/java/      WebView 壳（MainActivity.java）
test/, .devtest/        逻辑与界面测试
.github/workflows/      云端打包
tools/genicon.py        图标生成
```

## 说明

- 卡名、展开路线由你自己录入；工具不内置卡池，也不做任何联网查询。
- 「示例数据」按钮会填一套演示用的卡组和路线，可以随时清空。
