"""Teste headless (WebGL1/SwiftShader) dos shaders de MotionShaders.kt.
Uso: python3 run.py [saida_dir]
Compara PSNR do frame central: crossfade x interpolação com movimento, contra o frame real."""
import json, re, sys, base64, pathlib
from playwright.sync_api import sync_playwright

root = pathlib.Path(__file__).resolve()
kt = next(p for p in root.parents if (p / "app").exists()) / \
    "app/src/main/java/com/steadyvault/camera/capture/recorder/MotionShaders.kt"
src = kt.read_text()
shaders = {m.group(1): m.group(2) for m in re.finditer(r'const val (\w+) = """(.*?)"""', src, re.S)}
look_src = (kt.parent / "VideoLook.kt").read_text()
look_glsl = re.search(r'const val GLSL = """(.*?)"""', look_src, re.S).group(1)
shaders = {k: v.replace("LOOK_GLSL", look_glsl) for k, v in shaders.items()}
shaders["LOOK_GLSL"] = look_glsl
out = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/motion_out")
out.mkdir(parents=True, exist_ok=True)
page_html = (root.parent / "harness.html").read_text()

with sync_playwright() as p:
    b = p.chromium.launch(args=["--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader",
                                "--ignore-gpu-blocklist", "--allow-file-access-from-files"])
    pg = b.new_page()
    pg.on("console", lambda m: print("[js]", m.text))
    pg.set_content(page_html)
    if len(sys.argv) > 3 and sys.argv[2] == "--real":
        urls = ["data:image/png;base64," + base64.b64encode(open(x, "rb").read()).decode() for x in sys.argv[3:6]]
        pg.evaluate("(u) => window.loadReal(u)", urls)
        if len(sys.argv) > 6:
            pg.evaluate("(p) => { window.PARAMS = JSON.parse(p); }", sys.argv[6])
    res = pg.evaluate("(s) => window.runTests(s)", shaders)
    for k, v in res.items():
        if k.endswith("_png"):
            (out / (k[:-4] + ".png")).write_bytes(base64.b64decode(v.split(",")[1]))
    if len(sys.argv) > 2 and sys.argv[2] != "--real":  # imagem de entrada -> aplica o look e salva
        import base64 as b64
        data = "data:image/png;base64," + b64.b64encode(open(sys.argv[2], "rb").read()).decode()
        png = pg.evaluate("([s,d]) => window.lookTest(s,d)", [shaders, data])
        (out / "look.png").write_bytes(base64.b64decode(png.split(",")[1]))
    print(json.dumps({k: v for k, v in res.items() if not k.endswith("_png")}, indent=1))
    b.close()
