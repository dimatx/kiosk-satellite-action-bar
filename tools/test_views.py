#!/usr/bin/env python3
"""Measure native views on an adb-connected Android development device, without installing an app."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import uuid
import zipfile

from android_sdk import android_platform

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True, help='Explicit adb device serial; never targets the fleet')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
sdk = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'android-sdk'))))
platform = android_platform(sdk)
tools = sorted([*(sdk / 'build-tools').glob('*/d8'), *(sdk / 'build-tools').glob('*/d8.bat')],
               key=lambda p: tuple(int(v) for v in p.parent.name.split('.') if v.isdigit()))
if not tools:
    raise SystemExit('Android build-tools are required.')
java_home = os.environ.get('JAVA_HOME')
javac = str(Path(java_home) / 'bin' / 'javac') if java_home else 'javac'
adb = ['adb', '-s', args.serial]
remote = '/data/local/tmp/action-bar-view-test-' + uuid.uuid4().hex + '.jar'
with tempfile.TemporaryDirectory(prefix='action-bar-view-test-') as folder:
    folder = Path(folder)
    classes, dex = folder / 'classes', folder / 'dex'
    classes.mkdir()
    dex.mkdir()
    sources = [*sorted((root / 'sdk/src').rglob('*.java')), *sorted((root / 'src').rglob('*.java')),
               root / 'tests/com/shymoose/ksactionbar/NativeViewTest.java']
    subprocess.run([javac, '--release', '8', '-cp', str(platform), '-d', str(classes), *map(str, sources)], check=True)
    subprocess.run([str(tools[-1]), '--min-api', '26', '--lib', str(platform), '--output', str(dex),
                    *map(str, sorted(classes.rglob('*.class')))], check=True)
    jar = folder / 'test.jar'
    with zipfile.ZipFile(jar, 'w') as archive:
        for file in dex.glob('*.dex'):
            archive.write(file, file.name)
    try:
        subprocess.run([*adb, 'push', str(jar), remote], check=True)
        subprocess.run([*adb, 'shell', f'CLASSPATH={remote} app_process /system/bin com.shymoose.ksactionbar.NativeViewTest'],
                       check=True, timeout=120)
    finally:
        subprocess.run([*adb, 'shell', 'rm', '-f', remote], check=True)
