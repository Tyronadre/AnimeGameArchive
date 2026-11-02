(() => {
    "use strict";
    const terminal = new Set(["completed", "cancelled", "failed"]);
    window.createArtifactComparisonV2 = (root, callbacks) => {
        const panel = root.querySelector("[data-artifact-comparison]");
        if (!panel) return {setDocument() {}, changed() {}};
        const labels = new Map(Array.from(panel.querySelectorAll("[data-comparison-label]"), n => [n.dataset.comparisonLabel, n.textContent]));
        const t = key => labels.get(key.replace(/^comparison\./, "")) || key;
        const number = new Intl.NumberFormat(undefined, {maximumFractionDigits: 0});
        const percent = new Intl.NumberFormat(undefined, {maximumFractionDigits: 2, signDisplay: "exceptZero"});
        const slots = ["flower", "plume", "sands", "goblet", "circlet"];
        const node = (tag, text, cls) => {
            const n = document.createElement(tag); if (text != null) n.textContent = text; if (cls) n.className = cls; return n;
        };
        const button = (text, action) => { const n = node("button", text); n.type = "button"; n.addEventListener("click", action); return n; };
        let documentPayload, selected = new Set(), runId = null, status = null, timer = null, offset = 0;
        let runSignature = null, pending = false, pollVersion = 0, disconnected = false;
        const storageKey = `artifact-comparison:${panel.dataset.comparisonUrl}`;
        let restorePending = true;
        function saveRun() {
            try { if (runId) sessionStorage.setItem(storageKey, JSON.stringify({runId, runSignature})); else sessionStorage.removeItem(storageKey); } catch (_) { /* Storage is optional. */ }
        }
        const intro = node("header", null, "damage-v2-panel-intro");
        intro.append(node("h2", t("title")), node("p", t("help")));
        const goalBox = node("fieldset", null, "comparison-goals"); goalBox.append(node("legend", t("goal")));
        const goalOptions = node("div", null, "comparison-goal-options");
        const preset = button(t("preset"), () => { selected = new Set(["shades-hit-1", "shades-hit-2", "shades-hit-3"]); renderGoals(); changed(); });
        goalBox.append(preset, goalOptions);
        const actions = node("div", null, "comparison-actions");
        const start = button(t("analyze"), analyze);
        const cancel = button(t("cancel"), cancelRun); cancel.hidden = true;
        const refresh = button(t("reload"), () => callbacks.reload());
        actions.append(start, cancel, refresh);
        const notice = node("p", null, "comparison-notice"); notice.hidden = true; notice.setAttribute("role", "status");
        const error = node("p", null, "comparison-error"); error.hidden = true; error.setAttribute("role", "alert");
        const progressBox = node("section", null, "comparison-progress"); progressBox.hidden = true;
        const phase = node("strong"); const timing = node("p"); timing.setAttribute("aria-live", "polite");
        const progress = node("progress"); progress.max = 100; progress.setAttribute("aria-label", t("progress"));
        const counts = node("p"); const baseline = node("p", null, "comparison-baseline");
        const runDetails = node("details"); runDetails.append(node("summary", t("diagnostics")));
        const runCode = node("code");
        runDetails.append(runCode, button(t("copyRun"), async () => {
            try { await navigator.clipboard.writeText(runId); } catch (_) { runCode.focus(); }
        })); runCode.tabIndex = 0;
        progressBox.append(phase, progress, timing, counts, baseline, runDetails);
        const filters = node("div", null, "comparison-actions");
        const slotSelect = node("select"); slotSelect.setAttribute("aria-label", t("slot"));
        slotSelect.append(new Option(t("allSlots"), "")); slots.forEach(slot => slotSelect.append(new Option(t(slot), slot)));
        const sortSelect = node("select"); sortSelect.setAttribute("aria-label", t("sort"));
        ["current", "potential", "chance"].forEach(sort => sortSelect.append(new Option(t("sort." + sort), sort)));
        const results = node("div", null, "comparison-results");
        const pager = node("div", null, "comparison-actions");
        const previous = button(t("previous"), () => { offset = Math.max(0, offset - 50); poll(); });
        const next = button(t("next"), () => { offset += 50; poll(); }); const page = node("span");
        pager.append(previous, page, next); pager.hidden = true;
        filters.append(slotSelect, sortSelect);
        [slotSelect, sortSelect].forEach(select => select.addEventListener("change", () => { offset = 0; poll(); }));
        panel.append(intro, goalBox, actions, notice, error, progressBox, filters, results, pager);

        function signature() {
            const ctx = callbacks.context();
            return JSON.stringify([ctx.teamKeys, ctx.inputs, ctx.enabledEffects, Array.from(selected).sort(), documentPayload?.contextFingerprint]);
        }
        function active() { return runId && (!status || !terminal.has(status.progress.phase)); }
        function changed() {
            notice.hidden = !runSignature || runSignature === signature();
            notice.textContent = t("stale");
            start.disabled = pending || Boolean(active()) || !selected.size || !documentPayload?.contextFingerprint;
            refresh.disabled = pending || Boolean(active());
        }
        function goals() {
            return (documentPayload?.profile?.talents || []).filter(talent => !talent.key.startsWith("artifact.") && !talent.key.startsWith("weapon."))
                .flatMap(talent => talent.formulas.map(formula => ({...formula, talent: talent.name})));
        }
        function renderGoals() {
            goalOptions.replaceChildren();
            const all = goals();
            preset.hidden = !["shades-hit-1", "shades-hit-2", "shades-hit-3"].every(id => all.some(g => g.id === id));
            all.forEach(formula => {
                const label = node("label"); const check = node("input"); check.type = "checkbox"; check.checked = selected.has(formula.id);
                check.addEventListener("change", () => { if (check.checked) selected.add(formula.id); else selected.delete(formula.id); changed(); });
                label.append(check, node("span", `${formula.talent} · ${formula.label}`)); goalOptions.append(label);
            });
        }
        function setDocument(payload) {
            documentPayload = payload;
            const all = goals(); selected = new Set(Array.from(selected).filter(id => all.some(g => g.id === id)));
            if (!selected.size) {
                const shades = all.filter(g => ["shades-hit-1", "shades-hit-2", "shades-hit-3"].includes(g.id));
                selected = new Set((shades.length === 3 ? shades : all.slice(0, 1)).map(g => g.id));
            }
            renderGoals(); changed();
            if (restorePending) {
                restorePending = false;
                try { const saved = JSON.parse(sessionStorage.getItem(storageKey) || "null"); if (saved?.runId) {
                    runId = saved.runId; runSignature = saved.runSignature; changed(); poll();
                } } catch (_) { /* Ignore unavailable storage or expired local metadata. */ }
            }
        }
        async function request(url, options) {
            const response = await fetch(url, {...options, headers: callbacks.headers()});
            const body = await response.json();
            if (!response.ok) { const e = new Error(t(body.error || "failed")); e.status = response.status; throw e; }
            return body;
        }
        async function analyze() {
            if (start.disabled) return;
            pending = true; error.hidden = true; disconnected = false; offset = 0;
            const capturedSignature = signature();
            const ctx = callbacks.context();
            const body = JSON.stringify({calculation: {inputs: ctx.inputs, enabledEffects: ctx.enabledEffects, teamKeys: ctx.teamKeys},
                formulaIds: Array.from(selected), contextFingerprint: documentPayload.contextFingerprint});
            changed();
            try {
                const result = await request(panel.dataset.comparisonUrl, {method: "POST", body});
                runId = result.runId; runSignature = capturedSignature; status = result; saveRun(); render(); poll();
            } catch (e) { showError(e.message); }
            finally { pending = false; changed(); }
        }
        async function cancelRun() {
            if (!active()) return;
            cancel.disabled = true;
            try { await request(`${panel.dataset.comparisonUrl}/${runId}`, {method: "DELETE"}); poll(); }
            catch (e) { showError(e.message); cancel.disabled = false; }
        }
        async function poll() {
            clearTimeout(timer);
            if (!runId) return;
            const version = ++pollVersion;
            const query = new URLSearchParams({sort: sortSelect.value, offset: String(offset), limit: "50"});
            if (slotSelect.value) query.set("slot", slotSelect.value);
            try {
                const result = await request(`${panel.dataset.comparisonUrl}/${runId}?${query}`);
                if (version !== pollVersion) return;
                status = result; disconnected = false; error.hidden = true; render();
            } catch (e) {
                if (version !== pollVersion) return;
                disconnected = true; timing.textContent = t("connectionLost"); showError(e.message);
                if (e.status === 404 || e.status === 401 || e.status === 403) { runId = null; saveRun(); changed(); return; }
            }
            if (version === pollVersion && active()) timer = setTimeout(poll, 1000);
        }
        function showError(message) { error.hidden = false; error.textContent = message; }
        function duration(ms) {
            const seconds = Math.max(1, Math.ceil(ms / 1000));
            return seconds < 60 ? `${seconds} ${t("seconds")}` : `${Math.ceil(seconds / 60)} ${t("minutes")}`;
        }
        function gain(value) {
            if (!value) return "—";
            return `${number.format(value.damage)} (${percent.format(value.difference)}${value.percent == null ? "" : ` / ${percent.format(value.percent)}%`})`;
        }
        function render() {
            const p = status.progress;
            progressBox.hidden = false; progress.value = p.percent;
            phase.textContent = t("phase." + p.phase);
            if (!disconnected) timing.textContent = `${t("elapsed")}: ${duration(p.elapsedMillis)} · ` +
                (p.phase === "queued" ? `${t("queuedFor")}: ${duration(p.queueMillis)}` : terminal.has(p.phase) ? t("phase." + p.phase) :
                    p.remainingMillis == null ? t("estimating") : `${t("about")} ${duration(p.remainingMillis)} ${t(p.estimateScope === "phase" ? "phaseRemaining" : "remaining")}`);
            counts.textContent = `${p.completedCandidates}/${p.totalCandidates} ${t("artifacts")} · ${number.format(p.completedEvaluations)}/${number.format(p.totalEvaluations)} ${t("evaluations")}`;
            baseline.textContent = `${t("baseline")}: ${status.baselineDamage == null ? "—" : number.format(status.baselineDamage)}`;
            runCode.textContent = status.runId;
            cancel.hidden = terminal.has(p.phase); cancel.disabled = status.cancellationRequested;
            cancel.textContent = t(status.cancellationRequested ? "cancelling" : "cancel");
            if (status.error) showError(t(status.error));
            results.replaceChildren();
            if (p.phase !== "completed") results.append(node("p", t("partial"), "comparison-notice"));
            let lastSlot = null;
            status.rows.forEach(row => {
                const artifact = row.artifact;
                if (artifact.slotKey.toLowerCase() !== lastSlot) {
                    lastSlot = artifact.slotKey.toLowerCase(); results.append(node("h3", t(lastSlot)));
                }
                const card = node("article", null, "comparison-card" + (row.equipped ? " is-equipped" : ""));
                const details = node("div", null, "comparison-artifact");
                details.append(node("strong", `${artifact.setName || artifact.setKey} · +${artifact.level} · ${artifact.rarity}★`));
                details.append(node("p", row.equipped ? t("equipped") : artifact.location ? `${t("owner")}: ${artifact.location}` : t("unequipped")));
                if (artifact.locked) details.append(node("small", t("locked")));
                details.append(node("p", artifact.mainStatName || artifact.mainStatKey));
                const stats = node("ul"); artifact.substats.forEach(stat => stats.append(node("li", `${stat.name || stat.key}: ${stat.formattedValue || stat.value}`))); details.append(stats);
                const scores = node("div", null, "comparison-scores");
                scores.append(node("p", `${t("now")}: ${gain(row.current)}`));
                if (row.exclusion) scores.append(node("p", t(row.exclusion), "comparison-error"));
                if (row.projection) {
                    const projection = row.projection;
                    scores.append(node("p", `${t("expected")}: ${gain(projection.expected)}`));
                    scores.append(node("p", `${t("range")}: ${number.format(projection.low)}–${number.format(projection.high)}`));
                    if (!row.equipped) scores.append(node("p", `${t("chanceToday")}: ${number.format(projection.chanceToImprove * 100)}%`));
                    if (projection.versusLeveled) {
                        scores.append(node("p", `${t("versusLeveled")}: ${gain(projection.versusLeveled)}`));
                        scores.append(node("p", `${t("chanceLeveled")}: ${number.format(projection.chanceToBeatLeveled * 100)}%`));
                    } else if (!row.equipped) scores.append(node("small", t("referenceUnavailable")));
                    scores.append(node("small", `${projection.samples} ${t("samples")}`));
                } else if (row.projectionUnavailable) scores.append(node("p", t(row.projectionUnavailable)));
                else if (artifact.level < 20 && !row.exclusion) scores.append(node("p", t("projectionPending")));
                const effectDefaults = Object.entries(row.newEffectDefaults || {});
                const inputDefaults = Object.entries(row.newInputDefaults || {});
                if (effectDefaults.length || inputDefaults.length) {
                    const assumptions = node("details"); assumptions.append(node("summary", t("assumptions")));
                    effectDefaults.forEach(([name, value]) => assumptions.append(node("p", `${name}: ${t(value ? "enabled" : "disabled")}`)));
                    inputDefaults.forEach(([name, value]) => assumptions.append(node("p", `${name}: ${value}`)));
                    scores.append(assumptions);
                }
                card.append(details, scores); results.append(card);
            });
            pager.hidden = status.totalRows <= 50;
            previous.disabled = offset === 0; next.disabled = offset + 50 >= status.totalRows;
            page.textContent = `${offset + 1}–${Math.min(offset + 50, status.totalRows)} / ${status.totalRows}`;
            changed();
        }
        window.addEventListener("pagehide", () => clearTimeout(timer));
        return {setDocument, changed};
    };
})();
