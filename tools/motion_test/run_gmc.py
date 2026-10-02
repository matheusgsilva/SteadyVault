"""Teste headless (WebGL1/SwiftShader) da estimativa de movimento global (GlobalMotion.kt).
Gera uma cena sintética ruidosa, desloca por valores conhecidos e compara com a estimativa.
Uso: python3 run_gmc.py"""
import re, sys, pathlib, json, base64
import numpy as np
from playwright.sync_api import sync_playwright

root = pathlib.Path(__file__).resolve()
kt = next(p for p in root.parents if (p / "app").exists()) / \
    "app/src/main/java/com/steadyvault/camera/capture/recorder/GlobalMotion.kt"
src = kt.read_text()
sh = {m.group(1): m.group(2) for m in re.finditer(r'const val (\w+) = """(.*?)"""', src, re.S)}
DOWN = sh["DOWN_FRAGMENT"].replace("#extension GL_OES_EGL_image_external : require", "").replace("samplerExternalOES", "sampler2D")
W, H = 720, 1280
DS, RAD, BANDS = 8, 8, 8
SW, SH = W // DS, H // DS
BAND_H = (SH - 2 * RAD) // BANDS
COLS = (SW - 2 * RAD) // 2
ROWS = BAND_H // 2
CAND = 2 * RAD + 1
defines = (f"#define SW {SW}\n#define SH {SH}\n#define RAD {RAD}\n#define BANDS {BANDS}\n#define BAND_H {BAND_H}\n"
           f"#define COLS {COLS}\n#define ROWS {ROWS}\n#define CW {CAND}\n#define CH {CAND*BANDS}\n")
SAD = defines + sh["SAD_FRAGMENT"]
ARG = defines + sh["ARGMIN_FRAGMENT"]
VERT = sh["VERTEX"]

rng = np.random.default_rng(5)
def scene(h, w):
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    img = 0.35 + 0.1 * np.sin(x / 90) * np.cos(y / 70)
    for _ in range(60):  # blocos de "texto"/objetos
        cx, cy = rng.integers(0, w), rng.integers(0, h)
        ww, hh = rng.integers(20, 200), rng.integers(4, 30)
        img[cy:cy + hh, cx:cx + ww] = rng.uniform(0.1, 0.9)
    return img
base = scene(H + 300, W + 300)

def crop(dx, dy, sigma):
    a = base[150 - dy:150 - dy + H, 150 - dx:150 - dx + W].copy() if False else base[150 - int(round(dy)):150 - int(round(dy)) + H, 150 - int(round(dx)):150 - int(round(dx)) + W].copy()
    return np.clip(a + rng.normal(0, sigma, a.shape), 0, 1)

cases = [(0, 0), (8, 0), (20, 0), (0, 20), (24, 12), (-37, 15), (50, -30), (3, -2), (5, 4), (1, 0), (-6, 2)]
sigmas = [0.0, 0.03, 0.06]
tests = []
for s in sigmas:
    prev = crop(0, 0, s)
    pb = base64.b64encode((prev * 255).astype(np.uint8).tobytes()).decode()
    for dx, dy in cases:
        cur = crop(dx, dy, s)
        tests.append({"s": s, "dx": dx, "dy": dy, "prev": pb if (dx, dy) == cases[0] else "",
                      "cur": base64.b64encode((cur * 255).astype(np.uint8).tobytes()).decode()})

