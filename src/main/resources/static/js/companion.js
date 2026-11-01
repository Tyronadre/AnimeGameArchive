(() => {
    'use strict';
    if (window.archiveCompanion) return;
    const scriptUrl = document.currentScript.src;
    const base = new URL('../companion/', scriptUrl);
    const sprintBase = new URL('../startup/', scriptUrl);
    const archiveHome = new URL('../', scriptUrl);
    const currentUrl = new URL(location.href);
    if (currentUrl.pathname === archiveHome.pathname && currentUrl.searchParams.get('companionNavigation') === '1') {
        currentUrl.searchParams.delete('companionNavigation'); currentUrl.searchParams.delete('direct');
        history.replaceState(history.state, '', currentUrl.href);
    }
    const storageKey = 'genshin-archive.companion.v1';
    const motion = matchMedia('(prefers-reduced-motion: reduce)');
    const de = document.documentElement.lang.startsWith('de');
    const text = de ? {
        title: 'Lumine', open: 'Lumines Begleiter-Menü öffnen', close: 'Menü schließen',
        help: 'Ich begleite dich durch dein Archiv.', pause: 'Animation pausieren', resume: 'Animation fortsetzen',
        left: 'Nach links stellen', right: 'Nach rechts stellen', hide: 'Begleiter ausblenden',
        show: 'Begleiter einblenden', idle: 'Bereit für dein nächstes Abenteuer.', busy: 'Dein Archiv wird vorbereitet…',
        drag: 'Zum Verschieben ziehen. Anklicken öffnet das Menü. Die Seitenwahl setzt die Position zurück.',
    } : {
        title: 'Lumine', open: 'Open Lumine companion controls', close: 'Close menu',
        help: 'Keeping you company through your archive.', pause: 'Pause animation', resume: 'Resume animation',
        left: 'Move to the left', right: 'Move to the right', hide: 'Hide companion',
        show: 'Show companion', idle: 'Ready for your next adventure.', busy: 'Getting your archive ready…',
        drag: 'Drag to move. Click for controls. Move left or right in the menu to reset the position.',
    };
    let settings = {visible: true, paused: false, side: 'right', position: null};
    try {
        const saved = JSON.parse(localStorage.getItem(storageKey) || 'null');
        if (saved && typeof saved === 'object') settings = {
            visible: saved.visible !== false, paused: saved.paused === true,
            side: saved.side === 'left' ? 'left' : 'right',
            position: saved.position && ['x', 'y'].every(axis => typeof saved.position[axis] === 'number'
                && Number.isFinite(saved.position[axis]) && saved.position[axis] >= 0 && saved.position[axis] <= 1)
                ? {x: saved.position.x, y: saved.position.y} : null,
        };
    } catch (_) { /* Private browsing or invalid preferences use the defaults. */ }
    const toggles = [...document.querySelectorAll('[data-companion-toggle]')];
    let root, character, sprite, menu, pause, side, status, bounds;
    let drag, suppressClick = false, arrival;
    const handoff = document.getElementById('archive-startup-handoff');
    let arrivalVersion = 0, arrivalPoseReady;
    const handoffTimeout = handoff ? setTimeout(() => finishArrival(), 14000) : null;
    let initialized = false, initializing = false, state = 'idle', imageVersion = 0;
    let activityTimer, navigationTimer;
    const activities = new Set();
    const images = new Map();
    const decodedImages = new Map();
    let displayedPose, displayedUrl;
    const navigationKey = 'genshin-archive.companion.navigation';
    let navigationFrame;
    try {
        const frame = JSON.parse(sessionStorage.getItem(navigationKey) || 'null');
        sessionStorage.removeItem(navigationKey);
        if (!handoff && settings.visible && frame
            && Date.now() - frame.at >= 0 && Date.now() - frame.at < 15000
            && ['idle', 'running', 'starting', 'stopping'].includes(frame.pose)
            && typeof frame.image === 'string' && frame.image.startsWith('data:image/png;base64,') && frame.image.length < 262144
            && ['left', 'top', 'width', 'height', 'elapsed'].every(key => Number.isFinite(frame[key]))
            && frame.width > 0 && frame.width <= 512 && frame.height > 0 && frame.height <= 640) navigationFrame = frame;
    } catch (_) { /* Navigation continuity is optional when storage is unavailable. */ }
    const earlyContinuation = document.getElementById('archive-companion-continuation');
    const continuation = navigationFrame ? earlyContinuation || document.createElement('div') : null;
    if (!navigationFrame) earlyContinuation?.remove();
    if (continuation && !earlyContinuation) {
        continuation.id = 'archive-companion-continuation';
        continuation.setAttribute('aria-hidden', 'true');
        Object.assign(continuation.style, {
            position: 'fixed', pointerEvents: 'none', zIndex: '80', lineHeight: '0',
            left: `${Math.max(0, Math.min(innerWidth - navigationFrame.width, navigationFrame.left))}px`,
            top: `${Math.max(0, Math.min(innerHeight - navigationFrame.height, navigationFrame.top))}px`,
            width: `${navigationFrame.width}px`, height: `${navigationFrame.height}px`,
        });
        const image = document.createElement('img');
        image.alt = ''; image.src = navigationFrame.image; image.decoding = 'sync';
        image.style.cssText = 'display:block;width:100%;height:100%;max-width:none;margin:0;filter:drop-shadow(0 5px 5px rgba(0,0,0,.15))';
        continuation.append(image); document.body.append(continuation);
        setTimeout(() => finishContinuation(), 14000);
    }
    const poses = {
        idle: {name: 'lumine-idle', frames: 22, duration: 2.2, loop: true, base},
        running: {name: 'lumine-sprint', frames: 18, duration: .6, loop: true, base: sprintBase},
        starting: {name: 'lumine-start', frames: 22, duration: 2.1, loop: false, base},
        stopping: {name: 'lumine-stop', frames: 26, duration: 2.5, loop: false, base},
    };
    const persist = () => { try { localStorage.setItem(storageKey, JSON.stringify(settings)); } catch (_) {} };
    function finishContinuation() {
        continuation?.remove();
        if (root) delete root.dataset.continuing;
    }
    function saveNavigationFrame() {
        if (!root || !sprite?.classList.contains('is-ready') || !settings.visible) return;
        const image = decodedImages.get(displayedUrl);
        if (!image) return;
        try {
            const rect = character.getBoundingClientRect(), css = getComputedStyle(sprite), config = poses[displayedPose];
            const frame = clamp(Math.round(-parseFloat(css.backgroundPositionX) / rect.width), 0, image.naturalWidth / 256 - 1);
            const canvas = document.createElement('canvas'); canvas.width = 256; canvas.height = 320;
            canvas.getContext('2d').drawImage(image, frame * 256, 0, 256, 320, 0, 0, 256, 320);
            const elapsed = Math.max(0, Number(sprite.getAnimations()[0]?.currentTime || 0) - parseFloat(css.animationDelay) * 1000);
            sessionStorage.setItem(navigationKey, JSON.stringify({at: Date.now(), pose: displayedPose,
                image: canvas.toDataURL('image/png'), left: rect.left, top: rect.top, width: rect.width, height: rect.height,
                elapsed: config.loop ? elapsed % (config.duration * 1000) : Math.min(elapsed, config.duration * 1000 - 1)}));
        } catch (_) { /* Cross-document navigation still works without a saved frame. */ }
    }
    function sync() {
        toggles.forEach(button => {
            button.hidden = false;
            button.setAttribute('aria-pressed', String(settings.visible));
            button.title = settings.visible ? text.hide : text.show;
        });
        if (!root) return;
        root.hidden = !settings.visible;
        root.dataset.side = settings.side;
        root.dataset.paused = String(settings.paused);
        root.dataset.suspended = String(document.hidden);
        pause.textContent = settings.paused ? text.resume : text.pause;
        pause.setAttribute('aria-pressed', String(settings.paused));
        side.textContent = settings.side === 'right' ? text.left : text.right;
        placeCompanion();
        if (!settings.visible) closeMenu();
    }
    function movementBounds() {
        const area = bounds.getBoundingClientRect(), rect = character.getBoundingClientRect();
        return {left: area.left, top: area.top, right: Math.max(area.left, area.right - rect.width),
            bottom: Math.max(area.top, area.bottom - rect.height)};
    }
    const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
    function placeCompanion() {
        if (!root || root.hidden) return;
        if (settings.position) {
            const area = movementBounds();
            root.style.left = `${area.left + settings.position.x * (area.right - area.left)}px`;
            root.style.top = `${area.top + settings.position.y * (area.bottom - area.top)}px`;
            root.style.right = root.style.bottom = 'auto';
        } else {
            ['left', 'top', 'right', 'bottom'].forEach(property => root.style.removeProperty(property));
        }
        placeMenu();
    }
    function placeMenu() {
        if (!menu || menu.hidden) return;
        const rect = character.getBoundingClientRect(), area = bounds.getBoundingClientRect();
        menu.style.maxHeight = `${area.height}px`;
        const size = menu.getBoundingClientRect();
        const x = settings.side === 'left' ? rect.left : rect.right - size.width;
        const y = rect.top - size.height - 8 >= area.top ? rect.top - size.height - 8 : rect.bottom + 8;
        menu.style.left = `${clamp(x, area.left, Math.max(area.left, area.right - size.width))}px`;
        menu.style.top = `${clamp(y, area.top, Math.max(area.top, area.bottom - size.height))}px`;
    }
    function finishArrival() {
        const wasArriving = handoff?.isConnected;
        ++arrivalVersion;
        clearTimeout(handoffTimeout);
        arrival?.cancel(); arrival = null;
        if (root) delete root.dataset.arriving;
        handoff?.remove();
        if (wasArriving) settle();
    }
    async function arrive(poseReady) {
        const flight = handoff?.querySelector('#archive-startup-flight');
        if (!flight || motion.matches || settings.paused || !settings.visible || document.hidden) {
            finishArrival(); return;
        }
        root.dataset.arriving = 'true';
        const version = ++arrivalVersion;
        const destination = character.getBoundingClientRect();
        arrival = flight.animate([
            {transform: flight.style.transform},
            {transform: `translate(${destination.left}px, ${destination.top}px) scale(${destination.width / 256})`},
        ], {duration: 650, easing: 'cubic-bezier(.22, 1, .36, 1)', fill: 'forwards'});
        try {
            await arrival.finished;
            await Promise.race([poseReady, new Promise(resolve => setTimeout(resolve, 600))]);
            if (!handoff.isConnected || version !== arrivalVersion) return;
            delete root.dataset.arriving;
            arrival = flight.animate([{opacity: 1}, {opacity: 0}], {duration: 140, fill: 'forwards'});
            await arrival.finished;
        } catch (_) { /* Resizing, hiding or reduced motion can finish the handoff early. */ }
        finally { if (version === arrivalVersion) finishArrival(); }
    }
    function finishDrag(cancel = false) {
        if (!drag) return;
        const previous = drag; drag = null;
        delete root.dataset.dragging;
        if (previous.started) {
            if (cancel) { settings.position = previous.position; settings.side = previous.side; }
            else persist();
            sync();
        }
        if (character.hasPointerCapture(previous.id)) character.releasePointerCapture(previous.id);
    }
    function installDragging() {
        character.addEventListener('pointerdown', event => {
            if (event.button !== 0 || !event.isPrimary || drag) return;
            suppressClick = false;
            const rect = character.getBoundingClientRect();
            drag = {id: event.pointerId, x: event.clientX, y: event.clientY, left: rect.left, top: rect.top,
                started: false, position: settings.position, side: settings.side};
            character.setPointerCapture(event.pointerId);
        });
        character.addEventListener('pointermove', event => {
            if (!drag || drag.id !== event.pointerId) return;
            const dx = event.clientX - drag.x, dy = event.clientY - drag.y;
            if (!drag.started && Math.hypot(dx, dy) < 5) return;
            drag.started = true; suppressClick = true; root.dataset.dragging = 'true'; closeMenu();
            const area = movementBounds();
            settings.position = {
                x: clamp((drag.left + dx - area.left) / (area.right - area.left || 1), 0, 1),
                y: clamp((drag.top + dy - area.top) / (area.bottom - area.top || 1), 0, 1),
            };
            settings.side = settings.position.x < .5 ? 'left' : 'right';
            root.dataset.side = settings.side;
            placeCompanion(); event.preventDefault();
        });
        character.addEventListener('pointerup', () => finishDrag());
        character.addEventListener('pointercancel', () => finishDrag(true));
        character.addEventListener('lostpointercapture', () => finishDrag(true));
    }
    function closeMenu(restoreFocus = false) {
        if (!menu) return;
        menu.hidden = true;
        character.setAttribute('aria-expanded', 'false');
        if (restoreFocus) character.focus();
    }
    function button(label, action) {
        const node = document.createElement('button');
        node.type = 'button'; node.textContent = label; node.addEventListener('click', action);
        return node;
    }
    async function showPose() {
        if (!sprite || !settings.visible || document.hidden) return;
        const version = ++imageVersion;
        const reduced = motion.matches || settings.paused;
        // A paused companion stays at rest, even when background work is active.
        const pose = reduced ? 'idle' : state;
        const config = poses[pose];
        const frames = reduced ? 1 : config.frames;
        const url = new URL(config.name + (reduced ? '-poster' : '') + '.png', config.base).href;
        if (!images.has(url)) images.set(url, new Promise(resolve => {
            const image = new Image();
            image.onload = () => { decodedImages.set(url, image); resolve(true); };
            image.onerror = () => resolve(false); image.src = url;
        }));
        const loaded = await images.get(url);
        if (version !== imageVersion) return;
        if (!loaded) {
            // A missing optional transition must not leave the companion stuck.
            if (!config.loop) { state = pose === 'starting' ? 'running' : 'idle'; showPose(); }
            return;
        }
        if (!reduced && sprite.classList.contains('is-ready') && sprite.style.backgroundImage !== `url("${url}")`) {
            const ghost = sprite.cloneNode(false);
            ghost.className = 'companion-sprite is-ready';
            ghost.style.animation = 'none';
            ghost.style.backgroundPosition = getComputedStyle(sprite).backgroundPosition;
            character.append(ghost);
            ghost.animate([{opacity: 1}, {opacity: 0}], {duration: 160, fill: 'forwards'})
                .finished.then(() => ghost.remove(), () => ghost.remove());
        }
        sprite.classList.remove('is-animated');
        sprite.style.backgroundImage = `url("${url}")`;
        sprite.style.setProperty('--frames', frames);
        sprite.style.setProperty('--steps', config.loop ? frames : frames - 1);
        sprite.style.setProperty('--duration', `${config.duration}s`);
        sprite.style.animationIterationCount = config.loop ? 'infinite' : '1';
        sprite.style.animationFillMode = 'forwards';
        sprite.style.animationDelay = navigationFrame?.pose === pose ? `${-navigationFrame.elapsed}ms` : '0s';
        navigationFrame = null;
        // A one-shot needs a fresh timeline even when the same CSS keyframes
        // were just used by the looping pose.
        void sprite.offsetWidth;
        sprite.classList.add('is-ready');
        if (!reduced) sprite.classList.add('is-animated');
        sprite.onanimationend = config.loop ? null : () => {
            if (version !== imageVersion) return;
            state = pose === 'starting' ? (activities.size ? 'running' : 'stopping')
                : (activities.size ? 'starting' : 'idle');
            showPose();
        };
        root.dataset.state = pose;
        displayedPose = pose; displayedUrl = url;
        status.textContent = pose === 'running' || pose === 'starting' ? text.busy : text.idle;
    }
    function settle() {
        clearTimeout(activityTimer);
        if (handoff?.isConnected) return;
        if (motion.matches || settings.paused) {
            state = activities.size ? 'running' : 'idle'; showPose(); return;
        }
        if (!activities.size) {
            if (state === 'running') { state = 'stopping'; showPose(); }
            else if (state === 'idle') showPose();
            return;
        }
        // Avoid a distracting reaction to quick responses or routine polling.
        activityTimer = setTimeout(() => {
            if (state !== 'idle') return;
            state = 'starting'; showPose();
        }, 650);
    }
    const api = {
        begin() {
            const token = Symbol('archive activity'); activities.add(token); settle();
            return token;
        },
        end(token) { if (activities.delete(token)) settle(); },
    };
    window.archiveCompanion = api;
    function initialize() {
        if (initialized || initializing || !settings.visible) return;
        initializing = true;
        const style = document.createElement('link'); style.rel = 'stylesheet';
        style.href = new URL('../css/companion.css', scriptUrl).href;
        style.onload = () => {
            initialized = true; initializing = false;
            root = document.createElement('aside'); root.className = 'archive-companion'; root.hidden = true;
            if (continuation?.isConnected) root.dataset.continuing = 'true';
            root.setAttribute('aria-label', text.title);
            character = button('', event => {
                if (suppressClick && event.detail !== 0) { suppressClick = false; return; }
                menu.hidden = !menu.hidden;
                character.setAttribute('aria-expanded', String(!menu.hidden));
                placeMenu();
                if (!menu.hidden) pause.focus();
            });
            character.className = 'companion-character'; character.setAttribute('aria-label', text.open);
            character.setAttribute('aria-expanded', 'false'); character.setAttribute('aria-controls', 'companion-menu');
            sprite = document.createElement('span'); sprite.className = 'companion-sprite'; sprite.setAttribute('aria-hidden', 'true');
            character.append(sprite);
            bounds = document.createElement('div'); bounds.className = 'companion-bounds'; bounds.setAttribute('aria-hidden', 'true');
            const dragHelp = document.createElement('span'); dragHelp.id = 'companion-drag-help'; dragHelp.className = 'sr-only'; dragHelp.textContent = text.drag;
            character.setAttribute('aria-describedby', dragHelp.id);
            menu = document.createElement('div'); menu.id = 'companion-menu'; menu.className = 'companion-menu'; menu.hidden = true;
            const heading = document.createElement('strong'); heading.textContent = text.title;
            status = document.createElement('p'); status.textContent = text.help;
            pause = button(text.pause, () => {
                finishArrival(); settings.paused = !settings.paused;
                state = settings.paused ? 'idle' : activities.size ? 'starting' : 'idle';
                persist(); sync(); showPose();
            });
            side = button(text.left, () => {
                settings.side = settings.side === 'right' ? 'left' : 'right'; settings.position = null; persist(); sync();
            });
            const hide = button(text.hide, () => {
                finishArrival(); settings.visible = false; persist(); sync(); toggles[0]?.focus();
            });
            menu.append(heading, status, pause, side, hide, button(text.close, () => closeMenu(true)));
            root.append(bounds, character, menu, dragHelp); document.body.append(root);
            if (handoff?.isConnected && !motion.matches && !settings.paused) state = 'running';
            else if (navigationFrame) state = navigationFrame.pose;
            installDragging(); sync();
            arrivalPoseReady = showPose();
            if (continuation?.isConnected) {
                arrivalPoseReady.finally(() => { finishContinuation(); if (state === 'running') settle(); });
            }
            void arrive(arrivalPoseReady);
        };
        style.onerror = () => { initializing = false; style.remove(); finishArrival(); finishContinuation(); };
        document.head.append(style);
    }
    toggles.forEach(toggle => toggle.addEventListener('click', () => {
        finishArrival(); finishContinuation();
        settings.visible = !settings.visible; persist(); sync(); initialize(); showPose();
    }));
    sync();
    function schedule() {
        if (handoff?.isConnected || continuation?.isConnected) { initialize(); return; }
        if ('requestIdleCallback' in window) requestIdleCallback(initialize, {timeout: 2500});
        else setTimeout(initialize, 500);
    }
    if (handoff?.isConnected || continuation?.isConnected) schedule();
    else if (document.readyState === 'complete') schedule();
    else window.addEventListener('load', schedule, {once: true});
    document.addEventListener('keydown', event => {
        if (event.key !== 'Escape') return;
        if (drag) { finishDrag(true); return; }
        if (menu && !menu.hidden) closeMenu(true);
    });
    document.addEventListener('pointerdown', event => { if (root && !root.contains(event.target)) closeMenu(); });
    document.addEventListener('visibilitychange', () => {
        if (document.hidden) { finishDrag(true); finishArrival(); }
        sync(); if (!document.hidden) showPose();
    });
    motion.addEventListener('change', () => {
        finishArrival(); finishContinuation(); state = activities.size ? 'running' : 'idle'; showPose();
    });
    const resize = () => {
        finishDrag(true); placeCompanion();
        // Initial scrollbar/layout notifications must not discard a pending
        // handoff. If it is already flying, retarget from its current position.
        const flight = handoff?.querySelector('#archive-startup-flight');
        if (arrival && root?.dataset.arriving === 'true' && flight) {
            const rect = flight.getBoundingClientRect();
            ++arrivalVersion; arrival.cancel();
            flight.style.transform = `translate(${rect.left}px, ${rect.top}px) scale(${rect.width / 256})`;
            void arrive(arrivalPoseReady);
        }
    };
    window.addEventListener('resize', resize);
    window.visualViewport?.addEventListener('resize', resize);
    window.addEventListener('pagehide', saveNavigationFrame);
    window.addEventListener('pageshow', () => {
        clearTimeout(navigationTimer); activities.clear(); settle(); sync();
    });
    document.addEventListener('click', event => {
        const link = event.target.closest('a[href]');
        if (!link || event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey
            || link.hasAttribute('download') || (link.target && link.target !== '_self')) return;
        const url = new URL(link.href);
        if (url.origin !== location.origin || !/^https?:$/.test(url.protocol)) return;
        if (url.pathname === location.pathname && url.search === location.search) {
            if (url.pathname === archiveHome.pathname && !url.hash) event.preventDefault();
            return;
        }
        const token = api.begin();
        navigationTimer = setTimeout(() => api.end(token), 15000);
        if (url.pathname === archiveHome.pathname && !url.searchParams.has('direct')) {
            // Internal Home navigation goes straight through normal auth/MVC.
            // A fresh visit or reload of the cleaned URL still gets the intro.
            event.preventDefault();
            url.searchParams.set('direct', '1'); url.searchParams.set('companionNavigation', '1');
            saveNavigationFrame(); location.assign(url.href);
        }
    });
    document.addEventListener('submit', event => {
        const form = event.target;
        if (event.defaultPrevented || form.target === '_blank' || form.method === 'dialog') return;
        const token = api.begin();
        navigationTimer = setTimeout(() => api.end(token), 15000);
    });
})();
