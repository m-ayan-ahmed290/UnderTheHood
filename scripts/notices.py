#!/usr/bin/env python3
"""Collect license texts for Go modules used by the native Android library."""
import pathlib, subprocess
root = pathlib.Path(__file__).resolve().parent.parent
print('UnderTheHood: MIT. Native engine: Rclone (MIT) and its dependencies.\n')
print((root / 'LICENSE').read_text())
goroot = pathlib.Path(subprocess.check_output(['go', 'env', 'GOROOT'], text=True).strip())
print('\nGo runtime and standard library\n' + (goroot / 'LICENSE').read_text())
paths = subprocess.check_output(['go', 'list', '-mod=readonly', '-deps', '-f', '{{if .Module}}{{.Module.Dir}}{{end}}', '.'], cwd=root/'native/rclone', text=True)
for folder in sorted(set(paths.splitlines())):
    if not folder:
        continue
    module = pathlib.Path(folder)
    files = sorted({p for pattern in ('LICENSE*', 'LICENCE*', 'COPYING*', 'NOTICE*') for p in module.glob(pattern) if p.is_file()})
    print('\n' + '='*72 + '\n' + module.name)
    for file in files:
        print('\n' + file.name + '\n' + file.read_text(errors='replace'))
print('\nAndroid libraries and versions: apps/android/build.gradle.kts. AndroidX libraries: Apache-2.0. Kotlin and kotlinx.coroutines: Apache-2.0. Android platform components are provided by the device.')
