const initializeCharacterCatalog = () => {
    const catalog = document.querySelector("[data-character-catalog]");
    if (!catalog) return;

    const form = catalog.querySelector("[data-character-filter-form]");
    const search = catalog.querySelector("[data-character-search]");
    const clearSearch = catalog.querySelector("[data-character-search-clear]");
    const resetFilters = catalog.querySelector("[data-character-filter-reset]");
    const resultCount = catalog.querySelector("[data-character-result-count]");
    const emptyState = catalog.querySelector("[data-character-empty]");
    const cards = Array.from(catalog.querySelectorAll("[data-character-card]"));
    const elementButtons = Array.from(catalog.querySelectorAll("[data-element-filter]"));
    const weaponButtons = Array.from(catalog.querySelectorAll("[data-weapon-filter]"));
    const ownershipButtons = Array.from(catalog.querySelectorAll("[data-ownership-filter]"));
    const rarityButtons = Array.from(catalog.querySelectorAll("[data-rarity-filter]"));
    if (!form || !search || !clearSearch || !resetFilters || !resultCount || !emptyState) return;

    // Keep the original footprint so docking never changes the scroll threshold.
    const filterSlot = document.createElement("div");
    filterSlot.style.display = "flow-root";
    form.before(filterSlot);
    filterSlot.append(form);
    const desktopFilters = window.matchMedia("(min-width: 2080px)");
    const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
    let docked = false;
    let dockAnimation;
    const updateDock = () => {
        const slot = filterSlot.getBoundingClientRect();
        const shouldDock = desktopFilters.matches && slot.top < (docked ? 96 : 80);
        if (shouldDock === docked) return;
        const before = form.getBoundingClientRect();
        dockAnimation?.cancel();
        if (shouldDock) filterSlot.style.height = `${filterSlot.offsetHeight}px`;
        docked = shouldDock;
        catalog.classList.toggle("has-docked-filters", docked);
        if (!docked) filterSlot.style.height = "";
        const after = form.getBoundingClientRect();
        if (!reducedMotion.matches && form.animate) {
            dockAnimation = form.animate([
                {transform: `translate(${before.left - after.left}px, ${before.top - after.top}px) scale(${before.width / after.width}, ${before.height / after.height})`},
                {transform: "none"},
            ], {duration: 420, easing: "cubic-bezier(0.22, 1, 0.36, 1)"});
        }
    };
    let dockFrame;
    const scheduleDock = () => {
        if (dockFrame) return;
        dockFrame = requestAnimationFrame(() => {
            dockFrame = null;
            updateDock();
        });
    };
    window.addEventListener("scroll", scheduleDock, {passive: true});
    window.addEventListener("resize", scheduleDock);
    scheduleDock();

    const storageKey = "genshinapp.characterCatalog.filters";
    const allowedValues = (buttons, dataKey) =>
        new Set(buttons.map((button) => button.dataset[dataKey]));
    const elementValues = allowedValues(elementButtons, "elementFilter");
    const weaponValues = allowedValues(weaponButtons, "weaponFilter");
    const ownershipValues = allowedValues(ownershipButtons, "ownershipFilter");
    const rarityValues = allowedValues(rarityButtons, "rarityFilter");
    const normalize = (value) => (value || "").trim().toLocaleLowerCase();
    const validValue = (value, allowed) => allowed.has(value) ? value : "all";
    const validatedState = (candidate = {}) => ({
        element: validValue(candidate.element, elementValues),
        weapon: validValue(candidate.weapon, weaponValues),
        ownership: validValue(candidate.ownership, ownershipValues),
        rarity: validValue(candidate.rarity, rarityValues),
        query: typeof candidate.query === "string" ? candidate.query : "",
    });
    const storedState = (() => {
        try {
            return JSON.parse(sessionStorage.getItem(storageKey) || "{}");
        } catch (_) {
            return {};
        }
    })();
    const urlParameters = new URLSearchParams(window.location.search);
    const stateKeys = ["element", "weapon", "ownership", "rarity", "query"];
    const hasUrlState = stateKeys.some((key) => urlParameters.has(key));
    const state = validatedState(hasUrlState ? {
        element: urlParameters.get("element") || "all",
        weapon: urlParameters.get("weapon") || "all",
        ownership: urlParameters.get("ownership") || "all",
        rarity: urlParameters.get("rarity") || "all",
        query: urlParameters.get("query") || "",
    } : {
        ...storedState,
        query: typeof storedState.query === "string" ? storedState.query : search.value,
    });

    const isDefaultState = () =>
        state.element === "all" &&
        state.weapon === "all" &&
        state.ownership === "all" &&
        state.rarity === "all" &&
        !state.query.trim();

    const persistState = () => {
        try {
            sessionStorage.setItem(storageKey, JSON.stringify(state));
        } catch (_) {
            // The URL still preserves the selected filters when storage is unavailable.
        }

        const url = new URL(window.location.href);
        [
            ["element", state.element],
            ["weapon", state.weapon],
            ["ownership", state.ownership],
            ["rarity", state.rarity],
        ].forEach(([key, value]) => {
            if (value === "all") url.searchParams.delete(key);
            else url.searchParams.set(key, value);
        });
        const query = state.query.trim();
        if (query) url.searchParams.set("query", query);
        else url.searchParams.delete("query");
        window.history.replaceState(window.history.state, "", url);
    };

    const setActive = (buttons, dataKey, value) => {
        buttons.forEach((button) => {
            const active = button.dataset[dataKey] === value;
            button.classList.toggle("is-active", active);
            button.setAttribute("aria-pressed", String(active));
        });
    };

    const updateActiveControls = () => {
        setActive(elementButtons, "elementFilter", state.element);
        setActive(weaponButtons, "weaponFilter", state.weapon);
        setActive(ownershipButtons, "ownershipFilter", state.ownership);
        setActive(rarityButtons, "rarityFilter", state.rarity);
    };

    const applyFilters = () => {
        const normalizedQuery = normalize(state.query);
        let visible = 0;
        cards.forEach((card) => {
            const matches =
                (state.element === "all" || card.dataset.element === state.element) &&
                (state.weapon === "all" || card.dataset.weapon === state.weapon) &&
                (state.ownership === "all" ||
                    (state.ownership === "owned" && card.dataset.owned === "true") ||
                    (state.ownership === "unowned" && card.dataset.owned === "false")) &&
                (state.rarity === "all" || card.dataset.rarity === state.rarity) &&
                (!normalizedQuery || normalize(card.dataset.search).includes(normalizedQuery));
            const cardShell = card.closest("[data-character-card-shell]");
            if (cardShell) cardShell.hidden = !matches;
            else card.hidden = !matches;
            if (matches) visible += 1;
        });

        resultCount.textContent = (catalog.dataset.resultTemplate || "RESULT_COUNT results")
            .replace("RESULT_COUNT", String(visible));
        emptyState.hidden = visible !== 0;
        clearSearch.hidden = !search.value;
        resetFilters.hidden = isDefaultState();
        persistState();
    };

    const bindFilterButtons = (buttons, dataKey, stateKey) => {
        buttons.forEach((button) => {
            button.addEventListener("click", () => {
                state[stateKey] = button.dataset[dataKey];
                updateActiveControls();
                applyFilters();
            });
        });
    };

    bindFilterButtons(elementButtons, "elementFilter", "element");
    bindFilterButtons(weaponButtons, "weaponFilter", "weapon");
    bindFilterButtons(ownershipButtons, "ownershipFilter", "ownership");
    bindFilterButtons(rarityButtons, "rarityFilter", "rarity");

    const updateSearch = () => {
        state.query = search.value;
        applyFilters();
    };
    search.addEventListener("input", updateSearch);
    search.addEventListener("search", updateSearch);
    search.addEventListener("keydown", (event) => {
        if (event.key === "Escape" && search.value) {
            search.value = "";
            state.query = "";
            applyFilters();
        }
    });
    clearSearch.addEventListener("click", () => {
        search.value = "";
        state.query = "";
        search.focus();
        applyFilters();
    });
    resetFilters.addEventListener("click", () => {
        Object.assign(state, {
            element: "all",
            weapon: "all",
            ownership: "all",
            rarity: "all",
            query: "",
        });
        search.value = "";
        updateActiveControls();
        applyFilters();
        search.focus();
    });
    form.addEventListener("submit", (event) => {
        event.preventDefault();
        state.query = search.value;
        applyFilters();
    });

    search.value = state.query;
    updateActiveControls();
    applyFilters();
};

if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", initializeCharacterCatalog, {once: true});
} else {
    initializeCharacterCatalog();
}
