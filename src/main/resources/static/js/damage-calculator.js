(() => {
    "use strict";

    const root = document.querySelector("[data-damage-calculator]");
    const profileNode = document.getElementById("damage-profile");
    if (!root || !profileNode) return;

    const profile = JSON.parse(profileNode.textContent);
    const inputDefinitions = profile.inputSections.flatMap((section) => section.inputs);
    const definitionById = new Map(inputDefinitions.map((input) => [input.id, input]));
    const effectSections = profile.effectSections || [];
    const effectDefinitions = effectSections.flatMap((section) =>
        section.effects.map((effect) => ({...effect, sectionKey: section.key})),
    );
    const effectById = new Map(effectDefinitions.map((effect) => [effect.id, effect]));
    const teamMembers = profile.teamMembers || [];
    const teamMemberByKey = new Map(teamMembers.map((member) => [member.key, member]));
    const metadataById = new Map(
        Array.from(root.querySelectorAll("[data-formula-meta]"))
            .map((node) => [node.dataset.formulaMeta, {
                name: node.dataset.formulaName || "",
                tooltip: node.dataset.formulaTooltip || "",
            }]),
    );
    const effectMetadataById = new Map(
        Array.from(root.querySelectorAll("[data-formula-effect-meta]"))
            .map((node) => [node.dataset.formulaEffectMeta, {
                name: node.dataset.formulaName || "",
                tooltip: node.dataset.formulaTooltip || "",
                source: node.dataset.formulaSource || "character",
            }]),
    );
    const damageElement = root.dataset.damageElement || "neutral";
    const elementTokenClass = `formula-token-element-${damageElement}`;
    const tokenClassByInput = {
        characterLevel: "formula-token-character-level",
        attack: "formula-token-atk",
        elementalMastery: "formula-token-em",
        laumaElementalMastery: "formula-token-em",
        nahidaElementalMastery: "formula-token-em",
        columbinaMaxHp: "formula-token-hp",
        elementalDamageBonus: elementTokenClass,
        otherDamageBonus: "formula-token-damage-bonus",
        critRate: "formula-token-crit-rate",
        critDamage: "formula-token-crit-damage",
        neferConstellation: "formula-token-buff",
        laumaConstellation: "formula-token-buff",
        nahidaConstellation: "formula-token-buff",
        columbinaConstellation: "formula-token-buff",
        veilStacks: "formula-token-buff",
        enemyLevel: "formula-token-enemy-level",
        enemyResistance: `${elementTokenClass} formula-token-resistance`,
        defenseReduction: "formula-token-defense",
        defenseIgnore: "formula-token-defense-ignore",
    };
    const controls = new Map(
        Array.from(root.querySelectorAll("[data-damage-input]"))
            .map((control) => [control.dataset.damageInput, control]),
    );
    const effectControls = new Map(
        Array.from(root.querySelectorAll("[data-damage-effect]"))
            .map((control) => [control.dataset.damageEffect, control]),
    );
    const teamControls = Array.from(root.querySelectorAll("[data-team-slot]"));
    const numberFormatter = new Intl.NumberFormat(undefined, {maximumFractionDigits: 0});
    const oneDecimalFormatter = new Intl.NumberFormat(undefined, {
        minimumFractionDigits: 1,
        maximumFractionDigits: 1,
    });
    const formulaNumberFormatter = new Intl.NumberFormat(undefined, {
        maximumFractionDigits: 2,
    });
    const multiplierFormatter = new Intl.NumberFormat(undefined, {
        minimumFractionDigits: 3,
        maximumFractionDigits: 4,
    });
    const scalingPercentFormatter = new Intl.NumberFormat(undefined, {
        minimumFractionDigits: 1,
        maximumFractionDigits: 4,
    });

    function readState() {
        return Object.fromEntries(inputDefinitions.map((definition) => {
            const control = controls.get(definition.id);
            const value = definition.type === "checkbox"
                ? (control.checked ? 1 : 0)
                : Number(control.value);
            return [definition.id, value];
        }));
    }

    function readEffectState() {
        return Object.fromEntries(effectDefinitions.map((effect) => [
            effect.id,
            effectControls.get(effect.id)?.checked ?? effect.defaultEnabled,
        ]));
    }

    function readTeamState() {
        return teamControls.map((control) => control.value).filter(Boolean);
    }

    function effectIsTeamAvailable(effect, teamKeys) {
        const selected = new Set(teamKeys);
        const moonsignLevel = teamKeys.reduce((total, key) =>
            total + (teamMemberByKey.get(key)?.moonsignContribution || 0), 0);
        return (effect.requiredTeamMembers || []).every((key) => selected.has(key)) &&
            moonsignLevel >= (effect.minimumMoonsignLevel || 0);
    }

    function effectMeetsOwnershipRequirements(effect, state) {
        return Object.entries(effect.ownershipRequirements || {}).every(([input, minimum]) =>
            (state[input] || 0) >= minimum);
    }

    function effectDefaultEnabled(effect, state) {
        return Boolean(effect.defaultEnabled) && effectMeetsOwnershipRequirements(effect, state);
    }

    function effectIsActive(effect, effectState, teamKeys, visiting = new Set()) {
        if (!effectState[effect.id] || visiting.has(effect.id)) return false;
        if (!effectIsTeamAvailable(effect, teamKeys)) return false;
        const nextVisiting = new Set(visiting).add(effect.id);
        return (effect.requiresEffects || []).every((requirement) => {
            const dependency = effectById.get(requirement);
            return dependency && effectIsActive(dependency, effectState, teamKeys, nextVisiting);
        });
    }

    function activeEffectIds(effectState, teamKeys = readTeamState()) {
        return new Set(effectDefinitions
            .filter((effect) => effectIsActive(effect, effectState, teamKeys))
            .map((effect) => effect.id));
    }

    function modifierMatches(modifier, talent, instance) {
        if (modifier.talentKeys?.length && (!talent || !modifier.talentKeys.includes(talent.key))) return false;
        if (modifier.instanceIds?.length && (!instance || !modifier.instanceIds.includes(instance.id))) return false;
        if (modifier.requiredTags?.length && (!instance ||
            !modifier.requiredTags.every((tag) => instance.tags.includes(tag)))) return false;
        return true;
    }

    function activeEffectModifiers(
        effectState,
        layer = null,
        talent = null,
        instance = null,
        teamKeys = readTeamState(),
        state = readState(),
    ) {
        const activeIds = activeEffectIds(effectState, teamKeys);
        return effectDefinitions
            .filter((effect) => activeIds.has(effect.id))
            .flatMap((effect) => effect.modifiers.map((modifier, modifierIndex) => ({
                ...modifier,
                sourceEffectId: effect.id,
                sourceSection: effect.sectionKey,
                modifierIndex,
            })))
            .filter((modifier) => !layer || modifier.layer === layer)
            .filter((modifier) => (modifier.requiresEffects || []).every((id) => activeIds.has(id)))
            .filter((modifier) => modifierMatches(modifier, talent, instance));
    }

    function effectModifierValue(modifier, state, stats) {
        const inputValue = modifier.input ? (stats[modifier.input] ?? state[modifier.input] ?? 0) : 0;
        let value;
        if (modifier.operation === "inputScaled") {
            value = inputValue * modifier.amount;
        } else if (modifier.operation === "inputProductScaled") {
            const secondaryValue = modifier.secondaryInput
                ? (stats[modifier.secondaryInput] ?? state[modifier.secondaryInput] ?? 0)
                : 0;
            value = inputValue * secondaryValue * modifier.amount;
        } else if (modifier.operation === "thresholdScaled") {
            value = Math.max(0, inputValue - (modifier.threshold || 0)) * modifier.amount;
        } else if (modifier.operation === "thresholdFlatAdd") {
            value = inputValue >= (modifier.threshold || 0) ? modifier.amount : 0;
        } else if (modifier.operation === "levelValue" || modifier.operation === "levelValueScaled") {
            const values = modifier.values || [];
            const level = Math.round(stats[modifier.levelInput] ?? state[modifier.levelInput] ?? 1);
            const levelValue = values[Math.min(values.length - 1, Math.max(0, level - 1))] || 0;
            value = modifier.operation === "levelValueScaled" ? inputValue * levelValue : levelValue;
        } else {
            value = modifier.amount;
        }
        return modifier.max == null ? value : Math.min(value, modifier.max);
    }

    function effectiveStats(state, effectState) {
        const stats = {...state};
        profile.statModifiers.forEach((modifier) => {
            const trigger = state[modifier.input] || 0;
            if (modifier.operation === "flatAdd") {
                stats[modifier.stat] = (stats[modifier.stat] || 0) + modifier.amount * trigger;
            } else if (modifier.operation === "percentAdd") {
                stats[modifier.stat] = (stats[modifier.stat] || 0) * (1 + modifier.amount * trigger);
            }
        });
        activeEffectModifiers(effectState, "stat").forEach((modifier) => {
            const value = effectModifierValue(modifier, state, stats);
            if (modifier.operation === "percentAdd") {
                stats[modifier.stat] = (stats[modifier.stat] || 0) * (1 + value / 100);
            } else {
                stats[modifier.stat] = (stats[modifier.stat] || 0) + value;
            }
        });
        return stats;
    }

    function resistanceMultiplier(resistance) {
        if (resistance < 0) return 1 - resistance / 2;
        if (resistance < 0.75) return 1 - resistance;
        return 1 / (4 * resistance + 1);
    }

    function formatControlValue(definition, value) {
        const decimals = definition.step < 1 ? 1 : 0;
        return new Intl.NumberFormat(undefined, {
            minimumFractionDigits: decimals,
            maximumFractionDigits: decimals,
        }).format(value) + (definition.suffix || "");
    }

    function updateControlPresentation(state) {
        inputDefinitions.forEach((definition) => {
            const control = controls.get(definition.id);
            if (definition.type === "checkbox") return;
            const output = root.querySelector(`[data-input-output="${definition.id}"]`);
            if (output) output.textContent = formatControlValue(definition, state[definition.id]);
            const progress = ((state[definition.id] - definition.min) /
                (definition.max - definition.min)) * 100;
            control.style.setProperty("--range-progress", `${progress}%`);
        });
    }

    function scalingSegments(instance, levelIndex) {
        const terms = instance.terms.map((term) => [
            genericToken(
                `${oneDecimalFormatter.format(term.values[levelIndex] * 100)}%`,
                "formula-token-scaling",
                "talentScaling",
            ),
            " ",
            inputToken(term.stat, term.statLabel),
        ]);
        const segments = ["[", ...joinSegments(terms, " + ")];
        if (instance.hitCount > 1) {
            segments.push(
                " × ",
                genericToken(String(instance.hitCount), "formula-token-hit-count", "hitCount"),
            );
        }
        return segments;
    }

    function formulaLabel(inputId, fallback = inputId) {
        return definitionById.get(inputId)?.formulaLabel || fallback;
    }

    function formatPercent(value) {
        return oneDecimalFormatter.format(value) + "%";
    }

    function formatScalingPercent(value) {
        return scalingPercentFormatter.format(value) + "%";
    }

    function formatMultiplier(value) {
        return `×${multiplierFormatter.format(value)}`;
    }

    function formulaToken(text, className, metadataKey, tooltipOverride = "") {
        const token = document.createElement("span");
        token.className = `damage-formula-token ${className}`;
        token.textContent = text;
        token.tabIndex = 0;
        const metadata = metadataById.get(metadataKey) || {};
        const details = tooltipOverride || metadata.tooltip || "";
        const tooltip = metadata.name && details
            ? `${metadata.name} — ${details}`
            : (details || metadata.name || "");
        if (tooltip) {
            token.dataset.tooltip = tooltip;
            token.setAttribute("aria-label", tooltip);
        }
        return token;
    }

    function inputToken(inputId, text) {
        return formulaToken(
            text,
            tokenClassByInput[inputId] || "formula-token-input",
            inputId,
        );
    }

    function genericToken(text, className, metadataKey, tooltipOverride = "") {
        return formulaToken(text, className, metadataKey, tooltipOverride);
    }

    function effectToken(text, effectId) {
        const metadata = effectMetadataById.get(effectId) || {};
        const token = document.createElement("span");
        token.className = `damage-formula-token formula-token-effect formula-token-effect-${metadata.source || "character"}`;
        token.textContent = text;
        token.tabIndex = 0;
        const tooltip = metadata.name && metadata.tooltip
            ? `${metadata.name} — ${metadata.tooltip}`
            : (metadata.tooltip || metadata.name || "");
        if (tooltip) {
            token.dataset.tooltip = tooltip;
            token.setAttribute("aria-label", tooltip);
        }
        return token;
    }

    function joinSegments(groups, separator) {
        return groups.flatMap((group, index) => index === 0 ? group : [separator, ...group]);
    }

    function renderSegments(node, segments) {
        if (!node) return;
        node.replaceChildren(...segments.map((segment) =>
            typeof segment === "string" ? document.createTextNode(segment) : segment,
        ));
    }

    function statFormula(stat, state, stats, effectState) {
        const base = state[stat] || 0;
        const label = formulaLabel(stat, stat);
        const modifiers = profile.statModifiers.filter((modifier) => modifier.stat === stat);
        const effectModifiers = activeEffectModifiers(effectState, "stat")
            .filter((modifier) => modifier.stat === stat);
        if (!modifiers.length && !effectModifiers.length) {
            return [inputToken(stat, `${label} ${formulaNumberFormatter.format(base)}`)];
        }

        const segments = [inputToken(stat, label), " (", inputToken(stat, formulaNumberFormatter.format(base))];
        modifiers.forEach((modifier) => {
            const trigger = state[modifier.input] || 0;
            const source = modifier.label || formulaLabel(modifier.input, modifier.input);
            if (modifier.operation === "percentAdd") {
                segments.push(
                    " × [1 + ",
                    inputToken(modifier.input, `${formatPercent(modifier.amount * 100)} ${source} × ${formulaNumberFormatter.format(trigger)}`),
                    "]",
                );
            } else {
                segments.push(
                    " + ",
                    inputToken(modifier.input, `${formulaNumberFormatter.format(modifier.amount)} ${source} × ${formulaNumberFormatter.format(trigger)}`),
                );
            }
        });
        effectModifiers.forEach((modifier) => {
            const value = effectModifierValue(modifier, state, stats);
            const suffix = definitionById.get(stat)?.suffix || "";
            const formattedValue = `${formulaNumberFormatter.format(value)}${suffix}`;
            if (modifier.operation === "percentAdd") {
                segments.push(" × [1 + ", effectToken(formattedValue, modifier.sourceEffectId), "]");
            } else {
                segments.push(" + ", effectToken(formattedValue, modifier.sourceEffectId));
            }
        });
        segments.push(") = ", effectiveStatToken(stat, stats, effectState));
        return segments;
    }

    function effectiveStatToken(stat, stats, effectState) {
        const suffix = definitionById.get(stat)?.suffix || "";
        const contributions = activeEffectModifiers(effectState, "stat")
            .filter((modifier) => modifier.stat === stat)
            .map((modifier) => effectMetadataById.get(modifier.sourceEffectId)?.name)
            .filter(Boolean);
        const baseTooltip = metadataById.get(stat)?.tooltip || "";
        const sourceText = contributions.length
            ? `${baseTooltip} ${root.dataset.effectOriginLabel || "Active sources:"} ${[...new Set(contributions)].join(", ")}.`
            : baseTooltip;
        return formulaToken(
            `${formulaNumberFormatter.format(stats[stat] || 0)}${suffix}`,
            tokenClassByInput[stat] || "formula-token-input",
            stat,
            sourceText,
        );
    }

    function damageBonusInputApplies(bonus, damageModel) {
        return (!bonus.includedDamageModels?.length || bonus.includedDamageModels.includes(damageModel)) &&
            !(bonus.excludedDamageModels || []).includes(damageModel);
    }

    function lunarEmBonus(stats) {
        const elementalMastery = stats.elementalMastery || 0;
        return 6 * elementalMastery / (elementalMastery + 2000);
    }

    function bonusFormula(state, stats, effectState, talent, instance, levelIndex, bonusMultiplier) {
        const damageModel = instance?.damageModel || "direct";
        const parts = profile.formula.damageBonusInputs
            .filter((bonus) => damageBonusInputApplies(bonus, damageModel))
            .map((bonus) => [
                inputToken(
                    bonus.input,
                    `${formatPercent(stats[bonus.input])} ${formulaLabel(bonus.input)}`,
                ),
            ]);
        instance?.bonusTerms.forEach((term) => {
            parts.push([
                "(",
                inputToken(term.input, formulaNumberFormatter.format(state[term.input] || 0)),
                " × ",
                genericToken(
                    formatScalingPercent(term.values[levelIndex] * 100),
                    "formula-token-scaling",
                    "talentScaling",
                ),
                ` ${term.label})`,
            ]);
        });
        if (instance) {
            activeEffectModifiers(effectState, "damageBonus", talent, instance).forEach((modifier) => {
                parts.push([
                    effectToken(
                        `${formatPercent(effectModifierValue(modifier, state, stats))} ${effectMetadataById.get(modifier.sourceEffectId)?.name || "Effect"}`,
                        modifier.sourceEffectId,
                    ),
                ]);
            });
        }
        if (damageModel === "lunarReaction") {
            parts.push([
                genericToken(
                    formatPercent(lunarEmBonus(stats) * 100),
                    "formula-token-lunar",
                    "lunarEmBonus",
                ),
            ]);
        }
        return [
            "1 + ",
            ...joinSegments(parts, " + "),
            " = ",
            genericToken(
                formatMultiplier(bonusMultiplier),
                "formula-token-multiplier",
                "damageBonusMultiplier",
            ),
        ];
    }

    function defenseFormula(state, formula, defense, damageModel = "direct") {
        if (damageModel === "lunarReaction") {
            return [
                genericToken("×1", "formula-token-lunar", "defenseMultiplier"),
                ` — ${root.dataset.defenseIgnoredLabel || "Not applied to Lunar Reaction DMG"}`,
            ];
        }
        const characterLevel = formulaNumberFormatter.format(state[formula.characterLevelInput]);
        const enemyLevel = formulaNumberFormatter.format(state[formula.enemyLevelInput]);
        const reduction = formatPercent(state[formula.defenseReductionInput]);
        const ignored = formatPercent(state[formula.defenseIgnoreInput]);
        return [
            "(", inputToken(formula.characterLevelInput, characterLevel), " + 100) ÷ [(",
            inputToken(formula.characterLevelInput, characterLevel), " + 100) + (",
            inputToken(formula.enemyLevelInput, enemyLevel), " + 100) × (1 − ",
            inputToken(formula.defenseReductionInput, reduction), ") × (1 − ",
            inputToken(formula.defenseIgnoreInput, ignored), ")] = ",
            genericToken(
                formatMultiplier(defense),
                "formula-token-defense-multiplier",
                "defenseMultiplier",
            ),
        ];
    }

    function resistanceFormula(
        state,
        formula,
        resistance,
        effectState,
        stats,
        talent = null,
        instance = null,
    ) {
        const input = formula.resistanceInput;
        const rawResistance = state[input];
        const reductions = effectState
            ? activeEffectModifiers(effectState, "resistanceReduction", talent, instance)
            : [];
        const totalReduction = reductions.reduce((total, modifier) =>
            total + effectModifierValue(modifier, state, stats), 0);
        const effectiveResistance = rawResistance - totalReduction;
        const resistanceExpression = [inputToken(input, formatPercent(rawResistance))];
        reductions.forEach((modifier) => {
            resistanceExpression.push(
                " − ",
                effectToken(
                    formatPercent(effectModifierValue(modifier, state, stats)),
                    modifier.sourceEffectId,
                ),
            );
        });
        const result = genericToken(
            formatMultiplier(resistance),
            `${elementTokenClass} formula-token-resistance-multiplier`,
            "resistanceMultiplier",
        );
        if (effectiveResistance < 0) return ["1 − ([", ...resistanceExpression, "] ÷ 2) = ", result];
        if (effectiveResistance < 75) return ["1 − [", ...resistanceExpression, "] = ", result];
        return ["1 ÷ (4 × [", ...resistanceExpression, "] + 1) = ", result];
    }

    function baseDamageFormula(state, effectState, talent, instance, levelIndex, stats, rawBase, base) {
        const terms = instance.terms.map((term) => [
            "(",
            inputToken(term.stat, `${formulaNumberFormatter.format(stats[term.stat] || 0)} ${term.statLabel}`),
            " × ",
            genericToken(
                formatScalingPercent(term.values[levelIndex] * 100),
                "formula-token-scaling",
                "talentScaling",
            ),
            ")",
        ]);
        const segments = joinSegments(terms, " + ");
        if (instance.hitCount > 1) {
            segments.push(
                " × ",
                genericToken(`${instance.hitCount} hits`, "formula-token-hit-count", "hitCount"),
            );
        }
        activeEffectModifiers(effectState, "flatBaseDamage", talent, instance).forEach((modifier) => {
            segments.push(
                " + ",
                effectToken(
                    formulaNumberFormatter.format(effectModifierValue(modifier, state, stats)),
                    modifier.sourceEffectId,
                ),
            );
        });
        segments.push("]");
        const baseDamageBonuses = activeEffectModifiers(
            effectState,
            "baseDamageBonus",
            talent,
            instance,
        );
        if (baseDamageBonuses.length) {
            segments.push(" × (1 + ");
            baseDamageBonuses.forEach((modifier, index) => {
                if (index > 0) segments.push(" + ");
                segments.push(effectToken(
                    formatPercent(effectModifierValue(modifier, state, stats)),
                    modifier.sourceEffectId,
                ));
            });
            segments.push(")");
        }
        segments.push(
            " = ",
            genericToken(
                formulaNumberFormatter.format(base),
                `${elementTokenClass} formula-token-result`,
                "damageResult",
            ),
        );
        return segments;
    }

    function updatePipeline(state, stats, effectState, defense, resistance, globalBonus, critRate, critDamage) {
        renderSegments(
            root.querySelector("[data-pipeline-stats]"),
            joinSegments(
                ["attack", "elementalMastery"].map((stat) => statFormula(stat, state, stats, effectState)),
                " · ",
            ),
        );
        renderSegments(
            root.querySelector("[data-pipeline-bonus]"),
            bonusFormula(state, stats, effectState, null, null, 0, 1 + globalBonus),
        );
        renderSegments(
            root.querySelector("[data-pipeline-defense]"),
            defenseFormula(state, profile.formula, defense),
        );
        renderSegments(
            root.querySelector("[data-pipeline-resistance]"),
            resistanceFormula(state, profile.formula, resistance, effectState, stats),
        );
        renderSegments(root.querySelector("[data-pipeline-crit]"), [
            "1 + ",
            "[",
            ...scopedCritFormula(
                profile.formula.critRateInput,
                "critRate",
                state,
                stats,
                effectState,
                null,
                null,
            ),
            "] × [",
            ...scopedCritFormula(
                profile.formula.critDamageInput,
                "critDamage",
                state,
                stats,
                effectState,
                null,
                null,
            ),
            "]",
            " = ",
            genericToken(
                formatMultiplier(1 + critRate * critDamage),
                "formula-token-crit-multiplier",
                "critMultiplier",
            ),
        ]);
    }

    function scopedCritFormula(inputId, layer, state, stats, effectState, talent, instance) {
        const segments = [effectiveStatToken(inputId, stats, effectState)];
        const modifiers = activeEffectModifiers(effectState, layer, talent, instance);
        modifiers.forEach((modifier) => {
            segments.push(
                " + ",
                effectToken(
                    formatPercent(effectModifierValue(modifier, state, stats)),
                    modifier.sourceEffectId,
                ),
            );
        });
        const total = (stats[inputId] || 0) + modifiers.reduce((sum, modifier) =>
            sum + effectModifierValue(modifier, state, stats), 0);
        return layer === "critRate" && total > 100
            ? ["min(100%, ", ...segments, ")"]
            : segments;
    }

    function setFormula(card, key, segments) {
        renderSegments(card.querySelector(`[data-formula="${key}"]`), segments);
    }

    function updateTeamPresentation(teamKeys) {
        const selected = new Set(teamKeys);
        teamControls.forEach((control) => {
            Array.from(control.options).forEach((option) => {
                option.disabled = Boolean(option.value) &&
                    option.value !== control.value && selected.has(option.value);
            });
        });
        root.querySelectorAll("[data-input-team-member]").forEach((field) => {
            const requiredMember = field.dataset.inputTeamMember;
            if (requiredMember) field.hidden = !selected.has(requiredMember);
        });
        root.querySelectorAll(".damage-input-section").forEach((section) => {
            const fields = Array.from(section.querySelectorAll(".damage-slider-field"));
            section.hidden = fields.length > 0 && fields.every((field) => field.hidden);
        });
    }

    function updateEffectPresentation(effectState, state, stats, teamKeys) {
        const activeIds = activeEffectIds(effectState, teamKeys);
        effectDefinitions.forEach((effect) => {
            const enabled = Boolean(effectState[effect.id]);
            const active = activeIds.has(effect.id);
            const teamAvailable = effectIsTeamAvailable(effect, teamKeys);
            const owned = effectMeetsOwnershipRequirements(effect, state);
            const card = root.querySelector(`[data-effect-card="${effect.id}"]`);
            const status = root.querySelector(`[data-effect-status="${effect.id}"]`);
            const control = effectControls.get(effect.id);
            card?.classList.toggle("is-active", active);
            card?.classList.toggle("is-blocked", teamAvailable && enabled && !active);
            card?.classList.toggle("is-disabled", !enabled);
            card?.classList.toggle("is-unavailable", !teamAvailable);
            card?.classList.toggle("is-locked", teamAvailable && !owned);
            if (control) control.disabled = !teamAvailable;
            if (status) {
                const requirement = Object.entries(effect.ownershipRequirements || {})[0];
                const requirementText = requirement
                    ? `${formulaLabel(requirement[0])} ${numberFormatter.format(requirement[1])}`
                    : "";
                status.textContent = !teamAvailable
                    ? root.dataset.effectUnavailableLabel
                    : (!owned
                        ? `${active
                            ? (root.dataset.effectPreviewLabel || "Preview")
                            : (root.dataset.effectLockedLabel || "Not owned")}: ${requirementText}`
                        : (active
                            ? root.dataset.effectActiveLabel
                            : (enabled ? root.dataset.effectBlockedLabel : root.dataset.effectInactiveLabel)));
            }
            effect.modifiers.forEach((modifier, modifierIndex) => {
                const output = root.querySelector(
                    `[data-effect-metric="${effect.id}:${modifierIndex}"]`,
                );
                if (!output) return;
                const modifierActive = active &&
                    (modifier.requiresEffects || []).every((id) => activeIds.has(id));
                output.textContent = modifierActive
                    ? (modifier.displayFormat === "number"
                        ? numberFormatter.format(effectModifierValue(modifier, state, stats))
                        : formatPercent(effectModifierValue(modifier, state, stats)))
                    : "—";
            });
        });

        effectSections.forEach((section) => {
            const availableEffects = section.effects.filter((effect) => effectIsTeamAvailable(effect, teamKeys));
            const activeCount = availableEffects.filter((effect) => activeIds.has(effect.id)).length;
            const output = root.querySelector(`[data-effect-count="${section.key}"]`);
            const sectionNode = root.querySelector(`[data-effect-section="${section.key}"]`);
            if (sectionNode) sectionNode.hidden = availableEffects.length === 0;
            if (output) {
                output.textContent = `${activeCount}/${availableEffects.length} ${root.dataset.effectCountLabel}`;
            }
        });
    }

    function calculate() {
        const state = readState();
        const effectState = readEffectState();
        const teamKeys = readTeamState();
        const stats = effectiveStats(state, effectState);
        const activeIds = activeEffectIds(effectState, teamKeys);
        const formula = profile.formula;
        const characterLevel = state[formula.characterLevelInput];
        const enemyLevel = state[formula.enemyLevelInput];
        const defenseReduction = state[formula.defenseReductionInput] / 100;
        const defenseIgnore = state[formula.defenseIgnoreInput] / 100;
        const defense = (characterLevel + 100) /
            (characterLevel + 100 + (enemyLevel + 100) *
                (1 - defenseReduction) * (1 - defenseIgnore));
        const globalResistanceReduction = activeEffectModifiers(
            effectState,
            "resistanceReduction",
        ).reduce((total, modifier) => total + effectModifierValue(modifier, state, stats), 0);
        const resistance = resistanceMultiplier(
            (state[formula.resistanceInput] - globalResistanceReduction) / 100,
        );
        const globalBonus = formula.damageBonusInputs
            .filter((bonus) => damageBonusInputApplies(bonus, "direct"))
            .reduce((total, bonus) => total + stats[bonus.input] / 100, 0);
        const critRate = Math.min(1, Math.max(0, stats[formula.critRateInput] / 100));
        const critDamage = Math.max(0, stats[formula.critDamageInput] / 100);

        updateControlPresentation(state);
        updateTeamPresentation(teamKeys);
        updateEffectPresentation(effectState, state, stats, teamKeys);
        root.querySelectorAll("[data-effective-stat]").forEach((node) => {
            const value = stats[node.dataset.effectiveStat] || 0;
            node.textContent = node.dataset.effectiveFormat === "percent"
                ? formatPercent(value)
                : numberFormatter.format(value);
        });
        root.querySelector("[data-defense-multiplier]").textContent =
            oneDecimalFormatter.format(defense * 100) + "%";
        root.querySelector("[data-resistance-multiplier]").textContent =
            oneDecimalFormatter.format(resistance * 100) + "%";
        updatePipeline(state, stats, effectState, defense, resistance, globalBonus, critRate, critDamage);

        profile.talents.forEach((talent) => {
            const level = Math.round(stats[talent.levelInput] ?? state[talent.levelInput]);
            const levelIndex = Math.min(talent.maxLevel - 1, Math.max(0, level - 1));
            const levelNode = root.querySelector(`[data-talent-level="${talent.levelInput}"]`);
            if (levelNode) levelNode.textContent = level;

            talent.instances.forEach((instance) => {
                const card = root.querySelector(`[data-damage-instance="${instance.id}"]`);
                if (!card) return;
                const instanceActive = (instance.requiredEffects || []).every((id) => activeIds.has(id)) &&
                    !(instance.excludedEffects || []).some((id) => activeIds.has(id));
                card.hidden = !instanceActive;
                const basePerHit = instance.terms.reduce((total, term) =>
                    total + (stats[term.stat] || 0) * term.values[levelIndex], 0);
                const instanceBonus = instance.bonusTerms.reduce((total, term) =>
                    total + (stats[term.input] ?? state[term.input] ?? 0) * term.values[levelIndex], 0);
                const rawBase = basePerHit * instance.hitCount;
                const baseDamageBonus = activeEffectModifiers(
                    effectState,
                    "baseDamageBonus",
                    talent,
                    instance,
                ).reduce((total, modifier) =>
                    total + effectModifierValue(modifier, state, stats) / 100, 0);
                const effectDamageBonus = activeEffectModifiers(
                    effectState,
                    "damageBonus",
                    talent,
                    instance,
                ).reduce((total, modifier) =>
                    total + effectModifierValue(modifier, state, stats) / 100, 0);
                const flatBaseDamage = activeEffectModifiers(
                    effectState,
                    "flatBaseDamage",
                    talent,
                    instance,
                ).reduce((total, modifier) =>
                    total + effectModifierValue(modifier, state, stats), 0);
                const elevationModifiers = activeEffectModifiers(
                    effectState,
                    "elevation",
                    talent,
                    instance,
                );
                const elevation = elevationModifiers.reduce((total, modifier) =>
                    total + effectModifierValue(modifier, state, stats) / 100, 0);
                const resistanceReduction = activeEffectModifiers(
                    effectState,
                    "resistanceReduction",
                    talent,
                    instance,
                ).reduce((total, modifier) =>
                    total + effectModifierValue(modifier, state, stats), 0);
                const instanceResistance = resistanceMultiplier(
                    (state[formula.resistanceInput] - resistanceReduction) / 100,
                );
                const instanceCritRate = Math.min(1, Math.max(0, critRate +
                    activeEffectModifiers(effectState, "critRate", talent, instance)
                        .reduce((total, modifier) =>
                            total + effectModifierValue(modifier, state, stats) / 100, 0)));
                const instanceCritDamage = Math.max(0, critDamage +
                    activeEffectModifiers(effectState, "critDamage", talent, instance)
                        .reduce((total, modifier) =>
                            total + effectModifierValue(modifier, state, stats) / 100, 0));
                const configuredDamageBonus = formula.damageBonusInputs
                    .filter((bonus) => damageBonusInputApplies(bonus, instance.damageModel))
                    .reduce((total, bonus) => total + stats[bonus.input] / 100, 0);
                const reactionEmBonus = instance.damageModel === "lunarReaction"
                    ? lunarEmBonus(stats)
                    : 0;
                const appliedDefense = instance.damageModel === "lunarReaction" ? 1 : defense;
                const base = (rawBase + flatBaseDamage) * (1 + baseDamageBonus);
                const bonusMultiplier = 1 + configuredDamageBonus + effectDamageBonus +
                    instanceBonus + reactionEmBonus;
                const elevationMultiplier = 1 + elevation;
                const nonCritical = base * bonusMultiplier * appliedDefense * instanceResistance *
                    elevationMultiplier;
                const critical = nonCritical * (1 + instanceCritDamage);
                const average = nonCritical * (1 + instanceCritRate * instanceCritDamage);

                renderSegments(card.querySelector("[data-scaling]"), scalingSegments(instance, levelIndex));
                card.querySelector("[data-result='average']").textContent = numberFormatter.format(average);
                card.querySelector("[data-result='nonCritical']").textContent = numberFormatter.format(nonCritical);
                card.querySelector("[data-result='critical']").textContent = numberFormatter.format(critical);

                const bonusNode = card.querySelector("[data-instance-bonus]");
                if (instance.bonusTerms.length && instanceBonus > 0) {
                    const source = instance.bonusTerms[0];
                    const stacks = stats[source.input] ?? state[source.input];
                    bonusNode.textContent = `+${oneDecimalFormatter.format(instanceBonus * 100)}% ${source.label} (${numberFormatter.format(stacks)})`;
                    bonusNode.hidden = false;
                } else {
                    bonusNode.hidden = true;
                }

                const influencedStats = [...new Set(instance.terms.map((term) => term.statLabel))];
                const influencedBy = root.dataset.influencedLabel || "Influenced by:";
                card.querySelector("[data-formula-influences]").textContent =
                    `${influencedBy} ${influencedStats.join(", ")}`;
                setFormula(
                    card,
                    "base",
                    baseDamageFormula(state, effectState, talent, instance, levelIndex, stats, rawBase, base),
                );
                setFormula(
                    card,
                    "bonus",
                    bonusFormula(
                        state,
                        stats,
                        effectState,
                        talent,
                        instance,
                        levelIndex,
                        bonusMultiplier,
                    ),
                );
                setFormula(card, "defense", defenseFormula(state, formula, appliedDefense, instance.damageModel));
                setFormula(
                    card,
                    "resistance",
                    resistanceFormula(
                        state,
                        formula,
                        instanceResistance,
                        effectState,
                        stats,
                        talent,
                        instance,
                    ),
                );
                setFormula(
                    card,
                    "nonCritical",
                    [
                        genericToken(
                            formulaNumberFormatter.format(base),
                            `${elementTokenClass} formula-token-result`,
                            "damageResult",
                        ),
                        " × ",
                        genericToken(
                            multiplierFormatter.format(bonusMultiplier),
                            "formula-token-multiplier",
                            "damageBonusMultiplier",
                        ),
                        " × ",
                        genericToken(
                            multiplierFormatter.format(appliedDefense),
                            "formula-token-defense-multiplier",
                            "defenseMultiplier",
                        ),
                        " × ",
                        genericToken(
                            multiplierFormatter.format(instanceResistance),
                            `${elementTokenClass} formula-token-resistance-multiplier`,
                            "resistanceMultiplier",
                        ),
                        ...(elevationModifiers.length ? [
                            " × (1 + ",
                            ...joinSegments(elevationModifiers.map((modifier) => [
                                effectToken(
                                    formatPercent(effectModifierValue(modifier, state, stats)),
                                    modifier.sourceEffectId,
                                ),
                            ]), " + "),
                            " = ",
                            genericToken(
                                formatMultiplier(elevationMultiplier),
                                "formula-token-lunar formula-token-multiplier",
                                "elevationMultiplier",
                            ),
                            ")",
                        ] : []),
                        " = ",
                        genericToken(
                            numberFormatter.format(nonCritical),
                            `${elementTokenClass} formula-token-result`,
                            "damageResult",
                        ),
                    ],
                );
                setFormula(
                    card,
                    "critical",
                    [
                        genericToken(
                            formulaNumberFormatter.format(nonCritical),
                            `${elementTokenClass} formula-token-result`,
                            "damageResult",
                        ),
                        " × (1 + ",
                        ...scopedCritFormula(
                            formula.critDamageInput,
                            "critDamage",
                            state,
                            stats,
                            effectState,
                            talent,
                            instance,
                        ),
                        ") = ",
                        genericToken(
                            numberFormatter.format(critical),
                            `${elementTokenClass} formula-token-result`,
                            "damageResult",
                        ),
                    ],
                );
                setFormula(
                    card,
                    "average",
                    [
                        genericToken(
                            formulaNumberFormatter.format(nonCritical),
                            `${elementTokenClass} formula-token-result`,
                            "damageResult",
                        ),
                        " × (1 + ",
                        "[",
                        ...scopedCritFormula(
                            formula.critRateInput,
                            "critRate",
                            state,
                            stats,
                            effectState,
                            talent,
                            instance,
                        ),
                        "]",
                        " × ",
                        "[",
                        ...scopedCritFormula(
                            formula.critDamageInput,
                            "critDamage",
                            state,
                            stats,
                            effectState,
                            talent,
                            instance,
                        ),
                        "]",
                        ") = ",
                        genericToken(
                            numberFormatter.format(average),
                            `${elementTokenClass} formula-token-result`,
                            "damageResult",
                        ),
                    ],
                );
            });
        });
    }

    function synchronizeOwnershipEffects(state, changedInput = null) {
        effectDefinitions.forEach((effect) => {
            const requirements = effect.ownershipRequirements || {};
            if (!Object.keys(requirements).length || (changedInput && !(changedInput in requirements))) return;
            const control = effectControls.get(effect.id);
            if (control) control.checked = effectDefaultEnabled(effect, state);
        });
    }

    function setEffectSectionState(sectionKey, enabled) {
        effectDefinitions
            .filter((effect) => effect.sectionKey === sectionKey)
            .forEach((effect) => {
                const control = effectControls.get(effect.id);
                if (control) control.checked = enabled;
            });
        calculate();
    }

    function resetEffectSectionToDefaults(sectionKey) {
        const state = readState();
        effectDefinitions
            .filter((effect) => effect.sectionKey === sectionKey)
            .forEach((effect) => {
                const control = effectControls.get(effect.id);
                if (control) control.checked = effectDefaultEnabled(effect, state);
            });
        calculate();
    }

    controls.forEach((control, inputId) => control.addEventListener("input", () => {
        synchronizeOwnershipEffects(readState(), inputId);
        calculate();
    }));
    effectControls.forEach((control) => control.addEventListener("input", calculate));
    teamControls.forEach((control) => control.addEventListener("change", calculate));
    root.querySelectorAll("[data-effect-enable-all]").forEach((button) => {
        button.addEventListener("click", (event) => {
            event.preventDefault();
            event.stopPropagation();
            setEffectSectionState(button.dataset.effectEnableAll, true);
        });
    });
    root.querySelectorAll("[data-effect-disable-all]").forEach((button) => {
        button.addEventListener("click", (event) => {
            event.preventDefault();
            event.stopPropagation();
            setEffectSectionState(button.dataset.effectDisableAll, false);
        });
    });
    root.querySelector("[data-effect-reset-ownership]")?.addEventListener("click", (event) => {
        event.preventDefault();
        event.stopPropagation();
        resetEffectSectionToDefaults("constellation");
    });
    root.querySelector("[data-damage-reset]")?.addEventListener("click", () => {
        inputDefinitions.forEach((definition) => {
            const control = controls.get(definition.id);
            if (definition.type === "checkbox") {
                control.checked = definition.defaultValue > 0;
            } else {
                control.value = definition.defaultValue;
            }
        });
        const resetState = readState();
        effectDefinitions.forEach((effect) => {
            const control = effectControls.get(effect.id);
            if (control) control.checked = effectDefaultEnabled(effect, resetState);
        });
        teamControls.forEach((control, index) => {
            control.value = profile.defaultTeam[index] || "";
        });
        calculate();
    });
    root.querySelector("[data-expand-calculations]")?.addEventListener("click", (event) => {
        const button = event.currentTarget;
        const breakdowns = Array.from(root.querySelectorAll("[data-calculation-breakdown]"));
        const expand = breakdowns.some((breakdown) => !breakdown.open);
        breakdowns.forEach((breakdown) => { breakdown.open = expand; });
        button.setAttribute("aria-expanded", String(expand));
        button.textContent = expand ? button.dataset.collapseLabel : button.dataset.expandLabel;
    });

    calculate();
})();
