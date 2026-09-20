#!/usr/bin/env python3
"""Build a small, explicit-allowlist Chrome package; no backend state or secrets."""
import hashlib
import json
import shutil
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'demo/extension'
manifest = json.loads((SOURCE / 'manifest.json').read_text())
version = manifest['version']
files = ['manifest.json', 'background.js', 'guards.js', 'platforms.js', 'content.js',
         'popup.html', 'popup.css', 'popup.js', 'options.html', 'options.js', 'README.md',
         *[f'icons/icon{size}.png' for size in (16, 32, 48, 128)]]
dist = ROOT / 'dist'
dist.mkdir(exist_ok=True)
folder = dist / f'evidence-check-{version}'
if folder.exists():
    shutil.rmtree(folder)
folder.mkdir()
for name in files:
    target = folder / name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(SOURCE / name, target)
archive = dist / f'evidence-check-{version}.zip'
with ZipFile(archive, 'w', ZIP_DEFLATED) as bundle:
    for name in files:
        bundle.write(folder / name, f'{folder.name}/{name}')
# Assert the package is exactly the reviewed runtime file set.
with ZipFile(archive) as bundle:
    assert sorted(bundle.namelist()) == sorted(f'{folder.name}/{name}' for name in files)
    assert 'local-config' not in '\n'.join(bundle.namelist())
    token_file = ROOT / 'demo/data/local-token'
    if token_file.exists():
        token = token_file.read_bytes().strip()
        assert all(token not in bundle.read(name) for name in bundle.namelist())
digest = hashlib.sha256(archive.read_bytes()).hexdigest()
(dist / f'{archive.name}.sha256').write_text(f'{digest}  {archive.name}\n')
print(f'Unpacked extension: {folder}')
print(f'ZIP: {archive} ({archive.stat().st_size:,} bytes)')
print(f'Files: {len(files)}; no local pairing token included.')
