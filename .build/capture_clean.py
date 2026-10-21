import subprocess
import time

adb = r'.build\kotlin\android-sdk\platform-tools\adb.exe'

# 1. 앱을 다시 대시보드로 전환
print("Switching back to Dashboard tab...")
# 세션 상세에서 백키 1회
subprocess.run([adb, 'shell', 'input', 'keyevent', '4'])
time.sleep(1)
# 탭 0 (대시보드) 클릭
subprocess.run([adb, 'shell', 'input', 'tap', '36', '600'])
print("Waiting 4s for snackbar to dismiss completely...")
time.sleep(4)

# 대시보드 캡처
print("Capturing clean 02_dashboard.png...")
subprocess.run([adb, 'shell', 'screencap', '-p', '/sdcard/02_dashboard.png'])
subprocess.run([adb, 'pull', '/sdcard/02_dashboard.png', 'assets/screenshots/02_dashboard.png'])

# 탭 1 (세션 목록) 클릭
print("Switching to Sessions tab...")
subprocess.run([adb, 'shell', 'input', 'tap', '119', '600'])
time.sleep(1.5)

# 세션 목록 캡처
print("Capturing clean 03_sessions_list.png...")
subprocess.run([adb, 'shell', 'screencap', '-p', '/sdcard/03_sessions_list.png'])
subprocess.run([adb, 'pull', '/sdcard/03_sessions_list.png', 'assets/screenshots/03_sessions_list.png'])

print("Clean captures complete!")

