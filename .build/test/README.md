# Jules Code 테스트 및 UI 캡처 도구 (`.build/test/`)

이 디렉토리는 Android 앱의 자동화 테스트, UI 계층 구조 분석, 화면 캡처 스크립트를 모아둔 곳입니다.

## 파일 구성

| 파일명 | 설명 |
|--------|------|
| `run_tests.ps1` | 포터블 SDK 환경을 활성화하고 단위 테스트 또는 캡처 스크립트를 원클릭으로 실행하는 PowerShell 스크립트 |
| `parse_dump.py` | UI Automator dump XML 파일에서 텍스트 노드와 터치 좌표(`bounds`)를 추출해 출력하는 분석기 |
| `capture_all.py` | 세션 목록, 세션 상세, 저장소, 설정 등 주요 탭 전체 화면을 순차 캡처하는 스크립트 |
| `capture_new.py` | 온보딩부터 데모 모드 진입 및 세션 상세까지의 워크플로우 캡처 스크립트 |
| `capture_clean.py` | 스낵바 및 일시적 UI가 모두 닫힌 상태의 정적 화면을 깔끔하게 캡처하는 스크립트 |
| `capture_v2.py` | 최신 UI 개선 버전 대상 자동화 캡처 스크립트 |
| `ui_dump.xml` | 세션 상세 화면의 UI Automator 덤프 원본 데이터 |

## 사용 방법

### 1. 단위 테스트 실행 (JUnit)
```powershell
.\.build\test\run_tests.ps1 -Type unit
```

### 2. UI 덤프 좌표 분석
```powershell
python .build/test/parse_dump.py
```

### 3. 화면 캡처 자동화 (에뮬레이터 또는 디바이스 연결 시)
```powershell
.\.build\test\run_tests.ps1 -Type capture -CaptureScript capture_clean.py
```

