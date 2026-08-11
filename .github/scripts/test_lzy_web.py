import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).with_name("lzy_web.py")
SPEC = importlib.util.spec_from_file_location("lzy_web", SCRIPT)
lzy_web = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(lzy_web)


class LanzouUploadTest(unittest.TestCase):

    def test_main_reports_login_and_upload_failures(self):
        with mock.patch.object(lzy_web, "login_by_cookie", return_value=False):
            self.assertEqual(1, lzy_web.main(["apk", "1"]))
        with mock.patch.object(lzy_web, "login_by_cookie", return_value=True), \
                mock.patch.object(lzy_web, "upload", return_value=False):
            self.assertEqual(1, lzy_web.main(["apk", "1"]))

    def test_folder_upload_reports_any_failed_file(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, "a.apk").touch()
            Path(directory, "b.apk").touch()
            with mock.patch.object(lzy_web, "upload_file", side_effect=[True, False]):
                self.assertFalse(lzy_web.upload_folder(directory, "1"))


if __name__ == "__main__":
    unittest.main()
