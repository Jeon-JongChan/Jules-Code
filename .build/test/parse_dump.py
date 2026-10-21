import subprocess
import re

adb = r'.build\kotlin\android-sdk\platform-tools\adb.exe'
subprocess.run([adb, 'shell', 'uiautomator', 'dump', '/sdcard/nav_dump.xml'])
res = subprocess.run([adb, 'shell', 'cat', '/sdcard/nav_dump.xml'], capture_output=True)
content = res.stdout.decode('utf-8', errors='ignore')

pattern = r'(text="([^"]*)"|content-desc="([^"]*)")[^>]*bounds="(\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\])"'
matches = re.findall(pattern, content)
for m in matches:
    t = m[1] if m[1] else m[2]
    if t.strip():
        print(f"{t} -> {m[3]}")
