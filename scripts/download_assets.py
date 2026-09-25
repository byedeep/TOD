#!/usr/bin/env python3
"""Fetch legacy Minecraft assets over HTTPS; verify the Mojang index hashes."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import sys
import urllib.request


def main():
    index_path, asset_root = map(Path, sys.argv[1:])
    index = json.loads(index_path.read_text())

    def fetch(item):
        name, spec = item
        digest = spec['hash']
        relative = digest[:2] + '/' + digest
        target = asset_root / 'objects' / relative
        if target.exists() and hashlib.sha1(target.read_bytes()).hexdigest() == digest:
            data = target.read_bytes()
        else:
            for attempt in range(3):
                try:
                    with urllib.request.urlopen('https://resources.download.minecraft.net/' + relative, timeout=30) as response:
                        data = response.read()
                    if len(data) != spec['size'] or hashlib.sha1(data).hexdigest() != digest:
                        raise ValueError('asset checksum mismatch: ' + name)
                    target.parent.mkdir(parents=True, exist_ok=True)
                    temporary = target.with_suffix('.part')
                    temporary.write_bytes(data)
                    temporary.replace(target)
                    break
                except Exception:
                    if attempt == 2:
                        raise
        if index.get('virtual'):
            virtual = asset_root / 'virtual' / index_path.stem / name
            virtual.parent.mkdir(parents=True, exist_ok=True)
            virtual.write_bytes(data)

    # Multiple logical assets can share a hash; download each hash only once.
    objects = index['objects']
    if not index.get('virtual'):
        objects = {spec['hash']: spec for spec in objects.values()}
    with ThreadPoolExecutor(max_workers=8) as pool:
        for _ in pool.map(fetch, objects.items()):
            pass
    print('Verified', len(objects), 'Minecraft assets over HTTPS.')


if __name__ == '__main__':
    main()
