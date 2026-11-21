"""生成浮窗模板匹配用的卡图库。

输入：
  --deck  卡组索引 JSON：{中文名: {id, en, type, ...}}（本项目用 /root/workspace/deck_ids.json）
  --cards 原始卡图目录：{id}.jpg
输出：
  app/src/main/assets/cards/{id}.jpg   缩到 96px 宽的小图（匹配用，模板在端侧现算）
  app/src/main/assets/cards/manifest.json   [{id, cn, en, atk, kind}]

用法：
  python3 tools/gen_card_templates.py --deck /root/workspace/deck_ids.json --cards /tmp/cards
"""
import argparse
import json
import os
import re
import sys

from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "cards")
WIDTH = 96


def kind_of(type_text):
    t = type_text or ""
    if "融合" in t:
        return "融合怪"
    if "同步" in t:
        return "同步怪"
    if "超量" in t:
        return "超量怪"
    if "连接" in t:
        return "连接怪"
    if "魔法" in t:
        return "魔法"
    if "陷阱" in t:
        return "陷阱"
    return "怪兽"


def atk_of(type_text):
    # 形如 "[★4] 1800/0"
    m = re.search(r"\[★\d+\]\s*(\d+)/(\d+|-)", type_text or "")
    if m:
        return int(m.group(1))
    m = re.search(r"(\d+)/(\d+|-)", type_text or "")
    return int(m.group(1)) if m else 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--deck", required=True)
    ap.add_argument("--cards", required=True)
    ap.add_argument("--out", default=OUT)
    args = ap.parse_args()

    deck = json.load(open(args.deck, encoding="utf-8"))
    os.makedirs(args.out, exist_ok=True)

    manifest = []
    missing = []
    total = 0
    for cn, info in deck.items():
        if isinstance(info, dict):
            cid = info.get("id")
            en = info.get("en", "")
            typ = info.get("type", "")
        else:
            cid, en, typ = info, "", ""
        if not cid:
            missing.append((cn, "no id"))
            continue
        src = os.path.join(args.cards, "%d.jpg" % cid)
        if not os.path.exists(src):
            missing.append((cn, cid))
            continue
        im = Image.open(src).convert("RGB")
        w, h = im.size
        im2 = im.resize((WIDTH, max(1, round(WIDTH * h / w))), Image.LANCZOS)
        dst = os.path.join(args.out, "%d.jpg" % cid)
        im2.save(dst, "JPEG", quality=88, optimize=True)
        total += os.path.getsize(dst)
        manifest.append({
            "id": cid,
            "cn": cn,
            "en": en,
            "atk": atk_of(typ),
            "kind": kind_of(typ),
            "file": "%d.jpg" % cid,
        })

    manifest.sort(key=lambda x: x["cn"])
    with open(os.path.join(args.out, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1)

    print("模板 %d 张，合计 %.1f KB" % (len(manifest), total / 1024.0))
    if missing:
        print("缺图 %d：" % len(missing), missing, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
