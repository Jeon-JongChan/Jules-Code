"""전용 에뮬레이터의 실제 화면을 탐색하고 네이티브 라이브러리 매핑을 기록합니다."""

from pathlib import Path
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
ADB = ROOT / ".tools/.build/kotlin/android-sdk/platform-tools/adb.exe"
SERIAL = "emulator-5580"
PACKAGE = "com.jules.code.debug"
OUTPUT = Path(sys.argv[2]).resolve()


def adb(*arguments, timeout=45):
    """명시한 전용 에뮬레이터에만 명령을 보내고 실패 원인을 반환합니다."""
    result = subprocess.run([str(ADB), "-s", SERIAL, *arguments], capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(result.stderr.decode("utf-8", errors="replace"))
    return result.stdout.decode("utf-8", errors="replace").strip()


def hierarchy(stage):
    """현재 화면의 접근성 트리를 증거로 저장하고 조작할 노드를 반환합니다."""
    deadline = time.monotonic() + 35
    while time.monotonic() < deadline:
        try:
            dumped = adb("shell", "uiautomator", "dump", "/data/local/tmp/native-path-ui.xml")
            if "dumped to" not in dumped:
                time.sleep(1)
                continue
            source = adb("shell", "cat", "/data/local/tmp/native-path-ui.xml")
            (OUTPUT / f"{stage}.xml").write_text(source, encoding="utf-8")
            return ET.fromstring(source)
        except RuntimeError:
            time.sleep(1)
    raise TimeoutError(f"접근성 트리가 준비되지 않았습니다: {stage}")


def click_text(labels, stage):
    """언어에 맞는 실제 텍스트 노드를 찾아 부모 버튼의 중앙을 누릅니다."""
    root = hierarchy(stage)
    parents = {child: parent for parent in root.iter() for child in parent}
    for node in root.iter("node"):
        if node.get("text") in labels or node.get("content-desc") in labels:
            while node.get("clickable") != "true" and node in parents:
                node = parents[node]
            bounds = [int(value) for value in re.findall(r"\d+", node.get("bounds", ""))]
            if len(bounds) == 4:
                adb("shell", "input", "tap", str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
                time.sleep(0.8)
                return True
    return False


def capture_maps(stage):
    """실제 앱 프로세스의 메모리 매핑에서 대상 공유 라이브러리 로드 여부를 기록합니다."""
    pid = adb("shell", "pidof", PACKAGE).split()[0]
    mappings = adb("shell", "run-as", PACKAGE, "cat", f"/proc/{pid}/maps")
    lines = [line for line in mappings.splitlines() if "libandroidx.graphics.path.so" in line]
    (OUTPUT / f"{stage}-maps.txt").write_text(mappings, encoding="utf-8")
    return {"stage": stage, "pid": pid, "graphicsPathLoaded": bool(lines), "matchingMappings": lines}


def prepare():
    """부팅 완료를 기다리고 디버그 앱을 시작 직전 대기시킨 뒤 JDWP 포트를 연결합니다."""
    OUTPUT.mkdir(parents=True, exist_ok=True)
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        try:
            if adb("shell", "getprop", "sys.boot_completed", timeout=10) == "1":
                break
        except (RuntimeError, subprocess.TimeoutExpired):
            pass
        time.sleep(2)
    else:
        raise TimeoutError("전용 에뮬레이터가 부팅되지 않았습니다.")
    if adb("emu", "avd", "name").splitlines()[0] != "jules_release_35":
        raise RuntimeError("전용 검증 AVD가 아닙니다.")
    adb("install", "-r", str(ROOT / "gradle/Jules Code-v1.3.1-debug.apk"), timeout=90)
    adb("shell", "wm", "dismiss-keyguard")
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-D", "-n", f"{PACKAGE}/com.jules.code.MainActivity")
    # Activity 시작 요청이 반환된 뒤 앱 프로세스가 실제로 생길 때까지 기다립니다.
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        try:
            pid = adb("shell", "pidof", PACKAGE).split()[0]
            break
        except (RuntimeError, IndexError):
            time.sleep(0.5)
    else:
        raise TimeoutError("디버그 앱 프로세스가 시작되지 않았습니다.")
    adb("forward", "tcp:8799", f"jdwp:{pid}")
    (OUTPUT / "device.json").write_text(json.dumps({
        "serial": SERIAL, "pid": pid, "package": PACKAGE,
        "sdk": adb("shell", "getprop", "ro.build.version.sdk"),
        "release": adb("shell", "getprop", "ro.build.version.release"),
    }, ensure_ascii=False), encoding="utf-8")
    print(f"앱 시작 대기 및 JDWP 연결 준비 완료: PID {pid}, 포트 8799", flush=True)


def exercise():
    """오프라인 데모 앱에서 시작 화면·목록·저장소·설정·상세 탭·템플릿을 탐색합니다."""
    deadline = time.monotonic() + 45
    while not (OUTPUT / "ready.txt").exists():
        if time.monotonic() > deadline:
            raise TimeoutError("추적기 연결 준비 신호가 없습니다.")
        time.sleep(0.5)
    time.sleep(4)
    stages = [capture_maps("startup")]
    initial = hierarchy("startup")
    if any("Try Demo Simulation Mode" in node.get("text", "") for node in initial.iter("node")):
        click_text(["Try Demo Simulation Mode"], "before-demo")
        time.sleep(2)
        initial = hierarchy("demo-started")
    if not any("Demo Sandbox" in node.get("text", "") or "데모 샌드박스" in node.get("text", "") for node in initial.iter("node")):
        raise RuntimeError("오프라인 데모 모드가 아니므로 자동 화면 탐색을 중단합니다.")
    actions = [
        ("sessions", ["Sessions", "세션"]),
        ("sources", ["Sources", "저장소"]),
        ("settings", ["Settings", "설정"]),
        ("dashboard", ["Dashboard", "대시보드"]),
    ]
    for stage, labels in actions:
        if not click_text(labels, f"before-{stage}"):
            raise RuntimeError(f"화면 이동 버튼을 찾지 못했습니다: {stage}")
        hierarchy(stage)
        stages.append(capture_maps(stage))
        print(f"화면 탐색 완료: {stage}", flush=True)
    # 카드까지 이동하여 벡터 아이콘·버튼·목록이 실제로 그려지는 경로를 실행합니다.
    for _ in range(2):
        adb("shell", "input", "swipe", "1000", "1600", "1000", "500", "500")
    current = hierarchy("dashboard-scrolled")
    title = next((node.get("text") for node in current.iter("node")
                  if "네비게이션" in node.get("text", "") or "Room DB" in node.get("text", "")), None)
    if title and click_text([title], "before-detail"):
        for stage, labels in [
            ("detail-plan", ["Plan", "계획", "계획(Plan)"]),
            ("detail-code", ["Code", "코드", "Diff", "코드 Diff"]),
            ("detail-terminal", ["Terminal", "터미널", "Bash"]),
        ]:
            clicked = click_text(labels, f"before-{stage}")
            hierarchy(stage)
            stages.append(capture_maps(stage))
            print(f"상세 탭 확인: {stage}, 전환={clicked}", flush=True)
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
    stages.append(capture_maps("completed"))
    (OUTPUT / "mapped-libraries.json").write_text(json.dumps(stages, ensure_ascii=False, indent=2), encoding="utf-8")
    (OUTPUT / "trace.stop").write_text("탐색 완료", encoding="utf-8")
    print("화면 탐색 및 메모리 매핑 기록 완료", flush=True)


def cleanup():
    """전용 앱의 디버거 대기와 이 추적에서 만든 포트 연결만 해제합니다."""
    adb("shell", "am", "clear-debug-app")
    adb("forward", "--remove", "tcp:8799")


def details():
    """선택된 데모 세션의 모든 상세 탭과 템플릿 추가 창을 대상 전용 추적으로 탐색합니다."""
    stages = [capture_maps("detail-timeline")]
    try:
        for stage, prefixes in [
            ("detail-plan", ("Plan (", "계획 (")),
            ("detail-code", ("Code Diff (", "코드 Diff (")),
            ("detail-terminal", ("Terminal (", "터미널 (")),
        ]:
            root = hierarchy(f"before-{stage}")
            caption = next((node.get("text") for node in root.iter("node")
                            if node.get("text", "").startswith(prefixes)), None)
            if caption is None or not click_text([caption], f"tap-{stage}"):
                raise RuntimeError(f"상세 탭을 찾지 못했습니다: {stage}")
            hierarchy(stage)
            stages.append(capture_maps(stage))
            print(f"상세 화면 추적 완료: {stage}", flush=True)
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        stages.extend(template_stages())
        print("세션 상세 및 템플릿 화면 추적 완료", flush=True)
    finally:
        (OUTPUT / "mapped-libraries.json").write_text(json.dumps(stages, ensure_ascii=False, indent=2), encoding="utf-8")
        (OUTPUT / "trace.stop").write_text("상세 화면 탐색 종료", encoding="utf-8")


def template_stages():
    """대시보드 아래쪽 템플릿 탭과 추가 대화상자의 실제 표시 경로를 수집합니다."""
    for attempt in range(4):
        root = hierarchy(f"template-search-{attempt}")
        caption = next((node.get("text") for node in root.iter("node")
                        if "templates (" in node.get("text", "").lower()
                        or "수동 실행 템플릿 (" in node.get("text", "")), None)
        if caption and click_text([caption], "tap-templates"):
            break
        # 작성창 아래에 있는 탭을 찾기 위해 콘텐츠를 앞으로 스크롤합니다.
        adb("shell", "input", "swipe", "1000", "1550", "1000", "700", "500")
    else:
        raise RuntimeError("템플릿 탭을 찾지 못했습니다.")
    adb("shell", "input", "swipe", "1000", "1500", "1000", "1000", "500")
    hierarchy("templates")
    stages = [capture_maps("templates")]
    caption = None
    # 탭 선택으로 목록 길이가 달라지면 추가 버튼이 화면 아래로 밀릴 수 있습니다.
    for attempt in range(3):
        root = hierarchy(f"before-template-dialog-{attempt}")
        caption = next((node.get("text") for node in root.iter("node")
                        if "new template" in node.get("text", "").lower()
                        or node.get("text") in ("Add Template", "새 템플릿 추가")), None)
        if caption:
            break
        adb("shell", "input", "swipe", "550", "1510", "550", "500", "700")
    if caption is None or not click_text([caption], "tap-template-dialog"):
        raise RuntimeError("새 템플릿 추가 버튼을 찾지 못했습니다.")
    hierarchy("template-dialog")
    stages.append(capture_maps("template-dialog"))
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    return stages


def templates():
    """템플릿 화면만 별도 추적하며 성공 여부와 무관하게 추적 종료 신호를 남깁니다."""
    try:
        stages = template_stages()
        (OUTPUT / "mapped-libraries.json").write_text(json.dumps(stages, ensure_ascii=False, indent=2), encoding="utf-8")
        print("템플릿 목록 및 추가 대화상자 추적 완료", flush=True)
    finally:
        (OUTPUT / "trace.stop").write_text("템플릿 탐색 종료", encoding="utf-8")


def dialog():
    """화면 안에 있는 템플릿 추가 버튼과 대화상자만 짧게 추적합니다."""
    stages = [capture_maps("before-template-dialog")]
    try:
        if not click_text(["Add Template", "새 템플릿 추가"], "template-list"):
            raise RuntimeError("화면 안에서 추가 버튼을 찾지 못했습니다.")
        hierarchy("template-dialog")
        stages.append(capture_maps("template-dialog"))
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        print("새 템플릿 대화상자 실행 추적 완료", flush=True)
    finally:
        (OUTPUT / "mapped-libraries.json").write_text(json.dumps(stages, ensure_ascii=False, indent=2), encoding="utf-8")
        (OUTPUT / "trace.stop").write_text("대화상자 탐색 종료", encoding="utf-8")


if __name__ == "__main__":
    {"prepare": prepare, "exercise": exercise, "details": details, "templates": templates, "dialog": dialog, "cleanup": cleanup}[sys.argv[1]]()
