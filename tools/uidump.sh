#!/bin/sh
# Dump the current UI hierarchy from the pinned test phone into a file, and print its text nodes.
# Usage: sh tools/uidump.sh <device-serial> <out.xml>
export MSYS_NO_PATHCONV=1
S="$1"; OUT="$2"
adb -s "$S" shell cmd statusbar collapse >/dev/null 2>&1
adb -s "$S" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb -s "$S" shell cat /sdcard/ui.xml > "$OUT" 2>/dev/null
adb -s "$S" shell rm -f /sdcard/ui.xml >/dev/null 2>&1
PYTHONIOENCODING=utf-8 python - "$OUT" << 'PY'
import sys, xml.etree.ElementTree as ET
tree = ET.parse(sys.argv[1])
for n in tree.iter('node'):
    t = (n.attrib.get('text') or '').strip()
    rid = (n.attrib.get('resource-id') or '').split('/')[-1]
    tag = (n.attrib.get('class') or '').split('.')[-1]
    if t or rid or tag in ('EditText', 'Button'):
        print('%s|%s|%s|%s' % (tag, rid, t[:120], n.attrib.get('bounds')))
PY
