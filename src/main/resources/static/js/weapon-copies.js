(() => {
    const section = document.querySelector("[data-weapon-owned-copies]");
    const tooltip = section?.querySelector("[data-weapon-copy-tooltip]");
    if (!(section instanceof HTMLElement) || !(tooltip instanceof HTMLElement)) return;

    const rangeOutput = tooltip.querySelector("[data-weapon-copy-range]");
    const expCost = tooltip.querySelector("[data-weapon-copy-exp-cost]");
    const expOutput = tooltip.querySelector("[data-weapon-copy-exp]");
    const oreOutput = tooltip.querySelector("[data-weapon-copy-ore]");
    const materialList = tooltip.querySelector("[data-weapon-copy-material-list]");
    const readyOutput = tooltip.querySelector("[data-weapon-copy-ready]");
    const numberFormatter = new Intl.NumberFormat(document.documentElement.lang || "en");
    const targetsByCopy = new WeakMap();
    const saveRevisions = new WeakMap();
    const saveTimers = new WeakMap();
    const saveQueues = new WeakMap();
    let hideTimer;

    const hideTooltip = () => {
        window.clearTimeout(hideTimer);
        tooltip.hidden = true;
    };

    const scheduleHideTooltip = () => {
        window.clearTimeout(hideTimer);
        hideTimer = window.setTimeout(hideTooltip, 180);
    };

    const keepTooltipOpen = () => window.clearTimeout(hideTimer);

    const positionTooltip = (anchor, event) => {
        const gap = 12;
        const margin = 12;
        const anchorBounds = anchor.getBoundingClientRect();
        const pointerX = event?.clientX ?? anchorBounds.right;
        const pointerY = event?.clientY ?? anchorBounds.bottom;
        const tooltipBounds = tooltip.getBoundingClientRect();
        let left = pointerX + gap;
        let top = pointerY + gap;

        if (left + tooltipBounds.width > window.innerWidth - margin) {
            left = pointerX - tooltipBounds.width - gap;
        }
        if (top + tooltipBounds.height > window.innerHeight - margin) {
            top = pointerY - tooltipBounds.height - gap;
        }
        tooltip.style.left = `${Math.max(margin, left)}px`;
        tooltip.style.top = `${Math.max(margin, top)}px`;
    };

    const materialFallback = () => {
        const fallback = document.createElement("span");
        fallback.className = "weapon-copy-tooltip-material-fallback";
        fallback.textContent = "◇";
        fallback.setAttribute("aria-hidden", "true");
        return fallback;
    };

    const materialItem = (material) => {
        const item = document.createElement("li");
        const content = document.createElement(material.href ? "a" : "span");
        content.className = "weapon-copy-tooltip-material";
        if (content instanceof HTMLAnchorElement) content.href = material.href;

        if (material.imageUrl) {
            const image = document.createElement("img");
            image.src = material.imageUrl;
            image.alt = "";
            image.addEventListener("error", () => image.replaceWith(materialFallback()));
            content.append(image);
        } else {
            content.append(materialFallback());
        }
        const name = document.createElement("span");
        name.textContent = material.name || section.dataset.unknownMaterial || "Material";
        const count = document.createElement("b");
        count.textContent = `×${numberFormatter.format(material.amount)}`;
        content.append(name, count);
        item.append(content);
        return item;
    };

    const readInitialTargets = (copy) => {
        const targets = new Map();
        copy.querySelectorAll("[data-copy-target]").forEach((target) => {
            const ascension = Number(target.dataset.copyTarget);
            targets.set(ascension, {
                ascension,
                level: Number(target.dataset.targetLevel),
                experience: Number(target.dataset.targetExperience) || 0,
                mysticEnhancementOre: Number(target.dataset.targetOre) || 0,
                materials: Array.from(target.querySelectorAll("[data-material-name]")).map(
                    (material) => ({
                        name: material.dataset.materialName || "",
                        amount: Number(material.dataset.materialCount) || 0,
                        imageUrl: material.dataset.materialImage || "",
                        href: material.dataset.materialHref || "",
                    }),
                ),
            });
        });
        targetsByCopy.set(copy, targets);
    };

    const replaceTargets = (copy, targets) => {
        targetsByCopy.set(
            copy,
            new Map(targets.map((target) => [Number(target.ascension), target])),
        );
    };

    const targetData = (copy, ascension) =>
        targetsByCopy.get(copy)?.get(Number(ascension));

    const showTooltip = (copy, button, event) => {
        const target = targetData(copy, button.dataset.targetAscension);
        if (!target) return;

        keepTooltipOpen();
        const currentLevel = Number(copy.dataset.currentLevel);
        const experience = Number(target.experience) || 0;
        const ore = Number(target.mysticEnhancementOre) || 0;
        const materials = target.materials || [];

        if (rangeOutput) {
            rangeOutput.textContent = `${section.dataset.levelLabel || "Lv."} ${currentLevel} → ${target.level}`;
        }
        if (expCost instanceof HTMLElement) expCost.hidden = experience <= 0;
        if (expOutput) {
            expOutput.textContent = `${numberFormatter.format(experience)} ${section.dataset.expLabel || "EXP"}`;
        }
        if (oreOutput) oreOutput.textContent = `×${numberFormatter.format(ore)}`;
        if (materialList instanceof HTMLElement) {
            materialList.replaceChildren(...materials.map(materialItem));
            materialList.hidden = materials.length === 0;
        }
        const targetReached = experience <= 0 && materials.length === 0;
        if (readyOutput instanceof HTMLElement) readyOutput.hidden = !targetReached;

        tooltip.hidden = false;
        positionTooltip(button, event);
    };

    const selectTarget = (copy, picker, ascension) => {
        const currentAscension = Number(copy.dataset.currentAscension);
        picker.dataset.selectedAscension = String(ascension);
        picker.querySelectorAll("[data-target-ascension]").forEach((button) => {
            const buttonAscension = Number(button.dataset.targetAscension);
            const selected = buttonAscension === ascension;
            button.classList.toggle("is-selected", selected);
            button.classList.toggle(
                "is-targeted",
                buttonAscension > currentAscension && buttonAscension <= ascension,
            );
            button.setAttribute("aria-checked", String(selected));
            button.tabIndex = selected ? 0 : -1;
        });
    };

    const setSaveState = (copy, state, message) => {
        copy.classList.remove("is-saving", "is-saved", "is-save-error");
        if (state) copy.classList.add(state);
        const output = copy.querySelector("[data-weapon-save-state]");
        if (output) output.textContent = message || "";
    };

    const applySavedCopy = (copy, saved) => {
        copy.dataset.currentLevel = String(saved.level);
        copy.dataset.currentAscension = String(saved.ascension);
        const levelInput = copy.querySelector("[data-weapon-level-input]");
        if (levelInput instanceof HTMLInputElement) levelInput.value = String(saved.level);
        const levelOutput = copy.querySelector("[data-current-level-output]");
        if (levelOutput) {
            levelOutput.textContent = `${section.dataset.levelLabel || "Lv."} ${saved.level}`;
        }
        replaceTargets(copy, saved.targets || []);
        const picker = copy.querySelector(".weapon-copy-level-picker");
        if (picker instanceof HTMLElement) {
            picker.querySelectorAll("[data-target-ascension]").forEach((button) => {
                button.classList.toggle(
                    "is-reached",
                    Number(button.dataset.targetAscension) <= Number(saved.ascension),
                );
            });
            selectTarget(copy, picker, Number(saved.ascension));
        }
    };

    const persistLevel = async (copy, level, ascension, revision) => {
        const saveUrl = copy.dataset.saveUrl;
        const input = copy.querySelector("[data-weapon-level-input]");
        if (!saveUrl || !(input instanceof HTMLInputElement)) return;

        try {
            const headers = {
                Accept: "application/json",
                "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
            };
            if (section.dataset.csrfHeader && section.dataset.csrfToken) {
                headers[section.dataset.csrfHeader] = section.dataset.csrfToken;
            }
            const body = new URLSearchParams({level: String(level)});
            if (Number.isInteger(ascension)) body.set("ascension", String(ascension));
            const response = await fetch(saveUrl, {
                method: "POST",
                headers,
                body,
            });
            if (!response.ok) throw new Error(`Weapon level save failed with ${response.status}`);
            const saved = await response.json();
            if (saveRevisions.get(copy) !== revision) return;
            applySavedCopy(copy, saved);
            setSaveState(copy, "is-saved", section.dataset.savedLabel || "Saved");
            window.setTimeout(() => {
                if (saveRevisions.get(copy) === revision) setSaveState(copy, "", "");
            }, 1_100);
        } catch (error) {
            if (saveRevisions.get(copy) !== revision) return;
            input.value = copy.dataset.currentLevel;
            const picker = copy.querySelector(".weapon-copy-level-picker");
            if (picker instanceof HTMLElement) {
                selectTarget(copy, picker, Number(copy.dataset.currentAscension));
            }
            setSaveState(copy, "is-save-error", section.dataset.saveErrorLabel || "Could not save");
            console.error(error);
        }
    };

    const saveLevel = (copy, requestedLevel, requestedAscension = null) => {
        const input = copy.querySelector("[data-weapon-level-input]");
        if (!(input instanceof HTMLInputElement) || !copy.dataset.saveUrl) {
            return Promise.resolve();
        }
        const maxLevel = Number(input.max) || 90;
        const level = Math.min(maxLevel, Math.max(1, Number(requestedLevel) || 1));
        input.value = String(level);
        const revision = (saveRevisions.get(copy) || 0) + 1;
        const ascension = Number.isInteger(requestedAscension)
            ? requestedAscension
            : null;
        saveRevisions.set(copy, revision);
        setSaveState(copy, "is-saving", section.dataset.savingLabel || "Saving…");

        const previous = saveQueues.get(copy) || Promise.resolve();
        const queued = previous
            .catch(() => undefined)
            .then(() => persistLevel(copy, level, ascension, revision));
        saveQueues.set(copy, queued);
        return queued;
    };

    section.querySelectorAll(".weapon-owned-copy").forEach((copy) => {
        const picker = copy.querySelector(".weapon-copy-level-picker");
        const levelInput = copy.querySelector("[data-weapon-level-input]");
        if (!(copy instanceof HTMLElement) || !(picker instanceof HTMLElement)) return;
        readInitialTargets(copy);
        const buttons = Array.from(picker.querySelectorAll("[data-target-ascension]"));
        selectTarget(copy, picker, Number(picker.dataset.selectedAscension));

        buttons.forEach((button) => {
            const ascension = Number(button.dataset.targetAscension);
            button.addEventListener("mouseenter", (event) => showTooltip(copy, button, event));
            button.addEventListener("mousemove", (event) => {
                if (!tooltip.hidden) positionTooltip(button, event);
            });
            button.addEventListener("mouseleave", scheduleHideTooltip);
            button.addEventListener("focus", () => showTooltip(copy, button));
            button.addEventListener("blur", scheduleHideTooltip);
            button.addEventListener("click", () => {
                const target = targetData(copy, ascension);
                if (!target) return;
                selectTarget(copy, picker, ascension);
                hideTooltip();
                void saveLevel(copy, target.level, ascension);
            });
        });

        picker.addEventListener("keydown", (event) => {
            if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
            event.preventDefault();
            const selected = Number(picker.dataset.selectedAscension);
            const last = buttons.length - 1;
            const next = event.key === "Home"
                ? 0
                : event.key === "End"
                    ? last
                    : Math.min(last, Math.max(0, selected + (event.key === "ArrowLeft" ? -1 : 1)));
            buttons[next]?.click();
            buttons[next]?.focus();
        });

        if (levelInput instanceof HTMLInputElement) {
            levelInput.addEventListener("input", () => {
                window.clearTimeout(saveTimers.get(copy));
                saveTimers.set(
                    copy,
                    window.setTimeout(() => void saveLevel(copy, levelInput.value), 320),
                );
            });
            levelInput.addEventListener("keydown", (event) => {
                if (event.key !== "Enter") return;
                event.preventDefault();
                window.clearTimeout(saveTimers.get(copy));
                void saveLevel(copy, levelInput.value);
                levelInput.blur();
            });
        }
    });

    tooltip.addEventListener("mouseenter", keepTooltipOpen);
    tooltip.addEventListener("mouseleave", scheduleHideTooltip);
    window.addEventListener("scroll", hideTooltip, {passive: true});
    window.addEventListener("resize", hideTooltip);
})();
