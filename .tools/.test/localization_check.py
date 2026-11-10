"""출시 언어 리소스의 누락과 서식 인자 손상을 검사합니다."""

from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
LANGUAGES = ("ko", "es", "ja", "pt", "fr", "de", "zh", "hi", "ar", "ru", "id")


def strings(path):
    """실제 Android 문자열 리소스를 이름과 내용으로 읽습니다."""
    return {item.attrib["name"]: "".join(item.itertext()) for item in ET.parse(path).getroot() if item.tag == "string"}


class LocalizationReleaseCheck(unittest.TestCase):
    """모든 출시 언어가 기본 리소스와 같은 사용자 문구와 서식을 제공하는지 확인합니다."""

    def test_language_resources_and_format_arguments(self):
        """번역이 빠지거나 숫자·문자열 인자가 달라지는 회귀를 차단합니다."""
        base = strings(ROOT / "app/src/main/res/values/strings.xml")
        for language in LANGUAGES:
            with self.subTest(language=language):
                qualifier = "in" if language == "id" else language
                path = ROOT / f"app/src/main/res/values-{qualifier}/strings.xml"
                self.assertTrue(path.is_file(), f"번역 파일 없음: {language}")
                translated = strings(path)
                self.assertEqual(set(base), set(translated), f"번역 키 불일치: {language}")
                for name, value in base.items():
                    self.assertEqual(
                        sorted(re.findall(r"%\d+\$[dsf]", value)),
                        sorted(re.findall(r"%\d+\$[dsf]", translated[name])),
                        f"서식 인자 불일치: {language}/{name}",
                    )


if __name__ == "__main__":
    unittest.main()
