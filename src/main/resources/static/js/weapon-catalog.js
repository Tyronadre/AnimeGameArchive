const initializeWeaponCatalog = () => {
    const catalog = document.querySelector("[data-weapon-catalog]");
    if (!catalog) return;

    const cards = Array.from(catalog.querySelectorAll("[data-weapon-card]"));
    const search = catalog.querySelector("[data-weapon-search]");
    const clearSearch = catalog.querySelector("[data-weapon-search-clear]");
    const resultCount = catalog.querySelector("[data-weapon-result-count]");
    const emptyState = catalog.querySelector("[data-weapon-empty]");
    const typeButtons = Array.from(catalog.querySelectorAll("[data-type-filter]"));
    const ownershipButtons = Array.from(
        catalog.querySelectorAll("[data-ownership-filter]"),
    );
    if (!search || !clearSearch || !resultCount || !emptyState || !cards.length) return;

    const storageKey = "genshinapp.weaponCatalog.filters";
    const typeValues = new Set(typeButtons.map((button) => button.dataset.typeFilter));
    const ownershipValues = new Set(
        ownershipButtons.map((button) => button.dataset.ownershipFilter),
    );
    const normalize = (value) => (value || "").trim().toLocaleLowerCase();
    const validatedState = (candidate = {}) => ({
        type: typeValues.has(candidate.type) ? candidate.type : "all",
        ownership: ownershipValues.has(candidate.ownership) ? candidate.ownership : "all",
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
    const hasUrlState = ["type", "ownership", "query"].some((key) =>
        urlParameters.has(key),
    );
    const state = validatedState(hasUrlState ? {
        type: urlParameters.get("type") || "all",
        ownership: urlParameters.get("ownership") || "all",
        query: urlParameters.get("query") || "",
    } : storedState);

    const persistState = () => {
        try {
            sessionStorage.setItem(storageKey, JSON.stringify(state));
        } catch (_) {
            // URL state still preserves the filters if session storage is unavailable.
        }

        const url = new URL(window.location.href);
        [["type", state.type, "all"], ["ownership", state.ownership, "all"]]
            .forEach(([key, value, defaultValue]) => {
                if (value === defaultValue) url.searchParams.delete(key);
                else url.searchParams.set(key, value);
            });
        const query = state.query.trim();
        if (query) url.searchParams.set("query", query);
        else url.searchParams.delete("query");
        window.history.replaceState(window.history.state, "", url);
    };

    const setActive = (buttons, activeButton) => {
        buttons.forEach((button) => {
            const active = button === activeButton;
            button.classList.toggle("is-active", active);
            button.setAttribute("aria-pressed", String(active));
        });
    };

    const applyFilters = () => {
        let visible = 0;
        cards.forEach((card) => {
            const typeMatches = state.type === "all" || card.dataset.type === state.type;
            const ownershipMatches = state.ownership === "all" ||
                (state.ownership === "owned" && card.dataset.owned === "true") ||
                (state.ownership === "unowned" && card.dataset.owned === "false");
            const normalizedQuery = normalize(state.query);
            const searchMatches = !normalizedQuery ||
                normalize(card.dataset.search).includes(normalizedQuery);
            const matches = typeMatches && ownershipMatches && searchMatches;
            card.hidden = !matches;
            if (matches) visible += 1;
        });

        const noun = visible === 1
            ? catalog.dataset.resultSingular
            : catalog.dataset.resultPlural;
        resultCount.textContent = `${visible} ${noun}`;
        emptyState.hidden = visible !== 0;
        clearSearch.hidden = !search.value;
        persistState();
    };

    typeButtons.forEach((button) => {
        button.addEventListener("click", () => {
            state.type = button.dataset.typeFilter;
            setActive(typeButtons, button);
            applyFilters();
        });
    });

    ownershipButtons.forEach((button) => {
        button.addEventListener("click", () => {
            state.ownership = button.dataset.ownershipFilter;
            setActive(ownershipButtons, button);
            applyFilters();
        });
    });

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

    search.value = state.query;
    setActive(
        typeButtons,
        typeButtons.find((button) => button.dataset.typeFilter === state.type),
    );
    setActive(
        ownershipButtons,
        ownershipButtons.find((button) => button.dataset.ownershipFilter === state.ownership),
    );

    cards.forEach((card) => {
        const alignPopover = () => {
            const popover = card.querySelector(".weapon-copy-popover");
            if (!popover) return;
            popover.classList.remove("align-left", "align-right");
            const cardBounds = card.getBoundingClientRect();
            const popoverWidth = Math.min(300, window.innerWidth - 24);
            if (cardBounds.left + cardBounds.width / 2 - popoverWidth / 2 < 12) {
                popover.classList.add("align-left");
            } else if (cardBounds.right - cardBounds.width / 2 + popoverWidth / 2 >
                window.innerWidth - 12) {
                popover.classList.add("align-right");
            }
        };
        card.addEventListener("pointerenter", alignPopover);
        card.addEventListener("focusin", alignPopover);
    });

    applyFilters();
};

if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", initializeWeaponCatalog, {once: true});
} else {
    initializeWeaponCatalog();
}
