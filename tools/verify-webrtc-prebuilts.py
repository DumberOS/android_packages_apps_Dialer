#!/usr/bin/env python3
"""Check the shipped WebRTC artifacts without fetching or building WebRTC."""
import argparse
import hashlib
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle', type=Path, default=Path(__file__).resolve().parents[1] / 'prebuilts/webrtc')
    args = parser.parse_args()
    try:
        hashes = json.loads((args.bundle / 'manifest.json').read_text())['sha256']
        required = {'classes.jar', 'arm64-v8a/libjingle_peerconnection_so.so',
                    'armeabi-v7a/libjingle_peerconnection_so.so', 'LICENSE', 'THIRD_PARTY_LICENSES.md'}
        if not required.issubset(hashes):
            raise ValueError('Incomplete WebRTC manifest')
        for name, expected in hashes.items():
            path = args.bundle / name
            if not path.resolve().is_relative_to(args.bundle.resolve()):
                raise ValueError('Invalid artifact path')
            if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
                raise ValueError('Checksum mismatch: ' + name)
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(1, f'WebRTC prebuilts: {error}. Sync the complete prebuilt bundle; no WebRTC checkout is needed.\n')
    print('WebRTC ARM32/ARM64 libraries, Java bindings and licenses verified')


if __name__ == '__main__':
    main()
