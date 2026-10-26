import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';

const template = readFileSync(new URL('../../main/resources/templates/dashboard.html', import.meta.url), 'utf8');
const scripts = [...template.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g)].map(match => match[1]);
const routine = scripts.find(script => script.includes('const root = document.getElementById("dashboard-routine")'));
const polling = scripts.find(script => script.includes('const list = document.getElementById("dashboard-recent-list")'));
const progress = (claims = [], day = '2026-10-24', week = '2026-10-19') => ({day, week, claims});
const claim = (bossKey, day = '2026-10-24') => ({bossKey, day});

function fixture(initial, saved = {}, farming = null) {
    const element = () => ({
        textContent: '', disabled: false, hidden: false, handlers: {},
        style: {setProperty() {}}, classList: {toggle() {}},
        addEventListener(name, handler) { this.handlers[name] = handler; },
        children: [],
        replaceChildren(...children) { this.children = children; },
    });
    const count = element(), spent = element(), remaining = element(), button = element(), reset = element();
    const unconfirmed = element(), runCount = element(), runButton = element();
    class Element {}
    Object.setPrototypeOf(runButton, Element.prototype);
    runButton.dataset = {taskKey: 'ley-line:mora', resinCost: '20'};
    runButton.querySelector = () => runCount;
    runButton.closest = () => runButton;
    const nodes = {
        '[data-weekly-count]': count, '[data-resin-used]': spent, '[data-resin-remaining]': remaining,
        '[data-resin-ring]': element(), '[data-routine-reset]': reset,
        '[data-resin-unconfirmed]': unconfirmed,
    };
    const root = {
        dataset: {dayBudget: '200', weeklyLimit: '3', weeklyOpen: 'Record claim', weeklyDone: '3 of 3 complete'},
        querySelector: key => nodes[key], querySelectorAll: () => [element(), element(), element()],
    };
    const handlers = {};
    const ids = {
        'dashboard-routine': root, 'dashboard-recent-list': element(), 'dashboard-recent-empty': element(),
        'dashboard-content': {dataset: {snapshotRevision: '5'}},
    };
    let stored = JSON.stringify(saved);
    let poll;
    const weeklyList = element();
    weeklyList.children = ['collected-boss-card'];
    const context = vm.createContext({
        document: {
            hidden: false, getElementById: id => ids[id],
            querySelector: key => key === '[data-weekly-claim]' ? button
                : key === '[data-dashboard-list="weekly-main"]' ? weeklyList : null,
            querySelectorAll: key => key === '[data-resin-run]' ? [runButton] : [],
            addEventListener: (name, handler) => (handlers[name] ||= []).push(handler),
            dispatchEvent: event => (handlers[event.type] || []).forEach(handler => handler(event)),
        },
        localStorage: {getItem: () => stored, setItem: (_, value) => stored = value},
        CustomEvent: class { constructor(type, options = {}) { this.type = type; this.detail = options.detail; } },
        window: {setInterval(handler) { poll = handler; }},
        Element,
    });
    vm.runInContext(routine.replace('/*[[${weeklyBossProgress}]]*/ null', JSON.stringify(initial))
        .replace('/*[[${dailyFarmingProgress}]]*/ null', JSON.stringify(farming)), context);
    return {
        context, count, spent, remaining, button, reset,
        update: detail => context.document.dispatchEvent({type: 'dashboard:weekly-progress', detail}),
        saved: () => JSON.parse(stored),
        poll: () => poll(), weeklyList,
        runCount, unconfirmed,
        farmingUpdate: detail => context.document.dispatchEvent({type: 'dashboard:farming-progress', detail}),
        recordRun: () => context.document.dispatchEvent({type: 'click', target: runButton}),
    };
}

test('initial load and repeated polls count one boss once, not its three drops', () => {
    const detected = progress([claim('lordoferodedprimalfire')]);
    const page = fixture(detected);
    assert.equal(Number(page.count.textContent), 1);
    assert.equal(Number(page.spent.textContent), 30);
    page.update(detected);
    page.update(detected);
    assert.equal(Number(page.count.textContent), 1);
    assert.equal(Number(page.remaining.textContent), 170);
    assert.equal(Number(fixture(detected, page.saved()).count.textContent), 1);
});

test('detected farming counts and resin survive reloads and reconcile manual runs', () => {
    const farming = {day: '2026-10-24', runs: [{taskKey: 'ley-line:mora', count: 1, resin: 60, unconfirmedCount: 0}]};
    const page = fixture(progress(), {}, farming);
    assert.equal(page.runCount.textContent, '1×');
    assert.equal(Number(page.spent.textContent), 60);
    page.recordRun();
    assert.equal(page.runCount.textContent, '2×');
    assert.equal(Number(page.spent.textContent), 80);
    farming.runs[0] = {...farming.runs[0], count: 2, resin: 80};
    page.farmingUpdate(farming);
    page.farmingUpdate(farming);
    assert.equal(Number(page.spent.textContent), 80);
    const reloaded = fixture(progress(), page.saved(), farming);
    assert.equal(Number(reloaded.spent.textContent), 80);
    assert.equal(reloaded.runCount.textContent, '2×');
    page.update(progress([], '2026-10-25'));
    page.farmingUpdate({day: '2026-10-25', runs: []});
    assert.equal(Number(page.spent.textContent), 0);
    assert.equal(page.runCount.textContent, '+');
});

