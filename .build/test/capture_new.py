import subprocess
import time
import os
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, '..', '..'))
adb_candidate = os.path.join(PROJECT_ROOT, '.build', 'kotlin', 'android-sdk', 'platform-tools', 'adb.exe')
adb = adb_candidate if os.path.exists(adb_candidate) else 'adb'

def run(cmd):
    return subprocess.run(cmd, capture_output=True, text=True)

# 1. 앱 데이터 초기화 및 최초 실행
print("1. Clearing app data and launching MainActivity...")
subprocess.run([adb, 'shell', 'pm', 'clear', 'com.jules.code'])
time.sleep(1)
subprocess.run([adb, 'shell', 'am', 'start', '-n', 'com.jules.code/.MainActivity'])
time.sleep(3)

# 2. 온보딩 화면 캡처
print("2. Capturing 01_onboarding.png...")
subprocess.run([adb, 'shell', 'screencap', '-p', '/sdcard/01_onboarding.png'])
os.makedirs(os.path.join(PROJECT_ROOT, 'assets', 'screenshots'), exist_ok=True)
subprocess.run([adb, 'pull', '/sdcard/01_onboarding.png', os.path.join(PROJECT_ROOT, 'assets', 'screenshots', '01_onboarding.png')])

# 3. 온보딩 화면 덤프에서 데모 모드 버튼 위치 찾기
subprocess.run([adb, 'shell', 'uiautomator', 'dump', '/sdcard/dump.xml'])
res = subprocess.run([adb, 'shell', 'cat', '/sdcard/dump.xml'], capture_output=True)
content = res.stdout.decode('utf-8', errors='ignore')
print("Dump length:", len(content))

# 데모 모드로 둘러보기 클릭 (일반적으로 화면 하단 530 부근)
print("3. Tapping Demo Mode button...")
subprocess.run([adb, 'shell', 'input', 'tap', '160', '533'])
time.sleep(3)

# 4. 대시보드 화면 캡처
print("4. Capturing 02_dashboard.png...")
subprocess.run([adb, 'shell', 'screencap', '-p', '/sdcard/02_dashboard.png'])
os.makedirs(os.path.join(PROJECT_ROOT, 'assets', 'screenshots'), exist_ok=True)
subprocess.run([adb, 'pull', '/sdcard/02_dashboard.png', os.path.join(PROJECT_ROOT, 'assets', 'screenshots', '02_dashboard.png')])

# 5. 세션 탭 (탭 1) 클릭
print("5. Switching to Sessions Tab...")
subprocess.run([adb, 'shell', 'input', 'tap', '119', '600'])
time.sleep(2)

# 6. 세션 목록 화면 캡처
print("6. Capturing 03_sessions_list.png...")
subprocess.run([adb, 'shell', 'screencap', '-p', '/sdcard/03_sessions_list.png'])
os.makedirs(os.path.join(PROJECT_ROOT, 'assets', 'screenshots'), exist_ok=True)
subprocess.run([adb, 'pull', '/sdcard/03_sessions_list.png', os.path.join(PROJECT_ROOT, 'assets', 'screenshots', '03_sessions_list.png')])

# 7. 첫 번째 세션 카드 클릭하여 세션 상세(채팅 화면) 진입
print("7. Clicking first session to enter Chat / Session Detail...")
subprocess.run([adb, 'shell', 'input', 'tap', '160', '220'])
time.sleep(3)

# 8. 세션 채팅 화면 캡처 (타임라인 대화 + 메시지 입력 바)
print("8. Capturing 04_session_chat.png...")
subprocess.run([adb, 'shell', 'screencap', '-p', '/sdcard/04_session_chat.png'])
os.makedirs(os.path.join(PROJECT_ROOT, 'assets', 'screenshots'), exist_ok=True)
subprocess.run([adb, 'pull', '/sdcard/04_session_chat.png', os.path.join(PROJECT_ROOT, 'assets', 'screenshots', '04_session_chat.png')])

print("Capture complete!")

