"""Fetch the reporter's public textbook fixture into the test APK, never the app APK."""
import hashlib
from pathlib import Path
from urllib.parse import quote
from urllib.request import urlopen

URL = (
    # The original repository is blocked; this pinned LFS mirror has identical bytes.
    "https://media.githubusercontent.com/media/290713469/ChinaTextbook/"
    "69646c6c4ce1ca5f27f28d0edd70a07599fbb086/"
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