test('unconfirmed farming costs never silently become 20 resin when adding a manual run', () => {
    const farming = {day: '2026-10-24', runs: [{taskKey: 'ley-line:mora', count: 2, resin: 0, unconfirmedCount: 2}]};
    const page = fixture(progress(), {}, farming);
    assert.equal(page.runCount.textContent, '2×');
    assert.equal(Number(page.spent.textContent), 0);
    assert.equal(page.unconfirmed.hidden, false);
    page.recordRun();
    assert.equal(page.runCount.textContent, '3×');
    assert.equal(Number(page.spent.textContent), 20);
    assert.equal(page.unconfirmed.hidden, false);
    assert.equal(fixture(progress(), page.saved(), farming).unconfirmed.hidden, false);
});

test('a manually recorded claim is not added again when the farming hook detects it', () => {
    const page = fixture(progress(), {day: '2026-10-24', week: '2026-10-19', runs: {
        'ley-line:mora': {count: 1, cost: 20}, 'artifacts:goldentroupe': {count: 1, cost: 20},
    }});
    page.farmingUpdate({day: '2026-10-24', runs: [
        {taskKey: 'ley-line:mora', count: 1, resin: 20, unconfirmedCount: 0},
        {taskKey: 'artifacts:domain', count: 1, resin: 20, unconfirmedCount: 0},
    ]});
    assert.equal(page.runCount.textContent, '1×');
    assert.equal(Number(page.spent.textContent), 40);
});

test('legacy manual claims reconcile with detected claims and manual recording still increments', () => {
    const page = fixture(progress([claim('boss1')]), {
        week: '2026-43', day: '2026-10-24', weeklyClaims: ['2026-10-24'], runs: {},
    });
    assert.equal(Number(page.count.textContent), 1);
    page.button.handlers.click();
    assert.equal(Number(page.count.textContent), 2);
    page.update(progress([claim('boss1'), claim('boss2')]));
    assert.equal(Number(page.count.textContent), 2);
    assert.equal(Number(page.spent.textContent), 60);
    page.reset.handlers.click();
    assert.equal(Number(page.count.textContent), 2);
});

test('fourth boss costs 60 resin while the discounted claim counter stays at three', () => {
    const page = fixture(progress([claim('boss1'), claim('boss2'), claim('boss3'), claim('boss4')]));
    assert.equal(Number(page.count.textContent), 3);
    assert.equal(Number(page.spent.textContent), 150);
    assert.equal(Number(page.remaining.textContent), 50);
    assert.equal(page.button.disabled, true);
    assert.equal(page.button.textContent, '3 of 3 complete');
});

test('daily and weekly resets update an already open dashboard', () => {
    const claims = [claim('boss1')];
    const page = fixture(progress(claims), {
        week: '2026-10-19', day: '2026-10-24', runs: {domain: {count: 1, cost: 20}},
    });
    assert.equal(Number(page.spent.textContent), 50);
    page.update(progress(claims, '2026-10-25'));
    assert.equal(Number(page.count.textContent), 1);
    assert.equal(Number(page.spent.textContent), 0);
    page.update(progress([], '2026-10-26', '2026-10-26'));
    assert.equal(Number(page.count.textContent), 0);
    assert.equal(Number(page.remaining.textContent), 200);
});

test('activity poll refreshes weekly progress even when the snapshot revision is unchanged', async () => {
    const page = fixture(progress());
    const urls = [];
    page.context.fetch = async url => {
        urls.push(url);
        if (url === '/api/dashboard/plan') return {ok: true, json: async () => ({revision: 5})};
        return {ok: true, json: async () => ({
            revision: 5, activities: [], weeklyBossProgress: progress([claim('lordoferodedprimalfire')]),
        })};
    };
    vm.runInContext(polling, page.context);
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(urls, ['/api/dashboard/recent-activity', '/api/dashboard/plan']);
    assert.equal(page.weeklyList.children.length, 0);
    assert.equal(Number(page.count.textContent), 1);
    assert.equal(Number(page.spent.textContent), 30);
    await page.poll();
    assert.equal(urls.filter(url => url === '/api/dashboard/plan').length, 1);
});

test('new boss identity and weekly reset refresh recommendations without inventory changes', async () => {
    const page = fixture(progress());
    let detected = progress();
    let planRequests = 0;
    page.context.fetch = async url => {
        if (url === '/api/dashboard/plan') {
            planRequests++;
            return {ok: true, json: async () => ({revision: 5})};
        }
        return {ok: true, json: async () => ({revision: 5, activities: [], weeklyBossProgress: detected})};
    };
    vm.runInContext(polling, page.context);
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(planRequests, 1);
    page.button.handlers.click(); // Manual claims cannot identify or hide a boss.
    await page.poll();
    assert.equal(planRequests, 1);
    detected = progress([claim('lordoferodedprimalfire')]);
    await page.poll();
    assert.equal(planRequests, 2);
    detected = progress([], '2026-10-26', '2026-10-26');
    await page.poll();
    assert.equal(planRequests, 3);
    assert.equal(Number(page.count.textContent), 0);
});

test('a failed plan request is retried on the next unchanged activity poll', async () => {
    const page = fixture(progress());
    let attempts = 0;
    page.context.fetch = async url => {
        if (url === '/api/dashboard/plan') {
            attempts++;
            return {ok: attempts > 1, json: async () => ({revision: 5})};
        }
        return {ok: true, json: async () => ({revision: 5, activities: [], weeklyBossProgress: progress()})};
    };
    vm.runInContext(polling, page.context);
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(attempts, 1);
    await page.poll();
    assert.equal(attempts, 2);
    await page.poll();
    assert.equal(attempts, 2);
});
