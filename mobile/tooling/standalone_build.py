#!/usr/bin/env python3
"""Task-local, checksum-verified Android toolchain; no global Java changes."""
from pathlib import Path
import argparse
import hashlib
import os
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
CACHE = ROOT / '.cache/android/toolchains'
JDK_URL = 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz'
JDK_SHA = '3808d1d15e3ec6bd5b84057fb5d84c33d8a1536a258146bcea2e603fc726e08e'
GRADLE_URL = 'https://services.gradle.org/distributions/gradle-8.9-bin.zip'
GRADLE_SHA = 'd725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab'


def download(url, destination, expected):
    if destination.exists() and hashlib.sha256(destination.read_bytes()).hexdigest() == expected:
        return
    print('Downloading ' + destination.name, flush=True)
    partial = destination.with_suffix(destination.suffix + '.part')
    with urllib.request.urlopen(url, timeout=60) as response, partial.open('wb') as output:
        shutil.copyfileobj(response, output)
    if hashlib.sha256(partial.read_bytes()).hexdigest() != expected:
        raise RuntimeError('Downloaded archive checksum mismatch: ' + destination.name)
    partial.replace(destination)


def prepare():
    CACHE.mkdir(parents=True, exist_ok=True)
    jdks = list(CACHE.glob('jdk-17*/bin/java'))
    if not jdks:
        archive = CACHE / 'jdk17.tar.gz'
        download(JDK_URL, archive, JDK_SHA)
        with tarfile.open(archive) as source:
            for member in source.getmembers():
                if member.name.startswith('/') or '..' in Path(member.name).parts:
                    raise RuntimeError('Unsafe archive member')
            source.extractall(CACHE)
        jdks = list(CACHE.glob('jdk-17*/bin/java'))
    gradle = CACHE / 'gradle-8.9/bin/gradle'
    if not gradle.exists():
        archive = CACHE / 'gradle-8.9.zip'
        download(GRADLE_URL, archive, GRADLE_SHA)
        with zipfile.ZipFile(archive) as source:
            source.extractall(CACHE)
        gradle.chmod(0o755)
    if not jdks:
        raise RuntimeError('JDK archive did not contain the expected Java installation')
    sdk = Path(os.environ.get('ANDROID_HOME', ROOT / '.cache/android/sdk'))
    if not (sdk / 'platforms/android-35/android.jar').is_file() or not (sdk / 'build-tools/35.0.0').is_dir():
        raise RuntimeError('Android SDK Platform 35 and Build Tools 35.0.0 are required; set ANDROID_HOME to that SDK.')
    env = os.environ.copy()
    env.update(JAVA_HOME=str(jdks[0].parents[1]), ANDROID_HOME=str(sdk),
               GRADLE_USER_HOME=str(ROOT / '.cache/gradle'))
    return gradle, env


def build(tasks=None):
    gradle, env = prepare()
    subprocess.run([str(gradle), '--console=plain', '--no-daemon', *(tasks or [':app:assembleDebug'])],
                   cwd=ROOT / 'mobile/android', env=env, check=True)
    source = ROOT / 'mobile/android/app/build/outputs/apk/debug/app-debug.apk'
    if source.exists() and not tasks:
        destination = ROOT / 'dist/forward-check-debug.apk'
        shutil.copy2(source, destination)
        print('APK:', destination, 'bytes:', destination.stat().st_size)
        print('SHA256:', hashlib.sha256(destination.read_bytes()).hexdigest())
        return destination


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('tasks', nargs='*')
    options = parser.parse_args()
    build(options.tasks or None)
