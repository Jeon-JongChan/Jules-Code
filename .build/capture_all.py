import subprocess
import time

adb = r'.build\kotlin\android-sdk\platform-tools\adb.exe'

def tap(x, y):
    subprocess.run([adb, 'shell', 'input', 'tap', str(x), str(y)])
    time.sleep(1.5)

def keyevent(code):
    subprocess.run([adb, 'shell', 'input', 'keyevent', str(code)])
    time.sleep(1.5)

def capture(name):
    remote = f'/sdcard/{name}'
    local = f'assets/screenshots/{name}'
    subprocess.run([adb, 'shell', 'screencap', '-p', remote])
    subprocess.run([adb, 'pull', remote, local])
    print(f"Captured: {name}")

# 1. 탭 1 (세션 목록) 클릭
print("Switching to Sessions tab...")
tap(119, 600)
capture("03_sessions_list.png")

# 2. 세션 목록 중 첫 번째 세션 항목 클릭 (화면 중앙 상단 y=220 부근)
print("Opening first session detail...")
tap(160, 220)
capture("04_session_detail.png")

# 3. 뒤로 가기
print("Navigating back to main tabs...")
keyevent(4)

# 4. 탭 2 (저장소) 클릭
print("Switching to Sources tab...")
tap(200, 600)
capture("05_sources.png")

# 5. 탭 3 (설정) 클릭
print("Switching to Settings tab...")
tap(282, 600)
capture("06_settings.png")

print("All screenshots captured successfully!")
