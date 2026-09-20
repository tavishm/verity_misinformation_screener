#!/usr/bin/env python3
"""Build social work from a snapshot. Never overwrite the WhatsApp build or install a phone."""
from pathlib import Path
import argparse, hashlib, importlib.util, json, shutil, subprocess

ROOT=Path(__file__).resolve().parents[1]
SNAPSHOT=ROOT/'.cache/social-build/android'

def main():
    args=argparse.ArgumentParser();args.add_argument('--android-test',action='store_true');options=args.parse_args()
    spec=importlib.util.spec_from_file_location('standalone_build',ROOT/'mobile/tooling/standalone_build.py')
    tools=importlib.util.module_from_spec(spec);spec.loader.exec_module(tools)
    gradle,env=tools.prepare()
    source=ROOT/'mobile/android'
    SNAPSHOT.mkdir(parents=True,exist_ok=True)
    # Replace source directories only; keep this task's own Gradle build cache.
    for name in ['app/src','src','assets']:
        dst=SNAPSHOT/name
        if dst.exists():shutil.rmtree(dst)
        shutil.copytree(source/name,dst)
    for name in ['build.gradle','settings.gradle','gradle.properties','app/build.gradle']:
        shutil.copy2(source/name,SNAPSHOT/name)
    build=SNAPSHOT/'app/build.gradle'
    build.write_text(build.read_text().replace("file('../../../.cache/android/debug.keystore')", "file('"+str(ROOT/'.cache/android/debug.keystore')+"')"))
    env['GRADLE_USER_HOME']=str(ROOT/'.cache/gradle')
    tasks=[':app:testDebugUnitTest','--tests','org.fairc.forwardcheck.social.*',':app:assembleDebug']
    if options.android_test:tasks.append(':app:assembleDebugAndroidTest')
    subprocess.run([str(gradle),'--console=plain','--no-daemon',*tasks],cwd=SNAPSHOT,env=env,check=True)
    artifact=ROOT/'dist/forward-check-social-debug.apk';artifact.parent.mkdir(exist_ok=True)
    shutil.copy2(SNAPSHOT/'app/build/outputs/apk/debug/app-debug.apk',artifact)
    digest=hashlib.sha256(artifact.read_bytes()).hexdigest()
    manifest={str(p.relative_to(SNAPSHOT)):hashlib.sha256(p.read_bytes()).hexdigest() for top in ['app/src','src','assets'] for p in (SNAPSHOT/top).rglob('*') if p.is_file()}
    build_config={name:hashlib.sha256((SNAPSHOT/name).read_bytes()).hexdigest() for name in ['build.gradle','settings.gradle','gradle.properties','app/build.gradle']}
    (ROOT/'dist/social-build-manifest.json').write_text(json.dumps({'apk_sha256':digest,'files':manifest,'build_config':build_config},indent=2))
    if options.android_test:shutil.copy2(SNAPSHOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',ROOT/'dist/forward-check-social-tests.apk')
    print('Social APK:',artifact,'SHA256:',digest)

if __name__=='__main__':main()
