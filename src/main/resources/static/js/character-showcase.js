(() => {
    "use strict";

    const dialog = document.querySelector("[data-character-showcase-dialog]");
    if (!dialog) return;
    const title = dialog.querySelector("[data-showcase-title]");
    const heading = dialog.querySelector("[data-showcase-heading]");
    const elementCopy = dialog.querySelector("[data-showcase-element]");
    const weaponCopy = dialog.querySelector("[data-showcase-weapon]");
    const weaponFact = dialog.querySelector("[data-showcase-weapon-fact]");
    const regionFact = dialog.querySelector("[data-showcase-region]");
    const rarityFact = dialog.querySelector("[data-showcase-rarity]");
    const detailsLink = dialog.querySelector("[data-showcase-detail-link]");
    const hero = dialog.querySelector("[data-showcase-hero]");
    const stage = dialog.querySelector("[data-character-stage]");
    const canvas = stage.querySelector("[data-stage-canvas]");
    const portrait = stage.querySelector("[data-stage-portrait]");
    const fallback = stage.querySelector("[data-showcase-fallback]");
    const controls = stage.querySelector("[data-stage-controls]");
    const modeButton = stage.querySelector("[data-stage-mode]");
    const pauseButton = stage.querySelector("[data-stage-pause]");
    const status = stage.querySelector("[data-stage-status]");
    const cameraHint = stage.querySelector("[data-stage-camera-hint]");
    const loading = dialog.querySelector("[data-showcase-loading]");
    const labels = dialog.dataset;
    const settingsKey = "genshin-archive.character-stage.v1";
    const motion = matchMedia("(prefers-reduced-motion: reduce)");
    let settings = {paused: false, artwork: false};
    try {
        const saved = JSON.parse(localStorage.getItem(settingsKey) || "null");
        settings = {paused: saved?.paused === true, artwork: saved?.artwork === true};
    } catch (_) {}
    let descriptor = null, viewer = null, pendingEngine = null, pendingScene = null;
    let requestVersion = 0, currentCharacter = null;
    let returnFocus = null;
    const fallbackBackdrop = document.createElement("div");
    fallbackBackdrop.className = "character-showcase-fallback-backdrop";
    fallbackBackdrop.hidden = true;
    document.body.append(fallbackBackdrop);
    const text = {
        art: labels.labelArt, show: labels.labelShow3d, pause: labels.labelPause,
        resume: document.documentElement.lang.startsWith("de") ? "Bewegung fortsetzen" : "Resume motion",
        model: labels.labelModel, loading: labels.labelLoading,
        retry: document.documentElement.lang.startsWith("de") ? "3D erneut laden" : "Retry 3D",
        fallback: document.documentElement.lang.startsWith("de")
            ? "Für diesen Charakter ist kein kompatibles 3D-Modell verfügbar."
            : "No compatible 3D model is available for this character.",
        cameraHint: labels.labelCameraHint,
    };
    portrait.addEventListener("error", () => { portrait.hidden = true; }, {once: false});
    cameraHint.textContent = text.cameraHint;
    canvas.setAttribute("aria-label", document.documentElement.lang.startsWith("de")
        ? "3D-Charakter. Ziehen zum Drehen, scrollen zum Zoomen."
        : "3D character. Drag to rotate the camera and scroll to zoom.");

    function persist() {
        try { localStorage.setItem(settingsKey, JSON.stringify(settings)); } catch (_) {}
    }

    function syncControls() {
        stage.dataset.paused = String(settings.paused || motion.matches);
        pauseButton.textContent = settings.paused ? text.resume : text.pause;
        pauseButton.setAttribute("aria-pressed", String(settings.paused));
        modeButton.textContent = stage.dataset.presentation === "model" ? text.art : text.show;
        modeButton.setAttribute("aria-pressed", String(stage.dataset.presentation === "model"));
        cameraHint.hidden = stage.dataset.presentation !== "model";
    }

    function disposeViewer() {
        viewer?.dispose();
        viewer = null;
        if (pendingScene) pendingScene.dispose();
        pendingScene = null;
        if (pendingEngine) pendingEngine.dispose();
        pendingEngine = null;
        canvas.hidden = true;
        canvas.dataset.dragging = "false";
    }

    function closeShowcase() {
        requestVersion += 1;
        disposeViewer();
        currentCharacter = null;
        descriptor = null;
    }

    function isOpen() {
        return dialog.hasAttribute("open");
    }

    function openDialog() {
        if (typeof dialog.showModal === "function") {
            try {
                dialog.showModal();
                return;
            } catch (error) {
                console.debug("Using character showcase modal fallback:", error);
            }
        }
        fallbackBackdrop.hidden = false;
        dialog.classList.add("is-fallback-open");
        dialog.setAttribute("open", "");
        dialog.querySelector("[data-showcase-close]").focus();
    }

    function closeDialog() {
        if (!isOpen()) return;
        if (typeof dialog.close === "function" && !dialog.classList.contains("is-fallback-open")) {
            dialog.close();
            return;
        }
        dialog.removeAttribute("open");
        dialog.classList.remove("is-fallback-open");
        fallbackBackdrop.hidden = true;
        closeShowcase();
        returnFocus?.focus();
        returnFocus = null;
    }

    function loadScript(file) {
        return new Promise((resolve, reject) => {
            const base = new URL(dialog.dataset.stageAssets, location.href);
            const script = document.createElement("script");
            script.src = new URL(`vendor/${file}`, base).href;
            script.async = true;
            const timer = setTimeout(() => reject(new Error("Renderer timeout")), 15000);
            script.onload = () => { clearTimeout(timer); resolve(); };
            script.onerror = () => { clearTimeout(timer); reject(new Error("Renderer unavailable")); };
            document.head.append(script);
        });
    }

    async function loadRuntime() {
        if (!window.archiveShowcaseRuntime) {
            window.archiveShowcaseRuntime = (async () => {
                if (!window.BABYLON) await loadScript("babylon.js");
                if (!window.BABYLONMMD) await loadScript("babylon.mmd.min.js");
            })().catch((error) => { window.archiveShowcaseRuntime = null; throw error; });
        }
        return window.archiveShowcaseRuntime;
    }

    async function fetchDescriptor(character, version) {
        const api = new URL(dialog.dataset.stageApi, location.href);
        const endpoint = new URL(encodeURIComponent(character.stageKey), api);
        const response = await fetch(endpoint, {headers: {Accept: "application/json"}});
        if (!response.ok) throw new Error("Character stage unavailable");
        const result = await response.json();
        if (version !== requestVersion || !isOpen()) return null;
        descriptor = result;
        return result;
    }

    function showArtwork() {
        stage.dataset.presentation = "portrait";
        canvas.hidden = true;
        portrait.hidden = !portrait.hasAttribute("src");
        status.textContent = text.art;
        if (viewer) viewer.setRunning(false);
        syncControls();
    }

    async function prepareShowcase(version) {
        loading.textContent = text.loading;
        controls.hidden = false;
        status.textContent = text.art;
        try {
            const info = await fetchDescriptor(currentCharacter, version);
            if (!info || version !== requestVersion) return;
            modeButton.hidden = !info.available;
            if (!info.available) {
                loading.textContent = text.fallback;
                showArtwork();
            } else if (settings.artwork) {
                loading.textContent = "";
                showArtwork();
            } else {
                await showModel(version, info);
            }
        } catch (error) {
            if (version !== requestVersion) return;
            console.debug("Character showcase using artwork:", error);
            modeButton.hidden = true;
            loading.textContent = text.fallback;
            showArtwork();
        }
    }

    async function showModel(version = requestVersion, info = descriptor) {
        if (viewer) {
            stage.dataset.presentation = "model";
            canvas.hidden = false;
            loading.textContent = "";
            viewer.setRunning(!settings.paused && !motion.matches && isOpen());
            syncControls();
            return;
        }
        if (!info?.available || version !== requestVersion || !isOpen()) return;
        modeButton.disabled = true;
        loading.textContent = text.loading;
        let engine, scene, physicsRuntime, runtime, runtimeRegistered = false, physicsRegistered = false;
        try {
            await loadRuntime();
            if (version !== requestVersion || !isOpen()) return;
            const B = window.BABYLON, M = window.BABYLONMMD;
            if (!window.archiveShowcaseMmdRegistered) {
                M.RegisterMmdModelLoaderDefaultSharedMaterialBuilder?.();
                M.RegisterPmxLoader?.(); M.RegisterMmdOutlineRenderer?.(); M.RegisterMmdRuntimeModelAnimation?.();
                window.archiveShowcaseMmdRegistered = true;
            }
            engine = new B.Engine(canvas, true, {alpha: true, stencil: false, preserveDrawingBuffer: false});
            pendingEngine = engine;
            engine.setHardwareScalingLevel(Math.max(1, devicePixelRatio / 1.5));
            scene = new B.Scene(engine); pendingScene = scene;
            scene.clearColor = new B.Color4(0, 0, 0, 0); scene.skipPointerMovePicking = true;
            const camera = new B.ArcRotateCamera("showcase-camera", -Math.PI / 2, 1.42, 30, B.Vector3.Zero(), scene);
            camera.attachControl(canvas, false); camera.panningSensibility = 700; camera.inertia = .78; camera.wheelPrecision = 45;
            new B.HemisphericLight("showcase-fill", new B.Vector3(0, 1, 0), scene).intensity = .62;
            new B.DirectionalLight("showcase-key", new B.Vector3(-.5, -1, .8), scene).intensity = .82;
            const faceFill = new B.DirectionalLight("showcase-face-fill", new B.Vector3(0, -.25, 1), scene);
            faceFill.intensity = .34; faceFill.diffuse = new B.Color3(1, .93, .86);
            const modelContainer = await Promise.race([
                B.LoadAssetContainerAsync(info.modelUrl, scene),
                new Promise((_, reject) => setTimeout(() => reject(new Error("Model timeout")), 20000)),
            ]);
            if (version !== requestVersion || !isOpen()) { scene.dispose(); engine.dispose(); return; }
            modelContainer.addAllToScene();
            modelContainer.meshes.forEach((part) => { part.isPickable = false; });
            const mesh = modelContainer.meshes.find((part) => part.metadata?.bones?.length);
            if (!mesh || !mesh.metadata.bones.some((bone) => bone.name === "上半身2")) throw new Error("Incompatible character rig");
            try {
                const wasm = await Promise.race([
                    M.GetMmdWasmInstance(new M.MmdWasmInstanceTypeSPR()),
                    new Promise((_, reject) => setTimeout(() => reject(new Error("Physics runtime timeout")), 20000)),
                ]);
                physicsRuntime = new M.MultiPhysicsRuntime(wasm);
                physicsRuntime.setGravity(new B.Vector3(0, -98, 0));
                physicsRuntime.fixedTimeStep = 1 / 120; physicsRuntime.maxSubSteps = 8;
            } catch (error) {
                console.debug("Showcase using bone motion without cloth physics:", error);
            }
            runtime = new M.MmdRuntime(scene, physicsRuntime ? new M.MmdBulletPhysics(physicsRuntime) : null);
            const physicsBodies = Array.isArray(mesh.metadata.rigidBodies) && mesh.metadata.rigidBodies.length > 0;
            const model = runtime.createMmdModel(mesh, {buildPhysics: Boolean(physicsRuntime && physicsBodies)});
            const idle = await new M.VmdLoader(scene).loadAsync("showcase-idle", info.motionUrl);
            model.setRuntimeAnimation(model.createRuntimeAnimation(idle));
            runtime.setManualAnimationDuration(info.motionFrames);
            if (physicsRuntime) { physicsRuntime.register(scene); physicsRegistered = true; }
            runtime.register(scene); runtimeRegistered = true;
            await runtime.seekAnimation(0, true);
            runtime.autoPhysicsInitialization = false;
            await scene.whenReadyAsync();
            const min = new B.Vector3(Infinity, Infinity, Infinity), max = new B.Vector3(-Infinity, -Infinity, -Infinity);
            for (const part of modelContainer.meshes) {
                const points = part.getVerticesData(B.VertexBuffer.PositionKind);
                if (!points) continue;
                for (let i = 0; i < points.length; i += 3) {
                    const point = new B.Vector3(points[i], points[i + 1], points[i + 2]);
                    min.minimizeInPlace(point); max.maximizeInPlace(point);
                }
            }
            const height = max.y - min.y;
            if (!Number.isFinite(height) || height < .1) throw new Error("Invalid character bounds");
            const center = min.add(max).scale(.5); camera.setTarget(center);
            const radius = Math.max(height, (max.x - min.x) / Math.max(.5, stage.clientWidth / stage.clientHeight))
                / (2 * Math.tan(camera.fov / 2)) * 1.18;
            camera.lowerRadiusLimit = radius * .72; camera.upperRadiusLimit = radius * 1.9;
            camera.lowerBetaLimit = .35; camera.upperBetaLimit = Math.PI - .35;
            const accent = getComputedStyle(hero).getPropertyValue("--hero-accent").trim();
            const color = B.Color3.FromHexString(/^#[0-9a-f]{6}$/i.test(accent) ? accent : "#5d7f94");
            const material = new B.StandardMaterial("showcase-podium-material", scene);
            material.diffuseColor = color.scale(.2); material.emissiveColor = color.scale(.18); material.alpha = .7;
            const platform = B.MeshBuilder.CreateCylinder("showcase-podium", {diameter: height * .65, height: height * .015, tessellation: 64}, scene);
            platform.position.set(center.x, min.y - height * .02, center.z); platform.material = material; platform.isPickable = false;
            let active = false, seeking = false;
            const render = () => {
                scene.render();
                if (!seeking && runtime.currentTime >= info.motionFrames / 30 - .025) {
                    seeking = true;
                    runtime.seekAnimation(0, false).then(async () => {
                        if (active && !runtime.isAnimationPlaying) await runtime.playAnimation();
                    }).catch(() => viewer?.setRunning(false)).finally(() => { seeking = false; });
                }
            };
            const setRunning = (running) => {
                if (running === active) return;
                active = running;
                if (active) {
                    if (physicsRuntime && !physicsRegistered) { physicsRuntime.register(scene); physicsRegistered = true; }
                    if (!runtimeRegistered) { runtime.register(scene); runtimeRegistered = true; }
                    runtime.playAnimation().catch(() => {}); engine.runRenderLoop(render);
                } else {
                    runtime.pauseAnimation(); engine.stopRenderLoop(render);
                    if (runtimeRegistered) { runtime.unregister(scene); runtimeRegistered = false; }
                    if (physicsRuntime && physicsRegistered) { physicsRuntime.unregister(scene); physicsRegistered = false; }
                    scene.render();
                }
            };
            camera.radius = radius; scene.render();
            runtime.pauseAnimation(); runtime.unregister(scene); runtimeRegistered = false;
            if (physicsRuntime && physicsRegistered) { physicsRuntime.unregister(scene); physicsRegistered = false; }
            viewer = {
                setRunning,
                resize() { engine.resize(); if (!active) scene.render(); },
                dispose() {
                    setRunning(false); engine.stopRenderLoop(render);
                    if (runtimeRegistered) runtime.unregister(scene);
                    if (physicsRuntime && physicsRegistered) physicsRuntime.unregister(scene);
                    scene.dispose(); engine.dispose();
                },
            };
            pendingEngine = null; pendingScene = null;
            if (!settings.artwork) {
                stage.dataset.presentation = "model"; canvas.hidden = false; engine.resize(); scene.render();
                if (!motion.matches) canvas.animate([{opacity: 0, transform: "translateY(10px)"}, {opacity: 1, transform: "translateY(0)"}],
                    {duration: 650, easing: "cubic-bezier(.2,.8,.2,1)"});
                    setRunning(!settings.paused && !motion.matches && isOpen());
            }
            loading.textContent = ""; status.textContent = text.model; syncControls();
        } catch (error) {
            console.debug("Character showcase using artwork:", error);
            if (runtime && runtimeRegistered) runtime.unregister(scene);
            if (physicsRuntime && physicsRegistered) physicsRuntime.unregister(scene);
            scene?.dispose(); engine?.dispose();
            if (pendingScene === scene) pendingScene = null;
            if (pendingEngine === engine) pendingEngine = null;
            if (version === requestVersion && isOpen()) {
                loading.textContent = text.fallback; modeButton.textContent = text.retry; showArtwork();
            }
        } finally {
            modeButton.disabled = false;
        }
    }

    function openShowcase(button) {
        if (isOpen()) closeDialog();
        closeShowcase();
        returnFocus = button;
        loading.textContent = text.loading;
        openDialog();
        const element = (button.dataset.element || "neutral").toLowerCase();
        currentCharacter = {
            stageKey: button.dataset.stageKey || "",
            name: button.dataset.name || "Character",
            element,
        };
        title.textContent = currentCharacter.name;
        heading.textContent = currentCharacter.name;
        elementCopy.textContent = element === "neutral" ? "" : `${element.slice(0, 1).toUpperCase()}${element.slice(1)}`;
        weaponCopy.textContent = button.dataset.weapon || "";
        weaponFact.textContent = button.dataset.weapon || "—";
        regionFact.textContent = button.dataset.region || "—";
        rarityFact.textContent = button.dataset.rarity ? `${button.dataset.rarity} ★` : "—";
        detailsLink.href = button.dataset.detailUrl || "#";
        fallback.textContent = currentCharacter.name.slice(0, 1);
        hero.classList.forEach((className) => { if (className.startsWith("element-")) hero.classList.remove(className); });
        hero.classList.add(`element-${element}`);
        stage.dataset.characterName = currentCharacter.name;
        stage.dataset.descriptorUrl = new URL(encodeURIComponent(currentCharacter.stageKey),
            new URL(dialog.dataset.stageApi, location.href)).href;
        stage.dataset.presentation = "portrait";
        const image = button.dataset.portrait;
        portrait.alt = currentCharacter.name;
        if (image) { portrait.src = image; portrait.hidden = false; }
        else { portrait.removeAttribute("src"); portrait.hidden = true; }
        modeButton.hidden = true; modeButton.disabled = false;
        controls.hidden = false;
        loading.textContent = "";
        status.textContent = text.art;
        stage.dataset.paused = String(settings.paused || motion.matches);
        stage.dataset.suspended = "false";
        syncControls();
        void prepareShowcase(requestVersion);
    }

    dialog.querySelector("[data-showcase-close]").addEventListener("click", closeDialog);
    dialog.addEventListener("close", () => {
        fallbackBackdrop.hidden = true;
        dialog.classList.remove("is-fallback-open");
        closeShowcase();
        returnFocus?.focus();
        returnFocus = null;
    });
    dialog.addEventListener("click", (event) => { if (event.target === dialog) closeDialog(); });
    dialog.addEventListener("cancel", (event) => {
        if (dialog.classList.contains("is-fallback-open")) { event.preventDefault(); closeDialog(); }
    });
    fallbackBackdrop.addEventListener("click", closeDialog);
    document.addEventListener("keydown", (event) => {
        if (event.key === "Escape" && dialog.classList.contains("is-fallback-open")) closeDialog();
    });
    // Showcase triggers live on the character cards, outside the dialog itself.
    document.querySelectorAll("[data-character-showcase-open]").forEach((button) => {
        button.addEventListener("click", (event) => {
            event.preventDefault();
            event.stopPropagation();
            try {
                openShowcase(button);
            } catch (error) {
                console.error("Could not open character showcase:", error);
                if (!isOpen()) openDialog();
                loading.textContent = text.fallback;
            }
        });
    });
    modeButton.addEventListener("click", () => {
        if (stage.dataset.presentation === "model") {
            settings.artwork = true; persist(); showArtwork();
        } else {
            settings.artwork = false; persist();
            if (viewer) {
                stage.dataset.presentation = "model"; canvas.hidden = false;
                status.textContent = text.model; syncControls();
                viewer.setRunning(!settings.paused && !motion.matches && isOpen());
            } else void showModel(requestVersion, descriptor);
        }
    });
    pauseButton.addEventListener("click", () => {
        settings.paused = !settings.paused; persist(); syncControls();
        viewer?.setRunning(!settings.paused && !motion.matches && isOpen() && stage.dataset.presentation === "model");
    });
    motion.addEventListener("change", () => {
        stage.dataset.paused = String(settings.paused || motion.matches);
        viewer?.setRunning(!settings.paused && !motion.matches && isOpen() && stage.dataset.presentation === "model");
    });
    const resize = new ResizeObserver(() => viewer?.resize());
    resize.observe(stage);
    window.addEventListener("pagehide", () => { closeShowcase(); resize.disconnect(); });
})();
