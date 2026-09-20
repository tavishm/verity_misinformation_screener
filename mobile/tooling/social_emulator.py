#!/usr/bin/env python3
"""Isolated social-test Android 15 VM. Downloads only Google SDK artifacts.

Uses the existing local Linux container image and KVM device; no host permissions
are changed. Nothing connects to, installs on, or controls the shared phone.
"""
from pathlib import Path
import hashlib, os, shutil, subprocess, tempfile, urllib.request, zipfile

ROOT=Path(__file__).resolve().parents[2]
VM=ROOT/'.cache/social-vm'

def unpack(url,sha,destination):
    marker=destination/'.ready'
    if marker.exists():return
    destination.mkdir(parents=True,exist_ok=True)
    # Keep download archives in RAM temporarily; the host's disk is nearly full.
    fd,name=tempfile.mkstemp(prefix='forward-check-sdk-',suffix='.zip',dir='/dev/shm');os.close(fd)
    try:
        digest=hashlib.sha1()
        with urllib.request.urlopen(url,timeout=30) as source,open(name,'wb') as output:
            while block:=source.read(1024*1024):digest.update(block);output.write(block)
        if digest.hexdigest()!=sha:raise RuntimeError('SDK checksum mismatch')
        with zipfile.ZipFile(name) as archive:
            for item in archive.infolist():
                path=destination/item.filename
                if item.filename.startswith('/') or '..' in Path(item.filename).parts:raise ValueError('Unsafe SDK entry')
                if item.is_dir():path.mkdir(parents=True,exist_ok=True);continue
                path.parent.mkdir(parents=True,exist_ok=True)
                with archive.open(item) as source,path.open('wb') as output:
                    while block:=source.read(1024*1024):
                        if path.suffix=='.img' and block.count(0)==len(block):output.seek(len(block),1)
                        else:output.write(block)
                    output.truncate(item.file_size)
                if item.external_attr>>16:path.chmod((item.external_attr>>16)&0o777)
        marker.touch()
    finally:Path(name).unlink(missing_ok=True)

def main():
    VM.mkdir(parents=True,exist_ok=True)
    unpack('https://dl.google.com/android/repository/emulator-linux_x64-15917651.zip','1b1f78891abf8ec268264356e1365c25519e8379',VM/'sdk')
    print('Emulator ready',flush=True)
    unpack('https://dl.google.com/android/repository/sys-img/android/x86_64-35_r02.zip','2d857d170c0d1b827149565da34b3383e5306f7f',VM/'sdk/system-images/android-35/default')
    print('Android 15 system ready',flush=True)
    avds=VM/'avd';avd=avds/'SocialTest.avd';avd.mkdir(parents=True,exist_ok=True)
    (avds/'SocialTest.ini').write_text('avd.ini.encoding=UTF-8\npath=/avd/SocialTest.avd\ntarget=android-35\n')
    (avd/'config.ini').write_text('''AvdId=SocialTest
PlayStore.enabled=false
abi.type=x86_64
avd.ini.encoding=UTF-8
hw.cpu.arch=x86_64
hw.cpu.ncore=2
hw.ramSize=1536
hw.lcd.width=720
hw.lcd.height=1280
hw.lcd.density=240
hw.keyboard=yes
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
disk.dataPartition.size=1024M
image.sysdir.1=system-images/android-35/default/x86_64/
tag.id=default
tag.display=Default
skin.dynamic=yes
skin.name=720x1280
fastboot.forceColdBoot=yes
''')
    # This task uses a dedicated port and explicitly addresses emulator-5580.
    (VM/'start.sh').write_text('''#!/bin/bash
set -e
mkdir -p /avd/SocialTest.avd
cp /social/avd/SocialTest.ini /avd/SocialTest.ini
cp /social/avd/SocialTest.avd/config.ini /avd/SocialTest.avd/config.ini
exec /social/sdk/emulator/emulator -avd SocialTest -port 5580 -no-window -no-audio -no-snapshot -no-boot-anim -no-metrics -gpu swiftshader_indirect -memory 1536 -cores 2
''')
    command=['docker','run','--rm','--name','forward-check-social-vm','--device','/dev/kvm','--network','host',
             '--user',str(os.getuid())+':'+str(os.getgid()),'--group-add',str(os.stat('/dev/kvm').st_gid),
             '--tmpfs','/avd:rw,uid='+str(os.getuid())+',gid='+str(os.getgid())+',size=8g',
             '-v',str(VM)+':/social','-v',str(ROOT/'.cache/android/sdk/platform-tools')+':/social/sdk/platform-tools:ro',
             '-e','ANDROID_SDK_ROOT=/social/sdk','-e','ANDROID_AVD_HOME=/avd','-e','ANDROID_USER_HOME=/social/user',
             '--entrypoint','/bin/bash','humble_ws:latest','/social/start.sh']
    with (VM/'emulator.log').open('w') as log:
        process=subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
    print('VM process:',process.pid,'log:',VM/'emulator.log',flush=True)

if __name__=='__main__':main()
