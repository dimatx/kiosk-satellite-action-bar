#!/usr/bin/env python3
"""Run the action bar's JVM tests and the build tooling tests without a device."""
import os
import sys
from pathlib import Path
import subprocess
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
from android_sdk import android_platform

root = Path(__file__).resolve().parents[1]
java_home = os.environ.get('JAVA_HOME')
def tool(name):
    return str(Path(java_home) / 'bin' / name) if java_home else name
# The views use Android classes. The tests never draw one, so the platform's
# stub classes are enough to compile against.
sdk_root = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'android-sdk'))))
try:
    platform = str(android_platform(sdk_root))
except ValueError as error:
    raise SystemExit(str(error)) from error
sources = [
    *sorted((root / 'sdk/src').rglob('*.java')),
    *sorted((root / 'src').rglob('*.java')),
    *sorted((root / 'tests').rglob('*.java')),
]
with tempfile.TemporaryDirectory(prefix='kiosk-plugin-test-') as directory:
    subprocess.run([tool('javac'), '--release', '8', '-cp', platform, '-d', directory, *map(str, sources)], check=True)
    subprocess.run([tool('java'), '-ea', '-cp', os.pathsep.join([directory, platform]), 'com.shymoose.ksactionbar.ActionTest'], check=True)
    subprocess.run([tool('java'), '-ea', '-cp', os.pathsep.join([directory, platform]), 'com.shymoose.ksactionbar.ControllerTest'], check=True)

for script in ('test_android_sdk.py', 'test_plugin_manifest.py', 'test_plugin_assets.py', 'test_manifest_schema.py'):
    subprocess.run([sys.executable, str(root / 'tools' / script)], check=True)