JS = r"""
async (a) => {
  const [VERT, DOWN, SAD, ARG, tests, W, H, SW, SH, CAND, BANDS, RAD, DS] = a;
  const cv = document.createElement('canvas'); cv.width = 64; cv.height = 64;
  const gl = cv.getContext('webgl', {preserveDrawingBuffer: true, antialias: false});
  function prog(fs){const p=gl.createProgram();for(const [t,s] of [[gl.VERTEX_SHADER,VERT],[gl.FRAGMENT_SHADER,fs]]){const sh=gl.createShader(t);gl.shaderSource(sh,s);gl.compileShader(sh);if(!gl.getShaderParameter(sh,gl.COMPILE_STATUS))throw new Error(gl.getShaderInfoLog(sh));gl.attachShader(p,sh);}gl.linkProgram(p);if(!gl.getProgramParameter(p,gl.LINK_STATUS))throw new Error(gl.getProgramInfoLog(p));return p;}
  const pd=prog(DOWN), ps=prog(SAD), pa=prog(ARG);
  const buf=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,buf);
  gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([-1,-1,0,0, 1,-1,1,0, -1,1,0,1, 1,1,1,1]),gl.STATIC_DRAW);
  function quad(p){gl.bindBuffer(gl.ARRAY_BUFFER,buf);const a=gl.getAttribLocation(p,'aPosition'),b=gl.getAttribLocation(p,'aTexCoord');gl.enableVertexAttribArray(a);gl.enableVertexAttribArray(b);gl.vertexAttribPointer(a,2,gl.FLOAT,false,16,0);gl.vertexAttribPointer(b,2,gl.FLOAT,false,16,8);}
  function tex(w,h,data,filt){const t=gl.createTexture();gl.bindTexture(gl.TEXTURE_2D,t);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,filt);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,filt);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,w,h,0,gl.RGBA,gl.UNSIGNED_BYTE,data);return t;}
  function fbo(t){const f=gl.createFramebuffer();gl.bindFramebuffer(gl.FRAMEBUFFER,f);gl.framebufferTexture2D(gl.FRAMEBUFFER,gl.COLOR_ATTACHMENT0,gl.TEXTURE_2D,t,0);if(gl.checkFramebufferStatus(gl.FRAMEBUFFER)!==gl.FRAMEBUFFER_COMPLETE)throw new Error('fbo');return f;}
  const sm=[tex(SW,SH,null,gl.NEAREST),tex(SW,SH,null,gl.NEAREST)], sf=[fbo(sm[0]),fbo(sm[1])];
  const sadT=tex(CAND,CAND*BANDS,null,gl.NEAREST), sadF=fbo(sadT);
  const motT=tex(1,1,null,gl.NEAREST), motF=fbo(motT);
  const ident=new Float32Array([1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1]);
  function rgba(b64){const raw=Uint8Array.from(atob(b64),c=>c.charCodeAt(0));const o=new Uint8Array(W*H*4);for(let i=0;i<W*H;i++){o[i*4]=o[i*4+1]=o[i*4+2]=raw[i];o[i*4+3]=255;}return o;}
  function down(t,dst){gl.bindFramebuffer(gl.FRAMEBUFFER,sf[dst]);gl.viewport(0,0,SW,SH);gl.useProgram(pd);quad(pd);
    gl.uniformMatrix4fv(gl.getUniformLocation(pd,'uTextureMatrix'),false,ident);gl.uniform1f(gl.getUniformLocation(pd,'uRotationDegrees'),0);
    gl.uniform2f(gl.getUniformLocation(pd,'uStep'),(DS/4)/W,(DS/4)/H);gl.activeTexture(gl.TEXTURE0);gl.bindTexture(gl.TEXTURE_2D,t);gl.uniform1i(gl.getUniformLocation(pd,'sTexture'),0);gl.drawArrays(gl.TRIANGLE_STRIP,0,4);}
  const out=[];
  let lastPrev=null;
  for(const t of tests){
    if(t.prev) lastPrev=rgba(t.prev);
    const tp=tex(W,H,lastPrev,gl.LINEAR), tc=tex(W,H,rgba(t.cur),gl.LINEAR);
    down(tp,0); down(tc,1);
    gl.bindFramebuffer(gl.FRAMEBUFFER,sadF);gl.viewport(0,0,CAND,CAND*BANDS);gl.useProgram(ps);quad(ps);
    gl.activeTexture(gl.TEXTURE0);gl.bindTexture(gl.TEXTURE_2D,sm[1]);gl.uniform1i(gl.getUniformLocation(ps,'sCur'),0);
    gl.activeTexture(gl.TEXTURE1);gl.bindTexture(gl.TEXTURE_2D,sm[0]);gl.uniform1i(gl.getUniformLocation(ps,'sPrev'),1);gl.drawArrays(gl.TRIANGLE_STRIP,0,4);
    gl.bindFramebuffer(gl.FRAMEBUFFER,motF);gl.viewport(0,0,1,1);gl.useProgram(pa);quad(pa);
    gl.activeTexture(gl.TEXTURE0);gl.bindTexture(gl.TEXTURE_2D,sadT);gl.uniform1i(gl.getUniformLocation(pa,'sSad'),0);gl.drawArrays(gl.TRIANGLE_STRIP,0,4);
    const px=new Uint8Array(4);gl.readPixels(0,0,1,1,gl.RGBA,gl.UNSIGNED_BYTE,px);
    const ex=((px[0]*256+px[1])/65535*2*RAD-RAD)*DS, ey=((px[2]*256+px[3])/65535*2*RAD-RAD)*DS;
    out.push([t.s,t.dx,t.dy,ex,ey]);
    gl.deleteTexture(tp);gl.deleteTexture(tc);
  }
  return out;
}
"""
with sync_playwright() as p:
    b = p.chromium.launch(args=["--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--ignore-gpu-blocklist"])
    pg = b.new_page()
    pg.on("console", lambda m: print("[js]", m.text))
    pg.set_content("<html><body></body></html>")
    res = pg.evaluate(JS, [VERT, DOWN, SAD, ARG, tests, W, H, SW, SH, CAND, BANDS, RAD, DS])
    b.close()
print("sigma  truth(dx,dy)   estimado(dx,dy)  erro(px de saída)")
errs = {}
for s, dx, dy, ex, ey in res:
    e = max(abs(ex - dx), abs(ey - dy))
    errs.setdefault(s, []).append(e)
    print(f"{s:.2f}  ({dx:4d},{dy:4d})   ({ex:7.1f},{ey:7.1f})   {e:5.1f}")
for s, v in errs.items():
    print(f"sigma {s}: erro médio {np.mean(v):.1f}px  máx {np.max(v):.1f}px")
