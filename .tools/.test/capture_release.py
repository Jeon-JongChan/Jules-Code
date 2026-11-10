"""격리된 출시 검증 AVD에서 실제 UI를 조작하고 원본 스크린샷을 저장합니다."""

from pathlib import Path
import argparse
import json
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ADB = ROOT / ".tools/.build/kotlin/android-sdk/platform-tools/adb.exe"
SERIAL = os.environ.get("JULES_TEST_SERIAL", "emulator-5554")
ARTIFACTS = ROOT / ".tools/.test/ui-artifacts"
PHOTOS = ROOT / "assets/screenshots/release-20261110"


def adb(*arguments, binary=False):
    """명시한 검증 에뮬레이터에만 명령을 보내고 실패하면 즉시 중단합니다."""
    result = subprocess.run([str(ADB), "-s", SERIAL, *arguments], capture_output=True, timeout=45)
    if result.returncode:
        raise RuntimeError(result.stderr.decode("utf-8", errors="replace"))
    return result.stdout if binary else result.stdout.decode("utf-8", errors="replace").strip()


def check_isolated_device():
    """실제 사용자 기기나 기존 개인 AVD에 설치·입력을 수행하지 않도록 확인합니다."""
    name = adb("emu", "avd", "name").splitlines()[0]
    if name not in ("jules_release_35", "jules_release_21"):
        raise RuntimeError(f"격리 검증 AVD가 아닙니다: {name}")
    return name


def hierarchy():
    """실제 접근성 트리를 읽어 화면 이동과 번역 표시의 증거로 보관합니다."""
    adb("shell", "uiautomator", "dump", "/data/local/tmp/jules-release-ui.xml")
    xml = adb("shell", "cat", "/data/local/tmp/jules-release-ui.xml")
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    (ARTIFACTS / "latest.xml").write_text(xml, encoding="utf-8")
    return ET.fromstring(xml)


def tap_text(text, timeout=30):
    """텍스트의 실제 접근성 좌표를 눌러 해상도가 다른 화면에서도 동일하게 조작합니다."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        root = hierarchy()
        for node in root.iter("node"):
            if node.attrib.get("text") == text or node.attrib.get("content-desc") == text:
                points = [int(value) for value in re.findall(r"\d+", node.attrib["bounds"])]
                if len(points) == 4 and points[2] > points[0] and points[3] > points[1]:
                    adb("shell", "input", "tap", str((points[0] + points[2]) // 2), str((points[1] + points[3]) // 2))
                    time.sleep(0.8)
                    return
        time.sleep(0.5)
    raise AssertionError(f"화면에서 찾지 못한 문구: {text}")


def screenshot(name):
    """에뮬레이터의 원본 PNG를 수정하지 않고 README용 경로에 저장합니다."""
    PHOTOS.mkdir(parents=True, exist_ok=True)
    path = PHOTOS / name
    if path.exists():
        raise FileExistsError(f"기존 캡처를 덮어쓰지 않습니다: {path}")
    data = adb("exec-out", "screencap", "-p", binary=True)
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise AssertionError("스크린샷이 정상 PNG가 아닙니다.")
    path.write_bytes(data)
    print(f"실제 화면 저장: {path.relative_to(ROOT)}", flush=True)
    (ARTIFACTS / (name + ".xml")).write_text(ET.tostring(hierarchy(), encoding="unicode"), encoding="utf-8")


def prepare():
    """빌드한 새 디버그 앱을 깨끗한 검증 AVD에 설치하고 온보딩 화면을 기록합니다."""
    name = check_isolated_device()
    deadline = time.monotonic() + 180
    while adb("shell", "getprop", "sys.boot_completed") != "1":
        if time.monotonic() >= deadline:
            raise RuntimeError("에뮬레이터 부팅이 완료되지 않았습니다.")
        time.sleep(5)
    apk = ROOT / ".tools/build/app/outputs/apk/debug/Jules Code-v1.3.0-debug.apk"
    print(adb("install", "-r", str(apk)), flush=True)
    adb("shell", "am", "start", "-n", "com.jules.code.debug/com.jules.code.MainActivity")
    time.sleep(2)
    for _ in range(20):
        if any(node.attrib.get("text") == "Try Demo Simulation Mode" for node in hierarchy().iter("node")):
            break
        time.sleep(0.5)
    else:
        raise AssertionError("온보딩 화면이 표시되지 않았습니다.")
    screenshot("07_onboarding_api21_en.png" if name == "jules_release_21" else "01_onboarding_en.png")
    (ARTIFACTS / "device.json").write_text(json.dumps({"avd": name, "api": adb("shell", "getprop", "ro.build.version.sdk")}, indent=2), encoding="utf-8")


def demo():
    """키 없이 실행되는 데모의 대시보드·세션·설정 화면을 실제로 열어 기록합니다."""
    check_isolated_device()
    tap_text("Try Demo Simulation Mode")
    time.sleep(2)
    screenshot("02_dashboard_en.png")
    tap_text("Sessions")
    screenshot("03_sessions_en.png")
    tap_text("Settings")
    screenshot("05_settings_en.png")


def verify_languages():
    """실제 설정 메뉴에서 모든 출시 언어를 선택하고 번역 리소스가 표시되는지 검증합니다."""
    check_isolated_device()
    languages = [("ko", "한국어"), ("en", "English"), ("es", "Español"), ("ja", "日本語"),
                 ("pt", "Português"), ("fr", "Français"), ("de", "Deutsch"), ("zh", "简体中文"),
                 ("hi", "हिन्दी"), ("ar", "العربية"), ("ru", "Русский"), ("id", "Bahasa Indonesia")]
    # 기본 기기 언어가 영어인 전용 검증 AVD에서만 실행합니다.
    current = "Follow Device Default (Auto)"
    outcomes = []
    for code, label in languages:
        tap_text(current)
        for attempt in range(6):
            if any(node.attrib.get("text") == label for node in hierarchy().iter("node")):
                break
            # 드롭다운 내부를 스크롤하여 작은 화면의 접힌 언어도 선택합니다.
            adb("shell", "input", "swipe", "540", "1250", "540", "650", "350")
        tap_text(label)
        qualifier = "in" if code == "id" else code
        directory = "values" if code == "en" else f"values-{qualifier}"
        resources = ET.parse(ROOT / f"app/src/main/res/{directory}/strings.xml").getroot()
        expected = next(item.text for item in resources if item.attrib.get("name") == "settings_language_title").strip('"')
        actual = [node.attrib.get("text") for node in hierarchy().iter("node")]
        if expected not in actual:
            raise AssertionError(f"언어 표시 불일치: {code}, 기대={expected}, 실제={actual}")
        outcomes.append({"language": code, "title": expected, "passed": True})
        print(f"언어 화면 검증 통과: {code}", flush=True)
        if code in ("ko", "ar"):
            screenshot(f"06_settings_{code}.png")
        current = label
    tap_text(current)
    tap_text("English")
    (ARTIFACTS / "languages.json").write_text(json.dumps(outcomes, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("stage", choices=("prepare", "demo", "languages"))
    args = parser.parse_args()
    {"prepare": prepare, "demo": demo, "languages": verify_languages}[args.stage]()
