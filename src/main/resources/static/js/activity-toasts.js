(() => {
    const header = document.querySelector("[data-activity-toasts]");
    if (!header || window.archiveActivityToasts) return;
    window.archiveActivityToasts = true;

    const apiUrl = header.dataset.activityUrl || "/api/dashboard/recent-activity";
    const account = header.dataset.toastAccount || "default";
    const storageKey = `genshin-archive.activity-toasts.v1:${encodeURIComponent(account)}`;
    const maxRemembered = 120;
    const maxVisible = 3;
    const visibleDuration = 6500;
    const container = document.createElement("div");
    container.className = "activity-toast-stack";
    container.setAttribute("aria-live", "polite");
    container.setAttribute("aria-relevant", "additions");
    container.setAttribute("aria-label", "Recent game updates");
    document.body.append(container);

    const readSeen = () => {
        try {
            const raw = localStorage.getItem(storageKey);
            return raw === null ? null : new Set(JSON.parse(raw));
        } catch (_) {
            return null;
        }
    };
    let seen = readSeen();
    let initialized = seen !== null;
    let polling = false;

    const writeSeen = () => {
        try {
            seen = new Set([...seen].slice(-maxRemembered));
            localStorage.setItem(storageKey, JSON.stringify([...seen]));
        } catch (_) { /* Toasts still work for this page if storage is unavailable. */ }
    };

    const closeToast = toast => {
        toast.classList.add("is-leaving");
        window.setTimeout(() => toast.remove(), 220);
    };

    const showToast = activity => {
        const toast = document.createElement("article");
        toast.className = `activity-toast tone-${activity.tone || "gain"}`;
        toast.setAttribute("role", "status");

        const mark = document.createElement("span");
        mark.className = "activity-toast-mark";
        if (activity.materialImageUrl) {
            const image = document.createElement("img");
            image.src = activity.materialImageUrl;
            image.alt = "";
            image.addEventListener("error", () => image.remove(), {once: true});
            mark.append(image);
        } else {
            mark.textContent = activity.icon || "✦";
        }

        const copy = document.createElement("div");
        copy.className = "activity-toast-copy";
        const heading = document.createElement(activity.materialHref ? "a" : "strong");
        heading.className = "activity-toast-title";
        heading.textContent = activity.title || "Game update";
        if (activity.materialHref) heading.href = activity.materialHref;
        const detail = document.createElement("span");
        detail.className = "activity-toast-detail";
        detail.textContent = activity.detail || "";
        copy.append(heading, detail);

        const close = document.createElement("button");
        close.className = "activity-toast-close";
        close.type = "button";
        close.setAttribute("aria-label", "Dismiss update");
        close.textContent = "×";
        close.addEventListener("click", () => closeToast(toast), {once: true});

        toast.append(mark, copy, close);
        container.append(toast);
        while (container.children.length > maxVisible) container.firstElementChild.remove();
        window.setTimeout(() => {
            if (toast.isConnected) closeToast(toast);
        }, visibleDuration);
    };

    const poll = async () => {
        if (polling || document.hidden) return;
        polling = true;
        try {
            const response = await fetch(apiUrl, {
                headers: {Accept: "application/json"},
                cache: "no-store"
            });
            if (!response.ok) return;
            const payload = await response.json();
            const activities = Array.isArray(payload) ? payload : payload.activities || [];
            if (!Array.isArray(activities)) return;

            if (!initialized) {
                activities.forEach(activity => seen.add(String(activity.id || "")));
                seen.delete("");
                initialized = true;
                writeSeen();
                return;
            }

            const fresh = activities.filter(activity => {
                const id = String(activity.id || "");
                return id && !seen.has(id);
            });
            activities.forEach(activity => {
                const id = String(activity.id || "");
                if (id) seen.add(id);
            });
            writeSeen();
            fresh.slice(0, maxVisible).reverse().forEach(showToast);
        } catch (_) {
            // Retry on the next poll when the local backend is reachable again.
        } finally {
            polling = false;
        }
    };

    poll();
    window.setInterval(poll, 5000);
    document.addEventListener("visibilitychange", () => {
        if (!document.hidden) poll();
    });
})();
