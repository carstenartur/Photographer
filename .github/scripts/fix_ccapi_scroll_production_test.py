from pathlib import Path

path = Path(
    "app/src/test/java/com/photographer/focusstack/camera/backend/"
    "CanonCcapiProductionLiveViewTest.java"
)
text = path.read_text(encoding="utf-8")
old = (
    '                descriptor("/ccapi/ver100/shooting/liveview/scroll", '
    'true, false, true),'
)
new = (
    '                descriptor("/ccapi/ver100/shooting/liveview/scroll", '
    'flipGet, false, flipGet),'
)
count = text.count(old)
if count != 1:
    raise SystemExit(f"Expected one scroll catalog descriptor, found {count}")
path.write_text(text.replace(old, new), encoding="utf-8")
