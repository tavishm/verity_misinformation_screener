"""Fetch a task-owned Android command-line toolchain from official publishers."""
from pathlib import Path
import hashlib,json,tarfile,urllib.request,zipfile,xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2]
BASE=ROOT/'.cache/android'
BASE.mkdir(parents=True,exist_ok=True)
def download(url,path,checksum=None,algorithm='sha256'):
 if not path.exists():
  print('Downloading',path.name,flush=True)
  with urllib.request.urlopen(url,timeout=90) as r,path.open('wb') as f:
   while chunk:=r.read(1024*1024):f.write(chunk)
 if checksum:
  h=hashlib.new(algorithm)
  with path.open('rb') as f:
   while chunk:=f.read(1024*1024):h.update(chunk)
  if h.hexdigest().lower()!=checksum.lower():raise ValueError('Publisher checksum mismatch: '+path.name)
 print(path.name,path.stat().st_size,flush=True)
# Standalone build tools can use the available Java 11 runtime.
xml=urllib.request.urlopen('https://dl.google.com/android/repository/repository2-1.xml',timeout=60).read()
(BASE/'repository.xml').write_bytes(xml)
root=ET.fromstring(xml)
for name in ('platforms;android-35','build-tools;35.0.0','platform-tools'):
 pkg=max((p for p in root if p.tag.endswith('remotePackage') and p.attrib.get('path')==name), key=lambda p: int(p.findtext('revision/major') or 0))
 archives=pkg.find('archives')
 chosen=next(a for a in archives if a.findtext('host-os') in (None,'linux'))
 complete=chosen.find('complete');rel=complete.findtext('url');checksum=complete.find('checksum')
 dst=BASE/rel.split('/')[-1]
 download('https://dl.google.com/android/repository/'+rel,dst,checksum.text,checksum.attrib.get('type','sha1'))
 out=BASE/'unpack'/name.replace(';','-');out.mkdir(parents=True,exist_ok=True)
 with zipfile.ZipFile(dst) as z:
  z.extractall(out)
  for i in z.infolist():
   mode=i.external_attr>>16
   if mode and not i.is_dir():(out/i.filename).chmod(mode & 0o777)
 folder=next(p for p in out.iterdir() if p.is_dir())
 target=BASE/'sdk'/Path(*name.split(';'));target.parent.mkdir(parents=True,exist_ok=True)
 if not target.exists():folder.rename(target)
 print('Ready',target,flush=True)
print('Toolchain ready',BASE,flush=True)
