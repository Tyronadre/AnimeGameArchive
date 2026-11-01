(() => {
    "use strict";

    const initialUrl = new URL(window.location.href);
    const languageCookie = document.cookie.split(";").map(part => part.trim())
        .find(part => part.startsWith("genshin-language="))?.split("=").slice(1).join("=");
    const language = initialUrl.searchParams.get("lang") || languageCookie || "en";
    const messages = language.startsWith("de") ? {
        title: "Dein Archiv wird geöffnet · Genshin Archive",
        heading: "Noch ein kleines Stück…",
        copy: "Lumine läuft voraus, während dein Archiv bereit gemacht wird.",
        loading: "Dein Dashboard wird geöffnet…",
        failure: "Das Dashboard konnte nicht geladen werden. Bitte versuche es erneut.",
        timeout: "Das dauert länger als erwartet. Du kannst es erneut versuchen oder das Dashboard direkt öffnen.",
        retry: "Erneut versuchen", direct: "Dashboard direkt öffnen", image: "Lumine läuft auf der Stelle",
    } : {
        title: "Opening your archive · Genshin Archive",
        heading: "A little further…",
        copy: "Lumine is keeping pace while your archive gets ready.",
        loading: "Opening your dashboard…",
        failure: "The dashboard could not be loaded. Please try again.",
        timeout: "This is taking longer than expected. You can retry or open the dashboard directly.",
        retry: "Try again", direct: "Open dashboard directly", image: "Lumine running in place",
    };

    document.documentElement.lang = language.startsWith("de") ? "de" : "en";
    document.title = messages.title;
    document.getElementById("heading").textContent = messages.heading;
    document.getElementById("copy").textContent = messages.copy;
    document.querySelector(".runner").setAttribute("aria-label", messages.image);
    const status = document.getElementById("status");
    const actions = document.getElementById("actions");
    const retry = document.getElementById("retry");
    const direct = document.getElementById("direct");
    const directUrl = new URL(initialUrl);
    directUrl.searchParams.set("direct", "1");
    direct.href = directUrl.href;
    direct.textContent = messages.direct;
    retry.textContent = messages.retry;
    let pending = false;

    function withCompanionHandoff(markup) {
        // The overlay belongs to the new document, so normal document parsing,
        // authentication redirects and script execution remain unchanged.
        if (!markup.includes('data-companion-toggle') || matchMedia('(prefers-reduced-motion: reduce)').matches
            || document.hidden) return markup;
        try {
            const saved = JSON.parse(localStorage.getItem('genshin-archive.companion.v1') || 'null');
            if (saved?.visible === false || saved?.paused === true) return markup;
        } catch (_) { /* Use the same defaults as the companion. */ }
        const runner = document.querySelector('.runner');
        const rect = runner.getBoundingClientRect();
        const phase = Number(runner.getAnimations()[0]?.currentTime || 0) % 600;
        const handoff = document.createElement('div');
        handoff.id = 'archive-startup-handoff';
        handoff.setAttribute('aria-hidden', 'true');
        handoff.innerHTML = `<style>
            #archive-startup-handoff{position:fixed;inset:0;z-index:81;pointer-events:none;animation:archive-handoff-expire .15s 15s forwards}
            #archive-startup-shade{position:absolute;inset:0;background:#1d192d;animation:archive-handoff-reveal .3s ease-out forwards}
            #archive-startup-flight{position:absolute;left:0;top:0;width:256px;height:320px;transform-origin:0 0}
            #archive-startup-flight-sprite{width:100%;height:100%;background-repeat:no-repeat;background-size:4608px 320px;animation:archive-handoff-sprint .6s steps(18,end) infinite}
            @keyframes archive-handoff-sprint{to{background-position-x:-4608px}}
            @keyframes archive-handoff-reveal{to{opacity:0}}
            @keyframes archive-handoff-expire{to{opacity:0;visibility:hidden}}
            @media(prefers-reduced-motion:reduce){#archive-startup-handoff{display:none}}
        </style><div id="archive-startup-shade"></div><div id="archive-startup-flight"><div id="archive-startup-flight-sprite"></div></div>`;
        handoff.querySelector('#archive-startup-flight').style.transform =
            `translate(${rect.left}px, ${rect.top}px) scale(${rect.width / 256})`;
        const sprite = handoff.querySelector('#archive-startup-flight-sprite');
        sprite.style.backgroundImage = getComputedStyle(runner).backgroundImage;
        sprite.style.animationDelay = `${-phase}ms`;
        // Put the marker before the header script, including on warm-cache
        // parser execution. The real dashboard has scripts in its header.
        return markup.replace(/<body\b[^>]*>/i, opening => opening + handoff.outerHTML);
    }

    async function preloadStyles(markup, responseUrl, signal) {
        const parsed = new DOMParser().parseFromString(markup, "text/html");
        const links = [...parsed.querySelectorAll('link[rel="stylesheet"][href]')];
        await Promise.all(links.map(style => new Promise(resolve => {
            const url = new URL(style.getAttribute("href"), responseUrl);
            if (url.origin !== initialUrl.origin) return resolve();
            const link = document.createElement("link");
            const done = () => { clearTimeout(timer); resolve(); };
            const timer = setTimeout(done, 3000);
            link.rel = "preload";
            link.as = "style";
            link.href = url.href;
            link.onload = link.onerror = done;
            signal.addEventListener("abort", done, {once: true});
            document.head.append(link);
        })));
    }

    async function openDashboard() {
        if (pending) return;
        pending = true;
        actions.hidden = true;
        document.body.dataset.state = "loading";
        status.textContent = messages.loading;
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 45000);
        try {
            // Let the small first document paint before preparing the real page.
            // Background tabs must still be able to finish loading.
            await new Promise(resolve => document.hidden ? setTimeout(resolve, 0)
                : requestAnimationFrame(() => requestAnimationFrame(resolve)));
            const response = await fetch(initialUrl.href, {
                credentials: "same-origin", cache: "no-store", signal: controller.signal,
                headers: {"Accept": "text/html", "X-Archive-Ready": "true"},
            });
            if (!response.ok || !response.headers.get("Content-Type")?.includes("text/html")) {
                throw new Error("Dashboard unavailable");
            }
            const destination = new URL(response.url);
            if (destination.origin !== initialUrl.origin) throw new Error("Unexpected dashboard origin");
            const markup = await response.text();
            if (!/<html[\s>]/i.test(markup)) throw new Error("Incomplete dashboard document");
            await preloadStyles(markup, destination, controller.signal);
            if (controller.signal.aborted) throw new DOMException("Timed out", "AbortError");
            clearTimeout(timeout);
            if (destination.pathname === initialUrl.pathname) destination.hash = initialUrl.hash;
            // Preserve redirects (including login) and parse the actual server document
            // normally, so its scripts, forms, CSRF tokens, and page events work once.
            history.replaceState(history.state, "", destination.href);
            const nextDocument = withCompanionHandoff(markup);
            document.open();
            document.write(nextDocument);
            document.close();
        } catch (error) {
            clearTimeout(timeout);
            pending = false;
            document.body.dataset.state = "error";
            status.textContent = error.name === "AbortError" ? messages.timeout : messages.failure;
            actions.hidden = false;
        }
    }

    retry.addEventListener("click", openDashboard);
    void openDashboard();
})();
