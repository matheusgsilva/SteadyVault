async (A) => {
  const {VERT,DOWN,SAD,ARG,INGEST,frames,W,H,SW,SH,CAND,BANDS,RAD,DS,noiseScale}=A;
  const cv=document.createElement('canvas');cv.width=64;cv.height=64;
  const gl=cv.getContext('webgl',{preserveDrawingBuffer:true,antialias:false});
  function prog(fs){const p=gl.createProgram();for(const [t,s] of [[gl.VERTEX_SHADER,VERT],[gl.FRAGMENT_SHADER,fs]]){const sh=gl.createShader(t);gl.shaderSource(sh,s);gl.compileShader(sh);if(!gl.getShaderParameter(sh,gl.COMPILE_STATUS))throw new Error(gl.getShaderInfoLog(sh));gl.attachShader(p,sh);}gl.linkProgram(p);if(!gl.getProgramParameter(p,gl.LINK_STATUS))throw new Error(gl.getProgramInfoLog(p));return p;}
  const pd=prog(DOWN),ps=prog(SAD),pa=prog(ARG),pi=prog(INGEST);
  const buf=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,buf);gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([-1,-1,0,0,1,-1,1,0,-1,1,0,1,1,1,1,1]),gl.STATIC_DRAW);
  function quad(p){gl.bindBuffer(gl.ARRAY_BUFFER,buf);const a=gl.getAttribLocation(p,'aPosition'),b=gl.getAttribLocation(p,'aTexCoord');gl.enableVertexAttribArray(a);gl.enableVertexAttribArray(b);gl.vertexAttribPointer(a,2,gl.FLOAT,false,16,0);gl.vertexAttribPointer(b,2,gl.FLOAT,false,16,8);}
  function tex(w,h,data,filt){const t=gl.createTexture();gl.bindTexture(gl.TEXTURE_2D,t);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,filt);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,filt);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,w,h,0,gl.RGBA,gl.UNSIGNED_BYTE,data);return t;}
  function fbo(t){const f=gl.createFramebuffer();gl.bindFramebuffer(gl.FRAMEBUFFER,f);gl.framebufferTexture2D(gl.FRAMEBUFFER,gl.COLOR_ATTACHMENT0,gl.TEXTURE_2D,t,0);if(gl.checkFramebufferStatus(gl.FRAMEBUFFER)!==gl.FRAMEBUFFER_COMPLETE)throw new Error('fbo');return f;}
  const ident=new Float32Array([1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1]);
  function rgba(b64){const raw=Uint8Array.from(atob(b64),c=>c.charCodeAt(0));const o=new Uint8Array(W*H*4);for(let i=0;i<W*H;i++){o[i*4]=o[i*4+1]=o[i*4+2]=raw[i];o[i*4+3]=255;}return o;}
  const camTex=frames.map(f=>tex(W,H,rgba(f),gl.LINEAR));
  const result={};
  for(const mode of ['sem alinhamento','com alinhamento']){
    const useMotion=mode==='com alinhamento';
    const sm=[tex(SW,SH,null,gl.NEAREST),tex(SW,SH,null,gl.NEAREST)],sf=[fbo(sm[0]),fbo(sm[1])];
    const sadT=tex(CAND,CAND*BANDS,null,gl.NEAREST),sadF=fbo(sadT),motT=tex(1,1,null,gl.NEAREST),motF=fbo(motT);
    const ring=[tex(W,H,null,gl.LINEAR),tex(W,H,null,gl.LINEAR)],rf=[fbo(ring[0]),fbo(ring[1])];
    let cur=0,prevOut=-1;const outs=[],shifts=[];
    for(let t=0;t<frames.length;t++){
      // 1) downscale
      gl.bindFramebuffer(gl.FRAMEBUFFER,sf[cur]);gl.viewport(0,0,SW,SH);gl.useProgram(pd);quad(pd);
      gl.uniformMatrix4fv(gl.getUniformLocation(pd,'uTextureMatrix'),false,ident);gl.uniform1f(gl.getUniformLocation(pd,'uRotationDegrees'),0);
      gl.uniform2f(gl.getUniformLocation(pd,'uStep'),(DS/4)/W,(DS/4)/H);gl.activeTexture(gl.TEXTURE0);gl.bindTexture(gl.TEXTURE_2D,camTex[t]);gl.uniform1i(gl.getUniformLocation(pd,'sTexture'),0);gl.drawArrays(gl.TRIANGLE_STRIP,0,4);
      if(t>0){
        gl.bindFramebuffer(gl.FRAMEBUFFER,sadF);gl.viewport(0,0,CAND,CAND*BANDS);gl.useProgram(ps);quad(ps);
        gl.activeTexture(gl.TEXTURE0);gl.bindTexture(gl.TEXTURE_2D,sm[cur]);gl.uniform1i(gl.getUniformLocation(ps,'sCur'),0);
        gl.activeTexture(gl.TEXTURE1);gl.bindTexture(gl.TEXTURE_2D,sm[1-cur]);gl.uniform1i(gl.getUniformLocation(ps,'sPrev'),1);gl.drawArrays(gl.TRIANGLE_STRIP,0,4);
        gl.bindFramebuffer(gl.FRAMEBUFFER,motF);gl.viewport(0,0,1,1);gl.useProgram(pa);quad(pa);
        gl.activeTexture(gl.TEXTURE0);gl.bindTexture(gl.TEXTURE_2D,sadT);gl.uniform1i(gl.getUniformLocation(pa,'sSad'),0);gl.drawArrays(gl.TRIANGLE_STRIP,0,4);
        const px=new Uint8Array(4);gl.readPixels(0,0,1,1,gl.RGBA,gl.UNSIGNED_BYTE,px);
        shifts.push([+(((px[0]*256+px[1])/65535*2*RAD-RAD)*DS).toFixed(1),+(((px[2]*256+px[3])/65535*2*RAD-RAD)*DS).toFixed(1)]);
      } else shifts.push([0,0]);
      cur=1-cur;
      // 2) ingest (filtro temporal): destino = ring[k], histórico = saída anterior
      const k=t%2;
      gl.bindFramebuffer(gl.FRAMEBUFFER,rf[k]);gl.viewport(0,0,W,H);gl.useProgram(pi);quad(pi);
      gl.uniformMatrix4fv(gl.getUniformLocation(pi,'uTextureMatrix'),false,ident);gl.uniform1f(gl.getUniformLocation(pi,'uRotationDegrees'),0);
      gl.activeTexture(gl.TEXTURE0);gl.bindTexture(gl.TEXTURE_2D,camTex[t]);gl.uniform1i(gl.getUniformLocation(pi,'sTexture'),0);
      gl.activeTexture(gl.TEXTURE1);gl.bindTexture(gl.TEXTURE_2D,prevOut>=0?ring[prevOut]:null);gl.uniform1i(gl.getUniformLocation(pi,'sHistory'),1);
      gl.activeTexture(gl.TEXTURE2);gl.bindTexture(gl.TEXTURE_2D,motT);gl.uniform1i(gl.getUniformLocation(pi,'sMotion'),2);
      gl.uniform1f(gl.getUniformLocation(pi,'uHistoryWeight'),prevOut>=0?1:0);
      gl.uniform1f(gl.getUniformLocation(pi,'uMotionOn'),(useMotion&&prevOut>=0)?1:0);
      gl.uniform1f(gl.getUniformLocation(pi,'uShiftScale'),DS);gl.uniform1f(gl.getUniformLocation(pi,'uShiftRadius'),RAD);
      gl.uniform2f(gl.getUniformLocation(pi,'uOutTexel'),1/W,1/H);gl.uniform2f(gl.getUniformLocation(pi,'uTexel'),1/W,1/H);
      gl.uniform1f(gl.getUniformLocation(pi,'uNoiseScale'),noiseScale);gl.uniform1f(gl.getUniformLocation(pi,'uMaxAge'),5);
      gl.drawArrays(gl.TRIANGLE_STRIP,0,4);
      const o=new Uint8Array(W*H*4);gl.readPixels(0,0,W,H,gl.RGBA,gl.UNSIGNED_BYTE,o);
      const g=new Uint8Array(W*H);for(let i=0;i<W*H;i++)g[i]=o[i*4];
      outs.push(btoa(String.fromCharCode.apply(null,Array.from({length:0}))) );
      let s='';const CH=32768;for(let i=0;i<g.length;i+=CH)s+=String.fromCharCode.apply(null,g.subarray(i,i+CH));
      outs[outs.length-1]=btoa(s);
      prevOut=k;
    }
    result[mode]={frames:outs,shifts};
  }
  return result;
}
