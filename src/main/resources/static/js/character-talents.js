(() => {
    const section = document.querySelector("[data-talent-section]");
    const progressForm = document.querySelector("#character-progress-form");
    const tooltip = section?.querySelector("[data-talent-material-tooltip]");
    if (!section || !progressForm || !(tooltip instanceof HTMLElement)) return;

    const tooltipRange = tooltip.querySelector("[data-talent-cost-range]");
    const tooltipList = tooltip.querySelector("[data-talent-cost-list]");
    const saveUrl = section.dataset.talentSaveUrl;
    const numberFormatter = new Intl.NumberFormat(document.documentElement.lang || "en");
    const costsByLevel = new Map();
    let saveTimer;
    let saveRevision = 0;
    let saveQueue = Promise.resolve();

    section.querySelectorAll("[data-talent-cost-level]").forEach((levelCosts) => {
        const level = Number(levelCosts.dataset.talentCostLevel);
        const materials = Array.from(levelCosts.querySelectorAll("[data-material-id]")).map(
            (material) => ({
                id: material.dataset.materialId,
                name: material.dataset.materialName,
                count: Number(material.dataset.materialCount),
            }),
        );
        costsByLevel.set(level, materials);
    });

    const clampLevel = (level) => Math.min(10, Math.max(1, Number(level) || 1));

    const showScalingLevel = (picker, level) => {
        const disclosure = picker.closest(".talent-disclosure");
        if (!disclosure) return;

        disclosure.querySelectorAll("[data-scaling-level]").forEach((value) => {
            value.hidden = Number(value.dataset.scalingLevel) !== level;
        });
        const levelOutput = disclosure.querySelector("[data-talent-level-output]");
        if (levelOutput) levelOutput.textContent = String(level);
    };

    const updateDots = (picker, selectedLevel) => {
        picker.querySelectorAll("[data-level]").forEach((dot) => {
            const level = Number(dot.dataset.level);
            dot.classList.toggle("is-reached", level <= selectedLevel);
            dot.classList.toggle("is-selected", level === selectedLevel);
            dot.setAttribute("aria-checked", String(level === selectedLevel));
            dot.tabIndex = level === selectedLevel ? 0 : -1;
        });
    };

    const syncProgress = (picker, level) => {
        const progressField = picker.dataset.progressField;
        const progressInput = progressForm.elements.namedItem(progressField);
        if (!(progressInput instanceof HTMLInputElement)) return;

        progressInput.value = String(level);
        const targetField = `target${progressField.charAt(0).toUpperCase()}${progressField.slice(1)}`;
        const targetInput = progressForm.elements.namedItem(targetField);
        if (targetInput instanceof HTMLInputElement && Number(targetInput.value) < level) {
            targetInput.value = String(level);
        }
    };

    const selectLevel = (picker, requestedLevel, shouldSyncProgress) => {
        const level = clampLevel(requestedLevel);
        picker.dataset.selectedLevel = String(level);
        updateDots(picker, level);
        showScalingLevel(picker, level);
        if (shouldSyncProgress) syncProgress(picker, level);
    };

    const aggregateMaterials = (selectedLevel, hoveredLevel) => {
        const totals = new Map();
        for (let level = selectedLevel + 1; level <= hoveredLevel; level += 1) {
            (costsByLevel.get(level) || []).forEach((material) => {
                const existing = totals.get(material.id);
                if (existing) {
                    existing.count += material.count;
                } else {
                    totals.set(material.id, {...material});
                }
            });
        }
        return Array.from(totals.values());
    };

    const positionTooltip = (event) => {
        const gap = 16;
        const margin = 12;
        const bounds = tooltip.getBoundingClientRect();
        let left = event.clientX + gap;
        let top = event.clientY + gap;

        if (left + bounds.width > window.innerWidth - margin) {
            left = event.clientX - bounds.width - gap;
        }
        if (top + bounds.height > window.innerHeight - margin) {
            top = event.clientY - bounds.height - gap;
        }
        tooltip.style.left = `${Math.max(margin, left)}px`;
        tooltip.style.top = `${Math.max(margin, top)}px`;
    };

    const hideTooltip = () => {
        tooltip.hidden = true;
    };

    const showMaterialTooltip = (picker, hoveredLevel, event) => {
        const selectedLevel = Number(picker.dataset.selectedLevel);
        if (hoveredLevel <= selectedLevel) {
            hideTooltip();
            return;
        }

        const materials = aggregateMaterials(selectedLevel, hoveredLevel);
        if (materials.length === 0 || !(tooltipList instanceof HTMLElement)) {
            hideTooltip();
            return;
        }

        if (tooltipRange) tooltipRange.textContent = `Lv. ${selectedLevel} - ${hoveredLevel}`;
        tooltipList.replaceChildren(
            ...materials.map((material) => {
                const item = document.createElement("li");
                const image = document.createElement("img");
                image.src = `/media/materials/${encodeURIComponent(material.id)}`;
                image.alt = "";
                image.addEventListener("error", () => image.remove());
                const name = document.createElement("span");
                name.textContent = material.name;
                const count = document.createElement("b");
                count.textContent = numberFormatter.format(material.count);
                item.append(image, name, count);
                return item;
            }),
        );
        tooltip.hidden = false;
        positionTooltip(event);
    };

    const clearSaveStates = () => {
        section.querySelectorAll(".talent-level-picker").forEach((picker) => {
            picker.classList.remove("is-saving", "is-saved", "is-save-error");
        });
    };

    const applySavedProgress = (savedProgress) => {
        Object.entries(savedProgress).forEach(([progressField, savedLevel]) => {
            if (!Number.isFinite(Number(savedLevel))) return;
            const progressInput = progressForm.elements.namedItem(progressField);
            if (progressInput instanceof HTMLInputElement) {
                progressInput.value = String(savedLevel);
            }
            const picker = section.querySelector(
                `.talent-level-picker[data-progress-field="${progressField}"]`,
            );
            if (picker instanceof HTMLElement) selectLevel(picker, savedLevel, false);
        });
    };

    const scheduleProgressSave = (picker) => {
        if (!saveUrl) return;
        const revision = ++saveRevision;
        clearTimeout(saveTimer);
        clearSaveStates();
        picker.classList.add("is-saving");

        saveTimer = window.setTimeout(() => {
            const formData = new FormData(progressForm);
            saveQueue = saveQueue
                .catch(() => undefined)
                .then(async () => {
                    const response = await fetch(saveUrl, {
                        method: "POST",
                        headers: {Accept: "application/json"},
                        body: formData,
                    });
                    if (!response.ok) throw new Error(`Progress save failed with ${response.status}`);
                    const savedProgress = await response.json();
                    if (revision !== saveRevision) return;

                    applySavedProgress(savedProgress);
                    clearSaveStates();
                    picker.classList.add("is-saved");
                    window.setTimeout(() => picker.classList.remove("is-saved"), 900);
                })
                .catch((error) => {
                    if (revision !== saveRevision) return;
                    clearSaveStates();
                    picker.classList.add("is-save-error");
                    console.error(error);
                });
        }, 180);
    };

    section.querySelectorAll(".talent-level-picker").forEach((picker) => {
        selectLevel(picker, picker.dataset.selectedLevel, false);

        picker.querySelectorAll("[data-level]").forEach((dot) => {
            const level = Number(dot.dataset.level);
            dot.addEventListener("mouseenter", (event) => {
                showScalingLevel(picker, level);
                showMaterialTooltip(picker, level, event);
            });
            dot.addEventListener("mousemove", (event) => {
                if (!tooltip.hidden) positionTooltip(event);
            });
            dot.addEventListener("mouseleave", () => {
                showScalingLevel(picker, Number(picker.dataset.selectedLevel));
                hideTooltip();
            });
            dot.addEventListener("focus", () => showScalingLevel(picker, level));
            dot.addEventListener("blur", () => {
                showScalingLevel(picker, Number(picker.dataset.selectedLevel));
            });
            dot.addEventListener("click", () => {
                selectLevel(picker, level, true);
                scheduleProgressSave(picker);
                hideTooltip();
            });
        });

        picker.addEventListener("keydown", (event) => {
            if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
            event.preventDefault();
            const selectedLevel = Number(picker.dataset.selectedLevel);
            const nextLevel = event.key === "Home"
                ? 1
                : event.key === "End"
                    ? 10
                    : clampLevel(selectedLevel + (event.key === "ArrowLeft" ? -1 : 1));
            selectLevel(picker, nextLevel, true);
            scheduleProgressSave(picker);
            picker.querySelector(`[data-level="${nextLevel}"]`)?.focus();
            hideTooltip();
        });
    });

    progressForm.querySelectorAll("input[name$='Talent']:not([name^='target'])").forEach((input) => {
        input.addEventListener("input", () => {
            const picker = section.querySelector(
                `.talent-level-picker[data-progress-field="${input.name}"]`,
            );
            if (picker instanceof HTMLElement) selectLevel(picker, input.value, false);
        });
    });
})();
