#!/usr/bin/env python3
"""Local signed car build and explicit ADB deployment. Never runs tests or Lint."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from datetime import datetime

ROOT = Path(__file__).resolve().parents[1]
LATEST = ROOT / 'artifacts/releases/latest-car-build.json'
PACKAGE = 'com.laiqidong.xplay'
ACTIVITY = PACKAGE + '/com.shilapi.xcertplay.DiPlayActivity'


def run(args, **kwargs):
    return subprocess.run([str(a) for a in args], cwd=ROOT, check=True, **kwargs)


def sdk_tool(name):
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:
        props = ROOT / 'local.properties'
        if props.exists():
            match = re.search(r'^sdk.dir=(.+)$', props.read_text(), re.M)
            if match:
                sdk = match[1].replace('\\:', ':').replace('\\ ', ' ')
    sdk = Path(sdk) if sdk else Path.home() / 'Library/Android/sdk'
    candidates = list((sdk / 'build-tools').glob('*/' + name))
    if not candidates:
        raise RuntimeError(f'找不到 {name}，请设置 ANDROID_HOME。')
    return max(candidates, key=lambda p: tuple(int(x) for x in re.findall(r'\d+', p.parent.name)))


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def certificate(apk):
    result = run([sdk_tool('apksigner'), 'verify', '--print-certs', apk],
                 capture_output=True, text=True)
    match = re.search(r'Signer #1 certificate SHA-256 digest: (\w+)', result.stdout)
    if not match:
        raise RuntimeError('APK 签名验证失败。')
    return match[1]


def build():
    # A failed new build must never leave an older package deployable by accident.
    LATEST.unlink(missing_ok=True)
    env = os.environ.copy()
    signing = ROOT / '.private/xplay-signing'
    env.setdefault('DIPLAY_AUTH_ASSETS_DIR', str(ROOT / '.private/runtime-assets'))
    env.setdefault('ANDROID_KEYSTORE_PATH', str(signing / 'xplay-release.jks'))
    env.setdefault('ANDROID_KEY_ALIAS', 'xplay')
    if not env.get('ANDROID_KEYSTORE_PASSWORD'):
        password_file = signing / 'password'
        if not password_file.is_file():
            raise RuntimeError('缺少本地签名密码文件或 ANDROID_KEYSTORE_PASSWORD。')
        env['ANDROID_KEYSTORE_PASSWORD'] = password_file.read_text().strip()
    env.setdefault('ANDROID_KEY_PASSWORD', env['ANDROID_KEYSTORE_PASSWORD'])
    if not Path(env['ANDROID_KEYSTORE_PATH']).is_file():
        raise RuntimeError('签名密钥文件不存在。')
    if not Path(env['DIPLAY_AUTH_ASSETS_DIR']).is_dir():
        raise RuntimeError('本地认证资源目录不存在。')
    run(['./gradlew', ':mobile:assembleStandaloneRelease', '-x', ':mobile:lintVitalRelease'], env=env)
    source = ROOT / 'mobile/build/outputs/apk/release/mobile-release.apk'
    cert = certificate(source)
    now = datetime.now().astimezone()
    folder = ROOT / 'artifacts/releases' / now.strftime('%Y-%m-%d')
    folder.mkdir(parents=True, exist_ok=True)
    apk = folder / ('XPlay-car-' + now.strftime('%Y%m%d-%H%M%S-%f') + '-release.apk')
    shutil.copy2(source, apk)
    sha = digest(apk)
    apk.with_suffix('.apk.sha256').write_text(sha + '  ' + apk.name + '\n')
    metadata = {'apk': str(apk.relative_to(ROOT)), 'sha256': sha,
                'certificateSha256': cert, 'builtAt': now.isoformat(), 'package': PACKAGE}
    temporary = LATEST.with_suffix('.json.tmp')
    temporary.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n')
    temporary.replace(LATEST)
    print(f'\n打包完成：{apk}\n更新车机：./deplory.sh')


def deploy(args):
    if not LATEST.is_file():
        raise RuntimeError('没有脚本生成的安装包，请先执行 ./build.sh。')
    metadata = json.loads(LATEST.read_text())
    apk = (ROOT / metadata['apk']).resolve()
    if not apk.is_relative_to((ROOT / 'artifacts/releases').resolve()):
        raise RuntimeError('安装包路径不在 releases 目录内。')
    if digest(apk) != metadata['sha256'] or certificate(apk) != metadata['certificateSha256']:
        raise RuntimeError('安装包哈希或签名与打包记录不一致，请重新 ./build.sh。')
    badging = run([sdk_tool('aapt'), 'dump', 'badging', apk], capture_output=True, text=True).stdout
    if not re.search(r"package: name='" + re.escape(PACKAGE) + "'", badging):
        raise RuntimeError('安装包应用 ID 不匹配。')
    adb = shutil.which('adb')
    if not adb:
        raise RuntimeError('找不到 adb，请将 Android platform-tools 加入 PATH。')
    serial = args.device
    if ':' in serial:
        run([adb, 'connect', serial])
    state = run([adb, '-s', serial, 'get-state'], capture_output=True, text=True).stdout.strip()
    if state != 'device':
        raise RuntimeError('车机未连接或未授权。')
    print(f'安装：{apk.name}\n打包时间：{metadata["builtAt"]}', flush=True)
    # No uninstall: preserve application settings and user data.
    run([adb, '-s', serial, 'install', '--no-streaming', '-r', apk])
    package = run([adb, '-s', serial, 'shell', 'dumpsys', 'package', PACKAGE],
                  capture_output=True, text=True).stdout
    for line in package.splitlines():
        if 'versionName=' in line or 'lastUpdateTime=' in line:
            print(line.strip())
    if not args.no_launch:
        run([adb, '-s', serial, 'shell', 'am', 'start', '-n', ACTIVITY])
    print('更新完成，原设置保留。安装和启动成功不代表 CarPlay 实际画面已验证。')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('build', help='编译并归档签名车机包')
    deployment = sub.add_parser('deploy', help='安装最近一次 ./build.sh 的包，保留数据')
    deployment.add_argument('--device', default=os.environ.get('XPLAY_ADB_DEVICE', '192.168.43.1:5050'))
    deployment.add_argument('--no-launch', action='store_true', help='安装后不打开 XPlay')
    args = parser.parse_args()
    try:
        build() if args.command == 'build' else deploy(args)
    except (OSError, RuntimeError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        print(f'失败：{error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
