#!/usr/bin/env python3
"""Licence notices of the Go modules a tool vendors (rclone's vendor/ tree), for assets/licenses.

usage: third-party-notices.py <tool name> <vendor dir> <out file>
Every LICENSE/LICENCE/COPYING/NOTICE file under <vendor dir> is included once; modules with byte-identical texts
(the many Apache-2.0 copies) share one entry that lists them all. Output is sorted, so it is reproducible.
"""
import os
import re
import sys

tool, vendor, out = sys.argv[1:4]
NAME = re.compile(r'^(LICEN[CS]E|COPYING|NOTICE|PATENTS)([.-].*)?$', re.I)
texts = {}  # text -> set of "module (file)"
for root, dirs, files in os.walk(vendor):
    dirs.sort()
    for f in sorted(files):
        if not NAME.match(f):
            continue
        path = os.path.join(root, f)
        with open(path, encoding='utf-8', errors='replace') as fh:
            text = fh.read().strip().replace('\r\n', '\n')
        rel = os.path.relpath(path, vendor)
        texts.setdefault(text, set()).add(rel)

entries = sorted(texts.items(), key=lambda kv: sorted(kv[1])[0])
with open(out, 'w', encoding='utf-8') as o:
    o.write(f'Third-party software in {tool}\n\n')
    o.write(f'{tool} is built together with the Go modules below (its vendor/ directory). Their licence texts and\n'
            f'notices follow; modules with the same text are listed together. {len(texts)} texts, '
            f'{sum(len(v) for v in texts.values())} files.\n')
    for text, files in entries:
        o.write('\n' + '=' * 78 + '\n')
        for rel in sorted(files):
            o.write(rel + '\n')
        o.write('-' * 78 + '\n' + text + '\n')
