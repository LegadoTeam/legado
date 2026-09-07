"""Fetch the reporter's public textbook fixture into the test APK, never the app APK."""
import hashlib
from pathlib import Path
from urllib.parse import quote
from urllib.request import urlopen

URL = (
    "https://raw.githubusercontent.com/TapXWorld/ChinaTextbook/"
    "5a80345f2043ba6f8db8d7be9cf3db82725ff1f7/"
    "小学/数学/人教版/义务教育教科书·数学六年级下册.pdf"
)
SHA256 = "7e0e76e739c7ac65013c7eb9bfad0f7ef632b2d05cc8d5a1d0970c4b93aab18b"
target = Path("app/src/androidTest/assets/pdf_zoom_textbook.pdf")
with urlopen(quote(URL, safe=":/"), timeout=60) as response:
    data = response.read()
if hashlib.sha256(data).hexdigest() != SHA256:
    raise RuntimeError("PDF zoom textbook fixture does not match the pinned source")
target.parent.mkdir(parents=True, exist_ok=True)
target.write_bytes(data)
print(f"Verified PDF zoom textbook fixture: {len(data)} bytes, {SHA256}")
