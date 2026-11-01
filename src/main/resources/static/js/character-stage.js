(() => {
    'use strict';
    const stage = document.querySelector('[data-character-stage]');
    if (!stage) return;
    const scriptUrl = document.currentScript.src;
    const assets = new URL('../character-stage/', scriptUrl);
    const hero = stage.closest('.character-hero');
    const canvas = stage.querySelector('[data-stage-canvas]');
    const portrait = stage.querySelector('[data-stage-portrait]');
    const controls = stage.querySelector('[data-stage-controls]');
    const modeButton = stage.querySelector('[data-stage-mode]');
    const pauseButton = stage.querySelector('[data-stage-pause]');
    const status = stage.querySelector('[data-stage-status]');
    const motion = matchMedia('(prefers-reduced-motion: reduce)');
    const de = document.documentElement.lang.startsWith('de');
    const cameraHint = stage.querySelector('[data-stage-camera-hint]');
    const text = de ? {art: 'Charakterillustration', show: '3D-Bühne', artwork: 'Illustration', pause: 'Bewegung pausieren',
        resume: 'Bewegung fortsetzen', loading: '3D-Bühne wird vorbereitet…', model: '3D-Charakter', retry: '3D erneut laden',
        cameraHint: 'Ziehen zum Drehen · Scrollen zum Zoomen', cameraLabel: '3D-Charakter. Ziehen zum Drehen, scrollen zum Zoomen.'}
        : {art: 'Character artwork', show: '3D stage', artwork: 'Artwork', pause: 'Pause motion',
            resume: 'Resume motion', loading: 'Preparing character stage…', model: '3D character', retry: 'Retry 3D',
            cameraHint: 'Drag to rotate · scroll to zoom', cameraLabel: '3D character. Drag to rotate the camera and scroll to zoom.'};
    const key = 'genshin-archive.character-stage.v1';
    let settings = {paused: false, artwork: false};
    try { const saved = JSON.parse(localStorage.getItem(key) || 'null');
        settings = {paused: saved?.paused === true, artwork: saved?.artwork === true}; } catch (_) {}
    let visible = true, descriptor, descriptorLoading, viewer, loading = false, disposed = false, automaticAttempted = false, presentation = 'portrait';
    const entranceAnimations = [];
    const persist = () => { try { localStorage.setItem(key, JSON.stringify(settings)); } catch (_) {} };
    controls.hidden = false;
    function sync() {
        stage.dataset.paused = String(settings.paused || motion.matches);
        stage.dataset.suspended = String(!visible || document.hidden);
        pauseButton.textContent = settings.paused ? text.resume : text.pause;
        pauseButton.setAttribute('aria-pressed', String(settings.paused));
        modeButton.setAttribute('aria-pressed', String(presentation === 'model'));
        modeButton.textContent = presentation === 'model' ? text.artwork : text.show;
        if (cameraHint) cameraHint.hidden = presentation !== 'model';
        const running = !settings.paused && !motion.matches && visible && !document.hidden;
        entranceAnimations.forEach(a => {
            if(a.playState === 'finished') return;
            if(settings.paused || motion.matches) a.finish();
            else running ? a.play() : a.pause();
        });
        if(settings.paused || motion.matches) viewer?.finishEntrance();
        viewer?.setRunning(running && presentation === 'model');
    }
    function entrance(element, silhouette = true, delay = 0) {
        if (!element || motion.matches || settings.paused || document.hidden) return;
        const effect = element.animate(silhouette ? [
            {filter: 'brightness(.03) saturate(0)', transform: 'scale(1.065) translateY(14px)'},
            {filter: 'brightness(.25) saturate(.3)', offset: .25},
            {filter: 'brightness(1) saturate(1)', transform: 'scale(1) translateY(0)'},
        ] : [{opacity: .45, transform: 'translateY(8px)'}, {opacity: 1, transform: 'translateY(0)'}],
        {duration: silhouette ? 850 : 420, delay, fill: 'backwards', easing: 'cubic-bezier(.2,.8,.2,1)'});
        entranceAnimations.push(effect);
    }
    requestAnimationFrame(() => {
        entrance(portrait || stage.querySelector('.hero-art-fallback'));
        // The facts remain real, usable server HTML throughout the short reveal.
        for (const node of hero.querySelectorAll('.character-heading, .character-title')) entrance(node, false, 650);
        for (const node of hero.querySelectorAll('.facts, .good-character-state')) entrance(node, false, 850);
    });
    if (portrait) {
        portrait.addEventListener('error', () => {
            const fallback = portrait.dataset.fallbackSrc;
            if (fallback && portrait.src !== new URL(fallback, location.href).href) {
                portrait.hidden = false; portrait.src = fallback;
            } else portrait.hidden = true;
        });
        if (portrait.complete && !portrait.naturalWidth) portrait.dispatchEvent(new Event('error'));
    }
    function showArtwork() {
        presentation = 'portrait'; stage.dataset.presentation = presentation;
        canvas.hidden = true; canvas.dataset.dragging = 'false'; status.textContent = text.art; sync();
    }
    function loadScript(file) {
        return new Promise((resolve, reject) => {
            const script = document.createElement('script'); script.src = new URL('vendor/' + file, assets).href;
            script.async = true; const timer = setTimeout(() => reject(Error('Renderer timeout')), 15000);
            script.onload = () => {clearTimeout(timer);resolve();}; script.onerror = () => {clearTimeout(timer);reject(Error('Renderer unavailable'));};
            document.head.append(script);
        });
    }
    function loadRuntime() {
        if (!window.archiveStageRuntime) window.archiveStageRuntime = (async () => {
            if (!window.BABYLON) await loadScript('babylon.js');
            if (!window.BABYLONMMD) await loadScript('babylon.mmd.min.js');
        })().catch(error => {window.archiveStageRuntime = null;throw error;});
        return window.archiveStageRuntime;
    }
    async function getDescriptor() {
        if (!descriptorLoading) descriptorLoading = fetch(stage.dataset.descriptorUrl, {headers: {Accept: 'application/json'}})
            .then(r => {if (!r.ok) throw Error('Stage unavailable');return r.json();});
        descriptor = await descriptorLoading;
        modeButton.hidden = !descriptor.available;
        return descriptor;
    }
    async function showModel() {
        if (loading || disposed) return;
        if (viewer) {
            canvas.hidden = false; presentation = 'model';stage.dataset.presentation = presentation;
            status.textContent = text.model;sync();return;
        }
        loading = true; modeButton.disabled = true; status.textContent = text.loading;
        let engine, scene;
        try {
            const info = descriptor || await getDescriptor();
            if (!info.available) {showArtwork();return;}
            await loadRuntime(); if (disposed) return;
            const B = window.BABYLON, M = window.BABYLONMMD;
            M.RegisterMmdModelLoaderDefaultSharedMaterialBuilder?.();
            M.RegisterPmxLoader?.(); M.RegisterMmdOutlineRenderer?.(); M.RegisterMmdRuntimeModelAnimation?.();
            engine = new B.Engine(canvas, true, {alpha: true, stencil: false, preserveDrawingBuffer: false});
            engine.setHardwareScalingLevel(Math.max(1, devicePixelRatio / 1.5));
            scene = new B.Scene(engine);scene.clearColor = new B.Color4(0,0,0,0);scene.skipPointerMovePicking = true;
            const camera = new B.ArcRotateCamera('character-stage', -Math.PI/2, 1.42, 30, B.Vector3.Zero(), scene);
            camera.attachControl(canvas, false);
            camera.panningSensibility = 700;
            camera.inertia = .78;
            new B.HemisphericLight('soft-fill', new B.Vector3(0,1,0), scene).intensity = .62;
            new B.DirectionalLight('spotlight',new B.Vector3(-.5,-1,.8),scene).intensity = .82;
            const faceFill = new B.DirectionalLight('face-fill', new B.Vector3(0,-.25,1), scene);
            faceFill.intensity = .34;
            faceFill.diffuse = new B.Color3(1,.93,.86);
            faceFill.specular = new B.Color3(.18,.16,.14);
            const container = await Promise.race([B.LoadAssetContainerAsync(info.modelUrl, scene),
                new Promise((_,reject) => setTimeout(()=>reject(Error('Model timeout')),20000))]);
            if (disposed) {scene.dispose();engine.dispose();return;}
            container.addAllToScene();
            // The model is rendered as a companion; clicks only control the camera and never pick meshes.
            for (const part of container.meshes) part.isPickable = false;
            const mesh = container.meshes.find(m => m.metadata?.bones?.length);
            if (!mesh || !mesh.metadata.bones.some(b => b.name === '上半身2')) throw Error('Incompatible model rig');
            let physicsRuntime = null;
            try {
                const wasm = await Promise.race([M.GetMmdWasmInstance(new M.MmdWasmInstanceTypeSPR()),
                    new Promise((_, reject) => setTimeout(() => reject(Error('Physics runtime timeout')), 20000))]);
                physicsRuntime = new M.MultiPhysicsRuntime(wasm);
                physicsRuntime.setGravity(new B.Vector3(0, -98, 0));
                physicsRuntime.fixedTimeStep = 1 / 120;
                physicsRuntime.maxSubSteps = 8;
            } catch (error) {
                // Keep the model available if a browser cannot initialize WebAssembly physics.
                console.debug('Character stage using bone animation without cloth physics:', error);
                physicsRuntime = null;
            }
            const runtime = new M.MmdRuntime(scene, physicsRuntime ? new M.MmdBulletPhysics(physicsRuntime) : null);
            const physicsBodies = Array.isArray(mesh.metadata.rigidBodies) && mesh.metadata.rigidBodies.length > 0;
            const model = runtime.createMmdModel(mesh, {buildPhysics: Boolean(physicsRuntime && physicsBodies)});
            stage.dataset.physics = physicsRuntime && physicsBodies ? 'enabled' : 'unavailable';
            const idle = await new M.VmdLoader(scene).loadAsync('archive-idle', info.motionUrl);
            model.setRuntimeAnimation(model.createRuntimeAnimation(idle));
            runtime.setManualAnimationDuration(info.motionFrames);
            let runtimeRegistered = false, physicsRegistered = false;
            if (physicsRuntime) {physicsRuntime.register(scene);physicsRegistered = true;}
            runtime.register(scene);runtimeRegistered = true;
            await runtime.seekAnimation(0,true);
            // Keep the cloth solver's velocities through the repeated idle clip.
            // The initial seek above initializes bodies; loop seeks below must not reset them.
            runtime.autoPhysicsInitialization = false;
            await scene.whenReadyAsync();
            let min = new B.Vector3(Infinity,Infinity,Infinity), max = new B.Vector3(-Infinity,-Infinity,-Infinity);
            for (const part of container.meshes) {
                const points = part.getVerticesData(B.VertexBuffer.PositionKind);if (!points) continue;
                for (let i=0;i<points.length;i+=3) {const v=new B.Vector3(points[i],points[i+1],points[i+2]);min.minimizeInPlace(v);max.maximizeInPlace(v);}
            }
            const height = max.y-min.y;
            if (!Number.isFinite(height) || height < .1) throw Error('Invalid model bounds');
            const center = min.add(max).scale(.5);camera.setTarget(center);
            const radius = Math.max(height, (max.x-min.x)/Math.max(.5,stage.clientWidth/stage.clientHeight))/(2*Math.tan(camera.fov/2))*1.18;
            camera.lowerRadiusLimit = radius * .72;
            camera.upperRadiusLimit = radius * 1.9;
            camera.lowerBetaLimit = .35;
            camera.upperBetaLimit = Math.PI - .35;
            camera.wheelPrecision = 45;
            const accent = getComputedStyle(hero).getPropertyValue('--hero-accent').trim();
            const color = B.Color3.FromHexString(/^#[0-9a-f]{6}$/i.test(accent)?accent:'#5d7f94');
            const material = new B.StandardMaterial('elemental-podium',scene);material.diffuseColor=color.scale(.2);material.emissiveColor=color.scale(.18);material.alpha=.7;
            const platform = B.MeshBuilder.CreateCylinder('stage',{diameter:height*.65,height:height*.015,tessellation:64},scene);
            platform.position.set(center.x,min.y-height*.02,center.z);platform.material=material;platform.isPickable=false;
            let active = false, elapsed = motion.matches || settings.paused ? 850 : 0, seeking = false;
            let settlingCamera = elapsed < 850;
            const stopCameraSettle = () => {settlingCamera = false;canvas.dataset.dragging = 'true';};
            const releaseCameraDrag = () => {canvas.dataset.dragging = 'false';};
            canvas.setAttribute('aria-label', text.cameraLabel);
            canvas.tabIndex = 0;
            canvas.addEventListener('pointerdown', stopCameraSettle);
            canvas.addEventListener('pointerup', releaseCameraDrag);
            canvas.addEventListener('pointercancel', releaseCameraDrag);
            canvas.addEventListener('wheel', stopCameraSettle, {passive: true});
            const render = () => {
                if (settlingCamera) {
                    elapsed = Math.min(850,elapsed+Math.min(engine.getDeltaTime(),50));const t=1-Math.pow(1-elapsed/850,3);
                    camera.alpha=-Math.PI/2-.22*(1-t);camera.radius=radius*(1+.1*(1-t));
                    if (elapsed >= 850) settlingCamera = false;
                }
                scene.render();
                if (!seeking && runtime.currentTime >= info.motionFrames/30-.025) {
                    seeking=true;
                    runtime.seekAnimation(0,false).then(async()=>{
                        // The MMD clock can pause itself at the end of a clip.
                        // Seeking alone does not reliably resume the next cycle.
                        if(active && !runtime.isAnimationPlaying) await runtime.playAnimation();
                        stage.dispatchEvent(new CustomEvent('character-stage:loop'));
                    }).catch(()=>{viewer?.setRunning(false);showArtwork();})
                        .finally(()=>{seeking=false;});
                }
            };
            camera.radius=radius;scene.render();
            viewer = {
                setRunning(running) {
                    stage.dataset.rendering=String(running);
                    if (running === active) return;active=running;
                    if (active) {
                        if (physicsRuntime && !physicsRegistered) {physicsRuntime.register(scene);physicsRegistered = true;}
                        if (!runtimeRegistered) {runtime.register(scene);runtimeRegistered = true;}
                        runtime.playAnimation().catch(()=>{});engine.runRenderLoop(render);
                    } else {
                        runtime.pauseAnimation();engine.stopRenderLoop(render);
                        if (runtimeRegistered) {runtime.unregister(scene);runtimeRegistered = false;}
                        if (physicsRuntime && physicsRegistered) {physicsRuntime.unregister(scene);physicsRegistered = false;}
                        scene.render();
                    }
                },
                finishEntrance() {elapsed=850;settlingCamera=false;camera.alpha=-Math.PI/2;camera.radius=radius;scene.render();},
                resize() {engine.resize();if(!active)scene.render();},
                dispose() {
                    engine.stopRenderLoop(render);runtime.pauseAnimation();
                    if (runtimeRegistered) runtime.unregister(scene);
                    if (physicsRuntime && physicsRegistered) physicsRuntime.unregister(scene);
                    scene.dispose();engine.dispose();
                },
            };
            presentation='model';stage.dataset.presentation=presentation;canvas.hidden=false;engine.resize();scene.render();
            delete stage.dataset.modelFailed;
            status.textContent = text.model;sync();entrance(canvas);
        } catch (error) {
            console.debug('Character stage using artwork:',error);
            scene?.dispose();engine?.dispose();viewer=null;showArtwork();modeButton.textContent=text.retry;
            stage.dataset.modelFailed='true';
        } finally {loading=false;modeButton.disabled=false;}
    }
    modeButton.addEventListener('click', () => {
        if(presentation==='model') {settings.artwork=true;persist();showArtwork();}
        else {settings.artwork=false;persist();void showModel();}
    });
    pauseButton.addEventListener('click',()=>{settings.paused=!settings.paused;persist();sync();});
    const observer = new IntersectionObserver(entries=>{
        visible=entries[0].isIntersecting;sync();
        if(visible && descriptor?.available && !viewer && !loading && !automaticAttempted && !settings.artwork && !motion.matches
            && innerWidth>=720 && !navigator.connection?.saveData) {automaticAttempted=true;void showModel();}
    },{threshold:.05});observer.observe(stage);
    const resize = new ResizeObserver(()=>viewer?.resize());resize.observe(stage);
    document.addEventListener('visibilitychange',sync);motion.addEventListener('change',sync);
    window.addEventListener('pagehide',event=>{if(event.persisted)viewer?.setRunning(false);else{disposed=true;viewer?.dispose();observer.disconnect();resize.disconnect();}});
    window.addEventListener('pageshow',sync);
    function prepare() {
        const start=()=>getDescriptor().then(info=>{
            if(info.available && visible && !document.hidden && !automaticAttempted && !settings.artwork && !motion.matches
                && innerWidth>=720 && !navigator.connection?.saveData) {automaticAttempted=true;return showModel();}
        }).catch(()=>showArtwork());
        if('requestIdleCallback' in window)requestIdleCallback(start,{timeout:2000});else setTimeout(start,300);
    }
    if(document.readyState==='complete')prepare();else window.addEventListener('load',prepare,{once:true});
    sync();
})();
