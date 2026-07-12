(() => {
    const storageKey = "genshin-archive-theme";
    const root = document.documentElement;
    const systemTheme = window.matchMedia("(prefers-color-scheme: dark)");

    const readStoredTheme = () => {
        try {
            const storedTheme = window.localStorage.getItem(storageKey);
            return storedTheme === "light" || storedTheme === "dark" ? storedTheme : null;
        } catch {
            return null;
        }
    };

    const updateButtons = (theme) => {
        document.querySelectorAll("[data-theme-toggle]").forEach((button) => {
            const switchesToDark = theme === "light";
            const label = switchesToDark
                ? button.dataset.themeDarkLabel
                : button.dataset.themeLightLabel;

            button.setAttribute("aria-pressed", String(theme === "dark"));
            button.setAttribute("aria-label", label);
            button.setAttribute("title", label);
        });
    };

    const applyTheme = (theme, persist = false) => {
        root.dataset.theme = theme;
        root.style.colorScheme = theme;
        updateButtons(theme);

        if (persist) {
            try {
                window.localStorage.setItem(storageKey, theme);
            } catch {
                // The selected theme still applies for the current page.
            }
        }
    };

    applyTheme(readStoredTheme() ?? (systemTheme.matches ? "dark" : "light"));

    const connectThemeControls = () => {
        updateButtons(root.dataset.theme);
        document.querySelectorAll("[data-theme-toggle]").forEach((button) => {
            button.addEventListener("click", () => {
                applyTheme(root.dataset.theme === "dark" ? "light" : "dark", true);
            });
        });
    };

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", connectThemeControls, {once: true});
    } else {
        connectThemeControls();
    }

    systemTheme.addEventListener("change", (event) => {
        if (readStoredTheme() === null) {
            applyTheme(event.matches ? "dark" : "light");
        }
    });
})();
