import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {spawn} from 'node:child_process';
export const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
export async function until(operation, description, milliseconds = 30000) {
  const end = Date.now() + milliseconds;
  let last;
  while (Date.now() < end) {try {const value = await operation(); if (value) return value;} catch (error) {last = error;} await delay(150);}
  throw new Error(`Timed out: ${description}${last ? ': ' + last.message : ''}`);
}
export async function stopBrowser(chrome, socket, profile) {
  const stopped = new Promise(resolve => {
    if (chrome.exitCode !== null || chrome.signalCode !== null) resolve();
    else chrome.once('exit', resolve);
  });
  if (socket?.readyState === 1) socket.send(JSON.stringify({id: 0, method: 'Browser.close'}));
  await Promise.race([stopped, delay(1500)]);
  // Each test starts its own process group and disposable profile. Stop only
  // that group, including renderer children, before removing its profile.
  try {process.kill(-chrome.pid, 'SIGTERM');} catch (error) {if (error.code !== 'ESRCH') throw error;}
  await Promise.race([stopped, delay(500)]);
  try {process.kill(-chrome.pid, 'SIGKILL');} catch (error) {if (error.code !== 'ESRCH') throw error;}
  socket?.close();
  await delay(150);
  fs.rmSync(profile, {recursive: true, force: true, maxRetries: 5, retryDelay: 100});
}
export async function launch(executable, extension) {
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'evidence-check-e2e-'));
  const chrome = spawn(executable, ['--headless=new','--no-sandbox','--disable-gpu','--disable-background-networking',
    '--no-first-run','--no-default-browser-check','--remote-debugging-port=0',`--user-data-dir=${profile}`,
    `--disable-extensions-except=${extension}`,`--load-extension=${extension}`,'about:blank'], {detached: true, stdio: ['ignore','ignore','pipe']});
  let stderr = ''; chrome.stderr.on('data', value => {stderr = (stderr + value).slice(-4000);});
  let socket;
  const close = () => stopBrowser(chrome, socket, profile);
  try {
    const port = await until(() => {
      if (chrome.exitCode !== null) throw new Error(stderr);
      const file = path.join(profile, 'DevToolsActivePort');
      return fs.existsSync(file) && fs.readFileSync(file,'utf8').split('\n')[0];
    }, 'Chrome startup');
    const info = await (await fetch(`http://127.0.0.1:${port}/json/version`)).json();
    socket = new WebSocket(info.webSocketDebuggerUrl);
    await new Promise(resolve => socket.addEventListener('open', resolve, {once: true}));
    let sequence = 0;
    const pending = new Map(), handlers = [];
    socket.addEventListener('message', event => {
      const data = JSON.parse(event.data);
      if (data.id) {const request = pending.get(data.id); if (request) {pending.delete(data.id); clearTimeout(request.timeout); data.error ? request.reject(new Error(data.error.message)) : request.resolve(data.result);}}
      else for (const handler of handlers) handler(data);
    });
    const send = (method, params = {}, sessionId) => new Promise((resolve, reject) => {
      const id = ++sequence, timeout = setTimeout(() => {pending.delete(id); reject(new Error(`CDP timeout: ${method}`));}, 30000);
      pending.set(id, {resolve, reject, timeout}); socket.send(JSON.stringify({id,method,params,...(sessionId ? {sessionId} : {})}));
    });
    async function page(url = 'about:blank') {
      const {targetId} = await send('Target.createTarget', {url: 'about:blank'});
      const {sessionId} = await send('Target.attachToTarget', {targetId, flatten: true});
      const call = (method, params) => send(method, params, sessionId);
      await call('Page.enable'); await call('Runtime.enable'); await call('DOM.enable');
      const evaluate = async expression => {
        const value = await call('Runtime.evaluate', {expression,returnByValue:true,awaitPromise:true});
        if (value.exceptionDetails) throw new Error(value.exceptionDetails.exception?.description || value.exceptionDetails.text);
        return value.result.value;
      };
      if (url !== 'about:blank') await call('Page.navigate', {url});
      return {targetId, sessionId, call, evaluate};
    }
    return {send, page, close, on: handler => handlers.push(handler), version: info.Browser};
  } catch (error) {await close(); throw error;}
}
