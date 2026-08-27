#!/usr/bin/env python3
from pathlib import Path
import shutil
import sys

ROOT = Path(__file__).resolve().parents[1]
BASE = Path('/tmp/universal-loader-base')
MARKER = ROOT / '.runtime-vendored'

if MARKER.exists():
    print('Runtime already vendored; nothing to do.')
    sys.exit(0)

if not BASE.exists():
    raise SystemExit(f'Missing source runtime at {BASE}')

for name in ('runtime-core', 'runtime-bridge', 'runtime-codegen', 'licenses'):
    source = BASE / name
    target = ROOT / name
    if not source.exists():
        raise SystemExit(f'Missing {source}')
    if target.exists():
        shutil.rmtree(target)
    shutil.copytree(source, target)

# Remove target-specific and environment-hiding native modules from the universal build.
android_mk = ROOT / 'runtime-core/src/main/cpp/Android.mk'
text = android_mk.read_text(encoding='utf-8')
for source_name in (
    'Hook/CarromAimHook.cpp',
    'Utils/AntiDetection.cpp',
    'Utils/VirtualSpoof.cpp',
):
    text = text.replace('\\\n' + source_name + ' \\\n', '\\\n')
    text = text.replace(source_name + ' \\\n', '')
    text = text.replace(source_name + '\n', '')
text = text.replace('LOCAL_MODULE := carromruntime', 'LOCAL_MODULE := universalruntime')
android_mk.write_text(text, encoding='utf-8')

for relative in (
    'runtime-core/src/main/cpp/Hook/CarromAimHook.cpp',
    'runtime-core/src/main/cpp/Utils/AntiDetection.cpp',
    'runtime-core/src/main/cpp/Utils/VirtualSpoof.cpp',
):
    path = ROOT / relative
    if path.exists():
        path.unlink()

native_core = ROOT / 'runtime-core/src/main/java/dev/jaowzin/carromloader/runtime/core/NativeCore.java'
native_text = native_core.read_text(encoding='utf-8')
native_text = native_text.replace('System.loadLibrary("carromruntime")', 'System.loadLibrary("universalruntime")')
native_core.write_text(native_text, encoding='utf-8')

io_core = ROOT / 'runtime-core/src/main/java/dev/jaowzin/carromloader/runtime/core/IOCore.java'
io_text = io_core.read_text(encoding='utf-8').replace('/carromruntime/', '/universalruntime/')
io_core.write_text(io_text, encoding='utf-8')

# Keep the Apache-2.0 runtime notice exactly as vendored.
license_file = ROOT / 'licenses/runtime-engine/LICENSE'
if not license_file.is_file():
    raise SystemExit('Required runtime license was not copied')

MARKER.write_text(
    'Vendored from jaowzin/Loader runtime. CarromAimHook, AntiDetection and VirtualSpoof excluded.\n',
    encoding='utf-8'
)
print('Universal runtime vendored successfully.')
