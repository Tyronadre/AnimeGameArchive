(() => {
    'use strict';
    const stage = document.querySelector('[data-character-stage]');
    if (!stage) return;
    const inspector = document.querySelector('[data-character-inspector]');
    const openButton = document.querySelector('[data-inspector-open]');
    const closeButton = inspector?.querySelector('[data-inspector-close]');
    const scriptUrl = document.currentScript.src;
    const assets = new URL('../character-stage/', scriptUrl);
    stage.style.setProperty('--dendro-panorama', `url("${new URL('environments/dendro-forest-panorama.png', assets).href}")`);
    const hero = document.querySelector('.character-hero');
    const canvas = stage.querySelector('[data-stage-canvas]');
    const portrait = stage.querySelector('[data-stage-portrait]');
    const controls = stage.querySelector('[data-stage-controls]');
    const modeButton = stage.querySelector('[data-stage-mode]');
    const pauseButton = stage.querySelector('[data-stage-pause]');
    const status = stage.querySelector('[data-stage-status]');
    const motion = matchMedia('(prefers-reduced-motion: reduce)');
    const de = document.documentElement.lang.startsWith('de');
    const cameraHint = stage.querySelector('[data-stage-camera-hint]');
    const motionSelect = stage.querySelector('[data-stage-motion]');
    const lightingSelect = stage.querySelector('[data-stage-lighting]');
    const backgroundSelect = stage.querySelector('[data-stage-background]');
    const inspectorControls = [...stage.querySelectorAll('[data-inspector-control]')];
    if (backgroundSelect) backgroundSelect.value = stage.dataset.element === 'dendro' ? 'dendro' : 'element';
    stage.dataset.backdrop = backgroundSelect?.value || 'element';
    const text = de ? {art: 'Charakterillustration', show: '3D-Bühne', artwork: 'Illustration', pause: 'Bewegung pausieren',
        resume: 'Bewegung fortsetzen', loading: '3D-Bühne wird vorbereitet…', model: '3D-Charakter', retry: '3D erneut laden',
        loadingModel: '3D-Modell wird geladen…', loadingPhysics: 'Physik wird initialisiert…',
        cameraHint: 'Ziehen zum Drehen · Scrollen zum Zoomen', cameraLabel: '3D-Charakter. Ziehen zum Drehen, scrollen zum Zoomen.',
        motions: {idle:'Leerlauf',walk:'Gehen auf der Stelle',jog:'Joggen auf der Stelle',sprint:'Sprint auf der Stelle'}}
        : {art: 'Character artwork', show: '3D stage', artwork: 'Artwork', pause: 'Pause motion',
            resume: 'Resume motion', loading: 'Preparing character stage…', model: '3D character', retry: 'Retry 3D',
            loadingModel: 'Loading 3D model…', loadingPhysics: 'Initializing cloth physics…',
            cameraHint: 'Drag to rotate · scroll to zoom', cameraLabel: '3D character. Drag to rotate the camera and scroll to zoom.',
            motions: {idle:'Idle',walk:'Walk in place',jog:'Jog in place',sprint:'Sprint in place'}};
    const key = 'genshin-archive.character-stage.v1';
    let settings = {paused: false, artwork: false};
    try { const saved = JSON.parse(localStorage.getItem(key) || 'null');
        settings = {paused: saved?.paused === true, artwork: saved?.artwork === true}; } catch (_) {}
    let visible = true, descriptor, descriptorLoading, viewer, loading = false, disposed = false, presentation = 'portrait';
    const entranceAnimations = [];
    const persist = () => { try { localStorage.setItem(key, JSON.stringify(settings)); } catch (_) {} };
    controls.hidden = false;
    function sync() {
        const stageVisible = inspector ? inspector.open : visible;
        stage.dataset.paused = String(settings.paused || motion.matches);
        stage.dataset.suspended = String(!stageVisible || document.hidden);
        pauseButton.textContent = settings.paused ? text.resume : text.pause;
        pauseButton.setAttribute('aria-pressed', String(settings.paused));
        modeButton.setAttribute('aria-pressed', String(presentation === 'model'));
        modeButton.textContent = presentation === 'model' ? text.artwork : text.show;
        if (cameraHint) cameraHint.hidden = presentation !== 'model';
        inspectorControls.forEach(control => { control.hidden = presentation !== 'model'; });
        const running = !settings.paused && !motion.matches && stageVisible && !document.hidden;
        entranceAnimations.forEach(a => {
            if(a.playState === 'finished') return;
            if(settings.paused || motion.matches) a.finish();
            else running ? a.play() : a.pause();
        });
        if(settings.paused || motion.matches) viewer?.finishEntrance();
        viewer?.setRunning(running && presentation === 'model' && (!inspector || inspector.open));
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
        // The facts remain usable server HTML; only the page copy gets a subtle reveal.
        for (const node of hero?.querySelectorAll('.character-heading, .character-title') || []) entrance(node, false, 150);
        for (const node of hero?.querySelectorAll('.facts, .good-character-state') || []) entrance(node, false, 300);
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
        canvas.hidden = true; canvas.style.opacity = ''; canvas.dataset.dragging = 'false'; status.textContent = text.art; sync();
    }
    function loadScript(file) {
        return new Promise((resolve, reject) => {
            const script = document.createElement('script'); script.src = new URL('vendor/' + file, assets).href;
            script.async = true; const timer = setTimeout(() => reject(Error('Renderer timeout')), 15000);
            script.onload = () => {clearTimeout(timer);resolve();}; script.onerror = () => {clearTimeout(timer);reject(Error('Renderer unavailable'));};
            document.head.append(script);
        });
    }
    function withTimeout(promise, milliseconds, label) {
        let timer;
        return Promise.race([
            promise,
            new Promise((_, reject) => { timer = setTimeout(() => reject(Error(`${label} timed out`)), milliseconds); }),
        ]).finally(() => clearTimeout(timer));
    }
    function loadRuntime() {
        if (!window.archiveStageRuntime) window.archiveStageRuntime = (async () => {
            if (!window.BABYLON) await loadScript('babylon.js');
            if (!window.BABYLONMMD) await loadScript('babylon.mmd.min.js');
        })().catch(error => {window.archiveStageRuntime = null;throw error;});
        return window.archiveStageRuntime;
    }
    function createDendroEnvironment(B, scene, center, groundY, height) {
        const motes = [];
        const moteMaterial = new B.StandardMaterial('dendro-firefly', scene);
        moteMaterial.diffuseColor = new B.Color3(.74, .92, .34);
        moteMaterial.emissiveColor = new B.Color3(.38, .72, .12);
        moteMaterial.disableLighting = true;
        for (let i=0; i<18; i++) {
            const mote = B.MeshBuilder.CreateSphere(`dendro-firefly-${i}`, {diameter:height*(.012+(i%3)*.006), segments:6}, scene);
            mote.isPickable = false; mote.material = moteMaterial;
            const angle = i * 2.39996, radius = height * (.9 + (i%4)*.18);
            mote.position.set(center.x+Math.sin(angle)*radius,
                groundY+height*(.38+(i%6)*.16), center.z+Math.cos(angle)*radius);
            motes.push({mesh:mote, phase:i*.71, baseY:mote.position.y});
        }
        return {
            setEnabled(enabled) { motes.forEach(({mesh}) => mesh.setEnabled(enabled)); },
            tick(seconds, animate) {
                if (!animate) return;
                motes.forEach(({mesh, phase, baseY}) => { mesh.position.y = baseY + Math.sin(seconds*1.25+phase)*height*.055; });
            },
        };
    }
    async function loadDendroForest(B, scene, center, groundY, height) {
        const response = await withTimeout(fetch(new URL('environments/dendro-forest-environment.glb?v=3', assets)), 15000, 'Environment download');
        if (!response.ok) throw Error('3D forest environment unavailable');
        const bytes = await response.arrayBuffer();
        const header = new DataView(bytes);
        if (header.getUint32(0, true) !== 0x46546c67 || header.getUint32(4, true) !== 2) throw Error('Invalid forest GLB');
        let offset = 12, doc, binary;
        while (offset < bytes.byteLength) {
            const size = header.getUint32(offset, true), type = header.getUint32(offset + 4, true); offset += 8;
            if (type === 0x4e4f534a) doc = JSON.parse(new TextDecoder().decode(new Uint8Array(bytes, offset, size)).trim());
            else if (type === 0x004e4942) binary = bytes.slice(offset, offset + size);
            offset += size;
        }
        if (!doc || !binary) throw Error('Incomplete forest GLB');
        const root = new B.TransformNode('dendro-forest-root', scene);
        root.scaling.setAll(height / 18);
        root.position.set(center.x, groundY, center.z);
        const textureCache = new Map();
        const getTexture = index => {
            if (index == null) return null;
            if (textureCache.has(index)) return textureCache.get(index);
            const definition = doc.textures[index], image = doc.images[definition.source];
            const view = doc.bufferViews[image.bufferView];
            const payload = binary.slice((view.byteOffset || 0), (view.byteOffset || 0) + view.byteLength);
            const url = URL.createObjectURL(new Blob([payload], {type: image.mimeType || 'image/png'}));
            const texture = new B.Texture(url, scene, false, false, B.Texture.TRILINEAR_SAMPLINGMODE,
                () => URL.revokeObjectURL(url), () => URL.revokeObjectURL(url));
            textureCache.set(index, texture);
            return texture;
        };
        const materials = doc.materials.map((item, i) => {
            const pbr = item.pbrMetallicRoughness || {}, color = pbr.baseColorFactor || [1, 1, 1, 1];
            const material = new B.PBRMaterial(`dendro-forest-material-${i}`, scene);
            material.albedoColor = new B.Color3(color[0], color[1], color[2]);
            material.alpha = color[3]; material.metallic = pbr.metallicFactor || 0;
            material.roughness = pbr.roughnessFactor ?? .8; material.backFaceCulling = false;
            material.albedoTexture = getTexture(pbr.baseColorTexture?.index);
            material.bumpTexture = getTexture(item.normalTexture?.index);
            if (material.bumpTexture) material.bumpTexture.gammaSpace = false;
            if (item.alphaMode === 'MASK') {
                material.useAlphaFromAlbedoTexture = true;
                material.transparencyMode = B.PBRMaterial.MATERIAL_ALPHATEST;
                material.alphaCutOff = item.alphaCutoff ?? .2;
            }
            return material;
        });
        const accessor = (index, components) => {
            const item = doc.accessors[index], view = doc.bufferViews[item.bufferView];
            const stride = view.byteStride || components * 4;
            const base = (view.byteOffset || 0) + (item.byteOffset || 0);
            const values = new Float32Array(item.count * components), data = new DataView(binary);
            for (let i = 0; i < item.count; i++) for (let c = 0; c < components; c++)
                values[i * components + c] = data.getFloat32(base + i * stride + c * 4, true);
            return values;
        };
        const indices = index => {
            const item = doc.accessors[index], view = doc.bufferViews[item.bufferView];
            const data = new DataView(binary), base = (view.byteOffset || 0) + (item.byteOffset || 0);
            const values = new Uint32Array(item.count);
            for (let i = 0; i < item.count; i++) values[i] = data.getUint32(base + i * 4, true);
            return values;
        };
        let meshIndex = 0;
        for (const definition of doc.meshes || []) for (const primitive of definition.primitives || []) {
            const mesh = new B.Mesh(`dendro-forest-${meshIndex++}`, scene);
            const vertexData = new B.VertexData();
            vertexData.positions = accessor(primitive.attributes.POSITION, 3);
            vertexData.normals = accessor(primitive.attributes.NORMAL, 3);
            if (primitive.attributes.TEXCOORD_0 != null) vertexData.uvs = accessor(primitive.attributes.TEXCOORD_0, 2);
            if (primitive.attributes.COLOR_0 != null) vertexData.colors = accessor(primitive.attributes.COLOR_0, 4);
            vertexData.indices = indices(primitive.indices);
            vertexData.applyToMesh(mesh, false);
            mesh.material = materials[primitive.material] || null;
            mesh.parent = root; mesh.isPickable = false; mesh.alwaysSelectAsActiveMesh = true;
        }
        root.computeWorldMatrix(true);
        stage.dataset.environmentLoaded = 'true';
        return {setEnabled(enabled) {root.setEnabled(enabled);}, tick() {}, dispose() {root.dispose(false, true);}};
    }
    async function getDescriptor() {
        if (!descriptorLoading) descriptorLoading = fetch(stage.dataset.descriptorUrl, {headers: {Accept: 'application/json'}})
            .then(r => {if (!r.ok) throw Error('Stage unavailable');return r.json();});
        descriptor = await descriptorLoading;
        modeButton.hidden = !descriptor.available;
        if (openButton) openButton.hidden = !descriptor.available;
        if (motionSelect && descriptor.available && Array.isArray(descriptor.motions)) {
            const selected = motionSelect.value || 'idle';
            motionSelect.replaceChildren(...descriptor.motions.map(motion => {
                const option = document.createElement('option');
                option.value = motion.id; option.textContent = text.motions[motion.id] || motion.label;
                return option;
            }));
            motionSelect.value = descriptor.motions.some(motion => motion.id === selected) ? selected : 'idle';
        }
        return descriptor;
    }
    async function showModel() {
        if (loading || disposed) return;
        if (viewer) {
            canvas.hidden = false; presentation = 'model';stage.dataset.presentation = presentation;
            status.textContent = text.model;sync();return;
        }
        loading = true; modeButton.disabled = true; status.textContent = text.loading;
        canvas.hidden = false; canvas.style.opacity = '0';
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
            camera.angularSensibilityX = 460;
            camera.angularSensibilityY = 460;
            camera.inertia = .6;
            const ambient = new B.HemisphericLight('soft-fill', new B.Vector3(0,1,0), scene);
            const spotlight = new B.DirectionalLight('spotlight',new B.Vector3(-.5,-1,.8),scene);
            const faceFill = new B.DirectionalLight('face-fill', new B.Vector3(0,-.25,1), scene);
            faceFill.diffuse = new B.Color3(1,.93,.86);
            faceFill.specular = new B.Color3(.18,.16,.14);
            const applyLighting = () => {
                const presets = {soft:[.62,.82,.34],warm:[.72,.65,.56],dramatic:[.28,1.18,.18]};
                const [fill, keyLight, face] = presets[lightingSelect?.value] || presets.soft;
                ambient.intensity = fill; spotlight.intensity = keyLight; faceFill.intensity = face;
            };
            applyLighting();
            status.textContent = text.loadingModel;
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
                status.textContent = text.loadingPhysics;
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
            const motions = info.motions?.length ? info.motions : [{id:'idle',url:info.motionUrl,frames:info.motionFrames,loop:true}];
            let selectedMotion = motions.find(item => item.id === motionSelect?.value) || motions[0];
            status.textContent = de ? 'Animation wird geladen…' : 'Loading animation…';
            const initialMotion = await withTimeout(new M.VmdLoader(scene).loadAsync(`archive-${selectedMotion.id}`, selectedMotion.url), 20000, 'Animation load');
            model.setRuntimeAnimation(model.createRuntimeAnimation(initialMotion));
            runtime.setManualAnimationDuration(selectedMotion.frames);
            let runtimeRegistered = false, physicsRegistered = false;
            if (physicsRuntime) {physicsRuntime.register(scene);physicsRegistered = true;}
            runtime.register(scene);runtimeRegistered = true;
            await withTimeout(runtime.seekAnimation(0,true), 15000, 'Animation initialization');
            // Keep the cloth solver's velocities through the repeated idle clip.
            // The initial seek above initializes bodies; loop seeks below must not reset them.
            runtime.autoPhysicsInitialization = false;
            status.textContent = de ? 'Bühne wird gerendert…' : 'Rendering the stage…';
            await withTimeout(scene.whenReadyAsync(), 30000, 'Scene readiness');
            let motionRequest = 0;
            const chooseMotion = async id => {
                const choice = motions.find(item => item.id === id);
                if (!choice || choice.id === selectedMotion.id) return;
                const request = ++motionRequest;
                if (motionSelect) motionSelect.disabled = true; status.textContent = text.loading;
                try {
                    const animation = await withTimeout(new M.VmdLoader(scene).loadAsync(`archive-${choice.id}`, choice.url), 20000, 'Animation load');
                    if (request !== motionRequest || disposed) return;
                    model.setRuntimeAnimation(model.createRuntimeAnimation(animation));
                    selectedMotion = choice;
                    runtime.setManualAnimationDuration(choice.frames);
                    await runtime.seekAnimation(0, true);
                    runtime.autoPhysicsInitialization = false;
                    if (active) await runtime.playAnimation();
                    status.textContent = text.model;
                } catch (error) {
                    console.debug('Character inspection motion unavailable:', error);
                    if (motionSelect) motionSelect.value = selectedMotion.id; status.textContent = text.model;
                } finally { if (request === motionRequest && motionSelect) motionSelect.disabled = false; }
            };
            motionSelect?.addEventListener('change', () => { void chooseMotion(motionSelect.value); });
            lightingSelect?.addEventListener('change', applyLighting);
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
            camera.wheelPrecision = 26;
            const accent = getComputedStyle(hero || stage).getPropertyValue('--hero-accent').trim();
            const color = B.Color3.FromHexString(/^#[0-9a-f]{6}$/i.test(accent)?accent:'#5d7f94');
            const material = new B.StandardMaterial('elemental-podium',scene);material.diffuseColor=color.scale(.2);material.emissiveColor=color.scale(.18);material.alpha=.7;
            const platform = B.MeshBuilder.CreateCylinder('stage',{diameter:height*.65,height:height*.015,tessellation:64},scene);
            platform.position.set(center.x,min.y-height*.02,center.z);platform.material=material;platform.isPickable=false;
            let dendroEnvironment = null, dendroEnvironmentLoading = null;
            const applyBackdrop = () => {
                const dendro = backgroundSelect?.value === 'dendro';
                stage.dataset.backdrop = backgroundSelect?.value || 'element';
                platform.setEnabled(!dendro);
                if (dendro && !dendroEnvironment && !dendroEnvironmentLoading) {
                    status.textContent = de ? '3D-Dendro-Umgebung wird geladen…' : 'Loading 3D Dendro environment…';
                    dendroEnvironmentLoading = loadDendroForest(B, scene, center, min.y-height*.025, height).then(forest => {
                        const motes = createDendroEnvironment(B, scene, center, min.y-height*.025, height);
                        dendroEnvironment = {setEnabled(enabled) {forest.setEnabled(enabled); motes.setEnabled(enabled);},
                            tick(seconds, animate) {motes.tick(seconds, animate);}, dispose() {forest.dispose();}};
                        dendroEnvironment.setEnabled(backgroundSelect?.value === 'dendro');
                        status.textContent = text.model;
                    }).catch(error => {
                        stage.dataset.environmentLoaded = 'false';
                        console.debug('3D Dendro stage using panoramic fallback:', error);
                        status.textContent = text.model;
                    }).finally(() => {dendroEnvironmentLoading = null;});
                }
                dendroEnvironment?.setEnabled(dendro);
            };
            backgroundSelect?.addEventListener('change', applyBackdrop);
            applyBackdrop();
            let active = false, seeking = false;
            const stopCameraSettle = () => {canvas.dataset.dragging = 'true';};
            const releaseCameraDrag = () => {canvas.dataset.dragging = 'false';};
            canvas.setAttribute('aria-label', text.cameraLabel);
            canvas.tabIndex = 0;
            canvas.addEventListener('pointerdown', stopCameraSettle);
            canvas.addEventListener('pointerup', releaseCameraDrag);
            canvas.addEventListener('pointercancel', releaseCameraDrag);
            canvas.addEventListener('wheel', stopCameraSettle, {passive: true});
            const render = () => {
                dendroEnvironment?.tick(engine.getDeltaTime()/1000, !settings.paused && !motion.matches);
                scene.render();
                if (!seeking && runtime.currentTime >= selectedMotion.frames/30-.025) {
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
                finishEntrance() {},
                resize() {engine.resize();if(!active)scene.render();},
                dispose() {
                    engine.stopRenderLoop(render);runtime.pauseAnimation();
                    if (runtimeRegistered) runtime.unregister(scene);
                    if (physicsRuntime && physicsRegistered) physicsRuntime.unregister(scene);
                    scene.dispose();engine.dispose();
                },
            };
            presentation='model';stage.dataset.presentation=presentation;canvas.hidden=false;canvas.style.opacity='';engine.resize();scene.render();
            delete stage.dataset.modelFailed;
            status.textContent = text.model;sync();
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
    openButton?.addEventListener('click', () => {
        if (inspector && !inspector.open) inspector.showModal();
        settings.artwork = false; persist(); visible = true;
        sync();
        if (presentation !== 'model') void showModel();
    });
    closeButton?.addEventListener('click', () => inspector.close());
    inspector?.addEventListener('click', event => { if (event.target === inspector) inspector.close(); });
    inspector?.addEventListener('close', () => { visible = false; sync(); });
    pauseButton.addEventListener('click',()=>{settings.paused=!settings.paused;persist();sync();});
    const observer = new IntersectionObserver(entries=>{
        visible=inspector?.open ? true : entries[0].isIntersecting;sync();
    },{threshold:.05});observer.observe(stage);
    const resize = new ResizeObserver(()=>viewer?.resize());resize.observe(stage);
    document.addEventListener('visibilitychange',sync);motion.addEventListener('change',sync);
    window.addEventListener('pagehide',event=>{if(event.persisted)viewer?.setRunning(false);else{disposed=true;viewer?.dispose();observer.disconnect();resize.disconnect();}});
    window.addEventListener('pageshow',sync);
    function prepare() {
        const start=()=>getDescriptor().catch(()=>showArtwork());
        if('requestIdleCallback' in window)requestIdleCallback(start,{timeout:2000});else setTimeout(start,300);
    }
    if(document.readyState==='complete')prepare();else window.addEventListener('load',prepare,{once:true});
    sync();
})();
