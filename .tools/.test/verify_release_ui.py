"""격리 AVD에서 기본 문구의 실제 작성창 반영과 화면 크기 대응을 검증합니다."""

import argparse
import json
import re
import time
from capture_release import adb, hierarchy, tap_text, screenshot, check_isolated_device, ARTIFACTS


def texts():
    """현재 화면의 접근성 문자열을 읽어 실제 표시를 검사합니다."""
    return [node.attrib.get("text", "") for node in hierarchy().iter("node")]


def record(name):
    """레이아웃 검사 화면을 README 이미지와 분리해 테스트 산출물로 보존합니다."""
    path = ARTIFACTS / name
    if path.exists():
        raise FileExistsError(path)
    path.write_bytes(adb("exec-out", "screencap", "-p", binary=True))


def verify_draft():
    """기본 문구를 저장하고 켠 뒤 실제 작성창에서 수정 가능한 원문으로 보이는지 확인합니다."""
    tap_text("English")
    tap_text("Follow Device Default (Auto)")
    assert "App Language" in texts(), "기기 언어 복귀 실패"
    tap_text("Your default request text")
    adb("shell", "input", "text", "Review%ssecurity%sand%serrors.")
    adb("shell", "input", "keyevent", "4")
    tap_text("Save")
    switches = [node for node in hierarchy().iter("node") if node.attrib.get("checkable") == "true"]
    assert len(switches) == 1 and switches[0].attrib.get("checked") == "false"
    left, top, right, bottom = map(int, re.findall(r"\d+", switches[0].attrib["bounds"]))
    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
    tap_text("Dashboard")
    assert "Review security and errors." in texts(), "저장 문구가 작성창에 표시되지 않음"
    screenshot("09_visible_default_draft_en.png")
    tap_text("Sessions")
    tap_text("모바일 반응형 네비게이션 및 다크 테마 개선")
    screenshot("04_session_en.png")
    adb("shell", "input", "keyevent", "4")
    tap_text("Settings")
    print("기기 언어 복귀·기본 문구 저장·작성창 표시·세션 상세 검증 통과", flush=True)


def verify_layouts():
    """360dp 폭·130% 글꼴·가로 화면을 확인하고 검증 AVD의 표시 설정을 복원합니다."""
    previous_font = adb("shell", "settings", "get", "system", "font_scale")
    previous_rotation = adb("shell", "settings", "get", "system", "user_rotation")
    previous_auto = adb("shell", "settings", "get", "system", "accelerometer_rotation")
    assert "App Language" in texts(), "검사 시작 전 설정 화면이 열리지 않음"
    try:
        adb("shell", "wm", "size", "720x1280")
        adb("shell", "wm", "density", "320")
        adb("shell", "settings", "put", "system", "font_scale", "1.3")
        time.sleep(2)
        assert "App Language" in texts(), "작은 화면·큰 글꼴에서 언어 설정 접근 실패"
        record("layout-360dp-font130.png")
        adb("shell", "settings", "put", "system", "accelerometer_rotation", "0")
        adb("shell", "settings", "put", "system", "user_rotation", "1")
        time.sleep(2)
        root = hierarchy()
        assert root.attrib.get("rotation") == "1", "가로 화면 전환 실패"
        assert "Settings" in [node.attrib.get("text") for node in root.iter("node")]
        record("layout-landscape-font130.png")
        tap_text("Dashboard")
        for _ in range(5):
            if "Review security and errors." in texts():
                break
            adb("shell", "input", "swipe", "640", "480", "640", "220", "350")
        tap_text("Review security and errors.")
        adb("shell", "input", "keyevent", "123")
        adb("shell", "input", "text", "%sKeep%sdraft.")
        adb("shell", "input", "keyevent", "4")
        adb("shell", "settings", "put", "system", "user_rotation", "0")
        time.sleep(2)
        assert "Review security and errors. Keep draft." in texts(), "회전 후 작성 중 초안 유실"
        print("360dp 폭·130% 글꼴·가로 화면 검증 통과", flush=True)
    finally:
        adb("shell", "settings", "put", "system", "font_scale", previous_font)
        adb("shell", "settings", "put", "system", "user_rotation", previous_rotation)
        adb("shell", "settings", "put", "system", "accelerometer_rotation", previous_auto)
        adb("shell", "wm", "size", "reset")
        adb("shell", "wm", "density", "reset")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--layouts-only", action="store_true")
    args = parser.parse_args()
    assert check_isolated_device() == "jules_release_35"
    if args.layouts_only:
        tap_text("Settings")
    else:
        verify_draft()
    verify_layouts()
    (ARTIFACTS / "layout-checks.json").write_text(json.dumps({"width_dp": 360, "font_scale": 1.3,
        "landscape": True, "settings_tab_restored": True, "draft_restored": True}, indent=2), encoding="utf-8")
