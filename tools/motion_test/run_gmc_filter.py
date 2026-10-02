"""Teste headless do filtro de ingest (FRAGMENT_SHADER_INGEST) com e sem alinhamento por movimento global,
numa panorâmica sintética ruidosa. Uso: python3 run_gmc_filter.py"""
import re, pathlib, base64, json
import numpy as np
from playwright.sync_api import sync_playwright

root = pathlib.Path(__file__).resolve()
rec = next(p for p in root.parents if (p / "app").exists()) / "app/src/main/java/com/steadyvault/camera/capture/recorder"
def consts(f): return {m.group(1): m.group(2) for m in re.finditer(r'const val (\w+) = """(.*?)"""', (rec / f).read_text(), re.S)}
g = consts("GlobalMotion.kt"); b = consts("RealTimeCfrSurfaceBridge.kt")
OES = lambda t: t.replace("#extension GL_OES_EGL_image_external : require", "").replace("samplerExternalOES", "sampler2D")
W, H = 720, 1280; DS, RAD, BANDS = 8, 8, 8
SW, SH = W // DS, H // DS; BAND_H = (SH - 2 * RAD) // BANDS; COLS = (SW - 2 * RAD) // 2; ROWS = BAND_H // 2; CAND = 2 * RAD + 1
defs = (f"#define SW {SW}\n#define SH {SH}\n#define RAD {RAD}\n#define BANDS {BANDS}\n#define BAND_H {BAND_H}\n"
        f"#define COLS {COLS}\n#define ROWS {ROWS}\n#define CW {CAND}\n#define CH {CAND*BANDS}\n")
rng = np.random.default_rng(7)
def scene(h, w):
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    img = 0.35 + 0.1 * np.sin(x / 90) * np.cos(y / 70)
    for _ in range(90):
        cx, cy = rng.integers(0, w), rng.integers(0, h); ww, hh = rng.integers(20, 200), rng.integers(4, 30)
        img[cy:cy + hh, cx:cx + ww] = rng.uniform(0.1, 0.9)
    return img
base = scene(H + 100, W + 700)
FRAMES, DX, DY, SIG = 12, 14, 3, 0.04
frames, cleans = [], []
for t in range(FRAMES):
    ox, oy = 600 - DX * t, 50 - DY * t
    cl = base[oy:oy + H, ox:ox + W]; cleans.append(cl)
    frames.append(np.clip(cl + rng.normal(0, SIG, cl.shape), 0, 1))
enc = lambda a: base64.b64encode((a * 255).astype(np.uint8).tobytes()).decode()
JS = open(pathlib.Path(__file__).with_name("gmc_filter.js")).read()
with sync_playwright() as p:
    br = p.chromium.launch(args=["--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--ignore-gpu-blocklist"])
    pg = br.new_page(); pg.on("console", lambda m: print("[js]", m.text) if "stall" not in m.text else None)
    pg.set_content("<html><body></body></html>")
    out = pg.evaluate(JS, {"VERT": g["VERTEX"], "DOWN": OES(g["DOWN_FRAGMENT"]), "SAD": defs + g["SAD_FRAGMENT"], "ARG": defs + g["ARGMIN_FRAGMENT"],
                           "INGEST": OES(b["FRAGMENT_SHADER_INGEST"]), "frames": [enc(f) for f in frames], "W": W, "H": H, "SW": SW, "SH": SH,
                           "CAND": CAND, "BANDS": BANDS, "RAD": RAD, "DS": DS, "noiseScale": 1.0})
    br.close()
for mode in ("sem alinhamento", "com alinhamento"):
    outs = np.array([np.frombuffer(base64.b64decode(x), np.uint8).reshape(H, W).astype(np.float32) / 255 for x in out[mode]["frames"]])
    err = [float(np.std((outs[t] - cleans[t])[100:-100, 100:-100])) for t in range(FRAMES)]
    print(mode, "erro vs cena limpa por frame:", [round(e, 4) for e in err])
    print("   shifts estimados:", out[mode]["shifts"])
print("ruído de entrada:", SIG)
