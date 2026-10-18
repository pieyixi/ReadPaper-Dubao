#!/usr/bin/env python3
"""仅用于开发试听，不打包进 APK；只发送内置评测稿，不读取用户文章或粘贴文本。"""
import argparse
import asyncio
import hashlib
import html
import importlib.metadata
import json
import os
from pathlib import Path
import shutil
import subprocess
import time


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def write_page(output, corpus, report):
    cases = {case["id"]: case for case in corpus["cases"]}
    labels = {"news": "时政语气", "numbers": "数字与单位", "names": "多音字", "mixed": "中英混排", "headings": "标题与段落"}
    voices = list(dict.fromkeys(item["voice"] for item in report["results"]))
    groups = []
    for index, voice in enumerate(voices):
        parts = [f"<section><h2>候选 {chr(65 + index)}</h2>"]
        for item in report["results"]:
            if item["voice"] != voice:
                continue
            case = cases[item["case"]]
            parts.append(f"<h3>{html.escape(labels[case['id']])}</h3>")
            if item["status"] == "ok":
                parts.append(f'<audio controls preload="none" src="{html.escape(item["file"], quote=True)}"></audio>')
            else:
                parts.append("<p>本次未生成有效音频。</p>")
            parts.append(f"<details><summary>核对原文</summary><p>{html.escape(case['text'])}</p>"
                         f"<p>{html.escape('；'.join(case['checks']))}</p></details>")
        parts.append(f"<details><summary>查看音色名称</summary><p>{html.escape(voice)}</p></details></section>")
        groups.append("".join(parts))
    page = '''<!doctype html><html lang="zh-CN"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>读报语音试听</title>
<style>body{max-width:1000px;margin:40px auto;padding:0 20px;background:#f7f7f5;color:#232323;font:16px/1.8 system-ui,sans-serif}
h1{font-size:26px}h2{font-size:21px}h3{font-size:16px;margin:20px 0 8px}main{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,260px),1fr));gap:16px}
section{background:white;border:1px solid #ddd;border-radius:12px;padding:20px}audio{width:100%}summary{cursor:pointer;color:#555}details{margin:12px 0}p{white-space:pre-wrap}small{color:#555}</style>
<h1>读报语音试听</h1><p>人工编写的评测稿，非真实新闻。先比较语气和听感，再展开名称。所有样音均为原速、原音高，未做音效加工。</p>
<p>重点听：是否庄重自然，数字和多音字是否准确，长句是否有逻辑停顿，句末是否拖腔。这些结果尚未经过人工听音验收。</p><main>'''
    page += "".join(groups)
    page += '''</main><p><small>这是独立研发试听页，不是读报 App 界面。本页只播放已生成的本地音频，没有在线请求。合成阶段使用了非官方 Edge 在线接入，其可用性没有服务承诺。</small></p>
<script>document.querySelectorAll('audio').forEach(a=>a.addEventListener('play',()=>document.querySelectorAll('audio').forEach(b=>{if(a!==b)b.pause()})))</script></html>'''
    (output / "试听.html").write_text(page, encoding="utf-8")


async def run(args):
    if args.render_only:
        output = args.output.resolve()
        write_page(output, json.loads((output / "corpus.json").read_text(encoding="utf-8")),
                   json.loads((output / "report.json").read_text(encoding="utf-8")))
        return 0
    import edge_tts

    corpus = json.loads(Path(__file__).with_name("speech-corpus.json").read_text(encoding="utf-8"))
    requested = set(args.cases.split(","))
    cases = [case for case in corpus["cases"] if case["id"] in requested]
    if requested != {case["id"] for case in cases}:
        raise ValueError("Unknown corpus case")
    voices = args.voices.split(",")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    report = {"library": importlib.metadata.version("edge-tts"),
              "started_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
              "subjective_listening": "NOT EVALUATED", "rate": args.rate,
              "pitch": "+0Hz", "volume": "+0%", "results": []}
    # 不把带凭证的代理地址或第三方请求 URL 写入报告。
    proxy = os.getenv("DUBAO_SPEECH_PROXY")
    try:
        available = await asyncio.wait_for(edge_tts.list_voices(proxy=proxy), timeout=20)
        selected = [v for v in available if v["ShortName"] in voices]
        write_json(output / "voices.json", selected)
        report["voice_list"] = "ok"
    except Exception as error:
        report["voice_list"] = type(error).__name__
    for voice in voices:
        for case in cases:
            path = output / f"{voice}_{case['id']}.mp3"
            if path.exists():
                raise FileExistsError(f"Choose a new output directory: {path.name}")
            result = {"voice": voice, "case": case["id"], "file": path.name,
                      "text_sha256": hashlib.sha256(case["text"].encode()).hexdigest(),
                      "status": "failed"}
            started = time.perf_counter()
            first_audio = None
            boundaries = []
            try:
                communicate = edge_tts.Communicate(case["text"], voice, rate=args.rate,
                                                  pitch="+0Hz", volume="+0%", proxy=proxy,
                                                  connect_timeout=10, receive_timeout=20)
                async def receive():
                    nonlocal first_audio
                    with path.open("xb") as stream:
                        async for chunk in communicate.stream():
                            if chunk["type"] == "audio":
                                if first_audio is None:
                                    first_audio = time.perf_counter() - started
                                stream.write(chunk["data"])
                            elif chunk["type"] in ("WordBoundary", "SentenceBoundary"):
                                boundaries.append(chunk)
                await asyncio.wait_for(receive(), timeout=45)
                if not first_audio or not path.stat().st_size:
                    raise RuntimeError("EmptyAudio")
                result.update(status="ok", first_packet_seconds=round(first_audio, 3),
                              bytes=path.stat().st_size,
                              sha256=hashlib.sha256(path.read_bytes()).hexdigest())
                write_json(path.with_suffix(".boundaries.json"), boundaries)
                ffprobe = args.ffprobe or shutil.which("ffprobe")
                if ffprobe:
                    probe = subprocess.run([ffprobe, "-v", "error", "-show_entries",
                                            "format=duration:stream=codec_name,sample_rate,channels",
                                            "-of", "json", str(path)], capture_output=True,
                                           text=True, check=True, timeout=10)
                    result["audio_probe"] = json.loads(probe.stdout)
            except Exception as error:
                # 类名和 HTTP 状态足以区分网络、合成和解码错误，不记录含 token 的 URL。
                result.update(status="failed", error_type=type(error).__name__)
                if getattr(error, "status", None):
                    result["http_status"] = error.status
                # 不把失败请求留下的半截音频当作样音。
                path.unlink(missing_ok=True)
                path.with_suffix(".boundaries.json").unlink(missing_ok=True)
            result["total_seconds"] = round(time.perf_counter() - started, 3)
            report["results"].append(result)
            write_json(output / "report.json", report)
            print(json.dumps(result, ensure_ascii=False), flush=True)
    write_json(output / "corpus.json", corpus)
    write_page(output, corpus, report)
    return 0 if all(item["status"] == "ok" for item in report["results"]) else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="Use a fresh directory outside the repository")
    parser.add_argument("--voices", default="zh-CN-YunyangNeural,zh-CN-YunxiNeural,zh-CN-XiaoxiaoNeural")
    parser.add_argument("--cases", default="news,numbers")
    parser.add_argument("--rate", default="+0%")
    parser.add_argument("--ffprobe", help="Optional existing ffprobe binary; does not download it")
    parser.add_argument("--render-only", action="store_true", help="Rebuild the local listening page without network or edge-tts")
    raise SystemExit(asyncio.run(run(parser.parse_args())))
