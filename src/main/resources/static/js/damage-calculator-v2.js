(() => {
    "use strict";

    const root = document.querySelector("[data-damage-calculator-v2]");
    if (!root) return;

    const workspace = root.querySelector("[data-v2-workspace]");
    const statusNode = root.querySelector("[data-v2-status]");
    const errorNode = root.querySelector("[data-v2-error]");
    const errorMessageNode = root.querySelector("[data-v2-error-message]");
    const inputsNode = root.querySelector("[data-v2-inputs]");
    const characterStatsNode = root.querySelector("[data-v2-character-stats]");
    const calculationInputsNode = root.querySelector("[data-v2-calculation-inputs]");
    const effectsNode = root.querySelector("[data-v2-effects]");
    const teamNode = root.querySelector("[data-v2-team]");
    const storedTeamNode = root.querySelector("[data-v2-stored-team]");
    const storedTeamBody = root.querySelector("[data-v2-stored-team-body]");
    const storedTeamToggle = root.querySelector("[data-v2-stored-team-toggle]");
    const buildLabels = root.querySelector("[data-v2-build-labels]")?.dataset || {};
    const resultsNode = root.querySelector("[data-v2-results]");
    const currentNote = root.querySelector("[data-v2-current-note]");
    const expandButton = root.querySelector("[data-v2-expand]");
    const reactionToggle = root.querySelector("[data-v2-reaction-toggle]");
    const tabButtons = Array.from(root.querySelectorAll("[data-v2-tab]"));
    const tabPanels = Array.from(root.querySelectorAll("[data-v2-tab-panel]"));
    const controls = new Map();
    const currentMarkers = new Map();
    const hintCatalog = new Map(
        Array.from(root.querySelectorAll("[data-formula-hint-tag]"))
            .map((node) => [node.dataset.formulaHintTag, {
                name: node.dataset.formulaName || "",
                tooltip: node.dataset.formulaTooltip || "",
            }]),
    );
    const talentDescriptions = new Map(
        Array.from(root.querySelectorAll("[data-v2-talent-description-source]"))
            .map((node) => [node.dataset.talentKey, node.textContent.trim()])
            .filter(([, description]) => description),
    );

    let profile = null;
    let calculation = null;
    let inputById = new Map();
    let inputState = {};
    let effectState = {};
    let calculateTimer = null;
    let calculateController = null;
    let calculateVersion = 0;
    let allExpanded = false;
    let teamSlots = [];
    let storedBuilds = [];
    let statAlignmentFrame = null;

    const numberFormatter = new Intl.NumberFormat(undefined, {maximumFractionDigits: 2});
    const damageFormatter = new Intl.NumberFormat(undefined, {maximumFractionDigits: 0});
    const percentFormatter = new Intl.NumberFormat(undefined, {
        minimumFractionDigits: 1,
        maximumFractionDigits: 2,
    });
    const multiplierFormatter = new Intl.NumberFormat(undefined, {
        minimumFractionDigits: 3,
        maximumFractionDigits: 4,
    });

    const markerByTalent = {normal: "NA", skill: "E", burst: "Q"};
    const inputTokenClass = {
        characterLevel: "formula-token-character-level",
        enemyLevel: "formula-token-enemy-level",
        attack: "formula-token-atk",
        maxHp: "formula-token-hp",
        defense: "formula-token-defense",
        elementalMastery: "formula-token-em",
        elementalDamageBonus: "formula-token-element-dendro",
        physicalDamageBonus: "formula-token-element-physical",
        energyRecharge: "formula-token-buff",
        critRate: "formula-token-crit-rate",
        critDamage: "formula-token-crit-damage",
        enemyResistance: "formula-token-element-dendro formula-token-resistance",
        enemyPhysicalResistance: "formula-token-element-physical formula-token-resistance",
        defenseReduction: "formula-token-defense",
        defenseIgnore: "formula-token-defense-ignore",
        normalTalentLevel: "formula-token-buff",
        skillTalentLevel: "formula-token-buff",
        burstTalentLevel: "formula-token-buff",
        skillActive: "formula-token-buff",
        veilStacks: "formula-token-buff",
    };
    const inputHintTag = {
        normalTalentLevel: "talentLevel",
        skillTalentLevel: "talentLevel",
        burstTalentLevel: "talentLevel",
    };

    function label(key, fallback) {
        return root.dataset[key] || fallback;
    }

    function hint(tag, context = "") {
        const metadata = hintCatalog.get(tag) || {};
        const base = metadata.name && metadata.tooltip
            ? `${metadata.name} — ${metadata.tooltip}`
            : (metadata.tooltip || metadata.name || "");
        return [base, context].filter(Boolean).join(" ");
    }

    function el(tagName, className = "", text = "") {
        const node = document.createElement(tagName);
        if (className) node.className = className;
        if (text !== "") node.textContent = text;
        return node;
    }

    function formatInput(definition, value) {
        const suffix = definition.unit === "percentPoints" ? "%" : "";
        return `${numberFormatter.format(value)}${suffix}`;
    }

    function formatPercentRatio(value) {
        return `${percentFormatter.format(value * 100)}%`;
    }

    function formatMultiplier(value) {
        return `×${multiplierFormatter.format(value)}`;
    }

    function rangeProgress(control) {
        const min = Number(control.min);
        const max = Number(control.max);
        const progress = max === min ? 0 : ((Number(control.value) - min) / (max - min)) * 100;
        control.style.setProperty("--range-progress", `${progress}%`);
    }

    function isToggle(definition) {
        return definition.min === 0 && definition.max === 1 && definition.step === 1 &&
            definition.unit !== "percentPoints";
    }

    function inputSection(definition) {
        if (definition.section) return definition.section;
        if (definition.id.endsWith("TalentLevel")) return "talents";
        if (definition.id.endsWith("Constellation")) return "constellations";
        if (["enemyLevel", "enemyResistance", "defenseReduction", "defenseIgnore"].includes(definition.id)) {
            return "enemy";
        }
        if (["skillActive", "veilStacks"].includes(definition.id)) return "combat";
        return "build";
    }

    function sectionTitle(section) {
        return {
            build: label("labelSectionBuild", "Character build"),
            constellations: label("labelSectionConstellations", "Owned constellations"),
            talents: label("labelSectionTalents", "Talent levels"),
            combat: label("labelSectionCombat", "Combat state"),
            teamStats: label("labelSectionTeamStats", "Teammate stats"),
            enemy: label("labelSectionEnemy", "Enemy mitigation"),
        }[section];
    }

    function inputSource(definition) {
        if (!definition.initialValueSource) return "Editable calculator input";
        const source = definition.initialValueSource;
        return `Source: ${source.characterKey || profile.characterKey} · ${source.valueKey}`;
    }

    function currentValueMatches(definition) {
        const tolerance = Math.max(Math.abs(Number(definition.step) || 0) / 1000, 1e-9);
        return Math.abs(Number(inputState[definition.id]) - Number(definition.defaultValue)) <= tolerance;
    }

    function updateCurrentPresentation() {
        profile.inputs.forEach((definition) => {
            const marker = currentMarkers.get(definition.id);
            if (marker) marker.hidden = !currentValueMatches(definition);
        });
        currentNote.hidden = !profile.inputs.some((definition) =>
            definition.currentValueLoaded && currentValueMatches(definition),
        );
    }

    function renderInputs() {
        controls.clear();
        currentMarkers.clear();
        inputsNode.replaceChildren();
        calculationInputsNode.replaceChildren();
        characterStatsNode.replaceChildren();
        effectsNode.replaceChildren();

        const statInputIds = new Set(Object.values(profile.characterStatInputs || {}).flatMap(Object.values));
        const progressInputIds = new Set(Object.values(profile.characterProgressInputs || {}).flatMap(Object.values));
        const calculationDefinitions = profile.inputs.filter((definition) => inputSection(definition) === "enemy");
        const calculationIds = new Set(calculationDefinitions.map((definition) => definition.id));
        const effectDefinitions = profile.inputs.filter((definition) =>
            !statInputIds.has(definition.id) && !progressInputIds.has(definition.id) &&
            !calculationIds.has(definition.id) && definition.section !== "internal" &&
            !definition.section?.startsWith("artifact:") && !definition.section?.startsWith("weapon:"),
        );

        renderInputSections(inputsNode, effectDefinitions,
            ["constellations", "combat", "teamStats", "build"]);
        renderInputSections(calculationInputsNode, calculationDefinitions, ["enemy"]);
        renderCharacterStats();
        updateCurrentPresentation();
    }

    function renderInputSections(target, definitions, order) {
        order.forEach((sectionKey) => {
            const sectionDefinitions = definitions.filter((definition) =>
                inputSection(definition) === sectionKey ||
                (sectionKey === "build" && definition.id === "characterLevel"),
            );
            if (!sectionDefinitions.length) return;
            const section = el("section", "damage-input-section");
            section.append(el("h3", "", sectionTitle(sectionKey)));
            sectionDefinitions.forEach((definition) => section.append(renderInput(definition)));
            target.append(section);
        });
    }

    function statIcon(stat) {
        const wrapper = el("span", `damage-v2-stat-icon damage-v2-stat-icon-${stat}`);
        const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
        svg.setAttribute("viewBox", "0 0 24 24");
        svg.setAttribute("aria-hidden", "true");
        const drawings = {
            maxHp: '<path d="M20.8 4.8a5.5 5.5 0 0 0-7.8 0L12 5.9l-1.1-1.1a5.5 5.5 0 1 0-7.8 7.8L12 21l8.8-8.4a5.5 5.5 0 0 0 0-7.8Z"/>',
            attack: '<path d="m14.5 4.5 5-2-2 5-9 9-3 1 1-3 8-10Z"/><path d="m11 8 5 5M4 20l4-4"/>',
            defense: '<path d="M12 2 20 5v6c0 5.2-3.4 9.1-8 11-4.6-1.9-8-5.8-8-11V5l8-3Z"/><path d="M12 6v11"/>',
            elementalMastery: '<circle cx="12" cy="12" r="7"/><path d="M12 2v4m0 12v4M2 12h4m12 0h4M5 5l3 3m8 8 3 3m0-14-3 3M8 16l-3 3"/>',
            critRate: '<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="4"/><path d="m15 9 6-6m-1 0h1v1"/>',
            critDamage: '<path d="m12 2 2.2 6.2L21 6l-3.8 5.6L22 16l-6.8.2L12 22l-3.2-5.8L2 16l4.8-4.4L3 6l6.8 2.2L12 2Z"/>',
            energyRecharge: '<path d="m13.5 2-8 12h6L10.5 22l8-12h-6l1-8Z"/>',
            elementalDamageBonus: '<path d="M19.5 3.5C12 4 6.5 8.2 5 15c4.6.7 8.7-.7 11.2-4.1C13.8 15 10.5 18 5 20"/>',
            physicalDamageBonus: '<path d="M4 20 20 4M7 4l13 13M4 7l13 13"/>',
            characterLevel: '<path d="m12 2 2.5 5.1 5.6.8-4 4 .9 5.6-5-2.6-5 2.6.9-5.6-4-4 5.6-.8L12 2Z"/>',
            normalTalentLevel: '<path d="M5 20 19 6m-9-2 4 4m-8 8 4 4M4 4l16 16"/>',
            skillTalentLevel: '<circle cx="12" cy="12" r="8"/><path d="m12 7 3 5-3 5-3-5 3-5Z"/>',
            burstTalentLevel: '<path d="m12 2 2.2 6.2L21 6l-3.8 5.6L22 16l-6.8.2L12 22l-3.2-5.8L2 16l4.8-4.4L3 6l6.8 2.2L12 2Z"/>',
        };
        svg.innerHTML = drawings[stat] || '<circle cx="12" cy="12" r="8"/>';
        wrapper.append(svg);
        return wrapper;
    }

    function statLabel(stat, member) {
        return {
            maxHp: "Max HP",
            attack: "ATK",
            defense: "DEF",
            elementalMastery: "Elemental Mastery",
            critRate: "CRIT Rate",
            critDamage: "CRIT DMG",
            energyRecharge: "Energy Recharge",
            elementalDamageBonus: `${member.element} DMG Bonus`,
            physicalDamageBonus: "Physical DMG Bonus",
            characterLevel: "Character level",
            normalTalentLevel: "Normal Attack",
            skillTalentLevel: "Elemental Skill",
            burstTalentLevel: "Elemental Burst",
        }[stat] || stat;
    }

    function renderCharacterStats() {
        const statMappings = profile.characterStatInputs || {};
        const progressMappings = profile.characterProgressInputs || {};
        teamSlots.slice(0, 4).forEach((characterKey, slot) => {
            const member = profile.teamMembers.find((item) => item.key === characterKey);
            const build = storedBuilds.find((item) => item.key === characterKey);
            const card = el("article", `damage-v2-character-stat-card${member ? "" : " is-empty"}`);
            if (!member) {
                card.append(el("div", "damage-v2-character-stat-empty", `${label("labelTeamSlot", "Slot")} ${slot + 1}`));
                characterStatsNode.append(card);
                return;
            }
            const header = el("header", "damage-v2-character-stat-header");
            header.dataset.v2AlignRow = "character-header";
            header.append(buildIcon(build?.iconUrl || member.iconUrl, member.name));
            const copy = el("div");
            copy.append(el("h3", "", member.name), el("small", "", member.element));
            const resetButton = el("button", "damage-v2-character-reset", `↺ ${label("labelReset", "Reset")}`);
            resetButton.type = "button";
            resetButton.dataset.v2CharacterReset = characterKey;
            resetButton.setAttribute("aria-label", `${label("labelReset", "Reset")} ${member.name}`);
            resetButton.addEventListener("click", () => resetCharacter(characterKey));
            header.append(copy, resetButton);
            card.append(header);

            const progressGroup = el("section", "damage-v2-character-input-group damage-v2-character-progress");
            progressGroup.append(el("h4", "", label("labelProgress", "Character & talents")));
            const progressFields = el("div", "damage-v2-character-stat-fields");
            Object.entries(progressMappings[characterKey] || {}).forEach(([progress, inputId]) => {
                const definition = inputById.get(inputId);
                if (definition) progressFields.append(renderStatInput(
                    member, progress, definition, false, `progress:${progress}`,
                ));
            });
            progressGroup.append(progressFields);
            card.append(progressGroup);

            const statsGroup = el("section", "damage-v2-character-input-group");
            statsGroup.append(el("h4", "", label("labelCombatStats", "Effective combat stats")));
            const statFields = el("div", "damage-v2-character-stat-fields");
            Object.entries(statMappings[characterKey] || {}).forEach(([stat, inputId]) => {
                const definition = inputById.get(inputId);
                if (definition) statFields.append(renderStatInput(
                    member, stat, definition, true, `stat:${stat}`,
                ));
            });
            statsGroup.append(statFields);
            card.append(statsGroup);

            characterStatsNode.append(card);
        });
        updateEffectiveStats();
    }

    function renderStatInput(member, stat, definition, showCalculation = false, alignKey = "") {
        const field = el("div", "damage-v2-stat-field damage-slider-field");
        const controlId = `damage-v2-input-${definition.id}`;
        const heading = el("div", "damage-v2-stat-heading");
        if (alignKey) heading.dataset.v2AlignRow = `${alignKey}:heading`;
        const identity = el("span", "damage-v2-stat-identity");
        identity.append(statIcon(stat), el("span", "", statLabel(stat, member)));
        if (definition.currentValueLoaded) {
            const marker = el("small", "", label("labelCurrent", "Current"));
            marker.dataset.v2CurrentMarker = definition.id;
            identity.append(marker);
            currentMarkers.set(definition.id, marker);
        }
        const effective = el("span", "damage-v2-effective-value");
        effective.append(el("small", "", label("labelEffective", "Effective")));
        const value = calculation?.effectiveStats?.[definition.id] ?? inputState[definition.id];
        const effectiveValue = el("b", "", formatInput(definition, value));
        effectiveValue.dataset.v2EffectiveStat = definition.id;
        effective.append(effectiveValue);
        heading.append(identity, effective);

        const editors = el("div", "damage-v2-stat-editors");
        const number = document.createElement("input");
        number.type = "number";
        number.min = definition.min;
        number.max = definition.max;
        number.step = definition.step;
        number.value = inputState[definition.id];
        number.id = `${controlId}-number`;
        number.dataset.v2NumberInput = definition.id;
        number.setAttribute("aria-label", `${label("labelEntered", "Base")} ${statLabel(stat, member)}`);
        const range = document.createElement("input");
        range.type = "range";
        range.id = controlId;
        range.min = definition.min;
        range.max = definition.max;
        range.step = definition.step;
        range.value = inputState[definition.id];
        range.dataset.v2Input = definition.id;
        rangeProgress(range);
        const update = (rawValue) => {
            const numeric = Number(rawValue);
            if (!Number.isFinite(numeric)) return;
            const bounded = Math.min(Number(definition.max), Math.max(Number(definition.min), numeric));
            inputState[definition.id] = bounded;
            number.value = bounded;
            range.value = bounded;
            rangeProgress(range);
            updateCurrentPresentation();
            synchronizeOwnershipEffects(definition.id);
            scheduleCalculation();
        };
        number.addEventListener("input", () => update(number.value));
        range.addEventListener("input", () => update(range.value));
        editors.append(number, range);
        field.append(heading);
        if (showCalculation) {
            const calculationRow = el("div", "damage-v2-stat-calculation");
            calculationRow.dataset.v2StatCalculation = definition.id;
            if (alignKey) calculationRow.dataset.v2AlignRow = `${alignKey}:calculation`;
            renderStatCalculation(definition, calculationRow);
            field.append(calculationRow);
        }
        field.append(editors);
        controls.set(definition.id, range);
        return field;
    }

    function buildStatFormula(definition) {
        if (!definition.currentValueLoaded || !currentValueMatches(definition)) return "";
        const source = definition.initialValueSource;
        if (!source) return "";
        const build = storedBuilds.find((item) => item.key === (source.characterKey || profile.characterKey));
        if (!build) return "";
        const statKey = {
            totalHp: "hp",
            totalAttack: "atk",
            totalDefense: "def",
        }[source.valueKey] || source.valueKey.replace(/^stat:/, "");
        const storedStat = build.totalStats?.find((item) => item.key === statKey);
        return storedStat?.formula ? `${build.name} · ${storedStat.label}: ${storedStat.formula}` : "";
    }

    function statEffectClass(effect) {
        if (effect?.category?.startsWith("artifact:") || effect?.category?.startsWith("weapon:")) return "formula-token-effect-weapon";
        if (effect?.category === "team") return "formula-token-effect-team";
        return "formula-token-effect-character";
    }

    function renderStatCalculation(definition, row) {
        const baseValue = Number(inputState[definition.id] ?? definition.defaultValue);
        const effectiveValue = Number(calculation?.effectiveStats?.[definition.id] ?? baseValue);
        const changes = (calculation?.statChanges || []).filter((change) => change.stat === definition.id);
        const baseTooltip = buildStatFormula(definition) ||
            `${label("labelEntered", "Base")} ${definition.label}: ${formatInput(definition, baseValue)}.`;
        const formula = el("code");
        formula.append(token(
            `${label("labelEntered", "Base")} ${formatInput(definition, baseValue)}`,
            inputTokenClass[definition.hintTag] || inputTokenClass[definition.id] || "formula-token-input",
            baseTooltip,
        ));
        changes.forEach((change) => {
            const effect = profile.effects.find((item) => item.id === change.effectId);
            const amount = Number(change.amount);
            const sign = amount < 0 ? " − " : " + ";
            const effectName = effect?.name || change.effectId;
            const expression = change.expression
                ? ` Formula: ${expressionText(change.expression, change.references || [])}.`
                : "";
            formula.append(document.createTextNode(sign), token(
                `${formatInput(definition, Math.abs(amount))} ${effectName}`,
                statEffectClass(effect),
                `${effectName} — ${effect?.description || "Active stat change."}${expression}`,
            ));
        });
        formula.append(
            document.createTextNode(" = "),
            token(formatInput(definition, effectiveValue), "formula-token-multiplier formula-token-result",
                `${label("labelEffective", "Effective")} ${definition.label}: ${formatInput(definition, effectiveValue)}.`),
        );
        row.replaceChildren(el("small", "", label("labelStatCalculation", "Calculation")), formula);
    }

    function updateEffectiveStats() {
        root.querySelectorAll("[data-v2-effective-stat]").forEach((node) => {
            const definition = inputById.get(node.dataset.v2EffectiveStat);
            if (!definition) return;
            const value = calculation?.effectiveStats?.[definition.id] ?? inputState[definition.id];
            node.textContent = formatInput(definition, value);
            node.parentElement.classList.toggle("is-buffed", Math.abs(value - inputState[definition.id]) > 1e-9);
        });
        root.querySelectorAll("[data-v2-stat-calculation]").forEach((row) => {
            const definition = inputById.get(row.dataset.v2StatCalculation);
            if (definition) renderStatCalculation(definition, row);
        });
        scheduleStatAlignment();
    }

    function scheduleStatAlignment() {
        window.cancelAnimationFrame(statAlignmentFrame);
        statAlignmentFrame = window.requestAnimationFrame(() => {
            const statsPanel = root.querySelector('[data-v2-tab-panel="stats"]');
            if (!statsPanel || statsPanel.hidden) return;
            const groups = new Map();
            characterStatsNode.querySelectorAll("[data-v2-align-row]").forEach((node) => {
                node.style.minHeight = "";
                const key = node.dataset.v2AlignRow;
                if (!groups.has(key)) groups.set(key, []);
                groups.get(key).push(node);
            });
            groups.forEach((nodes) => {
                const height = Math.ceil(Math.max(...nodes.map((node) => node.getBoundingClientRect().height)));
                nodes.forEach((node) => { node.style.minHeight = `${height}px`; });
            });
        });
    }

    function closeTeamPickers(except = null) {
        teamNode.querySelectorAll("[data-team-picker-menu]").forEach((menu) => {
            if (menu === except) return;
            menu.hidden = true;
            menu.parentElement.querySelector("[data-team-picker-button]")?.setAttribute("aria-expanded", "false");
        });
    }

    function chooseTeamMember(slot, key) {
        const nextSlots = [...teamSlots];
        const currentKey = nextSlots[slot];
        if (!key) {
            if (currentKey === profile.characterKey) return;
            nextSlots[slot] = "";
        } else {
            const existingSlot = nextSlots.indexOf(key);
            if (existingSlot >= 0 && existingSlot !== slot) {
                nextSlots[existingSlot] = currentKey;
                nextSlots[slot] = key;
            } else {
                nextSlots[slot] = key;
            }
        }
        closeTeamPickers();
        if (!nextSlots.some((value, index) => value !== teamSlots[index])) return;
        if (currentKey === profile.characterKey && key && key !== profile.characterKey) {
            const target = new URL(window.location.href);
            target.pathname = target.pathname.replace(/[^/]+$/, encodeURIComponent(key));
            target.search = "";
            nextSlots.filter(Boolean).forEach((characterKey) => target.searchParams.append("team", characterKey));
            window.location.assign(target);
            return;
        }
        changeTeam(nextSlots);
    }

    function teamPicker(slot) {
        const key = teamSlots[slot] || "";
        const member = profile.teamMembers.find((item) => item.key === key);
        const build = storedBuilds.find((item) => item.key === key);
        const name = member?.name || buildLabel("empty", "Empty");
        const wrapper = el("span", "damage-team-picker");
        const button = el("button", "damage-team-picker-button");
        button.type = "button";
        button.dataset.teamPickerButton = String(slot);
        button.setAttribute("aria-haspopup", "listbox");
        button.setAttribute("aria-expanded", "false");
        button.setAttribute("aria-label", `${label("labelTeamSelect", "Choose character for slot")} ${slot + 1}`);
        button.append(buildIcon(build?.iconUrl || member?.iconUrl, name));
        const copy = el("span", "damage-team-picker-copy");
        copy.append(el("b", "", name), el("small", "", member?.element || `${label("labelTeamSlot", "Slot")} ${slot + 1}`));
        button.append(copy, el("span", "damage-team-picker-caret", "▾"));

        const menu = el("span", "damage-team-picker-menu");
        menu.dataset.teamPickerMenu = String(slot);
        menu.hidden = true;
        const search = el("input", "damage-team-picker-search");
        search.type = "search";
        search.placeholder = label("labelTeamSearch", "Search characters…");
        search.autocomplete = "off";
        const options = el("span", "damage-team-picker-options");
        options.setAttribute("role", "listbox");
        menu.append(search, options);

        const drawOptions = () => {
            const query = search.value.trim().toLocaleLowerCase();
            const candidates = profile.teamMembers.filter((candidate) =>
                !query || `${candidate.name} ${candidate.element}`.toLocaleLowerCase().includes(query),
            );
            options.replaceChildren();
            if (!query && key && key !== profile.characterKey) {
                const empty = el("button", "damage-team-picker-option");
                empty.type = "button";
                empty.setAttribute("role", "option");
                empty.append(el("span", "damage-team-picker-empty-icon", "—"), el("b", "", buildLabel("empty", "Empty")));
                empty.addEventListener("click", () => chooseTeamMember(slot, ""));
                options.append(empty);
            }
            candidates.forEach((candidate) => {
                const option = el("button", `damage-team-picker-option${candidate.key === key ? " is-selected" : ""}`);
                option.type = "button";
                option.setAttribute("role", "option");
                option.setAttribute("aria-selected", String(candidate.key === key));
                if (key === profile.characterKey && candidate.key !== key && !candidate.calculatorAvailable) {
                    option.disabled = true;
                    option.title = "A primary damage profile has not been authored for this character yet.";
                }
                option.append(buildIcon(candidate.iconUrl, candidate.name));
                const optionCopy = el("span", "damage-team-picker-copy");
                optionCopy.append(el("b", "", candidate.name), el("small", "", candidate.element || "—"));
                option.append(optionCopy);
                option.addEventListener("click", () => chooseTeamMember(slot, candidate.key));
                options.append(option);
            });
            if (!options.children.length) options.append(el("p", "damage-team-picker-no-results",
                label("labelTeamNoResults", "No matching characters")));
        };
        search.addEventListener("input", drawOptions);
        menu.addEventListener("click", (event) => event.stopPropagation());
        button.addEventListener("click", (event) => {
            event.stopPropagation();
            const open = menu.hidden;
            closeTeamPickers(open ? menu : null);
            menu.hidden = !open;
            button.setAttribute("aria-expanded", String(open));
            if (open) {
                search.value = "";
                drawOptions();
                search.focus();
            }
        });
        wrapper.append(button, menu);
        return wrapper;
    }

    function renderTeam() {
        teamNode.replaceChildren();
        for (let slot = 0; slot < 4; slot++) teamNode.append(teamPicker(slot));
        root.querySelector("[data-v2-team-context]").textContent =
            `${profile.selectedTeam.length}/4 · ${label("labelTeamContext", "Moonsign level")} ${profile.teamContext.moonsignLevel || 0}`;
        const notes = root.querySelector("[data-v2-team-notes]");
        notes.replaceChildren(el("summary", "", label("labelTeamNotes", "Coverage and data sources")));
        profile.supportProfiles.forEach((support) => {
            notes.append(el("b", "", support.name));
            support.notes.forEach((note) => notes.append(el("p", "", note)));
            support.sources.filter((source) => /^https:\/\//.test(source)).forEach((source, index) => {
                const link = el("a", "", `${support.name} · ${index + 1}`);
                link.href = source;
                link.target = "_blank";
                link.rel = "noopener noreferrer";
                notes.append(link);
            });
        });
        renderStoredBuilds();
    }

    function buildLabel(key, fallback) {
        return buildLabels[key] || fallback;
    }

    function buildIcon(url, name) {
        const frame = el("span", "damage-stored-icon");
        frame.append(el("span", "", name.slice(0, 1)));
        if (url) {
            const img = el("img");
            img.src = url;
            img.alt = "";
            img.loading = "lazy";
            img.addEventListener("error", () => img.remove(), {once: true});
            frame.append(img);
        }
        return frame;
    }

    function storedStatValue(stat) {
        return stat.value == null ? "—" : `${numberFormatter.format(stat.value)}${stat.key.endsWith("_") ? "%" : ""}`;
    }

    function storedStats(stats, source) {
        const list = el("dl", "damage-stored-stats");
        stats.forEach((stat) => {
            const row = el("div", `damage-stored-stat damage-stored-stat-${stat.key.replace(/[^a-zA-Z]/g, "")}`);
            row.tabIndex = 0;
            row.title = `${source} · ${stat.label}${stat.formula ? `\n${stat.formula}` : ""}${stat.value == null ? `\n${buildLabel("unavailable", "Unavailable in stored data")}` : ""}`;
            row.append(el("dt", "", stat.label), el("dd", "", storedStatValue(stat)));
            list.append(row);
        });
        return list;
    }

    function buildSection(title) {
        const section = el("section", "damage-stored-section");
        section.append(el("h4", "", title));
        return section;
    }

    function renderStoredBuilds() {
        if (!storedTeamNode) return;
        const grid = root.querySelector("[data-v2-stored-team-grid]");
        grid.replaceChildren();
        teamSlots.forEach((key) => {
            const build = storedBuilds.find((item) => item.key === key);
            const member = profile.teamMembers.find((item) => item.key === key);
            const name = build?.name || member?.name || buildLabel("empty", "Empty");
            const card = el("article", "damage-stored-build");
            grid.append(card);
            if (!key) {
                card.classList.add("damage-stored-build-empty");
                card.append(el("span", "", buildLabel("empty", "Empty")));
                return;
            }
            const header = el("header", "damage-stored-build-header");
            const heading = el("div");
            heading.append(el("h3", "", name));
            const progress = build?.level == null ? buildLabel("notOwned", "No stored build") :
                `${buildLabel("level", "Lv.")} ${build.level} · ${buildLabel("ascension", "Asc.")} ${build.ascension ?? "—"} · C${build.constellation ?? "—"}`;
            heading.append(el("p", "", progress));
            header.append(buildIcon(build?.iconUrl, name), heading);
            card.append(header);
            if (!build) {
                card.append(el("p", "damage-stored-muted", buildLabel("unavailable", "Unavailable in stored data")));
                return;
            }
            if (build.issues.length) {
                const issues = el("details", "damage-stored-issues");
                issues.append(el("summary", "", `${buildLabel("unavailable", "Unavailable in stored data")} (${build.issues.length})`));
                build.issues.forEach((issue) => issues.append(el("p", "", issue)));
                card.append(issues);
            }
            const character = buildSection(buildLabel("character", "Character base"));
            character.append(storedStats(build.characterStats, `${name} · ${progress}`));
            card.append(character);

            const weapon = buildSection(buildLabel("weapon", "Weapon"));
            if (build.weapon) {
                const item = build.weapon;
                const itemHeader = el("div", "damage-stored-equipment-heading");
                const copy = el("div");
                copy.append(el("b", "", item.name), el("small", "", `${buildLabel("level", "Lv.")} ${item.level} · ${buildLabel("ascension", "Asc.")} ${item.ascension} · ${buildLabel("refinement", "R")} ${item.refinement}`));
                itemHeader.append(buildIcon(item.iconUrl, item.name), copy);
                weapon.append(itemHeader, storedStats(item.stats, item.name));
            } else weapon.append(el("p", "damage-stored-muted", buildLabel("noWeapon", "No equipped weapon recorded")));
            card.append(weapon);

            const artifacts = buildSection(`${buildLabel("artifacts", "Artifacts")} · ${build.artifacts.length}/5`);
            build.artifacts.forEach((item) => {
                const piece = el("details", "damage-stored-artifact");
                const summary = el("summary");
                const copy = el("span", "damage-stored-artifact-copy");
                copy.append(el("b", "", item.name), el("small", "", `${item.slotName} · ${item.rarity}★ +${item.level}`));
                const main = el("span", "damage-stored-artifact-main", `${item.mainStat.label} ${storedStatValue(item.mainStat)}`);
                main.title = `${buildLabel("mainStat", "Main stat")} · ${item.name} · ${item.rarity}★ +${item.level}`;
                copy.append(main);
                summary.append(buildIcon(item.iconUrl, item.slotName), copy);
                piece.append(summary, storedStats(item.substats, `${item.name} · ${buildLabel("substats", "Substats")}`));
                artifacts.append(piece);
            });
            if (build.artifactStats.length) {
                const aggregate = el("details", "damage-stored-artifact-total");
                aggregate.open = true;
                aggregate.append(el("summary", "", buildLabel("artifactTotal", "Combined artifact stats")),
                    storedStats(build.artifactStats, buildLabel("artifactTotal", "Combined artifact stats")));
                artifacts.append(aggregate);
            } else artifacts.append(el("p", "damage-stored-muted", buildLabel("noArtifacts", "No equipped artifacts recorded")));
            card.append(artifacts);

            const totals = buildSection(buildLabel("totals", "Unbuffed total"));
            totals.classList.add("damage-stored-totals");
            const core = new Set(["hp", "atk", "def", "eleMas", "critRate_", "critDMG_", "enerRech_", `${build.element.toLowerCase()}_dmg_`]);
            const shown = build.totalStats.filter((stat) => core.has(stat.key) || (stat.value != null && stat.value !== 0));
            const remaining = build.totalStats.filter((stat) => !shown.includes(stat));
            totals.append(storedStats(shown, buildLabel("totals", "Unbuffed total")));
            if (remaining.length) {
                const more = el("details", "damage-stored-more");
                more.append(el("summary", "", buildLabel("moreStats", "Other stats")), storedStats(remaining, buildLabel("totals", "Unbuffed total")));
                totals.append(more);
            }
            card.append(totals);
        });
        storedTeamNode.hidden = false;
    }

    async function changeTeam(nextSlots) {
        window.clearTimeout(calculateTimer);
        const version = ++calculateVersion;
        calculateController?.abort();
        calculateController = new AbortController();
        workspace.inert = true;
        if (storedTeamNode) storedTeamNode.inert = true;
        workspace.classList.add("is-updating");
        try {
            const response = await fetch(root.dataset.teamUrl, {
                method: "POST", headers: requestHeaders(), signal: calculateController.signal,
                body: JSON.stringify({inputs: inputState, enabledEffects: effectState, teamKeys: nextSlots.filter(Boolean)}),
            });
            if (!response.ok) throw new Error(`Team failed to load (${response.status})`);
            const payload = await response.json();
            if (version !== calculateVersion) return;
            teamSlots = nextSlots;
            profile = payload.profile;
            storedBuilds = payload.teamBuilds || [];
            inputById = new Map(profile.inputs.map((input) => [input.id, input]));
            inputState = payload.defaultCalculation.inputs;
            effectState = Object.fromEntries(profile.effects.map((effect) =>
                [effect.id, effectState[effect.id] ?? effect.defaultEnabled],
            ));
            renderTeam();
            renderInputs();
            applyCalculation(payload.defaultCalculation);
        } catch (error) {
            if (error.name === "AbortError" || version !== calculateVersion) return;
            renderTeam();
            statusNode.hidden = false;
            statusNode.classList.add("damage-v2-update-error");
            statusNode.lastElementChild.textContent = error.message;
        } finally {
            if (version === calculateVersion) {
                workspace.inert = false;
                if (storedTeamNode) storedTeamNode.inert = false;
                workspace.classList.remove("is-updating");
            }
        }
    }

    function renderInput(definition) {
        const field = el("div", `damage-slider-field${isToggle(definition) ? " damage-toggle-field damage-v2-toggle-field" : ""}`);
        const controlId = `damage-v2-input-${definition.id}`;
        if (isToggle(definition)) {
            const fieldLabel = el("label", "damage-buff-toggle");
            fieldLabel.htmlFor = controlId;
            const copy = el("span");
            copy.append(el("b", "", definition.label));
            if (definition.description) copy.append(el("small", "", definition.description));
            const toggle = el("span", "damage-toggle-control");
            const input = document.createElement("input");
            input.type = "checkbox";
            input.id = controlId;
            input.checked = inputState[definition.id] > 0;
            input.addEventListener("change", () => {
                inputState[definition.id] = input.checked ? 1 : 0;
                updateCurrentPresentation();
                scheduleCalculation();
            });
            toggle.append(input, el("i"));
            fieldLabel.append(copy, toggle);
            field.append(fieldLabel);
            controls.set(definition.id, input);
            return field;
        }

        const heading = el("div", "damage-input-label");
        const inputLabel = el("label");
        inputLabel.htmlFor = controlId;
        inputLabel.append(document.createTextNode(definition.label));
        if (definition.currentValueLoaded) {
            const marker = el("small", "", label("labelCurrent", "Current"));
            marker.dataset.v2CurrentMarker = definition.id;
            inputLabel.append(marker);
            currentMarkers.set(definition.id, marker);
        }
        const output = el("output", "", formatInput(definition, inputState[definition.id]));
        output.htmlFor = controlId;
        output.dataset.v2InputOutput = definition.id;
        heading.append(inputLabel, output);

        const input = document.createElement("input");
        input.type = "range";
        input.id = controlId;
        input.min = definition.min;
        input.max = definition.max;
        input.step = definition.step;
        input.value = inputState[definition.id];
        input.dataset.v2Input = definition.id;
        rangeProgress(input);
        input.addEventListener("input", () => {
            inputState[definition.id] = Number(input.value);
            output.textContent = formatInput(definition, inputState[definition.id]);
            rangeProgress(input);
            updateCurrentPresentation();
            synchronizeOwnershipEffects(definition.id);
            scheduleCalculation();
        });
        field.append(heading, input);
        if (definition.description) field.append(el("small", "damage-v2-input-help", definition.description));
        controls.set(definition.id, input);
        return field;
    }

    function effectOwned(effect) {
        return Object.entries(effect.ownershipRequirements || {}).every(([inputId, minimum]) =>
            Number(inputState[inputId] || 0) >= Number(minimum),
        );
    }

    function inputConditionSatisfied(condition) {
        const value = Number(inputState[condition.input] || 0);
        if (condition.type === "inputAtLeast") return value >= Number(condition.value);
        if (condition.type === "inputAtMost") return value <= Number(condition.value);
        if (condition.type === "contextAtLeast") return Number(profile.teamContext[condition.input] || 0) >= Number(condition.value);
        if (condition.type === "teamMember") return profile.selectedTeam.includes(condition.teamMember);
        return true;
    }

    function effectOwnershipLabel(effect) {
        const requirement = Object.entries(effect.ownershipRequirements || {})[0];
        if (!requirement) return "";
        const [inputId, minimum] = requirement;
        const prefix = inputId === "neferConstellation" ? "C" : `${inputById.get(inputId)?.label || inputId} `;
        return `${prefix}${numberFormatter.format(minimum)}`;
    }

    function metricText(metric, value) {
        if (metric.unit === "percentPoints") return `${percentFormatter.format(value)}%`;
        if (metric.unit === "ratio") return formatPercentRatio(value);
        return numberFormatter.format(value);
    }

    function effectStatus(effect, active, enabled, owned) {
        if (!artifactAvailable(effect)) return label("labelArtifactUnavailable", "Not enough equipped pieces");
        const ownership = effectOwnershipLabel(effect);
        if (!owned) {
            return `${active ? label("labelPreview", "Preview") : label("labelLocked", "Not owned")}: ${ownership}`;
        }
        if (active) return label("labelActive", "Active");
        if (enabled && effect.stackingGroup && profile.effects.some((other) =>
            other.stackingGroup === effect.stackingGroup && calculation.activeEffectIds.includes(other.id))) {
            return label("labelArtifactDuplicate", "Already supplied by another set");
        }
        if (enabled && !effect.activation.conditions.every(inputConditionSatisfied)) {
            return label("labelConditionInactive", "Condition not met");
        }
        return enabled ? label("labelBlocked", "Dependency off") : label("labelInactive", "Off");
    }

    function artifactAvailable(effect) {
        const origin = effect.artifactOrigin;
        return !origin || origin.equippedPieces >= origin.requiredPieces;
    }

    function effectCategoryDefinition(key) {
        if (key.startsWith("weapon:")) {
            return {
                name: profile.effects.find((effect) => effect.category === key)?.source || "Weapon effects",
                help: "Equipped weapon passives. Set refinement and active buffs for this combat snapshot.",
                defaultOpen: true,
            };
        }
        if (key.startsWith("artifact:")) {
            const loadout = profile.artifactLoadouts.find((item) => `artifact:${item.characterKey}` === key);
            return {
                name: `${loadout.characterName} · ${label("labelArtifacts", "Artifact sets")}`,
                help: label("labelArtifactHelp", "Equipped pieces determine available bonuses. Buff toggles only change this calculation."),
                defaultOpen: true,
            };
        }
        if (key === "team") return {
            name: label("labelTeamEffects", "Team buffs"),
            help: label("labelTeamEffectsHelp", "Authored buffs from the selected party."),
            defaultOpen: true,
        };
        if (key === "constellation") {
            return {
                name: label("labelConstellationEffects", "Constellation effects"),
                help: label("labelConstellationEffectsHelp", "Owned ranks start enabled automatically."),
                defaultOpen: false,
            };
        }
        return {
            name: label("labelPassiveEffects", "Character passives"),
            help: label("labelPassiveEffectsHelp", "Nefer's own combat passives and calculated values."),
            defaultOpen: true,
        };
    }

    function effectCategoryButton(text, handler) {
        const button = el("button", "", text);
        button.type = "button";
        button.addEventListener("click", (event) => {
            event.preventDefault();
            event.stopPropagation();
            handler();
        });
        return button;
    }

    function renderEffectCard(effect) {
        const active = calculation?.activeEffectIds?.includes(effect.id) || false;
        const enabled = Boolean(effectState[effect.id]);
        const owned = effectOwned(effect);
        const classes = ["damage-effect-card"];
        if (active) classes.push("is-active");
        if (!enabled) classes.push("is-disabled");
        if (enabled && !active) classes.push("is-blocked");
        if (!owned) classes.push("is-locked");
        const card = el("article", classes.join(" "));
        card.dataset.v2EffectCard = effect.id;

        const controlId = `damage-v2-effect-${effect.id}`;
        const heading = el("div", "damage-effect-card-heading");
        const title = el("span");
        title.append(el("small", "damage-effect-source", effect.source), el("b", "", effect.name));
        const toggle = el("label", "damage-toggle-control");
        toggle.htmlFor = controlId;
        const input = document.createElement("input");
        input.type = "checkbox";
        input.id = controlId;
        input.checked = enabled;
        input.disabled = !artifactAvailable(effect);
        input.dataset.v2Effect = effect.id;
        input.setAttribute("aria-label", effect.name);
        input.addEventListener("change", () => {
            effectState[effect.id] = input.checked;
            scheduleCalculation();
        });
        toggle.append(input, el("i"));
        heading.append(title, toggle);

        const description = el("label", "", effect.description);
        description.htmlFor = controlId;
        const footer = el("div", "damage-effect-card-footer");
        footer.append(
            el("span", "damage-effect-status", effectStatus(effect, active, enabled, owned)),
            el("small", "", effect.impact || "No direct damage modifier."),
        );
        card.append(heading, description, footer);
        if (effect.artifactOrigin) {
            const origin = effect.artifactOrigin;
            card.append(el("small", "damage-v2-artifact-piece-count",
                `${origin.equippedPieces}/${origin.requiredPieces} ${label("labelArtifactPieces", "pieces equipped")}`));
        }
        (effect.metrics || []).forEach((metric, index) => {
            const row = el("small", "damage-effect-metric");
            row.append(
                el("span", "", metric.label),
                token(active ? metricText(metric, calculation.effectMetrics[effect.id]?.[index]?.value ?? 0) : "—",
                    "formula-token-buff", expressionText(metric.expression, calculation.effectMetrics[effect.id]?.[index]?.references || [])),
            );
            card.append(row);
        });
        (calculation.statChanges || []).filter((change) => change.effectId === effect.id).forEach((change) => {
            const row = el("small", "damage-effect-metric");
            const expression = change.expression;
            row.append(el("span", "", inputById.get(change.stat)?.label || change.stat),
                token(`+${numberFormatter.format(change.amount)}`, "formula-token-buff",
                    expression ? expressionText(expression, change.references) : effect.description));
            card.append(row);
        });
        return card;
    }

    function renderEffects() {
        const existingSections = new Map(Array.from(effectsNode.querySelectorAll("[data-v2-effect-category]"))
            .map((section) => [section.dataset.v2EffectCategory, section]));
        const categories = ["passive", "team", "constellation",
            ...new Set(profile.effects.filter((effect) => effect.category.startsWith("weapon:")).map((effect) => effect.category)),
            ...(profile.artifactLoadouts || []).filter((loadout) => loadout.sets.length > 0)
                .map((loadout) => `artifact:${loadout.characterKey}`)];
        categories.forEach((categoryKey) => {
            const effects = profile.effects.filter((effect) => effect.category === categoryKey);
            const loadout = (profile.artifactLoadouts || []).find((item) => `artifact:${item.characterKey}` === categoryKey);
            if (!effects.length && !loadout) return;
            const activeCount = effects.filter((effect) => calculation?.activeEffectIds?.includes(effect.id)).length;
            const countText = `${activeCount}/${effects.length} ${label("labelEffectCount", "active")}`;
            const existing = existingSections.get(categoryKey);
            if (existing) {
                existing.querySelector(".damage-effect-count").textContent = countText;
                existing.querySelector(".damage-effect-list").replaceChildren(...effects.map(renderEffectCard));
                return; // Keep slider nodes/focus intact while calculation responses arrive.
            }
            const category = effectCategoryDefinition(categoryKey);
            const details = el("details", `damage-effect-section damage-effect-section-${loadout ? "artifact" : categoryKey}`);
            details.dataset.v2EffectCategory = categoryKey;
            details.open = category.defaultOpen;

            const summary = document.createElement("summary");
            const copy = el("span");
            copy.append(el("b", "", category.name), el("small", "", category.help));
            const tools = el("span", "damage-effect-summary-tools");
            tools.append(el(
                "span",
                "damage-effect-count",
                countText,
            ));
            const actions = el("span", "damage-effect-bulk-actions");
            actions.append(
                effectCategoryButton(label("labelEnableAll", "Enable all"), () => setEffectCategory(categoryKey, true)),
                effectCategoryButton(label("labelDisableAll", "Disable all"), () => setEffectCategory(categoryKey, false)),
            );
            if (categoryKey === "constellation") {
                actions.append(effectCategoryButton(
                    label("labelOwnedDefaults", "Owned defaults"),
                    resetConstellationsToOwnership,
                ));
            }
            tools.append(actions);
            summary.append(copy, tools);
            details.append(summary);
            if (loadout) {
                const equipment = el("div", "damage-v2-artifact-loadout");
                if (!loadout.sets.length) equipment.append(el("p", "", label("labelArtifactEmpty", "No equipped artifacts found. Import or equip artifacts in your inventory.")));
                loadout.sets.forEach((set) => {
                    const chip = el("span", "damage-v2-artifact-set-chip", `${set.pieces} × ${set.name}`);
                    if (!set.supported) chip.append(document.createTextNode(` · ${label("labelArtifactUnknown", "Formula not available")}`));
                    equipment.append(chip);
                });
                details.append(equipment);
            }
            if (loadout || categoryKey.startsWith("weapon:")) {
                const artifactInputs = profile.inputs.filter((input) => input.section === categoryKey);
                if (artifactInputs.length) {
                    const fields = el("div", "damage-v2-artifact-inputs");
                    artifactInputs.forEach((definition) => fields.append(renderInput(definition)));
                    details.append(fields);
                }
            }
            const list = el("div", "damage-effect-list");
            effects.forEach((effect) => list.append(renderEffectCard(effect)));
            details.append(list);
            effectsNode.append(details);
        });
        updateCurrentPresentation();
    }

    function token(text, className, tooltip) {
        const node = el("span", `damage-formula-token ${className}`, text);
        if (tooltip) {
            node.tabIndex = 0;
            node.dataset.tooltip = tooltip;
            node.setAttribute("aria-label", tooltip);
        }
        return node;
    }

    function taggedToken(text, className, hintTag, context = "") {
        return token(text, className, hint(hintTag, context));
    }

    function inputTooltip(inputId) {
        const definition = inputById.get(inputId);
        if (!definition) return inputId;
        const baseValue = calculation.inputs[inputId] ?? 0;
        const effectiveValue = calculation.effectiveStats[inputId] ?? baseValue;
        const sources = (calculation.statChanges || []).filter((change) => change.stat === inputId)
            .map((change) => `${profile.effects.find((effect) => effect.id === change.effectId)?.name}: +${numberFormatter.format(change.amount)}`);
        const sourceText = sources.length ? ` Active effect sources: ${sources.join(", ")}.` : "";
        const valueText = effectiveValue !== baseValue
            ? ` Base value: ${formatInput(definition, baseValue)}; effective value: ${formatInput(definition, effectiveValue)}.`
            : ` Current evaluated value: ${formatInput(definition, effectiveValue)}.`;
        const sharedHint = hint(definition.hintTag || inputHintTag[inputId] || inputId) || definition.label;
        return `${sharedHint} ${inputSource(definition)}.${valueText}${sourceText} ${definition.description || ""}`;
    }

    function inputToken(inputId, text) {
        const reactionResistance = inputId.match(/^enemyReaction([A-Z][a-z]+)Resistance$/);
        const className = reactionResistance
            ? `formula-token-element-${reactionResistance[1].toLowerCase()} formula-token-resistance`
            : inputTokenClass[inputId] || inputTokenClass[inputById.get(inputId)?.hintTag] || "formula-token-input";
        return token(text, className, inputTooltip(inputId));
    }

    function resultToken(value, result) {
        return taggedToken(
            damageFormatter.format(value),
            `formula-token-element-${result.element.toLowerCase()} formula-token-result`,
            result.damageType,
            "This is the result after every displayed multiplier.",
        );
    }

    function reactionResultToken(value, reaction) {
        const element = (reaction.damageElement || reaction.triggerElement).toLowerCase();
        return token(
            damageFormatter.format(value),
            `formula-token-element-${element} formula-token-result`,
            `${reaction.name} with ${reaction.triggerElement} as the triggering element.`,
        );
    }

    function referenceValue(expression, references) {
        if (expression.type === "constant") return expression.value;
        if (expression.type === "input") {
            return calculation.effectiveStats[expression.input] ?? calculation.inputs[expression.input] ?? 0;
        }
        if (expression.type === "talentParameter") {
            const reference = references.find((item) =>
                referenceMatches(item, expression),
            );
            return reference?.value ?? 0;
        }
        if (expression.type === "teamMax") return Math.max(0, ...(profile.teamStatInputs[expression.input] || [])
            .map((id) => calculation.effectiveStats[id] || 0));
        const operands = expression.operands.map((operand) => referenceValue(operand, references));
        if (expression.type === "add") return operands.reduce((total, value) => total + value, 0);
        if (expression.type === "multiply") return operands.reduce((total, value) => total * value, 1);
        if (expression.type === "divide") return operands[1] === 0 ? 0 : operands[0] / operands[1];
        if (expression.type === "min") return Math.min(...operands);
        if (expression.type === "max") return Math.max(...operands);
        return 0;
    }

    function referenceMatches(reference, expression) {
        return reference.type === "talentParameter" && reference.key === `${expression.talent}.${expression.parameter}` &&
            (reference.characterKey || profile.characterKey) === (expression.characterKey || profile.characterKey);
    }

    function expressionText(expression, references = []) {
        if (expression.type === "constant") return numberFormatter.format(expression.value);
        if (expression.type === "input") {
            const value = references.find((item) => item.type === "input" && item.key === expression.input)?.value
                ?? calculation.effectiveStats[expression.input] ?? 0;
            return `${shortInputLabel(expression.input)} (${numberFormatter.format(value)})`;
        }
        if (expression.type === "talentParameter") {
            const ref = references.find((item) => referenceMatches(item, expression));
            return `${expression.characterKey || profile.characterKey} ${expression.talent}.${expression.parameter}` +
                ` [Lv. ${ref?.talentLevel ?? calculation.effectiveStats[expression.levelInput]}: ${numberFormatter.format(ref?.value ?? 0)}]`;
        }
        if (expression.type === "teamMax") return `max(${(profile.teamStatInputs[expression.input] || [])
            .map((id) => expressionText({type: "input", input: id}, references)).join(", ")})`;
        const operands = expression.operands.map((operand) => expressionText(operand, references));
        if (["min", "max"].includes(expression.type)) return `${expression.type}(${operands.join(", ")})`;
        return `(${operands.join({add: " + ", multiply: " × ", divide: " ÷ "}[expression.type] || ", ")})`;
    }

    function joinSegments(groups, separator) {
        return groups.flatMap((group, index) => index === 0 ? group : [separator, ...group]);
    }

    function expressionSegments(expression, references) {
        if (expression.type === "constant") {
            return [token(numberFormatter.format(expression.value), "formula-token-buff", "Authored constant value.")];
        }
        if (expression.type === "input") {
            const value = calculation.effectiveStats[expression.input] ?? calculation.inputs[expression.input] ?? 0;
            return [inputToken(expression.input, `${numberFormatter.format(value)} ${shortInputLabel(expression.input)}`)];
        }
        if (expression.type === "talentParameter") {
            const reference = references.find((item) =>
                referenceMatches(item, expression),
            );
            const value = reference?.value ?? 0;
            const level = reference?.talentLevel ?? calculation.inputs[expression.levelInput];
            return [taggedToken(
                formatPercentRatio(value),
                "formula-token-scaling",
                "talentScaling",
                `Reference: ${expression.characterKey || profile.characterKey} · ${expression.talent}.${expression.parameter}, Talent Lv. ${level}.`,
            )];
        }
        if (expression.type === "teamMax") return [token(
            numberFormatter.format(referenceValue(expression, references)), "formula-token-em", expressionText(expression, references),
        )];
        const groups = expression.operands.map((operand) => expressionSegments(operand, references));
        if (expression.type === "add") return ["(", ...joinSegments(groups, " + "), ")"];
        if (expression.type === "multiply") return ["(", ...joinSegments(groups, " × "), ")"];
        if (expression.type === "divide") return ["(", ...groups[0], " ÷ ", ...groups[1], ")"];
        if (expression.type === "min" || expression.type === "max") {
            return [`${expression.type}(`, ...joinSegments(groups, ", "), ")"];
        }
        return [expression.type];
    }

    function shortInputLabel(inputId) {
        return {
            attack: "ATK",
            elementalMastery: "EM",
            veilStacks: "stacks",
        }[inputId] || inputById.get(inputId)?.label || inputId;
    }

    function compactScalingTerm(expression, references) {
        const operands = expression.type === "multiply" ? expression.operands : [expression];
        const stats = operands.filter((operand) => operand.type === "input");
        const scalings = operands.filter((operand) => operand.type === "talentParameter");
        const constants = operands.filter((operand) => operand.type === "constant");
        const stat = stats[0];
        const scaling = scalings[0];
        const fixedScaling = scalings.length === 0 && stats.length === 1 && constants.length === 1
            ? constants[0]
            : null;
        const unsupported = operands.some((operand) =>
            !["input", "talentParameter", "constant"].includes(operand.type),
        );
        if (stats.length !== 1 || (!fixedScaling && scalings.length !== 1) || unsupported) {
            return expressionSegments(expression, references);
        }

        const scalingReference = scaling ? references.find((item) =>
            referenceMatches(item, scaling),
        ) : null;
        const scalingValue = fixedScaling?.value ?? scalingReference?.value ?? 0;
        const talentLevel = scaling
            ? (scalingReference?.talentLevel ?? calculation.effectiveStats[scaling.levelInput])
            : null;
        const segments = [
            fixedScaling
                ? token(
                    formatPercentRatio(scalingValue),
                    "formula-token-scaling",
                    "Authored fixed scaling supplied by an active character effect.",
                )
                : taggedToken(
                    formatPercentRatio(scalingValue),
                    "formula-token-scaling",
                    "talentScaling",
                    `Reference: ${scaling.talent}.${scaling.parameter}, Talent Lv. ${talentLevel}.`,
                ),
            " ",
            inputToken(stat.input, shortInputLabel(stat.input)),
        ];
        const hitCount = constants
            .filter((operand) => operand !== fixedScaling)
            .reduce((product, operand) => product * operand.value, 1);
        if (hitCount !== 1) {
            segments.push(
                " × ",
                taggedToken(numberFormatter.format(hitCount), "formula-token-hit-count", "hitCount"),
            );
        }
        return segments;
    }

    function compactScalingSegments(expression, references) {
        if (expression.type === "add") {
            return joinSegments(
                expression.operands.map((operand) => compactScalingTerm(operand, references)),
                " + ",
            );
        }
        return compactScalingTerm(expression, references);
    }

    function damageTypeBadge(damageType) {
        const lunar = damageType.kind === "lunar";
        const badge = taggedToken(
            lunar ? (hintCatalog.get("lunarBloom")?.name || "Lunar-Bloom") : damageType.element,
            `damage-v2-type-badge ${lunar ? "is-lunar" : "is-direct"} is-${damageType.element.toLowerCase()}`,
            damageType.id,
        );
        badge.prepend(el("i", "damage-v2-type-dot"));
        return badge;
    }

    function appendSegments(node, segments) {
        node.replaceChildren(...segments.map((segment) =>
            typeof segment === "string" ? document.createTextNode(segment) : segment,
        ));
    }

    function targetMatches(targets, formula) {
        const damageType = profile.damageTypes.find((type) => type.id === formula.damageType);
        const element = damageType?.element;
        return (!targets.formulaIds.length || targets.formulaIds.includes(formula.id)) &&
            targets.requiredTags.every((tag) => formula.tags.includes(tag)) &&
            (!targets.anyTags?.length || targets.anyTags.some((tag) => formula.tags.includes(tag))) &&
            (!targets.damageKinds?.length || targets.damageKinds.includes(damageType?.kind)) &&
            (!targets.elements?.length || targets.elements.some((target) => target.toLowerCase() === element?.toLowerCase()));
    }

    function activeFormulaModifiers(formula) {
        const independent = profile.damageTypes.find((type) => type.id === formula.damageType)?.independentProc;
        return profile.effects.flatMap((effect) => {
            if (!calculation.activeEffectIds.includes(effect.id)) return [];
            return effect.formulaModifiers
                .filter((modifier) => targetMatches(modifier.targets, formula) &&
                    (modifier.requiresEffects || []).every((id) => calculation.activeEffectIds.includes(id)) &&
                    (modifier.conditions || []).every(inputConditionSatisfied))
                .filter((modifier) => !independent || modifier.resistanceMitigation)
                .map((modifier) => ({effect, modifier: independent ? {resistanceMitigation: modifier.resistanceMitigation} : modifier}));
        });
    }

    function effectToken(text, effect, className = "formula-token-effect formula-token-effect-character", expression = null, references = []) {
        return token(text, className, `${effect.name} — ${effect.description}` +
            (expression ? ` Formula: ${expressionText(expression, references)}.` : ""));
    }

    function contributionRatio(contribution, references) {
        const value = referenceValue(contribution.expression, references);
        return contribution.unit === "percentPoints" ? value / 100 : value;
    }

    function formulaRow(labelText, helpText, segments, className = "") {
        const row = el("div", className);
        const copy = el("span");
        copy.append(el("b", "", labelText), el("small", "", helpText));
        const code = el("code");
        appendSegments(code, segments);
        row.append(copy, code);
        return row;
    }

    function baseSegments(formula, result) {
        const segments = [...expressionSegments(formula.base, result.base.references)];
        const modifiers = activeFormulaModifiers(formula);
        modifiers.forEach(({effect, modifier}) => {
            if (!modifier.base) return;
            const value = referenceValue(modifier.base, result.base.references);
            segments.push(
                " + [",
                ...expressionSegments(modifier.base, result.base.references),
                " = ",
                effectToken(numberFormatter.format(value), effect),
                "]",
            );
        });
        const baseDamageBonuses = modifiers.filter(({modifier}) => modifier.baseDamageBonus);
        if (baseDamageBonuses.length) {
            segments.unshift("(");
            segments.push(") × (1 + ");
            baseDamageBonuses.forEach(({effect, modifier}, index) => {
                if (index > 0) segments.push(" + ");
                segments.push(effectToken(
                    formatPercentRatio(contributionRatio(modifier.baseDamageBonus, result.base.references)),
                    effect,
                    "formula-token-effect formula-token-lunar",
                    modifier.baseDamageBonus.expression, result.base.references,
                ));
            });
            segments.push(")");
        }
        segments.push(" = ", resultToken(result.base.value, result));
        return segments;
    }

    function elevationSegments(formula, result) {
        const modifiers = activeFormulaModifiers(formula)
            .filter(({modifier}) => modifier.elevation);
        if (!modifiers.length) {
            return [taggedToken(formatMultiplier(1), "formula-token-lunar", "lunarBloom", "No Elevation modifier applies.")];
        }
        const segments = ["1"];
        modifiers.forEach(({effect, modifier}) => {
            segments.push(
                " + ",
                effectToken(
                    formatPercentRatio(contributionRatio(modifier.elevation, result.elevation.references)),
                    effect,
                    "formula-token-effect formula-token-lunar",
                ),
            );
        });
        segments.push(
            " = ",
            taggedToken(formatMultiplier(result.elevation.multiplier), "formula-token-lunar", "lunarBloom", "Final Elevation multiplier."),
        );
        return segments;
    }

    function postBonusSegments(formula, result) {
        const modifiers = activeFormulaModifiers(formula).filter(({modifier}) => modifier.postBonusBase);
        if (!modifiers.length) return ["0"];
        return [...joinSegments(modifiers.map(({effect, modifier}) => [
            "[", ...expressionSegments(modifier.postBonusBase, result.postBonusBase.references), " = ",
            effectToken(numberFormatter.format(referenceValue(modifier.postBonusBase, result.postBonusBase.references)),
                effect, "formula-token-effect", modifier.postBonusBase, result.postBonusBase.references),
            "]",
        ]), " + "), " → ", resultToken(result.postBonusBase.value, result)];
    }

    function additionalCritSegments(formula, result, field) {
        const segments = [];
        if (formula.crit?.[field]) segments.push(" + ", token(
            formatPercentRatio(contributionRatio(formula.crit[field], result.crit.references)),
            "formula-token-buff", expressionText(formula.crit[field].expression, result.crit.references),
        ));
        activeFormulaModifiers(formula).forEach(({effect, modifier}) => {
            const contribution = modifier.crit?.[field];
            if (contribution) segments.push(" + ", effectToken(
                formatPercentRatio(contributionRatio(contribution, result.crit.references)), effect,
                "formula-token-buff", contribution.expression, result.crit.references,
            ));
        });
        return segments;
    }

    function bonusSegments(formula, result, damageType) {
        const segments = ["1"];
        if (damageType.appliesElementalDamageBonus) {
            segments.push(
                " + ",
                inputToken("elementalDamageBonus", formatPercentRatio(result.damageBonus.elementalBonus)),
            );
        }
        if (damageType.innateDamageBonus) {
            const innateRatio = referenceValue(
                damageType.innateDamageBonus.expression,
                result.damageBonus.references,
            );
            const elementalMastery = calculation.effectiveStats.elementalMastery ??
                calculation.inputs.elementalMastery ?? 0;
            const formattedEm = numberFormatter.format(elementalMastery);
            segments.push(
                " + ",
                taggedToken(
                    `${formatPercentRatio(innateRatio)} Lunar EM`,
                    "formula-token-lunar",
                    "lunarEmBonus",
                    `Current calculation: ((6 × ${formattedEm}) ÷ (${formattedEm} + 2,000)) × 100% = ${formatPercentRatio(innateRatio)}.`,
                ),
            );
        }
        if (formula.damageBonus) {
            segments.push(" + [", ...expressionSegments(formula.damageBonus.expression, result.damageBonus.references), "]");
        }
        activeFormulaModifiers(formula).forEach(({effect, modifier}) => {
            if (!modifier.damageBonus) return;
            segments.push(
                " + ",
                token(
                    `${formatPercentRatio(contributionRatio(modifier.damageBonus, result.damageBonus.references))} ${effect.name}`,
                    "formula-token-effect formula-token-effect-character",
                    `${effect.name} — ${effect.description} Formula: ${expressionText(modifier.damageBonus.expression, result.damageBonus.references)}.`,
                ),
            );
        });
        segments.push(
            " = ",
            taggedToken(
                formatMultiplier(result.damageBonus.multiplier),
                "formula-token-multiplier",
                "damageBonusMultiplier",
            ),
        );
        return segments;
    }

    function defenseSegments(formula, result) {
        if (result.defenseMitigation.bypassed) {
            const type = profile.damageTypes.find((item) => item.id === result.damageType);
            return [
                taggedToken(
                    hintCatalog.get(result.damageType)?.name || type?.element || result.damageType,
                    type?.kind === "lunar" ? "formula-token-lunar" : `formula-token-element-${result.element.toLowerCase()}`,
                    result.damageType,
                ),
                " → ",
                taggedToken(
                    formatMultiplier(1),
                    "formula-token-defense-multiplier",
                    "defenseMultiplier",
                    label("labelBypassed", "Bypassed by damage type"),
                ),
            ];
        }
        const characterLevel = calculation.inputs.characterLevel;
        const enemyLevel = calculation.inputs.enemyLevel;
        const reductionSources = activeFormulaModifiers(formula).filter(({modifier}) => modifier.defenseMitigation?.reduction)
            .map(({effect, modifier}) => `${effect.name}: ${expressionText(modifier.defenseMitigation.reduction.expression, result.defenseMitigation.references)}`);
        return [
            "(", inputToken("characterLevel", numberFormatter.format(characterLevel)), " + 100) ÷ [(",
            inputToken("characterLevel", numberFormatter.format(characterLevel)), " + 100) + (",
            inputToken("enemyLevel", numberFormatter.format(enemyLevel)), " + 100) × (1 − ",
            token(formatPercentRatio(result.defenseMitigation.reduction), "formula-token-defense",
                `${inputTooltip("defenseReduction")} ${reductionSources.join("; ")}`), ") × (1 − ",
            inputToken("defenseIgnore", formatPercentRatio(result.defenseMitigation.ignore)), ")] = ",
            taggedToken(
                formatMultiplier(result.defenseMitigation.multiplier),
                "formula-token-defense-multiplier",
                "defenseMultiplier",
            ),
        ];
    }

    function resistanceSegments(formula, result) {
        const resistanceInput = profile.damageTypes.find((type) => type.id === result.damageType)?.resistanceInput || "enemyResistance";
        const elementClass = `formula-token-element-${result.element.toLowerCase()}`;
        const segments = [
            inputToken(resistanceInput, `${numberFormatter.format(calculation.inputs[resistanceInput])}%`),
        ];
        const reductions = [];
        if (formula.resistanceMitigation?.reduction) {
            reductions.push(token(
                formatPercentRatio(contributionRatio(
                    formula.resistanceMitigation.reduction,
                    result.resistanceMitigation.references,
                )),
                `${elementClass} formula-token-resistance`,
                "Resistance reduction authored directly on this formula.",
            ));
        }
        activeFormulaModifiers(formula).forEach(({effect, modifier}) => {
            if (!modifier.resistanceMitigation?.reduction) return;
            reductions.push(effectToken(
                formatPercentRatio(contributionRatio(
                    modifier.resistanceMitigation.reduction,
                    result.resistanceMitigation.references,
                )),
                effect,
                `formula-token-effect ${elementClass} formula-token-resistance`,
            ));
        });
        if (!reductions.length) {
            reductions.push(token(
                "0%",
                `${elementClass} formula-token-resistance`,
                "No additional resistance reduction applies.",
            ));
        }
        reductions.forEach((reduction) => segments.push(" − ", reduction));
        segments.push(
            " → ",
            taggedToken(
                formatMultiplier(result.resistanceMitigation.multiplier),
                `${elementClass} formula-token-resistance-multiplier`,
                "resistanceMultiplier",
            ),
        );
        return segments;
    }

    function sourceTalentKey(talent) {
        let found = null;
        function visit(expression) {
            if (!expression || found) return;
            if (expression.type === "talentParameter") found = expression.talent;
            expression.operands?.forEach(visit);
        }
        talent.formulas.forEach((formula) => visit(formula.base));
        return found || {normal: "combat1", skill: "combat2", burst: "combat3"}[talent.key];
    }

    function talentMarker(talent) {
        const marker = el("span", "damage-talent-marker");
        marker.append(el("span", "damage-talent-marker-fallback", markerByTalent[talent.key] || "◇"));
        const talentKey = sourceTalentKey(talent);
        if (talentKey && root.dataset.talentMediaBase) {
            const image = document.createElement("img");
            image.src = `${root.dataset.talentMediaBase}/${encodeURIComponent(talentKey)}`;
            image.alt = "";
            image.loading = "lazy";
            image.addEventListener("error", () => image.remove());
            marker.append(image);
        }
        return marker;
    }

    function renderTalentDescription(talent, open) {
        const description = talentDescriptions.get(sourceTalentKey(talent));
        if (!description) return null;
        const details = el("details", "damage-v2-talent-description");
        details.dataset.v2TalentDescription = talent.key;
        details.open = open;
        const summary = el("summary");
        summary.append(
            el("span", "", label("labelSkillDescription", "Skill description")),
            el("i", "", "+"),
        );
        details.append(summary, el("div", "damage-v2-talent-description-body", description));
        return details;
    }

    function renderReactionResults(result) {
        if (!result.reactions?.length) return null;
        const panel = el("div", "damage-v2-reaction-results");
        result.reactions.forEach((reaction) => {
            const card = el("article", `damage-v2-reaction-result is-${reaction.kind}`);
            const heading = el("header");
            const identity = el("div");
            const resultElement = (reaction.damageElement || reaction.triggerElement).toLowerCase();
            const kindLabels = {
                amplifying: label("labelReactionAmplifying", "Amplifying"),
                additive: label("labelReactionAdditive", "Additive"),
                transformative: label("labelReactionTransformative", "Transformative"),
                utility: label("labelReactionUtility", "Status"),
                shield: label("labelReactionShield", "Shield"),
            };
            identity.append(
                token(reaction.name, `formula-token-element-${resultElement}`,
                    `${reaction.triggerElement} triggers ${reaction.name} on ${reaction.auraLabel}.`),
                el("span", "damage-v2-reaction-kind", kindLabels[reaction.kind] || reaction.kind),
                el("small", "", `${label("labelReactionTrigger", "Trigger")} · ${reaction.triggerElement} · ` +
                    `${label("labelReactionAura", "Aura")} · ${reaction.auraLabel}` +
                    (reaction.auraProviders?.length ? ` · ${reaction.auraProviders.join(", ")}` : "")),
            );
            let primary;
            if (reaction.kind === "utility") {
                primary = el("strong", "is-utility", label("labelReactionNoDamage", "No direct damage"));
            } else if (reaction.kind === "shield") {
                primary = el("strong", "", damageFormatter.format(reaction.utilityValue || 0));
                primary.prepend(el("small", "", `${label("labelReactionShieldAbsorption", "Shield absorption")} `));
            } else {
                primary = el("strong", "", damageFormatter.format(reaction.average));
                primary.prepend(el("small", "", reaction.kind === "transformative"
                    ? `${label("labelReactionDamage", "Reaction DMG")} `
                    : `${label("labelAverage", "Average")} `));
            }
            heading.append(identity, primary);

            const outcomes = el("dl");
            const outcomeValues = reaction.kind === "transformative"
                ? [
                    [label("labelReactionDamage", "Reaction DMG"), reaction.reactionDamage],
                    [label("labelReactionCombined", "Direct + reaction average"), reaction.combinedAverage],
                ]
                : ["amplifying", "additive"].includes(reaction.kind)
                    ? [
                        [label("labelNonCrit", "Non-CRIT"), reaction.nonCritical],
                        [label("labelCrit", "CRIT"), reaction.critical],
                    ]
                    : [];
            outcomeValues.forEach(([name, value]) => {
                const group = el("div");
                group.append(el("dt", "", name), el("dd", "", damageFormatter.format(value)));
                outcomes.append(group);
            });

            const formula = el("code", "damage-v2-reaction-formula");
            const emToken = token(formatPercentRatio(reaction.elementalMasteryBonus), "formula-token-em",
                `${label("labelReactionEmBonus", "EM bonus")}: ` +
                `(${numberFormatter.format(reaction.elementalMasteryCoefficient)} × ${numberFormatter.format(reaction.elementalMastery)}) ÷ ` +
                `(${numberFormatter.format(reaction.elementalMastery)} + ${numberFormatter.format(reaction.elementalMasteryDenominator)}).`);
            const bonusSegments = [];
            (reaction.bonusSources || []).forEach((source) => {
                const effect = profile.effects.find((candidate) => candidate.id === source.effectId);
                bonusSegments.push(
                    " + ",
                    effect
                        ? effectToken(formatPercentRatio(source.value), effect)
                        : token(formatPercentRatio(source.value), "formula-token-buff", source.effectId),
                );
            });
            if (!reaction.bonusSources?.length) {
                bonusSegments.push(" + ", token("0%", "formula-token-buff",
                    label("labelReactionBonus", "Reaction DMG Bonus")));
            }
            const scalingToken = token(numberFormatter.format(reaction.baseMultiplier),
                `formula-token-element-${resultElement}`,
                `${reaction.name}'s reaction scaling coefficient.`);
            let segments;
            if (reaction.kind === "amplifying") {
                segments = [
                    token(formatMultiplier(reaction.baseMultiplier), `formula-token-element-${resultElement}`,
                        `${reaction.name}'s base multiplier for a ${reaction.triggerElement} trigger.`),
                    " × (1 + ", emToken, ...bonusSegments, ") = ",
                    token(formatMultiplier(reaction.multiplier), "formula-token-multiplier",
                        label("labelReactionMultiplier", "Reaction multiplier")),
                    " · ", resultToken(result.average, result), " × ",
                    token(formatMultiplier(reaction.multiplier), "formula-token-multiplier",
                        label("labelReactionMultiplier", "Reaction multiplier")),
                    " = ", reactionResultToken(reaction.average, reaction),
                ];
            } else if (reaction.kind === "additive") {
                segments = [
                    taggedToken(numberFormatter.format(reaction.levelMultiplier), "formula-token-character-level",
                        "characterLevel", label("labelReactionLevelMultiplier", "Reaction level multiplier")),
                    " × ", scalingToken, " × (1 + ", emToken, ...bonusSegments, ") = ",
                    token(numberFormatter.format(reaction.reactionBase), `formula-token-element-${resultElement}`,
                        label("labelReactionAdditiveBase", "Additive reaction base")),
                    " → × ", token(formatMultiplier(result.damageBonus.multiplier), "formula-token-bonus-multiplier",
                        label("labelBonus", "DMG Bonus")),
                    " × ", token(formatMultiplier(result.defenseMitigation.multiplier), "formula-token-defense-multiplier",
                        label("labelDefense", "Defense mitigation")),
                    " × ", token(formatMultiplier(result.resistanceMitigation.multiplier),
                        `formula-token-element-${resultElement} formula-token-resistance-multiplier`,
                        label("labelResistance", "Resistance mitigation")),
                    " × ", token(formatMultiplier(result.elevation.multiplier), "formula-token-elevation",
                        label("labelElevation", "Elevation")),
                    " × ", token(formatMultiplier(1 + result.crit.effectiveRate * result.crit.effectiveDamage),
                        "formula-token-crit-rate", label("labelAverage", "Average CRIT multiplier")),
                    " = ", reactionResultToken(reaction.reactionDamage, reaction),
                    " · ", resultToken(result.average, result), " + ",
                    reactionResultToken(reaction.reactionDamage, reaction), " = ",
                    reactionResultToken(reaction.average, reaction),
                ];
            } else if (reaction.kind === "transformative") {
                const resistanceSegments = [
                    reaction.resistanceInput
                        ? inputToken(reaction.resistanceInput, formatPercentRatio(reaction.enemyResistance))
                        : token(formatPercentRatio(reaction.enemyResistance || 0),
                            `formula-token-element-${resultElement} formula-token-resistance`,
                            label("labelReactionResistance", "Reaction element resistance")),
                ];
                (reaction.resistanceSources || []).forEach((source) => {
                    const effect = profile.effects.find((candidate) => candidate.id === source.effectId);
                    resistanceSegments.push(
                        " − ",
                        effect
                            ? effectToken(formatPercentRatio(source.value), effect,
                                `formula-token-effect formula-token-element-${resultElement} formula-token-resistance`)
                            : token(formatPercentRatio(source.value), "formula-token-resistance", source.effectId),
                    );
                });
                if (!reaction.resistanceSources?.length) {
                    resistanceSegments.push(" − ", token("0%", "formula-token-resistance",
                        label("labelReactionResistance", "Reaction element resistance")));
                }
                resistanceSegments.push(" → ", token(formatMultiplier(reaction.resistanceMultiplier),
                    `formula-token-element-${resultElement} formula-token-resistance-multiplier`,
                    label("labelReactionResistance", "Reaction element resistance")));
                segments = [
                    taggedToken(numberFormatter.format(reaction.levelMultiplier), "formula-token-character-level",
                        "characterLevel", label("labelReactionLevelMultiplier", "Reaction level multiplier")),
                    " × ", scalingToken, " × (1 + ", emToken, ...bonusSegments, ") × [",
                    ...resistanceSegments, "] = ", reactionResultToken(reaction.reactionDamage, reaction),
                    " · ", token(label("labelReactionIgnoresDefenseCrit",
                        "Transformative reactions ignore DEF and cannot CRIT."), "formula-token-input",
                    label("labelReactionIgnoresDefenseCrit",
                        "Transformative reactions ignore DEF and cannot CRIT.")),
                    " · ", resultToken(result.average, result), " + ",
                    reactionResultToken(reaction.reactionDamage, reaction), " = ",
                    reactionResultToken(reaction.combinedAverage, reaction),
                ];
            } else if (reaction.kind === "shield") {
                segments = [
                    taggedToken(numberFormatter.format(reaction.levelMultiplier), "formula-token-character-level",
                        "characterLevel", label("labelReactionCrystallizeBase", "Crystallize base shield at this level")),
                    " × (1 + ", emToken, ...bonusSegments, ") = ",
                    token(damageFormatter.format(reaction.utilityValue || 0), "formula-token-defense",
                        label("labelReactionShieldAbsorption", "Shield absorption")),
                ];
            } else {
                segments = [token(label("labelReactionNoDamage", "No direct damage"), "formula-token-input",
                    reaction.description || label("labelReactionNoDamage", "No direct damage"))];
            }
            appendSegments(formula, segments);
            card.append(heading);
            if (reaction.description) card.append(el("p", "damage-v2-reaction-description", reaction.description));
            if (outcomeValues.length) card.append(outcomes);
            card.append(formula);
            panel.append(card);
        });
        return panel;
    }

    function renderResults() {
        const openFormulaIds = new Set(
            Array.from(resultsNode.querySelectorAll("details[open][data-v2-formula-details]"))
                .map((node) => node.dataset.v2FormulaDetails),
        );
        const openTalentDescriptions = new Set(
            Array.from(resultsNode.querySelectorAll("details[open][data-v2-talent-description]"))
                .map((node) => node.dataset.v2TalentDescription),
        );
        resultsNode.replaceChildren();
        const resultById = new Map(calculation.results.map((result) => [result.formulaId, result]));
        const damageTypeById = new Map(profile.damageTypes.map((type) => [type.id, type]));
        profile.talents.forEach((talent) => {
            if (!talent.formulas.some((formula) => resultById.has(formula.id))) return;
            const section = el("section", "damage-talent");
            const heading = el("header", "damage-talent-heading");
            heading.append(talentMarker(talent));
            const title = el("div");
            title.append(el("small", "", talent.key.startsWith("artifact.") ? label("labelArtifacts", "Artifact sets") : talent.key), el("h3", "", talent.name));
            const levelInput = findTalentLevelInput(talent);
            const level = levelInput ? calculation.effectiveStats[levelInput] : null;
            const levelNode = el("span", "damage-talent-level");
            levelNode.append(el("span", "", label("labelTalentLevel", "Level")), el("b", "", level == null ? "—" : numberFormatter.format(level)));
            heading.append(title);
            if (level != null) heading.append(levelNode);

            const grid = el("div", "damage-instance-grid");
            talent.formulas.forEach((formula) => {
                const result = resultById.get(formula.id);
                if (!result) return;
                const damageType = damageTypeById.get(formula.damageType);
                grid.append(renderFormulaCard(formula, result, damageType, openFormulaIds.has(formula.id) || allExpanded));
            });
            section.append(heading);
            const description = renderTalentDescription(talent, openTalentDescriptions.has(talent.key));
            if (description) section.append(description);
            section.append(grid);
            resultsNode.append(section);
        });
    }

    function renderFormulaCard(formula, result, damageType, open) {
        const hasModifier = (part) => formula[part] || activeFormulaModifiers(formula).some(({modifier}) => modifier[part]);
        const card = el("article", "damage-instance");
        card.dataset.v2Formula = formula.id;
        const heading = el("div", "damage-instance-heading");
        const title = el("div");
        const titleRow = el("span", "damage-instance-title-row");
        titleRow.append(el("h4", "", formula.label));
        const scaling = el("small", "damage-v2-main-scaling");
        appendSegments(scaling, compactScalingSegments(formula.base, result.base.references));
        title.append(titleRow, scaling);
        heading.append(title, damageTypeBadge(damageType));

        const average = el("div", "damage-average-result");
        average.append(
            el("small", "", label("labelAverage", "Average")),
            el("strong", "", damageFormatter.format(result.average)),
        );
        const secondary = el("dl", "damage-secondary-results");
        [[label("labelNonCrit", "Non-CRIT"), result.nonCritical], [label("labelCrit", "CRIT"), result.critical]]
            .forEach(([name, value]) => {
                const group = el("div");
                group.append(el("dt", "", name), el("dd", "", damageFormatter.format(value)));
                secondary.append(group);
            });

        const details = el("details", "damage-calculation-breakdown");
        details.dataset.v2FormulaDetails = formula.id;
        details.open = open;
        const detailsSummary = el("summary");
        detailsSummary.append(
            el("span", "", label("labelViewFormula", "View full calculation")),
            el("i", "", "+"),
        );
        const rows = el("div", "damage-formula-rows");
        rows.append(
            formulaRow(
                label("labelBase", "Talent base damage"),
                `Stored expression · ${formula.id}.base`,
                baseSegments(formula, result),
            ),
            formulaRow(
                label("labelBonus", "Damage bonuses"),
                hasModifier("damageBonus") ? "Shared values plus active authored modifiers." : label("labelNoEffect", "No additional authored modifier"),
                bonusSegments(formula, result, damageType),
            ),
            formulaRow(
                label("labelDefense", "Defense mitigation"),
                result.defenseMitigation.bypassed ? label("labelBypassed", "Bypassed by damage type") : (hasModifier("defenseMitigation") ? "Includes active authored modifiers." : label("labelNoEffect", "No additional authored modifier")),
                defenseSegments(formula, result),
            ),
            formulaRow(
                label("labelResistance", "Resistance mitigation"),
                hasModifier("resistanceMitigation") ? "Includes active authored modifiers." : label("labelNoEffect", "No additional authored modifier"),
                resistanceSegments(formula, result),
            ),
            formulaRow(
                label("labelElevation", "Elevation"),
                result.elevation.authoredBonus
                    ? "A separate Lunar damage multiplier applied after RES."
                    : label("labelNoEffect", "No additional authored modifier"),
                elevationSegments(formula, result),
            ),
            formulaRow(
                label("labelPostBonusBase", "Additive damage"),
                "Added after DMG Bonus, before mitigation and CRIT. Each card assumes its own available buff stack.",
                postBonusSegments(formula, result),
            ),
            formulaRow(
                label("labelNonCrit", "Non-CRIT"),
                "(Base × DMG Bonus + Additive) × DEF × RES × Elevation",
                [
                    "(", resultToken(result.base.value, result), " × ",
                    taggedToken(multiplierFormatter.format(result.damageBonus.multiplier), "formula-token-multiplier", "damageBonusMultiplier"),
                    " + ", resultToken(result.postBonusBase.value, result), ") × ",
                    taggedToken(multiplierFormatter.format(result.defenseMitigation.multiplier), "formula-token-defense-multiplier", "defenseMultiplier"), " × ",
                    taggedToken(multiplierFormatter.format(result.resistanceMitigation.multiplier), `formula-token-element-${result.element.toLowerCase()} formula-token-resistance-multiplier`, "resistanceMultiplier"), " × ",
                    taggedToken(multiplierFormatter.format(result.elevation.multiplier), "formula-token-lunar", "lunarBloom", "Elevation multiplier."), " = ",
                    resultToken(result.nonCritical, result),
                ],
                "damage-formula-result-row",
            ),
            formulaRow(
                label("labelCrit", "CRIT"),
                damageType.allowsCriticalHits === false ? label("labelCannotCrit", "This damage instance cannot CRIT.") :
                    (hasModifier("crit") ? "Base CRIT DMG plus active authored modifiers." : label("labelNoEffect", "No additional authored modifier")),
                damageType.allowsCriticalHits === false ? [resultToken(result.nonCritical, result), " × 1 = ", resultToken(result.critical, result)] : [
                    resultToken(result.nonCritical, result), " × (1 + ",
                    inputToken("critDamage", `${percentFormatter.format(calculation.effectiveStats.critDamage)}%`),
                    ...additionalCritSegments(formula, result, "additionalDamage"),
                    ") = ", resultToken(result.critical, result),
                ],
                "damage-formula-result-row",
            ),
            formulaRow(
                label("labelAverage", "Average"),
                damageType.allowsCriticalHits === false ? label("labelCannotCrit", "This damage instance cannot CRIT.") : "CRIT Rate-weighted expected damage.",
                damageType.allowsCriticalHits === false ? [resultToken(result.nonCritical, result), " × 1 = ", resultToken(result.average, result)] : [
                    resultToken(result.nonCritical, result), " × [1 + clamp(",
                    inputToken("critRate", `${percentFormatter.format(calculation.effectiveStats.critRate)}%`),
                    ...additionalCritSegments(formula, result, "additionalRate"),
                    ", 0%, 100%) × ", taggedToken(formatPercentRatio(result.crit.effectiveDamage), "formula-token-crit-damage", "critMultiplier", "Effective CRIT DMG."),
                    "] = ", resultToken(result.average, result),
                ],
                "damage-formula-result-row damage-formula-average-row",
            ),
        );
        details.append(detailsSummary, rows);
        card.append(heading, average, secondary);
        const reactions = renderReactionResults(result);
        if (reactions) card.append(reactions);
        card.append(details);
        return card;
    }

    function findTalentLevelInput(talent) {
        let found = null;
        function visit(expression) {
            if (!expression || found) return;
            if (expression.type === "talentParameter") found = expression.levelInput;
            expression.operands?.forEach(visit);
        }
        talent.formulas.forEach((formula) => visit(formula.base));
        return found;
    }

    function applyCalculation(nextCalculation) {
        calculation = nextCalculation;
        updateEffectiveStats();
        renderEffects();
        renderResults();
        workspace.hidden = false;
        workspace.classList.remove("is-updating");
        statusNode.hidden = true;
        errorNode.hidden = true;
    }

    function scheduleCalculation(delay = 90) {
        window.clearTimeout(calculateTimer);
        calculateTimer = window.setTimeout(calculate, delay);
    }

    function requestHeaders() {
        const headers = {"Content-Type": "application/json", "Accept": "application/json"};
        if (root.dataset.csrfHeader && root.dataset.csrfToken) headers[root.dataset.csrfHeader] = root.dataset.csrfToken;
        return headers;
    }

    async function calculate() {
        const version = ++calculateVersion;
        calculateController?.abort();
        calculateController = new AbortController();
        workspace.classList.add("is-updating");
        statusNode.classList.remove("damage-v2-update-error");
        const companionActivity = window.archiveCompanion?.begin();
        try {
            const headers = requestHeaders();
            const response = await fetch(root.dataset.calculateUrl, {
                method: "POST",
                headers,
                body: JSON.stringify({inputs: inputState, enabledEffects: effectState, teamKeys: teamSlots.filter(Boolean)}),
                signal: calculateController.signal,
            });
            if (!response.ok) throw new Error(`Calculation failed (${response.status})`);
            const nextCalculation = await response.json();
            if (version !== calculateVersion) return;
            applyCalculation(nextCalculation);
        } catch (error) {
            if (error.name === "AbortError" || version !== calculateVersion) return;
            workspace.classList.remove("is-updating");
            statusNode.hidden = false;
            statusNode.classList.add("damage-v2-update-error");
            statusNode.lastElementChild.textContent = error.message || label("labelError", "Calculation failed");
        } finally {
            window.archiveCompanion?.end(companionActivity);
        }
    }

    function reset() {
        inputState = Object.fromEntries(profile.inputs.map((input) => [input.id, input.defaultValue]));
        effectState = Object.fromEntries(profile.effects.map((effect) => [effect.id, effect.defaultEnabled]));
        profile.inputs.forEach(syncInputControl);
        updateCurrentPresentation();
        updateEffectiveStats();
        renderEffects();
        scheduleCalculation(0);
    }

    function syncInputControl(definition) {
        const control = controls.get(definition.id);
        if (!control) return;
        if (isToggle(definition)) {
            control.checked = inputState[definition.id] > 0;
            return;
        }
        control.value = inputState[definition.id];
        rangeProgress(control);
        const number = root.querySelector(`[data-v2-number-input="${definition.id}"]`);
        if (number) number.value = inputState[definition.id];
        const output = root.querySelector(`[data-v2-input-output="${definition.id}"]`);
        if (output) output.textContent = formatInput(definition, inputState[definition.id]);
    }

    function resetCharacter(characterKey) {
        const inputIds = new Set([
            ...Object.values(profile.characterProgressInputs?.[characterKey] || {}),
            ...Object.values(profile.characterStatInputs?.[characterKey] || {}),
            ...(profile.characterBuffInputs?.[characterKey] || []),
        ]);
        profile.inputs.filter((definition) => inputIds.has(definition.id)).forEach((definition) => {
            inputState[definition.id] = definition.defaultValue;
            syncInputControl(definition);
        });
        updateCurrentPresentation();
        updateEffectiveStats();
        scheduleCalculation(0);
    }

    function setEffectCategory(category, enabled) {
        profile.effects.filter((effect) => effect.category === category).forEach((effect) => {
            effectState[effect.id] = enabled && artifactAvailable(effect);
        });
        renderEffects();
        scheduleCalculation(0);
    }

    function resetConstellationsToOwnership() {
        profile.effects.filter((effect) => effect.category === "constellation").forEach((effect) => {
            effectState[effect.id] = effectOwned(effect);
        });
        renderEffects();
        scheduleCalculation(0);
    }

    function synchronizeOwnershipEffects(changedInput) {
        const ownedEffects = profile.effects.filter((effect) =>
            changedInput in (effect.ownershipRequirements || {}),
        );
        if (!ownedEffects.length) return;
        ownedEffects.forEach((effect) => {
            effectState[effect.id] = effectOwned(effect);
        });
        renderEffects();
    }

    function toggleAllFormulas() {
        allExpanded = !allExpanded;
        root.querySelectorAll("[data-v2-formula-details]").forEach((details) => {
            details.open = allExpanded;
        });
        expandButton.textContent = allExpanded
            ? label("labelCollapse", "Collapse all calculations")
            : label("labelExpand", "Expand all calculations");
    }

    function selectTab(tabKey, focus = false) {
        tabButtons.forEach((button) => {
            const selected = button.dataset.v2Tab === tabKey;
            button.setAttribute("aria-selected", String(selected));
            button.tabIndex = selected ? 0 : -1;
            if (selected && focus) button.focus();
        });
        tabPanels.forEach((panel) => {
            panel.hidden = panel.dataset.v2TabPanel !== tabKey;
        });
        if (tabKey === "stats") scheduleStatAlignment();
    }

    tabButtons.forEach((button, index) => {
        button.addEventListener("click", () => selectTab(button.dataset.v2Tab));
        button.addEventListener("keydown", (event) => {
            let nextIndex = null;
            if (["ArrowRight", "ArrowDown"].includes(event.key)) nextIndex = (index + 1) % tabButtons.length;
            if (["ArrowLeft", "ArrowUp"].includes(event.key)) nextIndex = (index - 1 + tabButtons.length) % tabButtons.length;
            if (event.key === "Home") nextIndex = 0;
            if (event.key === "End") nextIndex = tabButtons.length - 1;
            if (nextIndex == null) return;
            event.preventDefault();
            selectTab(tabButtons[nextIndex].dataset.v2Tab, true);
        });
    });

    function showInitialError(error) {
        statusNode.hidden = true;
        workspace.hidden = true;
        errorNode.hidden = false;
        errorMessageNode.textContent = error.message || label("labelError", "The calculator could not be loaded.");
    }

    async function initialize() {
        statusNode.hidden = false;
        errorNode.hidden = true;
        try {
            const requestedTeam = new URLSearchParams(window.location.search).getAll("team").filter(Boolean);
            const profileUrl = new URL(root.dataset.profileUrl, window.location.origin);
            requestedTeam.forEach((characterKey) => profileUrl.searchParams.append("team", characterKey));
            const response = await fetch(profileUrl, {headers: {"Accept": "application/json"}});
            if (!response.ok) throw new Error(`Profile failed to load (${response.status})`);
            const documentPayload = await response.json();
            if (!documentPayload.profile || !documentPayload.defaultCalculation) {
                throw new Error(label("labelError", "The endpoint returned an invalid profile."));
            }
            profile = documentPayload.profile;
            storedBuilds = documentPayload.teamBuilds || [];
            const selectedOrder = requestedTeam.length ? requestedTeam : profile.selectedTeam;
            teamSlots = [profile.characterKey, ...selectedOrder.filter((key) => key !== profile.characterKey)];
            teamSlots = [...new Set(teamSlots)].slice(0, 4);
            while (teamSlots.length < 4) teamSlots.push("");
            inputById = new Map(profile.inputs.map((input) => [input.id, input]));
            inputState = Object.fromEntries(profile.inputs.map((input) => [input.id, input.defaultValue]));
            effectState = Object.fromEntries(profile.effects.map((effect) => [effect.id, effect.defaultEnabled]));
            calculation = documentPayload.defaultCalculation;
            renderTeam();
            renderInputs();
            applyCalculation(documentPayload.defaultCalculation);
        } catch (error) {
            showInitialError(error);
        }
    }

    root.querySelector("[data-v2-reset]")?.addEventListener("click", reset);
    root.querySelector("[data-v2-retry]")?.addEventListener("click", initialize);
    expandButton?.addEventListener("click", toggleAllFormulas);
    reactionToggle?.addEventListener("change", () => {
        root.classList.toggle("is-showing-reactions", reactionToggle.checked);
    });
    storedTeamToggle?.addEventListener("click", () => {
        const open = storedTeamBody.hidden;
        storedTeamBody.hidden = !open;
        storedTeamNode.classList.toggle("is-open", open);
        storedTeamToggle.setAttribute("aria-expanded", String(open));
        closeTeamPickers();
    });
    document.addEventListener("click", () => closeTeamPickers());
    document.addEventListener("keydown", (event) => {
        if (event.key === "Escape") closeTeamPickers();
    });
    window.addEventListener("resize", scheduleStatAlignment);

    initialize();
})();
