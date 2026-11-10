"""격리 AVD에서 공식 가짜 SDK 흐름과 재실행 후 업데이트 설정 보존을 검증합니다."""

import argparse
import json
import time
import xml.etree.ElementTree as ET
from capture_release import adb, check_isolated_device, ARTIFACTS, ROOT


def wait_for_boot():
    """설치 전 기기 부팅 완료를 제한된 시간 동안 확인합니다."""
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        try:
            if adb("shell", "getprop", "sys.boot_completed") == "1":
                return
        except RuntimeError:
            pass
        time.sleep(3)
    raise TimeoutError("검증 AVD 부팅 지연")


def read_automatic_setting():
    """API 키 파일을 읽지 않고 업데이트 설정 파일의 자동 확인 값만 반환합니다."""
    try:
        raw = adb("exec-out", "run-as", "com.jules.code.debug", "cat", "shared_prefs/play_updates.xml")
    except RuntimeError as error:
        if "No such file" in str(error) or not str(error):
            return True, False
        raise
    if "No such file" in raw:
        return True, False
    root = ET.fromstring(raw)
    item = next((item for item in root if item.attrib.get("name") == "automatic"), None)
    return item is None or item.attrib.get("value") == "true", True


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--api", choices=("21", "35"), required=True)
    args = parser.parse_args()
    assert check_isolated_device() == f"jules_release_{args.api}"
    wait_for_boot()
    print(adb("install", "-r", str(ROOT / ".tools/build/app/outputs/apk/debug/Jules Code-v1.3.0-debug.apk")), flush=True)
    print(adb("install", "-r", str(ROOT / ".tools/build/app/outputs/apk/androidTest/debug/Jules Code-debug-androidTest.apk")), flush=True)
    output = adb("shell", "am", "instrument", "-w", "com.jules.code.debug.test/com.jules.code.PlayUpdateInstrumentation")
    (ROOT / f".tools/logs/play-updates-device-api{args.api}.log").write_text(output, encoding="utf-8")
    assert "PASS: consent" in output, output
    initial_launch = adb("shell", "am", "start", "-W", "-n", "com.jules.code.debug/com.jules.code.MainActivity")
    assert "Status: ok" in initial_launch, initial_launch
    before, stored = read_automatic_setting()
    adb("shell", "am", "force-stop", "com.jules.code.debug")
    launch = adb("shell", "am", "start", "-W", "-n", "com.jules.code.debug/com.jules.code.MainActivity")
    assert "Status: ok" in launch, launch
    after, _ = read_automatic_setting()
    assert after == before, "재실행 후 업데이트 설정 유실"
    if args.api == "35":
        assert stored, "화면에서 저장한 업데이트 설정 파일이 없음"
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    (ARTIFACTS / f"play-update-settings-api{args.api}.json").write_text(json.dumps({
        "api": int(args.api), "fake_sdk_flow": True, "app_restarted": True,
        "automatic_setting_file_present": stored, "automatic_setting_preserved": True,
        "automatic_setting_value": after}, indent=2), encoding="utf-8")
    print(f"API {args.api}: update SDK flow and restart settings check passed", flush=True)
