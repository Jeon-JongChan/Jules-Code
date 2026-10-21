import subprocess
import time

adb = r'.build\kotlin\android-sdk\platform-tools\adb.exe'

# 1. 앱 데이터 초기화 및 최초 실행
print("1. Clearing data and starting app...")
subprocess.run([adb, 'shell', 'pm', 'clear', 'com.jules.code'])
time.sleep(1)
subprocess.run([adb, 'shell', 'am', 'start', '-n', 'com.jules.code/.MainActivity'])

# 스플래시가 지나가고 실제 온보딩 카드가 렌더링될 때까지 충분히 대기
print("Waiting 5s for onboarding screen...")
time.sleep(5)

# 2. 온보딩 화면 캡처
print("Capturing 01_onboarding.png...")
subprocess.run([adb, 'shell', 'screencap', '-p', '/sdcard/01_onboarding.png'])
subprocess.run([adb, 'pull', '/sdcard/01_onboarding.png', 'assets/screenshots/01_onboarding.png'])

# 3. 데모 모드 버튼 클릭
print("Clicking Demo Mode button at (160, 533)...")
subprocess.run([adb, 'shell', 'input', 'tap', '160', '533'])
time.sleep(3)

# 4. 대시보드 화면 캡처
print("Capturing 02_dashboard.png...")
subprocess.run([adb, 'shell', 'screencap', '-p', '/sdcard/02_dashboard.png'])
subprocess.run([adb, 'pull', '/sdcard/02_dashboard.png', 'assets/screenshots/02_dashboard.png'])

# 5. 세션 탭 (탭 1) 클릭
print("Switching to Sessions Tab at (119, 600)...")
subprocess.run([adb, 'shell', 'input', 'tap', '119', '600'])
time.sleep(2)

# 6. 세션 목록 화면 캡처
print("Capturing 03_sessions_list.png...")
subprocess.run([adb, 'shell', 'screencap', '-p', '/sdcard/03_sessions_list.png'])
subprocess.run([adb, 'pull', '/sdcard/03_sessions_list.png', 'assets/screenshots/03_sessions_list.png'])

# 7. 세션 카드 클릭 (y=480은 세션 카드 본문 위치)
print("Clicking session card at (160, 480)...")
subprocess.run([adb, 'shell', 'input', 'tap', '160', '480'])
time.sleep(3)

# 8. 세션 채팅 화면 캡처
print("Capturing 04_session_chat.png...")
subprocess.run([adb, 'shell', 'screencap', '-p', '/sdcard/04_session_chat.png'])
subprocess.run([adb, 'pull', '/sdcard/04_session_chat.png', 'assets/screenshots/04_session_chat.png'])

print("All captures completed successfully!")

