import subprocess
import re
import os
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, '..', '..'))
adb_candidate = os.path.join(PROJECT_ROOT, '.build', 'kotlin', 'android-sdk', 'platform-tools', 'adb.exe')
adb = adb_candidate if os.path.exists(adb_candidate) else 'adb'

# xml 파일이 로컬에 제공되면 해당 파일 분석, 아니면 기기에서 덤프
local_dump = os.path.join(SCRIPT_DIR, 'ui_dump.xml')
if len(sys.argv) > 1 and os.path.exists(sys.argv[1]):
    with open(sys.argv[1], 'r', encoding='utf-8', errors='ignore') as f:
        content = f.read()
elif os.path.exists(local_dump):
    with open(local_dump, 'r', encoding='utf-8', errors='ignore') as f:
        content = f.read()
else:
    subprocess.run([adb, 'shell', 'uiautomator', 'dump', '/sdcard/nav_dump.xml'])
    res = subprocess.run([adb, 'shell', 'cat', '/sdcard/nav_dump.xml'], capture_output=True)
    content = res.stdout.decode('utf-8', errors='ignore')

pattern = r'(text="([^"]*)"|content-desc="([^"]*)")[^>]*bounds="(\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\])"'
matches = re.findall(pattern, content)
for m in matches:
    t = m[1] if m[1] else m[2]
    if t.strip():
        print(f"{t} -> {m[3]}")
