#!/usr/bin/env python3
"""核对保留的正式网页基线与原生可见文案；无障碍描述单独处理。"""
import hashlib
import json
import pathlib
import re
import xml.etree.ElementTree as ET

root = pathlib.Path(__file__).resolve().parents[1]
baseline = json.loads((root / 'ui-copy-baseline.json').read_text())
for name, digest in baseline['assets_sha256'].items():
    assert hashlib.sha256((root / name).read_bytes()).hexdigest() == digest, f'正式视觉基线被修改: {name}'
for element in ET.parse(root / 'app/src/main/res/values/strings.xml').getroot():
    name = element.attrib['name']
    if name.startswith('a11y_'):
        continue
    assert baseline['visible_strings'].get(name) == element.text, f'新增/改变可见文案: {name}={element.text}'
models = (root / 'app/src/main/java/com/newspaper/helper/Models.kt').read_text()
for name, subtitle in re.findall(r'\b[A-Z]+\("([^"\n]+)", "([^"\n]+)"', models):
    assert name in baseline['source_names_and_subtitles'], f'来源名称发生改变: {name}'
    assert subtitle in baseline['source_names_and_subtitles'], f'来源副标题发生改变: {subtitle}'
for path in (root / 'app/src/main/java').rglob('*'):
    if path.suffix not in ('.kt', '.java'):
        continue
    source = path.read_text()
    assert not re.search(r'android\.webkit|\bWebView\b|evaluateJavascript|loadDataWithBaseURL|Html\.fromHtml', source), f'存在网页业务渲染: {path}'
    for text in re.findall(r'(?:\.text\s*=|setText\()\s*"([^"$]*)"', source):
        if re.search('[\u3400-\u9fff]', text):
            assert text in baseline['visible_strings'].values(), f'未审计的硬编码界面文案: {path}: {text}'
for path in (root / 'app/src/main/res/layout').glob('*.xml'):
    for element in ET.parse(path).iter():
        for key, value in element.attrib.items():
            if key.rsplit('}', 1)[-1] in ('text', 'hint'):
                assert value.startswith('@string/ui_'), f'布局含未审计文案: {path}: {value}'
print(f"文案审计通过：{len(baseline['visible_strings'])} 条可见资源；正式网页资产保持原样；无网页业务渲染。")
