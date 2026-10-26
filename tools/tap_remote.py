"""Test a screen-coordinate tap through the real controller video view.

Usage: python tools/tap_remote.py HOST_SERIAL CLIENT_SERIAL EXACT_HOST_TEXT
The script derives the source target from UIAutomator, then injects a touch
only on the controller device. The APK must transmit and execute that touch.
"""
import re
import sys
from device_ui import adb, nodes


def bounds(node):
    return list(map(int, re.findall(r'\d+', node.attrib['bounds'])))


host, client, text = sys.argv[1:4]
host_nodes = list(nodes(host))
targets = [n for n in host_nodes if n.attrib.get('text') == text]
if len(targets) != 1:
    raise RuntimeError('Expected one visible host target')
viewers = [n for n in nodes(client) if ('SurfaceViewRenderer' in n.attrib.get('class', '') or n.attrib.get('class') == 'android.view.SurfaceView')
           or (n.attrib.get('class') == 'android.view.View' and not n.attrib.get('resource-id')
               and n.attrib.get('package') == 'cn.lanpeer')]
if len(viewers) != 1:
    raise RuntimeError('Expected one controller video view')
# wm size reports the natural orientation, not the current rotated display.
_, _, width, height = bounds(host_nodes[0])
hx1, hy1, hx2, hy2 = bounds(targets[0])
cx1, cy1, cx2, cy2 = bounds(viewers[0])
x = round(cx1 + ((hx1 + hx2) / 2 / width) * (cx2 - cx1))
y = round(cy1 + ((hy1 + hy2) / 2 / height) * (cy2 - cy1))
adb(client, 'shell', 'input', 'tap', str(x), str(y))
print('Sent a controller screen touch. Check the host UI for the expected result.')
