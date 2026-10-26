"""Pair the test APK through its real UI on two authorized USB devices.

Run with HOST_SERIAL CLIENT_SERIAL [--approve]. The host must already display
its invitation and the client must be on the home screen. --approve explicitly
confirms the host's connection dialog for this development test only.
"""
import re
import sys
import time
from device_ui import adb, nodes


def tap_node(serial, node):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.attrib['bounds']))
    adb(serial, 'shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))


def tap_text(serial, text):
    matches = [n for n in nodes(serial) if n.attrib.get('text') == text]
    if not matches:
        raise RuntimeError('Expected UI element is not visible: ' + text)
    tap_node(serial, matches[0])


def main():
    host, client = sys.argv[1:3]
    details = None
    for node in nodes(host):
        details = re.search(r'地址：([0-9.]+):(\d+)\n连接码：(\d+)\n设备校验码：(\d+)', node.attrib.get('text', ''))
        if details:
            break
    if not details:
        raise RuntimeError('Host is not showing an invitation')
    address, port, pin, fingerprint = details.groups()
    tap_text(client, '手动输入连接')
    for index, value in enumerate([address + ':' + port, pin, fingerprint]):
        fields = [n for n in nodes(client) if n.attrib.get('class') == 'android.widget.EditText']
        tap_node(client, fields[index])
        adb(client, 'shell', 'input', 'text', value)
    tap_text(client, '连接')
    if '--approve' in sys.argv:
        for _ in range(6):
            try:
                tap_text(host, '允许本次连接')
                print('Host confirmed the test connection.')
                break
            except RuntimeError:
                time.sleep(0.3)
        else:
            raise RuntimeError('No connection approval dialog appeared')
    print('Pairing UI flow completed; inspect video and control separately.')


if __name__ == '__main__':
    main()
