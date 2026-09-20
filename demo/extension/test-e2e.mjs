// Actual unpacked MV3 extension + actual loopback API + actual local GPU inference.
// Page requests are locally fulfilled fixtures, never authenticated social feeds.
import fs from 'node:fs';
import {createHash} from 'node:crypto';
import path from 'node:path';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
import {launch, delay, until} from './tests/chrome.mjs';
import {fixtures} from './tests/fixtures.mjs';
const directory = path.dirname(fileURLToPath(import.meta.url));
const browserPath = process.argv[2] || path.resolve('.cache/chrome-for-testing/chrome-linux64/chrome');
const extensionPath = process.argv[3] ? path.resolve(process.argv[3]) : directory;
const output = path.resolve('demo/data/extension-tests'); fs.mkdirSync(output, {recursive: true});
const browser = await launch(browserPath, extensionPath);
const errors = [], contexts = new Map();
browser.on(event => {
  if (event.method === 'Runtime.exceptionThrown') errors.push(event.params.exceptionDetails.exception?.description || event.params.exceptionDetails.text);
  if (event.method === 'Runtime.executionContextCreated' && event.params.context.origin.startsWith('chrome-extension://')) contexts.set(event.sessionId, event.params.context.id);
});
async function panels(page) {
  const root = (await page.call('DOM.getDocument', {depth:-1,pierce:true})).root;
  const result = [];
  const visit = node => {
    if (node.attributes?.includes('data-evidence-check')) {
      const shadow = node.shadowRoots?.[0];
      if (shadow) result.push({host:node,shadow});
    }
    for (const child of [...(node.children || []), ...(node.shadowRoots || [])]) visit(child);
  };
  visit(root); return result;
}
async function panel(page, index, functionDeclaration) {
  const node = (await panels(page))[index];
  assert(node, `Missing panel ${index}`);
  const {object} = await page.call('DOM.resolveNode', {backendNodeId:node.shadow.backendNodeId});
  const value = await page.call('Runtime.callFunctionOn', {objectId:object.objectId,functionDeclaration,returnByValue:true,awaitPromise:true});
  await page.call('Runtime.releaseObject', {objectId:object.objectId});
  if (value.exceptionDetails) throw new Error(value.exceptionDetails.text);
  return value.result.value;
}
const status = (page, index = 0) => panel(page,index,'function(){return this.querySelector(".status").textContent;}');
async function click(page, index = 0) {
  const point = await panel(page,index,'function(){const r=this.querySelector("button").getBoundingClientRect();return{x:r.x+r.width/2,y:r.y+r.height/2};}');
  await page.call('Input.dispatchMouseEvent',{type:'mousePressed',button:'left',clickCount:1,...point});
  await page.call('Input.dispatchMouseEvent',{type:'mouseReleased',button:'left',clickCount:1,...point});
}
async function screenshot(page, name) {
  const shot = await page.call('Page.captureScreenshot',{format:'png'});
  fs.writeFileSync(path.join(output, name + '.png'),Buffer.from(shot.data,'base64'));
}
async function fixture(url) {
  const page = await browser.page();
  browser.on(event => {
    if (event.sessionId !== page.sessionId || event.method !== 'Fetch.requestPaused') return;
    const request = event.params;
    const operation = fixtures[request.request.url] ? page.call('Fetch.fulfillRequest', {requestId:request.requestId,responseCode:200,responseHeaders:[{name:'Content-Type',value:'text/html; charset=utf-8'}],body:Buffer.from(fixtures[request.request.url]).toString('base64')}) : page.call('Fetch.failRequest', {requestId:request.requestId,errorReason:'BlockedByClient'});
    operation.catch(error => errors.push(error.message));
  });
  await page.call('Fetch.enable',{patterns:[{urlPattern:'*'}]});
  await page.call('Emulation.setDeviceMetricsOverride',{width:1100,height:1000,deviceScaleFactor:1,mobile:false});
  await page.call('Page.navigate',{url}); await page.call('Page.bringToFront');
  await until(async () => (await panels(page)).length > 0,'actual content-script panel');
  return page;
}
try {
  const id = createHash('sha256').update(extensionPath).digest('hex').slice(0,32).replace(/[0-9a-f]/g,x=>String.fromCharCode(97+parseInt(x,16)));
  await until(async () => (await browser.send('Target.getTargets')).targetInfos.find(target => target.type === 'service_worker' && target.url === `chrome-extension://${id}/background.js`), 'MV3 service worker');
  const setup = await browser.page(`chrome-extension://${id}/options.html`);
  await until(() => setup.evaluate('document.querySelector("#status")?.textContent === "Ready to connect"'),'setup page initialized');
  await setup.evaluate('document.querySelector("#connect").click()');
  await until(async () => (await setup.evaluate('document.querySelector("#status").textContent')).includes('ready to check'),'actual extension pairing');
  console.log('PASS real extension pairing → loopback API → model health');
  const popup = await browser.page(`chrome-extension://${id}/popup.html`);
  await until(async () => (await popup.evaluate('document.querySelector("#status")?.textContent')) === 'Ready to check','popup status');
  await popup.call('Emulation.setDeviceMetricsOverride',{width:390,height:640,deviceScaleFactor:1,mobile:false});
  await screenshot(popup,'popup');
  const x = await fixture('https://x.com/home');
  assert.equal((await panels(x)).length,2,'Only visible posts receive panels');
  assert.equal(await status(x),'','Auto off by default');
  await panel(x,0,'function(){this.querySelector("button").click();}'); await delay(300);
  assert.equal(await status(x),'','Synthetic page click cannot start checking');
  await click(x);
  // The source pipeline now runs on demand. A fixture must prove the actual
  // MV3 submission/polling path without treating an eventual verdict, timing,
  // or source availability as a pinned product outcome.
  await until(async () => /Checking evidence|Still checking|Review pending|Probably true|Likely false|Unsure|Mixed claims|Needs further review/.test(await status(x)), 'X review accepted', 12000);
  const firstStatus = await status(x);
  assert(!/^(Post changed|No readable claim|Checker offline)$/.test(firstStatus), 'Trusted fixture submission reaches the checker');
  await x.evaluate('document.querySelector("#post-one [data-testid=tweetText]").textContent = "Changed post with a different date in 2025."');
  await until(async () => (await status(x)).includes('Post changed'),'edited-post invalidation');
  assert.equal(await panel(x,0,'function(){return this.querySelector("blockquote")?.textContent || "";}'),'');
  const settings = await popup.evaluate('chrome.runtime.sendMessage({type:"GET_SETTINGS"})');
  assert.equal(settings.data.autoCheckEnabled,false,'Automatic checks remain opt-in');
  assert.equal(settings.data.feedCaptureEnabled,false,'Synthetic fixtures never enter live-feed exposure tracking');
  assert.equal((await panels(x)).length,2,'Off-screen post remains untouched');
  const results = [{layout:'X',review_state:firstStatus}];
  for (const [name,url] of [['reddit-modern','https://www.reddit.com/r/india/'],['reddit-legacy-dark','https://new.reddit.com/r/news/'],['reddit-old','https://old.reddit.com/r/news/']]) {
    const page = await fixture(url);
    await until(async () => (await panels(page)).length > 0,`${name} panel`);
    assert(!(await panel(page,0,'function(){return this.textContent;}')).includes('HIDDEN TEXT MUST NEVER BE SUBMITTED'));
    results.push({layout:name,panel:true});
    console.log(`PASS ${name} public rendered-text extraction`);
  }
  await x.call('Page.bringToFront'); await x.evaluate('history.pushState({},"","/messages")');
  await until(async () => (await panels(x)).length === 0,'private-route cleanup');
  assert.deepEqual(errors,[],'No browser runtime exceptions');
  fs.writeFileSync(path.join(output,'results.json'),JSON.stringify({browser:browser.version,extension:'0.3.0',actualExtension:true,actualLocalInference:true,reviewOutcomePinned:false,livePlatform:false,feedCaptureEnabled:false,results,errors},null,2));
  console.log('PASS packaged MV3 pairing, controlled-fixture submission, conservative pending state, public extraction, edit invalidation, opt-in capture, and private-route cleanup.');
} finally {await browser.close();}
