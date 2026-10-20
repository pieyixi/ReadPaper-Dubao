"""仅用本次云端实际报告生成发行附件；缺少关键设备/在线证据时禁止发布。"""
from pathlib import Path
import hashlib
import json
import os
import re
import shutil
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "app/build"
CERTIFICATE = "0c22a51a5eda729e3e45e742e7cbd326484120b4fc924c0c6e9ddd847b4a9669"


def prepare():
    signature = json.loads((BUILD / "release-evidence/verification.json").read_text(encoding="utf-8"))
    assert (signature["versionName"], signature["versionCode"], signature["debuggable"]) == ("2.0.3", 11, False)
    assert signature["certificateSha256"] == CERTIFICATE
    assert signature["signatureV1"] is True and signature["signatureV2"] is True
    apk = BUILD / "outputs/apk/release/app-release.apk"
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    assert signature["sha256"] == digest and signature["bytes"] == apk.stat().st_size
    expected = {
        "reports/tests/offline-regression/index.html": (43, 1),
        "reports/tests/testDebugUnitTest/index.html": (1, 0),
        "topbar-evidence/reports-320/connected/debug/index.html": (10, 0),
        "topbar-evidence/reports-390/connected/debug/index.html": (10, 0),
        "topbar-evidence/reports-motion/connected/debug/index.html": (5, 0),
        "speech-cache-evidence/connected/debug/index.html": (7, 0),
        "reports/androidTests/connected/debug/index.html": (4, 0),
    }
    reports = {}
    for name, (tests, skipped) in expected.items():
        text = (BUILD / name).read_text(encoding="utf-8")
        counters = {key: value for value, key in re.findall(r'<div class="counter">(.*?)</div>\s*<p>(.*?)</p>', text, re.S)}
        assert int(counters["tests"]) == tests and int(counters["failures"]) == 0, (name, counters)
        assert int(counters.get("skipped", counters.get("ignored", 0))) == skipped, (name, counters)
        reports[name] = counters
    flash = json.loads((BUILD / "topbar-evidence/screenshots/click-color-flash.json").read_text(encoding="utf-8"))
    assert flash["actual_touch_clicks"] and flash["rapid_back_stable"] and not flash["unexpected_layers"]
    assert len(flash["checks"]) == 21
    for check in flash["checks"]:
        assert check["frames"] > 0 and len(check["samples"]) == 3
        assert all(sample["tinted_ratio"] < .3 for sample in check["samples"])
    notes = json.loads((BUILD / "speech-evidence/android/pasted-playback.json").read_text(encoding="utf-8"))
    for flag in ("online_mp3", "private_cache", "manual_start_only", "notification_opens_paused_note",
                 "home_badge_opens_playing_note", "editing_cancels_old_audio", "edited_paragraph_prefetched",
                 "loop_uses_latest_original_text", "paused_edit_stays_paused", "stop_rejects_delayed_edit"):
        assert notes[flag] is True, flag
    papers = json.loads((BUILD / "speech-evidence/android/playback.json").read_text(encoding="utf-8"))
    assert {paper["paper"] for paper in papers} == {"STUDY", "ORGANIZATION", "PEOPLE", "SOUTH", "QIUSHI"}
    for paper in papers:
        for flag in ("notification_cold_start", "notification_warm_start", "home_playing_badge", "back_returns_home"):
            assert paper[flag] is True, (paper, flag)
    modes = json.loads((BUILD / "speech-evidence/android/repeat-modes.json").read_text(encoding="utf-8"))
    assert modes["first_segment_reused_without_regeneration"] is True
    captions = json.loads((BUILD / "speech-evidence/android/caption-playback.json").read_text(encoding="utf-8"))
    assert all(captions[key] is True for key in ("caption_not_queued", "body_after_photo_spoken", "legacy_caption_anchor_resumed_body", "loop_skips_captions", "photo_only_stops_cleanly", "original_blocks_retained"))
    reading = {}
    for width in (320, 390):
        checks = json.loads((BUILD / f"topbar-evidence/screenshots/{width}-caption-reading.json").read_text(encoding="utf-8"))
        assert len(checks) == 3 and {check["theme"] for check in checks} == {0, 1, 2}
        assert all(check["visible_pixels_identical"] is True for check in checks)
        reading[str(width)] = checks
    lint = {}
    for kind in ("debug", "release"):
        issues = ET.parse(BUILD / f"reports/lint-results-{kind}.xml").getroot().findall("issue")
        assert not any(issue.get("severity") in ("Error", "Fatal") for issue in issues)
        lint[kind] = {"errors": 0, "warnings": sum(issue.get("severity") == "Warning" for issue in issues)}
    assert "Status: ok" in (BUILD / "release-evidence/cold-start.txt").read_text(encoding="utf-8")
    assert (BUILD / "release-evidence/process.txt").read_text(encoding="utf-8").strip().isdigit()
    assert "FATAL EXCEPTION" not in (BUILD / "release-evidence/crash-log.txt").read_text(encoding="utf-8")
    sha = os.environ["GITHUB_SHA"]
    run = f'https://github.com/{os.environ["GITHUB_REPOSITORY"]}/actions/runs/{os.environ["GITHUB_RUN_ID"]}'
    report = dict(source=sha, run=run, signature=signature, reports=reports, lint=lint,
                  colorFlash=flash, notePlayback=notes, paperPlayback=papers, repeatModes=modes,
                  captionPlayback=captions, captionReadingPixels=reading,
                  limitations=["未进行用户真机验收或人耳听感评分；五来源设备播放使用真实原页回归样本，语音为真实联网合成。"])
    target = BUILD / "release-evidence/publish"
    target.mkdir(parents=True, exist_ok=True)
    name = "dubao-2.0.3-release.apk"
    shutil.copy2(apk, target / name)
    (target / (name + ".sha256")).write_text(digest + "  " + name + "\n", encoding="ascii")
    (target / "release-validation.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    text = f"""正式版 **2.0.3**（versionCode 11），原始签名，支持覆盖升级。

- 阅读页面完整保留配图和标注，语音跳过结构上确认的图注、摄影署名及求是配图说明。
- 图片邻接、语义标签、图注样式和专门排版联合判断，不按“摄、重要讲话”等词删除普通正文；不确定的段落继续朗读。
- 自己粘贴的文本完整朗读；正文已有音频继续复用，旧播放位置在图注时转到后续正文，循环也跳过图注。
- 只有图片和确认图注的文章不生成空播放任务；断网升级图注标记时仍可阅读近期缓存。
- 保留 2.0.2 的转场、通知/主页播放入口、字号和求是页尾修复。

云端验证：JVM 42 项通过、1 项环境条件跳过；独立联网语音 1 项通过；Android 320/390dp、动效、缓存和在线播放共 36 项通过。三主题共 21 次真实入口点按并采集中间帧；五来源通知冷/热启动、主页耳机跳转及便签手动开读均通过。Debug/Release lint 无错误，原签名正式包冷启动通过。

图注专项：五来源原始正文样本、误判反例、音频缓存复用和旧位置迁移通过；320/390dp 各三主题切换朗读标记前后的阅读区像素完全一致；真实在线队列与循环跳过图注，配图后的普通正文仍播放。

真机手感和人耳听感尚未验收。五来源设备播放采用真实原页回归样本，语音为真实联网合成。

源码：`{sha}` · [完整云端检查]({run})。附件 JSON 保存检查计数、跳转及闪屏证据；SHA-256：`{digest}`。
"""
    (target / "release-validation.md").write_text(text, encoding="utf-8")
    print(json.dumps(dict(directory=str(target), sha256=digest, source=sha, run=run)))


if __name__ == "__main__":
    prepare()
