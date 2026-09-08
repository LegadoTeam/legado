"""Bound the experimental adaptation to one Java method in one official class."""
from pathlib import Path
import subprocess
import zipfile

original = Path("app/cronetlib/cronet_impl_native_java.jar")
adapted = Path("app/build/cronet-dynamic/cronet_impl_native_java.jar")
loader = "org/chromium/net/impl/CronetLibraryLoader.class"
with zipfile.ZipFile(original) as before, zipfile.ZipFile(adapted) as after:
    assert before.namelist() == after.namelist()
    changed = [name for name in before.namelist() if before.read(name) != after.read(name)]
    assert changed == [loader], changed

def disassemble(jar):
    return subprocess.check_output([
        "javap", "-p", "-c", "-classpath", str(jar),
        "org.chromium.net.impl.CronetLibraryLoader",
    ], text=True)

before, after = disassemble(original), disassemble(adapted)
start = "  public static void loadLibrary();"
end = "  public static void switchToTestLibrary();"
def split(text):
    prefix, rest = text.split(start, 1)
    method, suffix = rest.split(end, 1)
    return prefix, method, suffix

bp, bm, bs = split(before)
ap, am, ass = split(after)
assert bp == ap and bs == ass, "A method other than loadLibrary() changed"
assert bm.count("Method java/lang/System.loadLibrary:") == 3
assert am.count("Method java/lang/System.loadLibrary:") == 1
assert am.count("Method io/legado/app/lib/cronet/CronetLoader.loadLibrary:") == 2
assert am.index("putstatic") > am.rindex("loadLibrary:"), "Loaded flag precedes successful loading"
output = Path("app/build/cronet-runtime")
output.mkdir(parents=True, exist_ok=True)
(output / "adapted-loader-bytecode.txt").write_text(after)
print("Only loadLibrary() changed; all other methods, initialization and version checks are identical.")
