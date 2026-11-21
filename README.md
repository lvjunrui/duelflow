# DuelFlow 展开助手

游戏王大师决斗（Master Duel）的**离线展开辅助工具**。装到手机上，实战时点出手牌，工具直接告诉你哪条展开线能走、缺哪张卡、下一步该点什么。

## 功能

- **实战**：从卡组点出手牌 → 所有展开路线按匹配度自动排序（命中率条 + 缺哪张），点「开始展开」进入逐步播放器，一步一步跟着做。
- **卡组**：录入自己的牌，标成 抓手 / 展开点 / 手坑 / 终端；实时算起手概率（至少 1 抓手、抓手与展开点同时上手、至少 1 手坑）。
- **路线**：把常用展开线写下来——需要哪几张卡、每一步操作。支持编辑、删除、导入导出 JSON。
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
node test/core.test.js        # 概率/匹配逻辑
node .devtest/ui.test.js      # 界面（需先 npm i jsdom）
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
