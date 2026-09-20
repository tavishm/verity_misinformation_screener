#!/usr/bin/env python3
"""Exercise the real Android service on clearly marked feed fixtures, never a phone."""
from pathlib import Path
import json, re, subprocess, time, xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[2]
ADB=[str(ROOT/'.cache/android/sdk/platform-tools/adb'),'-s','emulator-5580']
OUT=ROOT/'dist/social-emulator';OUT.mkdir(parents=True,exist_ok=True)

def adb(*args,raw=False):
    r=subprocess.run([*ADB,*args],check=True,capture_output=True,timeout=45)
    return r.stdout if raw else r.stdout.decode(errors='replace').strip()
def dump():
    adb('shell','uiautomator','dump','/data/local/tmp/social-window.xml')
    return ET.fromstring(adb('exec-out','cat','/data/local/tmp/social-window.xml'))
def texts():
    values=[n.attrib.get('text','') for n in dump().iter('node') if n.attrib.get('text')]
    # The stock hierarchy dumper only returns the application window. Include
    # accessibility overlay titles, which contain the generic verdict, no post text.
    values += [line for line in adb('shell','dumpsys','window','windows').splitlines() if 'Forward Check · ' in line and 'Window #' in line]
    return values
def tap(label):
    for node in dump().iter('node'):
        if node.attrib.get('text','').casefold()==label.casefold():
            a=list(map(int,re.findall(r'\d+',node.attrib['bounds'])));adb('shell','input','tap',str((a[0]+a[2])//2),str((a[1]+a[3])//2));return
    raise AssertionError('Cannot find '+label)
def screenshot(name): (OUT/(name+'.png')).write_bytes(adb('exec-out','screencap','-p',raw=True))
def wait_text(part,timeout=18):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        found=texts()
        if any(part in t for t in found):return found
        time.sleep(.4)
    raise AssertionError('Missing '+part+'; visible '+str(found))

def main():
    assert adb('shell','getprop','ro.hardware')=='ranchu','Emulator required'
    assert adb('shell','getprop','sys.boot_completed')=='1','Wait for boot first'
    for file in [ROOT/'dist/forward-check-social-debug.apk',ROOT/'dist/forward-check-social-tests.apk',*(ROOT/'.cache/social-fixture/app/build/outputs/apk').rglob('*.apk')]:print('Install',file.name,adb('install','-r',str(file)),flush=True)
    adb('shell','settings','delete','secure','enabled_accessibility_services')
    adb('shell','run-as','org.fairc.forwardcheck','rm','-f','shared_prefs/social_screening.xml')
    adb('shell','am','force-stop','org.fairc.forwardcheck')
    adb('shell','am','start','-n','org.fairc.forwardcheck/.MainActivity')
    secret=Path.home()/'.config/forward-check/openrouter.key'
    adb('push',str(secret),'/data/local/tmp/social-test-key')
    try:
        adb('shell','run-as','org.fairc.forwardcheck','mkdir','-p','files')
        adb('shell','run-as','org.fairc.forwardcheck','cp','/data/local/tmp/social-test-key','files/social-test-key')
    finally:adb('shell','rm','-f','/data/local/tmp/social-test-key')
    print(adb('shell','am','instrument','-w','-e','class','org.fairc.forwardcheck.social.SocialEmulatorSetupTest','org.fairc.forwardcheck.test/androidx.test.runner.AndroidJUnitRunner'),flush=True)
    adb('shell','am','start','-n','org.fairc.forwardcheck/.MainActivity')
    tap('Social media');wait_text('Screen posts');screenshot('settings-off')
    for app in ['X','Reddit','Google News']:tap(app)
    tap('Screen posts');wait_text('Check posts online?');screenshot('consent');tap('Turn on')
    # OS service grant is a test harness action on a disposable emulator only.
    adb('shell','settings','put','secure','enabled_accessibility_services','org.fairc.forwardcheck/org.fairc.forwardcheck.social.SocialScreeningService')
    adb('shell','settings','put','secure','accessibility_enabled','1');time.sleep(1)
    checks=[]
    for name,pkg in [('x','com.twitter.android'),('reddit','com.reddit.frontpage'),('news','com.google.android.apps.magazines')]:
        start=time.monotonic();adb('shell','am','start','-n',pkg+'/org.fairc.socialfixture.Feed')
        wait_text('AI · Looks false');screenshot(name+'-false');checks.append({'test':name+' fixture false badge','pass':True,'observed_by_seconds':round(time.monotonic()-start,2)})
        if name=='x':
            tap('Next test post');wait_text('AI · Looks true');screenshot('x-true');checks.append({'test':'changed post true badge','pass':True})
            tap('Next test post');time.sleep(4);assert not any('Looks' in t for t in texts());checks.append({'test':'personal post no badge','pass':True})
        adb('shell','input','keyevent','3');time.sleep(.4);assert not any('Looks' in t for t in texts());checks.append({'test':name+' leaving feed clears badge','pass':True})
    adb('shell','am','start','-n','org.fairc.forwardcheck/.MainActivity');tap('Social media');tap('Screen posts')
    adb('shell','am','start','-n','com.google.android.apps.magazines/org.fairc.socialfixture.Feed');time.sleep(3)
    assert not any('Looks' in t for t in texts());checks.append({'test':'off removes screening','pass':True})
    (OUT/'checks.json').write_text(json.dumps({'scope':'Synthetic native feed fixtures, NOT real-app layout validation','checks':checks},indent=2))
    (OUT/'costs.jsonl').write_text(adb('shell','run-as','org.fairc.forwardcheck','cat','files/social-costs.jsonl'))
    print(json.dumps(checks,indent=2),flush=True)

if __name__=='__main__':main()
