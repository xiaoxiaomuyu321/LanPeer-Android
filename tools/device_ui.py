"""Development helper for the USB-connected test devices; not included in the APK.

Usage: python tools/device_ui.py SERIAL dump|tap|text|shot [TEXT_OR_PATH]
All actions are explicit. Does not auto-accept connection or projection prompts.
"""
from pathlib import Path
import json
import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
LOCAL_ADB = ROOT / '.tools/android-sdk/platform-tools/adb.exe'
SDK_ADB = Path(os.environ.get('ANDROID_HOME', '')) / 'platform-tools/adb.exe' if os.environ.get('ANDROID_HOME') else None
ADB = str(LOCAL_ADB if LOCAL_ADB.exists() else SDK_ADB if SDK_ADB and SDK_ADB.exists() else shutil.which('adb') or 'adb')


def adb(serial, *args):
    return subprocess.run([ADB, '-s', serial, *args], capture_output=True, check=True).stdout


def nodes(serial):
    adb(serial, 'shell', 'uiautomator', 'dump', '/sdcard/lanpeer-ui.xml')
    data = adb(serial, 'shell', 'cat', '/sdcard/lanpeer-ui.xml')
    return ET.fromstring(data).iter('node')


def main():
    serial, action, *rest = sys.argv[1:]
    if action == 'dump':
        for node in nodes(serial):
            attrs = node.attrib
            if attrs.get('text') or attrs.get('class') == 'android.widget.EditText':
                print(json.dumps({key: attrs.get(key) for key in ('text', 'class', 'resource-id', 'bounds', 'clickable', 'enabled')}, ensure_ascii=False))
    elif action == 'tap':
        matches = []
        for _ in range(3):
            matches = [n for n in nodes(serial) if rest[0] in n.attrib.get('text', '') and n.attrib.get('enabled') == 'true']
            if matches:
                break
            time.sleep(0.4)
        if not matches:
            raise RuntimeError('No visible matching text')
        match = min(matches, key=lambda n: len(n.attrib.get('text', '')))
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', match.attrib['bounds']))
        adb(serial, 'shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
    elif action == 'fill':
        index, value = int(rest[0]), rest[1]
        if not re.fullmatch(r'[A-Za-z0-9.:_-]+', value):
            raise ValueError('Only literal ASCII test inputs are supported')
        fields = [n for n in nodes(serial) if n.attrib.get('class') == 'android.widget.EditText']
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', fields[index].attrib['bounds']))
        adb(serial, 'shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
        adb(serial, 'shell', 'input', 'text', value)
    elif action == 'text':
        if not re.fullmatch(r'[A-Za-z0-9.:_-]+', rest[0]):
            raise ValueError('Only literal ASCII test inputs are supported')
        adb(serial, 'shell', 'input', 'text', rest[0])
    elif action == 'shot':
        adb(serial, 'shell', 'screencap', '-p', '/sdcard/lanpeer-test.png')
        destination = Path(rest[0]).resolve()
        destination.relative_to(ROOT)
        destination.parent.mkdir(parents=True, exist_ok=True)
        adb(serial, 'pull', '/sdcard/lanpeer-test.png', str(destination))
        print(destination)
    else:
        raise ValueError('Unknown action')


if __name__ == '__main__':
    main()
