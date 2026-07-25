#!/usr/bin/env python3
"""修复行尾空白和尾部空格，保留 UTF-8 BOM（如果有）。"""
import sys
import re

TARGET_EXTS = ('.kt', '.kts', '.xml', '.gradle', '.gradle.kts',
               '.md', '.yaml', '.yml', '.properties', '.java')

for path in sys.argv[1:]:
    if not path.endswith(TARGET_EXTS):
        continue
    try:
        with open(path, encoding='utf-8') as f:
            text = f.read()
    except Exception:
        continue
    # 去除末尾空白行和每行末尾的尾部空格，统一为 LF
    fixed = re.sub(r'[ \t]*\r?\n', '\n', text.rstrip()) + '\n'
    with open(path, 'w', encoding='utf-8', newline='') as f:
        f.write(fixed)
