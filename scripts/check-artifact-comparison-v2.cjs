const fs = require('node:fs'), path = require('node:path'), http = require('node:http'), assert = require('node:assert/strict');
const {withBrowser} = require('./browser-test-session.cjs');
const resources = path.resolve(__dirname, '../src/main/resources');
const labels = Object.fromEntries(fs.readFileSync(path.join(resources, 'messages.properties'), 'utf8').split(/\r?\n/)
    .filter(line => line.startsWith('damage.comparison.')).map(line => { const i = line.indexOf('='); return [line.slice(0, i), line.slice(i + 1)]; }));
let phase = 'projections', failPoll = false, posts = 0, gets = 0, lastRequest;
const progress = () => ({phase, completedCandidates: 12, totalCandidates: 75, completedEvaluations: 9600,
    totalEvaluations: 60000, percent: 16, elapsedMillis: 15000, queueMillis: 1, remainingMillis: 80000, estimateScope: 'run'});
const row = {inventoryIndex: 5, equipped: false, artifact: {setKey: 'GildedDreams', setName: 'Gilded Dreams', slotKey: 'flower',
    level: 16, rarity: 5, mainStatName: 'HP', location: 'Kaveh', locked: true,
    substats: [{name: 'Elemental Mastery', value: 63}, {name: 'CRIT Rate', value: 7.8}]},
    current: {damage: 99000, difference: -1000, percent: -1}, projection: {samples: 800,
        expected: {damage: 108000, difference: 8000, percent: 8}, low: 101000, high: 114000, chanceToImprove: .92,
        versusLeveled: {damage: 108000, difference: 4000, percent: 3.85}, chanceToBeatLeveled: .7},
    newEffectDefaults: {'New set effect': true}, newInputDefaults: {'Stacks': 3}};
const payload = () => ({runId: 'test-run', contextFingerprint: 'fixture', progress: progress(), baselineDamage: 100000,
    rows: [row], totalRows: 1, offset: 0, error: phase === 'failed' ? 'comparison.failed' : null, cancellationRequested: false});
const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://localhost');
    const json = data => {res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(data));};
    if (url.pathname.startsWith('/api/')) {
        if (req.method === 'POST') { let body = ''; for await (const chunk of req) body += chunk;
            lastRequest = JSON.parse(body); posts++; return json(payload()); }
        if (req.method === 'DELETE') {phase = 'cancelled'; return json(payload());}
        gets++; if (failPoll) {res.statusCode = 503; return json({error: 'comparison.busy'});}
        return json(payload());
    }
    if (url.pathname === '/') {
        let fragment = fs.readFileSync(path.join(resources, 'templates/artifact-comparison-v2.html'), 'utf8');
        fragment = fragment.slice(fragment.indexOf('<section'), fragment.indexOf('</section>') + 10)
            .replace('data-artifact-comparison ', 'data-artifact-comparison data-comparison-url="/api/comparison" ')
            .replace('data-v2-tab-panel="comparison" hidden', 'data-v2-tab-panel="comparison"')
            .replace(/th:text="#\{([^}]+)\}"[^>]*><\/span>/g, (_, key) => `>${labels[key] || key}</span>`);
        res.setHeader('Content-Type', 'text/html');
        return res.end(`<!doctype html><html><head><meta charset="utf-8"><link rel="stylesheet" href="/css/app.css"><link rel="stylesheet" href="/css/damage-calculator.css"><link rel="stylesheet" href="/css/damage-calculator-v2.css"></head>
            <body><main class="damage-calculator-page damage-calculator-v2-page damage-element-dendro" style="padding:32px">${fragment}</main>
            <script src="/js/artifact-comparison-v2.js"></script><script>
            window.state={inputs:{elementalMastery:1000},enabledEffects:{},teamKeys:['nefer','lauma','nahida','columbina']};
            window.comparison=createArtifactComparisonV2(document.querySelector('main'),{context:()=>state,headers:()=>({'Content-Type':'application/json'}),reload:()=>{}});
            comparison.setDocument({contextFingerprint:'fixture',profile:{talents:[{key:'skill',name:'Elemental Skill',formulas:[1,2,3].map(i=>({id:'shades-hit-'+i,label:'Phantasm Performance '+i+' (Shades)'}))}]}});
            </script></body></html>`);
    }
    const file = path.join(resources, 'static', url.pathname);
    if (fs.existsSync(file)) {res.setHeader('Content-Type', file.endsWith('.js') ? 'text/javascript' : 'text/css'); return res.end(fs.readFileSync(file));}
    res.statusCode = 404; res.end();
});
withBrowser(server, async ({base, call, evaluate, until, sleep, screenshot, errors}) => {
    await call('Emulation.setDeviceMetricsOverride', {width: 1500, height: 1100, deviceScaleFactor: 1, mobile: false});
    await call('Page.navigate', {url: base});
    await until(() => evaluate(`document.querySelectorAll('.comparison-goal-options input:checked').length===3`), 'Shades preset missing');
    await evaluate(`document.querySelector('.comparison-actions button').click()`);
    await until(() => evaluate(`document.querySelector('.comparison-card')?.textContent.includes('800 simulated outcomes')`), 'Projection result missing');
    assert.deepEqual(lastRequest.formulaIds, ['shades-hit-1', 'shades-hit-2', 'shades-hit-3']);
    assert.equal(lastRequest.calculation.inputs.elementalMastery, 1000);
    assert.ok(await evaluate(`document.querySelector('.comparison-progress').textContent.includes('About 2 min remaining')`));
    await screenshot('artifact-comparison-v2.png');
    await evaluate(`state.inputs.elementalMastery=1100; comparison.changed()`);
    assert.ok(await evaluate(`!document.querySelector('.comparison-notice').hidden`));
    failPoll = true;
    await until(() => evaluate(`document.querySelector('.comparison-progress').textContent.includes('Connection interrupted')`), 'Polling failure not shown');
    failPoll = false;
    await until(() => evaluate(`!document.querySelector('.comparison-progress').textContent.includes('Connection interrupted')`), 'Polling did not recover');
    await evaluate(`Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='Cancel analysis').click()`);
    await until(() => evaluate(`document.querySelector('.comparison-progress strong').textContent.includes('cancelled')`), 'Cancellation missing');
    assert.ok(await evaluate(`document.querySelector('.comparison-results').textContent.includes('Partial results')`));
    const finishedGets = gets; await sleep(1200); assert.equal(gets, finishedGets, 'Polling continued after cancellation');
    phase = 'projections'; await evaluate(`document.querySelector('.comparison-actions button').click()`);
    await until(() => posts === 2, 'Second analysis not started');
    await call('Page.reload');
    await until(() => evaluate(`document.querySelector('.comparison-card')!==null`), 'Run did not restore after refresh');
    phase = 'completed';
    await until(() => evaluate(`document.querySelector('.comparison-progress strong').textContent==='Analysis complete'`), 'Completion missing');
    assert.equal(errors.length, 0, JSON.stringify(errors));
    console.log('Artifact comparison browser checks passed: goals, results, ETA, stale settings, polling recovery, cancellation, resume, completion.');
}).catch(error => {console.error(error); process.exitCode = 1;});
