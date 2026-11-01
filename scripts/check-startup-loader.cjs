// End-to-end checks in an isolated, headless Chromium profile. No third-party packages.
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const {spawn} = require('node:child_process');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
const browserPath = process.env.STARTUP_TEST_BROWSER || [
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
].find(fs.existsSync);
assert(browserPath, 'Set STARTUP_TEST_BROWSER to a Chromium executable');
const profile = fs.mkdtempSync(path.join(root, '.tmp/startup-browser-'));
const page = fs.readFileSync(path.join(root, 'src/main/resources/startup/loading.html'), 'utf8')
    .replaceAll('__ARCHIVE_CONTEXT_PATH__', '');
const requests = [], pending = new Set(), counts = new Map();
const continuityBootstrap = fs.readFileSync(path.join(root, 'src/main/resources/templates/fragments.html'), 'utf8')
    .match(/<script data-companion-continuity>[\s\S]*?<\/script>/)[0];
const completed = `<!doctype html><html lang="en"><head><meta charset="UTF-8"><title>Dashboard</title>
<link rel="stylesheet" href="/css/dashboard-test.css"></head><body><main id="dashboard">Archive ready</main>
<form action="/save" method="post"><input type="hidden" name="_csrf" value="retained-token"></form>
<script>window.scriptRuns=(window.scriptRuns||0)+1;document.addEventListener('DOMContentLoaded',()=>{
document.body.dataset.loaded='true';document.body.dataset.runs=String(window.scriptRuns);
document.body.dataset.route=location.pathname;});</script></body></html>`;
const withCompanion = completed.replace('</head>', '<link rel="stylesheet" href="/css/app.css"></head>')
    .replace('<body>', `<body><header>${continuityBootstrap}<button data-companion-toggle hidden>Lumine</button><script defer src="/js/companion.js"></script></header>`)
    .replace('<main id="dashboard">', '<main id="dashboard" style="min-height:2000px">');
