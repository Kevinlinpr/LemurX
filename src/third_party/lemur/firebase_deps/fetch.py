#!/usr/bin/env python3
"""Restore the Maven artifacts required by Aero's Firebase Analytics integration."""
import argparse
import hashlib
import json
from pathlib import Path
import tempfile
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true',
                        help='Check local hashes without downloading')
    args = parser.parse_args()
    directory = Path(__file__).resolve().parent
    for artifact in json.loads((directory / 'artifacts.json').read_text()):
        destination = directory / Path(artifact['path']).name
        if destination.is_file():
            if hashlib.sha256(destination.read_bytes()).hexdigest() == artifact['sha256']:
                print('OK:', destination.name)
                continue
        if args.check:
            raise SystemExit('Missing or mismatched artifact: ' + destination.name)
        with urllib.request.urlopen(artifact['url'], timeout=60) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != artifact['sha256']:
            raise SystemExit('SHA-256 mismatch: ' + artifact['url'])
        destination.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=destination.parent, delete=False) as temporary:
            temporary.write(data)
            temporary_path = Path(temporary.name)
        temporary_path.replace(destination)
        print('Downloaded:', destination.name)


if __name__ == '__main__':
    main()
