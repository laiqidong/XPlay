#!/usr/bin/env python3
"""Build locally by default; --run --serial DEVICE explicitly runs read-only P7 diagnostics."""
import argparse
import datetime
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
FIELDS = ('VCU_BMS_SOCDISP', 'VCU_CURRENT_GEARLEV', 'VCU_RAW_CAR_SPEED', 'VCU_ACCPEDAL_SIG', 'VCU_BRKPEDAL_ST')


def command(args, timeout=60, env=None):
    try:
        p = subprocess.run([str(x) for x in args], capture_output=True, text=True, timeout=timeout, env=env)
        return p.returncode, p.stdout + p.stderr
    except subprocess.TimeoutExpired:
        return 124, 'HOST TIMEOUT (no automatic retry)\n'


def build(output):
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:
        props = ROOT / 'local.properties'
        match = re.search(r'^sdk.dir=(.+)$', props.read_text(), re.M) if props.exists() else None
        sdk = match[1] if match else str(Path.home() / 'Library/Android/sdk')
    sdk = Path(sdk)
    javac = shutil.which('javac')
    if Path('/opt/homebrew/opt/openjdk/bin/javac').exists():
        javac = '/opt/homebrew/opt/openjdk/bin/javac'
    platforms = sorted(sdk.glob('platforms/android-*/android.jar'), key=lambda p: tuple(int(n) for n in re.findall(r'\d+', p.parent.name)))
    versions = sorted(sdk.glob('build-tools/*/d8'), key=lambda p: tuple(int(n) for n in re.findall(r'\d+', p.parent.name)))
    if not javac or not platforms or not versions:
        raise RuntimeError('JDK, Android SDK platform or d8 missing')
    output.mkdir(parents=True, exist_ok=True)
    classes = output / 'classes'
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir()
    code, text = command([javac, '--release', '8', '-classpath', platforms[-1], '-d', classes,
                          *sorted((ROOT / 'tools/p7-probe').glob('*.java'))])
    if code:
        raise RuntimeError(text)
    artifact = output / 'p7-probe.jar'
    env = os.environ.copy()
    env['JAVA_HOME'] = str(Path(javac).resolve().parent.parent)
    code, text = command([versions[-1], '--min-api', '28', '--lib', platforms[-1], '--output', artifact,
                          *sorted(classes.rglob('*.class'))], env=env)
    if code:
        raise RuntimeError(text)
    return artifact


def cached_fields(text):
    return [line.split(',bytes:', 1)[0][:600] for line in text.splitlines() if 'lastEvent:Property=' in line and
            any('lastEvent:Property=' + name + '(' in line for name in FIELDS)]


def run(adb, serial, artifact, output):
    base = [adb, '-s', serial]
    code, state = command(base + ['get-state'], timeout=10)
    if code or state.strip() != 'device':
        raise RuntimeError('Selected device is not connected; no upload attempted')
    # A unique fixed-character path; only this temporary artifact is removed in cleanup.
    remote = '/data/local/tmp/xplay-p7-probe-' + datetime.datetime.now().strftime('%Y%m%d%H%M%S') + '.jar'
    report = output / ('report-' + datetime.datetime.now().strftime('%Y%m%d-%H%M%S') + '.txt')
    with report.open('w') as log:
        def record(title, code, text):
            block = '\n=== ' + title + ' (exit=' + str(code) + ') ===\n' + text[:24000] + '\n'
            log.write(block)
            log.flush()
            print(block, flush=True)
        code, text = command(base + ['push', artifact, remote], timeout=30)
        record('upload', code, text)
        if code:
            raise RuntimeError('Upload failed')
        try:
            for prop in ('ro.product.model', 'ro.build.version.sdk', 'ro.product.cpu.abilist'):
                code, text = command(base + ['shell', 'getprop', prop], timeout=10)
                record(prop, code, text)
            working = []
            # Test both runtime architectures before selecting one. Never alter system settings.
            for binary in ('app_process', 'app_process64', 'app_process32'):
                cmd = 'CLASSPATH=' + shlex.quote(remote) + ' timeout -s 9 20 /system/bin/' + binary
                code, text = command(base + ['shell', cmd + ' /system/bin com.laiqidong.xplay.tools.P7Probe surface'], timeout=25)
                record('surface/' + binary, code, text)
                if code == 0 and 'FRAMEWORK available' in text:
                    working.append(binary)
            if working:
                binary = working[0]
                for mode, context in (('async3', 'system'), ('async3', 'package'), ('async2', 'system'),
                                      ('sync', 'system'), ('syncHandler', 'system'), ('sync', 'package'),
                                      ('lifecycle', 'system'), ('lifecycleWait', 'system')):
                    cmd = 'CLASSPATH=' + shlex.quote(remote) + ' timeout -s 9 20 /system/bin/' + binary
                    cmd += ' /system/bin com.laiqidong.xplay.tools.P7Probe ' + mode + ' ' + context
                    code, text = command(base + ['shell', cmd], timeout=25)
                    record(mode + '/' + context, code, text)
            else:
                record('direct API', 2, 'No usable runtime; direct-query cases skipped.\n')
            code, text = command(base + ['shell', 'dumpsys', '-t', '3', 'car_service'], timeout=10)
            selected = cached_fields(text)
            # Never save the full dump: it can contain unrelated vehicle identifiers.
            record('dumpsys comparison', code, '\n'.join(selected) if selected else 'No target cached fields returned.\n')
        finally:
            code, text = command(base + ['shell', 'rm', '-f', remote], timeout=10)
            record('remove temporary probe', code, text)
    return report


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--run', action='store_true', help='Upload and run on the explicit connected device')
    p.add_argument('--serial', help='Exact adb device serial; required with --run')
    p.add_argument('--output', type=Path, default=ROOT / 'build/tools/p7-probe')
    args = p.parse_args()
    if args.run and not args.serial:
        p.error('--run requires --serial; automatic device selection is forbidden')
    artifact = build(args.output.resolve())
    print('Built:', artifact)
    if args.run:
        adb = shutil.which('adb')
        if not adb:
            raise RuntimeError('adb missing')
        print('Report:', run(adb, args.serial, artifact, args.output.resolve()))
    else:
        print('Local build only. No ADB commands were issued.')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError) as error:
        print('ERROR:', error, file=sys.stderr)
        sys.exit(1)