function send(res, body = completed) { if (!res.destroyed) {res.writeHead(200, {'Content-Type':'text/html; charset=utf-8'});res.end(body);} }
const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://localhost');
    requests.push({path:url.pathname, ready:req.headers['x-archive-ready'] === 'true'});
    if (/^\/(js\/companion\.js|css\/(companion|app)\.css|companion\/lumine-(idle|start|stop)(-poster)?\.png)$/.test(url.pathname)) {
        const asset = path.join(root, 'src/main/resources/static', url.pathname);
        const delay = req.headers.referer?.includes('assets=slow')
            ? url.pathname === '/js/companion.js' ? 2800 : url.pathname === '/css/companion.css' ? 1000 : 0 : 0;
        const deliver = () => {
            res.writeHead(200, {'Content-Type':asset.endsWith('.js')?'application/javascript':asset.endsWith('.css')?'text/css':'image/png'});
            res.end(fs.readFileSync(asset));
        };
        if (delay) setTimeout(deliver, delay); else deliver();
        return;
    }
    if (url.pathname === '/css/dashboard-test.css') {
        res.writeHead(200, {'Content-Type':'text/css', 'Cache-Control':'public,max-age=600'});
        return res.end('body{background:rgb(240,241,242)}');
    }
    if (url.pathname.startsWith('/startup/')) {
        const name = url.pathname.slice('/startup/'.length);
        if (!['loader.js','lumine-sprint.png','lumine-sprint-poster.png'].includes(name)) {res.writeHead(404);return res.end();}
        res.writeHead(200, {'Content-Type':name.endsWith('.js')?'application/javascript':'image/png'});
        return res.end(fs.readFileSync(path.join(root, 'src/main/resources/static/startup', name)));
    }
    if (url.pathname === '/login' || url.searchParams.get('direct') === '1') return send(res);
    if (req.headers['x-archive-ready'] !== 'true') return send(res, page);
    const mode = url.searchParams.get('mode') || 'slow';
    const attempt = (counts.get(mode) || 0) + 1; counts.set(mode, attempt);
    if (mode === 'error' || (mode === 'retry' && attempt === 1)) {res.writeHead(503);return res.end('Unavailable');}
    if (mode === 'login') {res.writeHead(302, {Location:'/login'});return res.end();}
    if (mode === 'slow' || mode === 'handoff') {res.loaderMode=mode;pending.add(res);res.on('close',()=>pending.delete(res));return;}
    send(res);
});
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(read, message, limit = 15000) {
    const deadline = Date.now() + limit;
    while (Date.now() < deadline) {const value = await read();if(value)return value;await sleep(40);}
    throw Error(message);
}
(async () => {
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const base = 'http://127.0.0.1:' + server.address().port;
    const child = spawn(browserPath, ['--headless=new','--disable-gpu','--no-first-run','--no-default-browser-check',
        '--disable-background-networking','--remote-debugging-port=0','--user-data-dir='+profile,'about:blank'], {stdio:'ignore',windowsHide:true});
    let socket;
    try {
        const portFile = path.join(profile,'DevToolsActivePort');
        await until(()=>fs.existsSync(portFile),'Headless browser did not start');
        const [port, endpoint] = fs.readFileSync(portFile,'utf8').trim().split(/\r?\n/);
        socket = new WebSocket('ws://127.0.0.1:'+port+endpoint);
        await new Promise((resolve,reject)=>{socket.addEventListener('open',resolve,{once:true});socket.addEventListener('error',reject,{once:true});});
        const calls = new Map(); let sequence = 0;
        socket.addEventListener('message',event=>{
            const message=JSON.parse(event.data);const call=calls.get(message.id);
            if(call){calls.delete(message.id);clearTimeout(call.timer);message.error?call.reject(Error(message.error.message)):call.resolve(message.result);}
        });
        function cdp(method,params={},sessionId) {return new Promise((resolve,reject)=>{
            const id=++sequence;const timer=setTimeout(()=>{calls.delete(id);reject(Error('CDP timeout: '+method));},15000);
            calls.set(id,{resolve,reject,timer});socket.send(JSON.stringify({id,method,params,sessionId}));
        });}
        const {targetId}=await cdp('Target.createTarget',{url:'about:blank'});
        const {sessionId}=await cdp('Target.attachToTarget',{targetId,flatten:true});
        const call=(method,params)=>cdp(method,params,sessionId);
        const evaluate=async expression=>{
            const result=await call('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});
            if(result.exceptionDetails)throw Error(result.exceptionDetails.text);
            return result.result.value;
        };
        const navigate=async suffix=>{await call('Page.navigate',{url:base+suffix});};
        await call('Page.enable'); await call('Runtime.enable');
        await call('Emulation.setDeviceMetricsOverride',{width:1120,height:800,deviceScaleFactor:1,mobile:false});
        await navigate('/?mode=slow');
        await until(()=>evaluate("document.body?.dataset.state==='loading' && performance.getEntriesByType('paint').length>0"),'Loader did not paint before dashboard completed');
        await until(()=>pending.size,'Dashboard was not requested');
        assert.equal(counts.get('slow'),1,'Only one dashboard request should be made');
        await until(()=>evaluate("performance.getEntriesByType('resource').some(r=>r.name.includes('lumine-sprint.png'))"),'Sprite did not load');
        const pose1=await evaluate("getComputedStyle(document.querySelector('.runner')).backgroundPositionX");await sleep(120);
        const pose2=await evaluate("getComputedStyle(document.querySelector('.runner')).backgroundPositionX");
        assert.notEqual(pose1,pose2,'The sprint sprite should animate');
        const firstPaint=await evaluate("performance.getEntriesByType('paint').find(p=>p.name==='first-contentful-paint').startTime");
        const screenshot=await call('Page.captureScreenshot',{format:'png'});
        fs.writeFileSync(path.join(root,'.tmp/lumine-loading-preview.png'),Buffer.from(screenshot.data,'base64'));
        for(const response of pending)send(response);pending.clear();
        await until(()=>evaluate("document.body?.dataset.loaded==='true'"),'Dashboard hand-off did not finish');
        assert.equal(await evaluate('document.body.dataset.runs'),'1');
        assert.equal(await evaluate("!!document.getElementById('archive-startup-handoff')"),false,'Login must not receive a companion handoff');
        assert.equal(await evaluate("document.querySelector('[name=_csrf]').value"),'retained-token');
        assert.equal(await evaluate('document.body.dataset.route'),'/');
        assert.equal(await evaluate('getComputedStyle(document.body).backgroundColor'),'rgb(240, 241, 242)');

        await navigate('/?mode=login');
        await until(()=>evaluate("document.body?.dataset.loaded==='true' && location.pathname==='/login'"),'Login redirect was not preserved');
        assert.equal(await evaluate('document.body.dataset.runs'),'1');

        await navigate('/?mode=retry&lang=de');
        await until(()=>evaluate("document.body?.dataset.state==='error'"),'Failure UI did not appear');
        assert.equal(await evaluate('document.documentElement.lang'),'de');
        assert.equal(await evaluate('document.getElementById("actions").hidden'),false);
        assert.match(await evaluate('document.getElementById("direct").href'),/direct=1/);
        assert.match(await evaluate('document.getElementById("direct").href'),/lang=de/);
        await evaluate('document.getElementById("retry").click()');
        await until(()=>evaluate("document.body?.dataset.loaded==='true'"),'Retry did not recover');
        assert.equal(counts.get('retry'),2);

        await call('Emulation.setEmulatedMedia',{features:[{name:'prefers-reduced-motion',value:'reduce'}]});
        await call('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});
        await navigate('/?mode=slow&mobile=1');
        await until(()=>evaluate("document.body?.dataset.state==='loading' && document.querySelector('.runner')"),'Mobile loader did not load');
        assert.equal(await evaluate('document.body.scrollWidth <= innerWidth'),true,'Mobile loader should not overflow');
        assert.equal(await evaluate("getComputedStyle(document.querySelector('.runner')).animationName"),'none');
        assert.match(await evaluate("getComputedStyle(document.querySelector('.runner')).backgroundImage"),/poster\.png/);
        const mobileScreenshot=await call('Page.captureScreenshot',{format:'png'});
        fs.writeFileSync(path.join(root,'.tmp/lumine-loading-mobile-preview.png'),Buffer.from(mobileScreenshot.data,'base64'));

        await evaluate("localStorage.setItem('genshin-archive.companion.v1',JSON.stringify({visible:true,paused:false,side:'left',position:{x:.3,y:.35}}))");
        await call('Emulation.setEmulatedMedia',{features:[]});
        await call('Emulation.setDeviceMetricsOverride',{width:1120,height:800,deviceScaleFactor:1,mobile:false});
        await navigate('/?mode=handoff&assets=slow');
        await until(()=>evaluate("!!document.querySelector('.runner')"),'Full-screen runner missing');
        await until(()=>[...pending].some(r=>r.loaderMode==='handoff'),'Handoff page was not requested');
        for(const response of pending)send(response,withCompanion);pending.clear();
        await until(()=>evaluate("!!window.archiveCompanion && !document.querySelector('.archive-companion')"),'Delayed companion stylesheet did not reproduce initialization window');
        await evaluate("window.dispatchEvent(new Event('resize'));window.visualViewport?.dispatchEvent(new Event('resize'))");
        assert.equal(await evaluate("!!document.getElementById('archive-startup-handoff')"),true,'Initial layout events must preserve the pending handoff');
        await until(()=>evaluate("document.querySelector('.archive-companion')?.dataset.arriving==='true'"),'Corner handoff did not begin');
        assert.equal(await evaluate("getComputedStyle(document.getElementById('archive-startup-handoff')).visibility"),'visible','Slow assets must not expire the visible flight before it starts');
        assert.equal(await evaluate("document.body.dataset.loaded"),'true','Page must be usable during the transition');
        assert.equal(await evaluate("getComputedStyle(document.getElementById('archive-startup-handoff')).pointerEvents"),'none');
        const flightWidth=()=>evaluate("document.getElementById('archive-startup-flight').getBoundingClientRect().width");
        const width1=await flightWidth(); await sleep(120); const width2=await flightWidth();
        assert.ok(width2 < width1,'Lumine should shrink continuously toward the companion');
        await call('Emulation.setDeviceMetricsOverride',{width:1000,height:760,deviceScaleFactor:1,mobile:false});
        await sleep(80);
        assert.equal(await evaluate("!!document.getElementById('archive-startup-handoff') && document.querySelector('.archive-companion').dataset.arriving==='true'"),true,'Resize during flight should retarget it instead of removing it');
        const handoffShot=await call('Page.captureScreenshot',{format:'png'});
        fs.writeFileSync(path.join(root,'.tmp/lumine-handoff-preview.png'),Buffer.from(handoffShot.data,'base64'));
        await until(()=>evaluate("!document.getElementById('archive-startup-handoff') && document.querySelector('.companion-sprite.is-ready')"),'Handoff did not clean up');
        assert.equal(await evaluate("document.querySelectorAll('.archive-companion').length"),1);
        await until(()=>evaluate("document.querySelector('.archive-companion').dataset.state==='stopping'"),'Arrival should use the authored stopping motion');
        await until(()=>evaluate("document.querySelector('.archive-companion').dataset.state==='idle'"),'Stopping motion did not return to idle');
        assert.equal(await evaluate("document.querySelector('.archive-companion').dataset.arriving || ''"),'');
        const savedTarget=await evaluate("(()=>{const r=document.querySelector('.companion-character').getBoundingClientRect();return {x:r.left,y:r.top,width:document.documentElement.clientWidth,height:innerHeight}})()");
        assert.ok(Math.abs(savedTarget.x-(12+.3*(savedTarget.width-24-128)))<1 && Math.abs(savedTarget.y-(12+.35*(savedTarget.height-24-160)))<1,'Handoff should land at the saved dragged position after resizing and adding a scrollbar');
        assert.equal(await evaluate('document.body.dataset.runs'),'1');

        // Exercise an actual reload with warm assets, rather than only a first visit.
        await call('Page.reload',{ignoreCache:false});
        await until(()=>[...pending].some(r=>r.loaderMode==='handoff'),'Reload did not request the real page');
        for(const response of pending)send(response,withCompanion);pending.clear();
        await until(()=>evaluate("document.querySelector('.archive-companion')?.dataset.arriving==='true'"),'Reload skipped the full-screen flight');
        assert.equal(await evaluate("getComputedStyle(document.getElementById('archive-startup-handoff')).visibility"),'visible');
        await until(()=>evaluate("!document.getElementById('archive-startup-handoff') && document.querySelector('.archive-companion')?.dataset.state==='idle'"),'Reload handoff did not settle');

        // Hidden and reduced-motion preferences bypass the flight entirely.
        await evaluate("localStorage.setItem('genshin-archive.companion.v1',JSON.stringify({visible:false}))");
        await navigate('/?mode=handoff&hidden=1'); await until(()=>[...pending].some(r=>r.loaderMode==='handoff'),'Hidden handoff page missing');
        const hiddenStart=requests.length;
        for(const response of pending)send(response,withCompanion);pending.clear();
        await until(()=>evaluate("document.body?.dataset.loaded==='true' && !!window.archiveCompanion"),'Hidden handoff did not load');
        await sleep(100);
        assert.equal(await evaluate("!!document.getElementById('archive-startup-handoff') || !!document.querySelector('.archive-companion')"),false);
        assert.equal(requests.slice(hiddenStart).some(r=>r.path.startsWith('/companion/') || r.path==='/css/companion.css'),false);
        await evaluate("localStorage.removeItem('genshin-archive.companion.v1')");
        await call('Emulation.setEmulatedMedia',{features:[{name:'prefers-reduced-motion',value:'reduce'}]});
        await navigate('/?mode=handoff&reduced=1'); await until(()=>[...pending].some(r=>r.loaderMode==='handoff'),'Reduced handoff page missing');
        for(const response of pending)send(response,withCompanion);pending.clear();
        await until(()=>evaluate("!!document.querySelector('.companion-sprite.is-ready')"),'Reduced-motion companion did not appear');
        assert.equal(await evaluate("!!document.getElementById('archive-startup-handoff')"),false);
        assert.equal(await evaluate("getComputedStyle(document.querySelector('.companion-sprite')).animationName"),'none');
        assert.equal(requests.some(r=>/\.pmx|\.vmd|babylon|three\./i.test(r.path)),false,'Startup should not load a 3D runtime or source model');
        console.log(JSON.stringify({passed:true,firstContentfulPaintMs:firstPaint,
            checks:['paint before data','animated sprite','single hand-off','scripts once','CSRF retained','stylesheet ready','login redirect','localized retry','mobile layout','reduced motion',
                'full-screen to corner flight','slow assets remain visible','initial layout preserves handoff','resize retargets flight','actual reload repeats flight',
                'authored run-to-idle arrival','saved destination','usable page during flight','single companion after cleanup','hidden companion bypass','reduced-motion bypass','no 3D runtime'],
            screenshot:'.tmp/lumine-loading-preview.png'},null,2));
    } finally {
        for(const response of pending)response.destroy();
        socket?.close();child.kill();server.closeAllConnections();server.close();
    }
})().catch(error=>{console.error(error);process.exitCode=1;});
