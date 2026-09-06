from pathlib import Path

path = Path('app/build.gradle.kts')
text = path.read_text(encoding='utf-8')
old = '''        jniLibs {\n            useLegacyPackaging = true\n            keepDebugSymbols += setOf(\n'''
new = '''        jniLibs {\n            useLegacyPackaging = true\n            // VLC e OpenCV distribuem a mesma runtime libc++_shared. O APK deve\n            // carregar uma única cópia por ABI; ambas usam a ABI estável do NDK.\n            pickFirsts += setOf("**/libc++_shared.so")\n            keepDebugSymbols += setOf(\n'''
if old not in text:
    raise SystemExit('bloco jniLibs nao encontrado')
path.write_text(text.replace(old, new, 1), encoding='utf-8')
