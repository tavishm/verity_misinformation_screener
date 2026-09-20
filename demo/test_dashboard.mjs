// Browser smoke for the real local dashboard and API. It submits one explicitly
// development-scoped replay and never creates a live-feed observation.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {launch, until} from './extension/tests/chrome.mjs';

const browserPath = process.argv[2] || '/usr/bin/google-chrome';
const output = path.resolve('demo/data/dashboard-tests');
fs.mkdirSync(output, {recursive:true});
const browser = await launch(browserPath, path.resolve('demo/extension'));
const errors = [];
browser.on(event => {
  if (event.method === 'Runtime.exceptionThrown')
    errors.push(event.params.exceptionDetails.exception?.description || event.params.exceptionDetails.text);
});

const result = {browser:browser.version, url:'http://127.0.0.1:8870/dashboard', checks:[]};
try {
  const page = await browser.page(result.url);
  await page.call('Emulation.setDeviceMetricsOverride', {width:1440,height:1100,deviceScaleFactor:1,mobile:false});
  await page.call('Page.navigate', {url:result.url});
  await until(() => page.evaluate('document.querySelector("#system-label")?.textContent === "Local model ready"'), 'dashboard and local model ready');

  const initial = await page.evaluate(`({
    active:document.querySelector('.view.active')?.id,
    liveObserved:document.querySelector('#m-observed')?.textContent,
    examples:document.querySelectorAll('#examples button').length,
    body:document.body.innerText
  })`);
  assert.equal(initial.active, 'overview');
  assert.equal(initial.liveObserved, '0');
  assert(initial.examples >= 4);
  assert(!initial.body.includes('[object Object]'));
  const initialDevelopment = Number(await page.evaluate(`(() => {document.querySelector('[data-scope="development"]').click();return document.querySelector('#m-observed').textContent})()`));
  await page.evaluate(`document.querySelector('[data-scope="live_feed"]').click()`);
  result.checks.push('live-feed scope starts at zero and renders independently');

  await page.evaluate(`document.querySelector('[data-tab="costs"]').click()`);
  await until(() => page.evaluate('document.querySelector("#costs").classList.contains("active")'), 'cost view');
  const defaults = await page.evaluate(`({startup:document.querySelector('#startup-research').textContent,daily:document.querySelector('#daily-research').textContent,x:document.querySelector('#startup-x').textContent})`);
  assert.equal(defaults.startup, '$1,406');
  assert.equal(defaults.daily, '$141');
  assert.equal(defaults.x, '$0.00');
  const custom = await page.evaluate(`(() => {
    const set=(id,value)=>{const node=document.querySelector(id);node.value=value;node.dispatchEvent(new Event('input',{bubbles:true}))};
    set('#cost-initial',1);set('#cost-daily',1);set('#cost-search-share',100);set('#cost-model-share',100);set('#cost-x-initial',100);set('#cost-x-daily',100);
    return {startup:document.querySelector('#startup-research').textContent,daily:document.querySelector('#daily-research').textContent,x:document.querySelector('#startup-x').textContent,total:document.querySelector('#startup-total').textContent};
  })()`);
  assert.deepEqual(custom, {startup:'$0.17',daily:'$0.17',x:'$0.50',total:'$0.67'});
  result.costs = {defaults, custom};
  result.checks.push('cost arithmetic matches six searches, Sonnet tokens, reserve, and separate X reads');

  await page.evaluate(`document.querySelector('[data-tab="overview"]').click()`);
  await page.evaluate(`document.querySelectorAll('#examples button')[3].click()`);
  const chosen = await page.evaluate(`document.querySelector('#post-text').value`);
  assert.equal(chosen, 'A presidential notice continued for one year the national emergency declared after the September 11 attacks.');
  await page.evaluate(`document.querySelector('#check-button').click()`);
  await until(() => page.evaluate(`!document.querySelector('#result-card').getAttribute('aria-busy') || document.querySelector('#result-card').getAttribute('aria-busy') === 'false'`), 'development replay result', 180000);
  const assessment = await page.evaluate(`({label:document.querySelector('#result-card .verdict')?.textContent,route:document.querySelector('#result-card .route')?.textContent,text:document.querySelector('#result-card').innerText})`);
  assert(assessment.label);
  assert(assessment.route);
  assert(!assessment.text.includes('[object Object]'));
  result.assessment = assessment;
  result.checks.push('source-derived dev-politics-004 completed through the real API');

  await page.evaluate(`document.querySelector('[data-scope="development"]').click()`);
  await until(async () => Number(await page.evaluate(`document.querySelector('#m-observed').textContent`)) > initialDevelopment, 'development metric refresh');
  const development = await page.evaluate(`({observed:document.querySelector('#m-observed').textContent,note:document.querySelector('#scope-note').textContent})`);
  assert(Number(development.observed) > initialDevelopment);
  assert(development.note.includes('do not describe live-feed coverage'));
  await page.evaluate(`document.querySelector('[data-scope="live_feed"]').click()`);
  const liveAfter = await page.evaluate(`document.querySelector('#m-observed').textContent`);
  assert.equal(liveAfter, '0');
  result.scopes = {development,liveObserved:liveAfter};
  result.checks.push('development observation increments while live-feed denominator remains zero');

  await page.evaluate(`document.querySelector('[data-tab="activity"]').click()`);
  await until(() => page.evaluate('document.querySelector("#recent-list .recent-item") !== null'), 'recent development activity');
  const recent = await page.evaluate(`document.querySelector('#recent-list').innerText`);
  assert(!recent.includes('[object Object]'));
  result.checks.push('object-shaped reuse route renders as readable text');

  await page.evaluate(`document.querySelector('[data-tab="overview"]').click();document.querySelector('[data-scope="live_feed"]').click();scrollTo(0,0);document.querySelector('.topbar').style.position='relative'`);
  const metrics = await page.call('Page.getLayoutMetrics');
  const width = Math.ceil(metrics.cssContentSize.width), height = Math.ceil(metrics.cssContentSize.height);
  const shot = await page.call('Page.captureScreenshot', {format:'png',captureBeyondViewport:true,clip:{x:0,y:0,width,height,scale:1}});
  fs.writeFileSync(path.join(output,'dashboard.png'), Buffer.from(shot.data,'base64'));
  assert.deepEqual(errors, []);
  result.errors = errors;
  result.screenshot = 'demo/data/dashboard-tests/dashboard.png';
  result.status = 'PASS';
  fs.writeFileSync(path.join(output,'report.json'), JSON.stringify(result,null,2));
  console.log('PASS', result.checks.join('; '));
} finally {
  await browser.close();
}
