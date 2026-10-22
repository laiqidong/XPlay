#!/usr/bin/env python3
"""Prepare user-supplied local PingFang SC resources; never downloads fonts."""
import argparse
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, help='Local PingFang.ttc supplied by the user')
    args = parser.parse_args()
    try:
        from fontTools.ttLib import TTCollection
    except ImportError:
        parser.error('fontTools is required in this Python environment.')
    source = args.source
    if source is None:
        choices = sorted(Path('/System/Library/AssetsV2').glob('com_apple_MobileAsset_Font*/*/AssetData/PingFang.ttc'))
        source = choices[-1] if choices else Path('/System/Library/Fonts/PingFang.ttc')
    if not source.is_file():
        parser.error('No local PingFang.ttc found. Provide --source.')
    output = Path(__file__).resolve().parents[1] / 'common/src/main/res/font'
    output.mkdir(exist_ok=True)
    wanted = {'PingFangSC-Regular': 'regular', 'PingFangSC-Medium': 'medium', 'PingFangSC-Semibold': 'semibold'}
    collection = TTCollection(str(source), lazy=True)
    try:
        matches = {font['name'].getDebugName(6): font for font in collection.fonts}
        missing = set(wanted) - set(matches)
        if missing:
            parser.error('Missing local font faces: ' + ', '.join(sorted(missing)))
        for name, suffix in wanted.items():
            font = matches[name]
            if font.sfntVersion != 'OTTO':
                parser.error('Expected the local OpenType/CFF PingFang collection.')
            destination = output / f'pingfang_{suffix}.otf'
            font.save(destination)
            print(f'{name}: {destination.name}, {destination.stat().st_size} bytes')
    finally:
        collection.close()


if __name__ == '__main__':
    main()
