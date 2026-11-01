// Real-browser interaction checks using actual companion assets; no npm packages.
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const {spawn} = require('node:child_process');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
const staticRoot = path.join(root, 'src/main/resources/static');
const continuityBootstrap = fs.readFileSync(path.join(root, 'src/main/resources/templates/fragments.html'), 'utf8')
    .match(/<script data-companion-continuity>[\s\S]*?<\/script>/)[0];
const browser = process.env.STARTUP_TEST_BROWSER || [
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
].find(fs.existsSync);
assert(browser, 'Set STARTUP_TEST_BROWSER to Chromium or Edge');
const profile = fs.mkdtempSync(path.join(root, '.tmp/companion-browser-'));
const requests = [], requestUrls = [];
const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://localhost'); requests.push(url.pathname); requestUrls.push(req.url);
    const asset = url.pathname.replace(/^\/archive/, '');
    if (/^\/(js\/companion\.js|css\/(app|companion)\.css|companion\/lumine-(idle|start|stop)(-poster)?\.png|startup\/lumine-sprint(-poster)?\.png)$/.test(asset)) {
        const deliver = () => {
            res.writeHead(200, {'Content-Type':asset.endsWith('.js')?'application/javascript':asset.endsWith('.css')?'text/css':'image/png'});
            res.end(fs.readFileSync(path.join(staticRoot, asset)));
        };
        if (req.headers.referer?.includes('continuity=slow') && (asset === '/js/companion.js' || asset === '/css/companion.css' || asset.endsWith('.png'))) setTimeout(deliver, 1200);
        else deliver();
        return;
    }
    res.writeHead(200, {'Content-Type':'text/html; charset=utf-8'});
    res.end(`<!doctype html><html lang="${url.searchParams.get('lang') === 'de' ? 'de' : 'en'}" data-theme="dark">
<meta name="viewport" content="width=device-width, initial-scale=1"><link rel="stylesheet" href="/archive/css/app.css">
<body><header class="site-header">${continuityBootstrap}<a class="brand" href="/archive/">✦ Genshin Archive</a>
<button type="button" data-companion-toggle hidden>Lumine</button><script defer src="/archive/js/companion.js"></script></header>
<main style="padding:50px;min-height:1200px"><p class="eyebrow">Today in Teyvat</p>
<h1>Your next adventure</h1><p>The archive remains usable while Lumine keeps you company.</p>
<a href="/archive/characters" id="navigate">Characters</a></main>
</body></html>`);
});
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(read, message, limit = 15000) {
    const deadline = Date.now() + limit;
    while (Date.now() < deadline) { const value = await read(); if (value) return value; await sleep(50); }
    throw Error(message);
}
(async () => {
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const base = 'http://127.0.0.1:' + server.address().port;
    const child = spawn(browser, ['--headless=new','--disable-gpu','--no-first-run','--no-default-browser-check',
        '--disable-background-networking','--remote-debugging-port=0','--user-data-dir='+profile,'about:blank'], {stdio:'ignore',windowsHide:true});
    let socket;
    try {
        const portFile = path.join(profile,'DevToolsActivePort');
        await until(() => fs.existsSync(portFile), 'Browser did not start');
        const [port, endpoint] = fs.readFileSync(portFile,'utf8').trim().split(/\r?\n/);
        socket = new WebSocket('ws://127.0.0.1:'+port+endpoint);
        await new Promise((resolve,reject) => { socket.addEventListener('open',resolve,{once:true}); socket.addEventListener('error',reject,{once:true}); });
        const calls = new Map(); let sequence = 0;
        socket.addEventListener('message', event => {
            const m = JSON.parse(event.data), call = calls.get(m.id);
            if (m.method === 'Runtime.exceptionThrown') console.error('Browser error:', JSON.stringify(m.params));
            if (call) { calls.delete(m.id); clearTimeout(call.timer); m.error ? call.reject(Error(m.error.message)) : call.resolve(m.result); }
        });
        const cdp = (method, params = {}, sessionId) => new Promise((resolve,reject) => {
            const id = ++sequence, timer = setTimeout(() => { calls.delete(id); reject(Error(method+' timed out')); },15000);
            calls.set(id,{resolve,reject,timer}); socket.send(JSON.stringify({id,method,params,sessionId}));
        });
        const {targetId} = await cdp('Target.createTarget',{url:'about:blank'});
        const {sessionId} = await cdp('Target.attachToTarget',{targetId,flatten:true});
        const call = (method,params) => cdp(method,params,sessionId);
        const evaluate = async expression => {
            const result = await call('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});
            if (result.exceptionDetails) throw Error(JSON.stringify(result.exceptionDetails));
            return result.result.value;
        };
        const navigate = async (suffix = '') => {
            await call('Page.navigate',{url:base+'/archive/'+suffix});
            await until(() => evaluate('!!window.archiveCompanion'), 'Companion script did not initialize');
        };
        const ready = () => until(() => evaluate("!!document.querySelector('.companion-sprite.is-ready')"), 'Character did not render');
        await call('Page.enable'); await call('Runtime.enable');
        await call('Emulation.setDeviceMetricsOverride',{width:1120,height:800,deviceScaleFactor:1,mobile:false});
        await navigate(); await ready();
        assert.equal(await evaluate("document.querySelector('.archive-companion').dataset.state"), 'idle');
        const pose1 = await evaluate("getComputedStyle(document.querySelector('.companion-sprite')).backgroundPositionX");
        await sleep(350);
        assert.notEqual(await evaluate("getComputedStyle(document.querySelector('.companion-sprite')).backgroundPositionX"),pose1);
        await evaluate('window.workA=archiveCompanion.begin();window.workB=archiveCompanion.begin();undefined');
        await until(() => evaluate("document.querySelector('.archive-companion').dataset.state==='starting'"), 'Authored start transition missing');
        assert.equal(await evaluate("getComputedStyle(document.querySelector('.companion-sprite')).animationIterationCount"),'1');
        await until(() => evaluate("document.querySelector('.archive-companion').dataset.state==='running'"), 'Busy state missing');
        await evaluate('archiveCompanion.end(workA)'); await sleep(60);
        assert.equal(await evaluate("document.querySelector('.archive-companion').dataset.state"),'running');
        await evaluate('archiveCompanion.end(workB)');
        await until(() => evaluate("document.querySelector('.archive-companion').dataset.state==='stopping'"), 'Authored stop transition missing');
        await sleep(250);
        const stopProgress = await evaluate("document.querySelector('.companion-sprite').getAnimations()[0].currentTime");
        await evaluate('window.quickWork=archiveCompanion.begin();undefined'); await sleep(70);
        await evaluate('archiveCompanion.end(quickWork)'); await sleep(40);
        assert.ok(await evaluate("document.querySelector('.companion-sprite').getAnimations()[0].currentTime") >= stopProgress,
            'Quick work during stopping must not restart the authored clip');
        await evaluate('window.interruptedWork=archiveCompanion.begin();undefined');
        await until(() => evaluate("document.querySelector('.archive-companion').dataset.state==='starting'"),'Work during stopping should queue the next authored start');
        await evaluate('archiveCompanion.end(interruptedWork)');
        await until(() => evaluate("document.querySelector('.archive-companion').dataset.state==='stopping'"),'Work finishing during start should proceed to the authored stop');
        await until(() => evaluate("document.querySelector('.archive-companion').dataset.state==='idle'"), 'Idle did not return');
        await evaluate("document.querySelector('.companion-character').click()");
        assert.equal(await evaluate("document.activeElement.textContent"),'Pause animation');
        await call('Input.dispatchKeyEvent',{type:'keyDown',key:'Escape',code:'Escape'});
        assert.equal(await evaluate("document.activeElement.className"),'companion-character');
        assert.equal(await evaluate("document.querySelector('.companion-menu').hidden"),true);
        await evaluate("document.querySelector('.companion-character').click();document.querySelectorAll('.companion-menu button')[1].click()");
        assert.equal(await evaluate("document.querySelector('.archive-companion').dataset.side"),'left');
        await navigate('characters'); await ready();
        assert.equal(await evaluate("document.querySelector('.archive-companion').dataset.side"),'left');

        const characterRect = () => evaluate("(()=>{const r=document.querySelector('.companion-character').getBoundingClientRect();return {left:r.left,top:r.top,width:r.width,height:r.height,right:r.right,bottom:r.bottom}})()");
        const mouse = (type, x, y, pressed = false) => call('Input.dispatchMouseEvent', {
            type, x, y, button: type === 'mouseMoved' && !pressed ? 'none' : 'left', buttons: pressed ? 1 : 0,
            clickCount: type === 'mouseMoved' ? 0 : 1,
        });
        async function dragTo(x, y) {
            const rect = await characterRect(), startX = rect.left + rect.width / 2, startY = rect.top + rect.height / 2;
            await mouse('mouseMoved', startX, startY);
            await mouse('mousePressed', startX, startY, true);
            await mouse('mouseMoved', x, y, true);
            await sleep(60);
            await mouse('mouseReleased', x, y);
        }
        await dragTo(510, 310);
        const dropped = await characterRect();
        assert.ok(dropped.left > 300 && dropped.top < 400, 'Mouse dragging should move Lumine freely');
        assert.equal(await evaluate("document.querySelector('.companion-menu').hidden"),true,'Dropping must not open the menu');
        assert.equal(await evaluate("!!JSON.parse(localStorage.getItem('genshin-archive.companion.v1')).position"),true);
        await navigate('characters'); await ready();
        const restored = await characterRect();
        assert.ok(Math.abs(restored.left - dropped.left) < 1 && Math.abs(restored.top - dropped.top) < 1, 'Dragged position should survive navigation');
        await call('Network.enable'); await call('Network.setCacheDisabled',{cacheDisabled:true});
        await call('Page.navigate',{url:base+'/archive/inventory?continuity=slow'});
        await until(()=>evaluate("document.getElementById('archive-companion-continuation')?.querySelector('img').complete"),'Navigation did not immediately display the saved character frame');
        assert.equal(await evaluate('!!window.archiveCompanion'),false,'Saved frame must appear before the companion script downloads');
        const carried = await evaluate("(()=>{const c=document.getElementById('archive-companion-continuation'),r=c.getBoundingClientRect();return {left:r.left,top:r.top,source:c.querySelector('img').src,visible:getComputedStyle(c).visibility,opacity:getComputedStyle(c).opacity}})()");
        assert.ok(carried.source.startsWith('data:image/png;base64,'),'Continuity must not depend on another image request');
        assert.ok(Math.abs(carried.left-dropped.left)<1 && Math.abs(carried.top-dropped.top)<1);
        assert.equal(carried.visible,'visible'); assert.equal(carried.opacity,'1');
        await sleep(300);
        assert.equal(await evaluate("!!document.getElementById('archive-companion-continuation')"),true,'Character must remain visible while the next page assets are delayed');
        const navigationShot=await call('Page.captureScreenshot',{format:'png'});
        fs.writeFileSync(path.join(root,'.tmp/companion-navigation-preview.png'),Buffer.from(navigationShot.data,'base64'));
        await ready();
        await until(()=>evaluate("!document.getElementById('archive-companion-continuation') && !document.querySelector('.archive-companion').dataset.continuing"),'Navigation continuity did not hand over to the live companion');
        assert.equal(await evaluate("document.querySelectorAll('.archive-companion').length"),1);
        const carriedPosition=await characterRect();
        assert.ok(Math.abs(carriedPosition.left-dropped.left)<1 && Math.abs(carriedPosition.top-dropped.top)<1);
        await call('Network.setCacheDisabled',{cacheDisabled:false});
        await evaluate("document.querySelector('a.brand').click()");
        await until(()=>evaluate("location.pathname==='/archive/' && !!document.querySelector('.companion-sprite.is-ready') && !location.search.includes('companionNavigation')"),'Home navigation did not restore the companion and clean its URL');
        assert.ok(requestUrls.some(url=>url.includes('/archive/?direct=1&companionNavigation=1')),'Internal Home links should bypass repeating the full-screen intro');
        assert.equal(await evaluate('location.search'),'');
        assert.equal(await evaluate("document.querySelectorAll('.archive-companion').length"),1);
        await dragTo(0, 0);
        const clamped = await characterRect();
        assert.ok(clamped.left >= 12 && clamped.top >= 12, 'Dragging must stay inside the viewport');
        // Escape cancels a drag without saving its intermediate location.
        const originalPosition = await evaluate("localStorage.getItem('genshin-archive.companion.v1')");
        await mouse('mousePressed', clamped.left + 40, clamped.top + 50, true);
        await mouse('mouseMoved', 600, 450, true);
        await call('Input.dispatchKeyEvent',{type:'keyDown',key:'Escape',code:'Escape'});
        await mouse('mouseReleased', 600, 450);
        assert.equal(await evaluate("localStorage.getItem('genshin-archive.companion.v1')"),originalPosition);
        assert.ok(Math.abs((await characterRect()).left - clamped.left) < 1);
        // A normal mouse click still opens the controls after dragging.
        await mouse('mousePressed', clamped.left + 40, clamped.top + 50, true);
        await mouse('mouseReleased', clamped.left + 40, clamped.top + 50);
        assert.equal(await evaluate("document.querySelector('.companion-menu').hidden"),false);
        await call('Emulation.setDeviceMetricsOverride',{width:390,height:260,deviceScaleFactor:1,mobile:true});
        await sleep(80);
        assert.equal(await evaluate("(()=>{const r=document.querySelector('.companion-menu').getBoundingClientRect();return r.left>=0&&r.top>=0&&r.right<=innerWidth&&r.bottom<=innerHeight})()"),true,'Menu must fit even above a dragged character in a short viewport');
        const resized = await characterRect();
        assert.ok(resized.left >= 12 && resized.top >= 12 && resized.right <= 378 && resized.bottom <= 248);
        await call('Emulation.setDeviceMetricsOverride',{width:1120,height:800,deviceScaleFactor:1,mobile:false});
        // Dock controls clear the free position and retain keyboard alternatives.
        await evaluate("document.querySelectorAll('.companion-menu button')[1].click()");
        assert.equal(await evaluate("JSON.parse(localStorage.getItem('genshin-archive.companion.v1')).position"),null);
        await evaluate("document.querySelectorAll('.companion-menu button')[3].click()");
        await evaluate("document.querySelector('.companion-character').click();document.querySelector('.companion-menu button').click()");
        await until(() => evaluate("getComputedStyle(document.querySelector('.companion-sprite')).backgroundImage.includes('poster.png')"),'Pause did not select static asset');
        assert.equal(await evaluate("document.querySelector('.archive-companion').dataset.paused"),'true');
        await navigate('characters?continuity=slow');
        await until(()=>evaluate("!!document.getElementById('archive-companion-continuation')"),'Paused companion should also stay visible across pages');
        await ready();
        assert.equal(await evaluate("document.querySelector('.archive-companion').dataset.paused"),'true');
        await evaluate("document.querySelectorAll('.companion-menu button')[2].click()");
        assert.equal(await evaluate("document.querySelector('.archive-companion').hidden"),true);
        const before = requests.length;
        await navigate('inventory'); await sleep(700);
        assert.equal(await evaluate("document.querySelector('.archive-companion')===null"),true);
        assert.equal(requests.slice(before).some(p=>/companion\/|companion\.css/.test(p)),false,'Hidden companion must not request assets');
        await evaluate("document.querySelector('[data-companion-toggle]').click()"); await ready();
        assert.equal(await evaluate("document.querySelector('.archive-companion').dataset.paused"),'true');
        await evaluate("localStorage.removeItem('genshin-archive.companion.v1')");
        await call('Emulation.setEmulatedMedia',{features:[{name:'prefers-reduced-motion',value:'reduce'}]});
        await call('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});
        const reducedStart = requests.length;
        await navigate('?lang=de'); await ready();
        assert.equal(await evaluate("getComputedStyle(document.querySelector('.companion-sprite')).animationName"),'none');
        assert.equal(requests.slice(reducedStart).some(p=>p.endsWith('/lumine-idle.png')),false,'Reduced motion must use only the poster');
        await evaluate("document.querySelector('.companion-character').click()");
        assert.equal(await evaluate('document.activeElement.textContent'),'Animation pausieren');
        assert.equal(await evaluate("document.querySelector('.companion-menu').getBoundingClientRect().left>=0 && document.querySelector('.companion-menu').getBoundingClientRect().right<=innerWidth"),true);
        const shot = await call('Page.captureScreenshot',{format:'png'});
        fs.writeFileSync(path.join(root,'.tmp/companion-mobile-preview.png'),Buffer.from(shot.data,'base64'));
        await evaluate("localStorage.setItem('genshin-archive.companion.v1','broken json')");
        await call('Emulation.setEmulatedMedia',{features:[]});
        await call('Emulation.setDeviceMetricsOverride',{width:1120,height:800,deviceScaleFactor:1,mobile:false});
        await navigate(); await ready();
        await evaluate("document.querySelector('.companion-character').click()");
        const desktop = await call('Page.captureScreenshot',{format:'png'});
        fs.writeFileSync(path.join(root,'.tmp/companion-desktop-preview.png'),Buffer.from(desktop.data,'base64'));
        assert.equal(requests.some(p=>/babylon|\.pmx|\.vmd|\.wasm/i.test(p)),false);
        console.log(JSON.stringify({passed:true,checks:['authored idle','authored start and stop one-shots','interrupted transitions without restarting','overlapping activity states','keyboard menu','saved side across pages',
            'mouse dragging','saved free position','visible saved frame across real page navigation','continuity with slow uncached assets','no duplicate after resuming',
            'Home navigation avoids repeating the intro and cleans its URL',
            'viewport clamping','Escape cancels drag','click after dragging','resize and short viewport menus','dock reset',
            'persistent pause with navigation continuity','hide without asset downloads','restore','reduced motion','German controls','mobile menu bounds','invalid preferences','context path','no 3D runtime']},null,2));
    } finally { socket?.close(); child.kill(); server.closeAllConnections(); server.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
