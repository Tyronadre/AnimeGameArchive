(() => {
    const hero = document.querySelector(".weapon-detail-hero");
    const art = hero?.querySelector(".weapon-hero-art");
    const image = art?.querySelector(".weapon-display-image");
    const effectLayer = art?.querySelector(".weapon-vfx");
    const imageToggle = art?.querySelector(".weapon-image-toggle");

    if (!hero || !art || !effectLayer) return;

    const weaponKind = normalizeWeaponType(hero.dataset.weaponType);
    hero.classList.add(`weapon-kind-${weaponKind}`);
    buildEffects(effectLayer, weaponKind);

    if (!image) return;

    const updateArtwork = () => {
        art.classList.add("weapon-image-loaded");
        const analysis = analyzeImage(image);
        if (!analysis) return;

        normalizeImage(image, analysis.bounds);

        const palette = analysis.palette;
        if (!palette) return;

        const primary = palette[0];
        const secondary = palette[1];
        const deep = withLightness(primary, 0.29);
        const bright = withLightness(secondary, Math.max(0.58, rgbToHsl(secondary).l));

        hero.style.setProperty("--weapon-primary", toHex(primary));
        hero.style.setProperty("--weapon-secondary", toHex(bright));
        hero.style.setProperty("--weapon-deep", toHex(deep));
        hero.style.setProperty("--weapon-primary-rgb", toRgbChannels(primary));
        hero.style.setProperty("--weapon-secondary-rgb", toRgbChannels(bright));
        hero.style.setProperty("--hero-accent", toHex(primary));
        hero.style.setProperty("--hero-accent-deep", toHex(deep));
        hero.style.setProperty("--hero-glow", `rgba(${toRgbChannels(primary)}, 0.46)`);
        hero.classList.add("weapon-palette-ready");
    };

    image.addEventListener("error", () => {
        if (!image.classList.contains("is-switching")) image.remove();
    });

    if (imageToggle) {
        initializeImageToggle(imageToggle, image, updateArtwork);
    }

    if (image.complete && image.naturalWidth > 0) {
        requestAnimationFrame(updateArtwork);
    } else {
        image.addEventListener("load", updateArtwork, { once: true });
    }

    function normalizeWeaponType(value = "") {
        const normalized = value.toLowerCase().replace(/[^a-z]/g, "");
        if (normalized.includes("claymore")) return "claymore";
        if (normalized.includes("polearm") || normalized.includes("spear")) return "polearm";
        if (normalized.includes("catalyst")) return "catalyst";
        if (normalized.includes("bow")) return "bow";
        if (normalized.includes("sword")) return "sword";
        return "unknown";
    }

    function initializeImageToggle(toggle, sourceImage, imageLoaded) {
        const formLabel = toggle.querySelector("[data-weapon-form-label]");
        toggle.addEventListener("click", () => {
            const currentlyAscended = toggle.getAttribute("aria-pressed") === "true";
            const showAscended = !currentlyAscended;
            const nextUrl = showAscended
                ? toggle.dataset.ascendedUrl
                : toggle.dataset.originalUrl;
            if (!nextUrl || toggle.disabled) return;

            toggle.disabled = true;
            const preloader = document.createElement("img");
            preloader.decoding = "async";
            preloader.addEventListener("error", () => {
                toggle.disabled = false;
            }, { once: true });
            preloader.addEventListener("load", () => {
                crossfadeImage(toggle, sourceImage, nextUrl, showAscended, formLabel, imageLoaded);
            }, { once: true });
            preloader.src = nextUrl;
        });
    }

    function crossfadeImage(toggle, sourceImage, nextUrl, showAscended, formLabel, imageLoaded) {
        const previousUrl = sourceImage.currentSrc || sourceImage.src;
        const outgoingImage = sourceImage.cloneNode(false);
        outgoingImage.className = "weapon-display-image weapon-image-outgoing";
        outgoingImage.removeAttribute("alt");
        outgoingImage.setAttribute("aria-hidden", "true");
        sourceImage.after(outgoingImage);
        sourceImage.classList.add("is-switching", "weapon-image-incoming");

        const cleanupListeners = () => {
            sourceImage.removeEventListener("load", handleLoad);
            sourceImage.removeEventListener("error", handleError);
        };
        const handleLoad = () => {
            cleanupListeners();
            sourceImage.classList.remove("is-switching");
            imageLoaded();
            toggle.setAttribute("aria-pressed", String(showAscended));
            if (formLabel) {
                formLabel.textContent = showAscended
                    ? toggle.dataset.ascendedLabel
                    : toggle.dataset.originalLabel;
            }

            requestAnimationFrame(() => requestAnimationFrame(() => {
                sourceImage.classList.remove("weapon-image-incoming");
                outgoingImage.classList.add("is-leaving");
                setTimeout(() => {
                    outgoingImage.remove();
                    toggle.disabled = false;
                }, IMAGE_CROSSFADE_MS);
            }));
        };
        const handleError = () => {
            cleanupListeners();
            sourceImage.classList.remove("is-switching", "weapon-image-incoming");
            sourceImage.src = previousUrl;
            outgoingImage.remove();
            toggle.disabled = false;
        };

        sourceImage.addEventListener("load", handleLoad);
        sourceImage.addEventListener("error", handleError);
        sourceImage.src = nextUrl;
    }

    function buildEffects(layer, kind) {
        const core = document.createElement("span");
        core.className = "weapon-vfx-core";
        core.textContent = {
            sword: "✦",
            claymore: "◆",
            bow: "➶",
            polearm: "✧",
            catalyst: "⌬",
            unknown: "✦",
        }[kind];
        layer.append(core);

        for (let index = 0; index < 2; index += 1) {
            const ring = document.createElement("span");
            ring.className = `weapon-vfx-ring ring-${index + 1}`;
            layer.append(ring);
        }

        for (let index = 0; index < 8; index += 1) {
            const trail = document.createElement("span");
            trail.className = `weapon-vfx-trail trail-${index + 1}`;
            trail.style.setProperty("--trail-index", index);
            trail.style.setProperty("--trail-angle", `${index * 45 - 72}deg`);
            trail.style.setProperty("--trail-y", `${18 + index * 8}%`);
            trail.style.setProperty("--trail-delay", `${index * -0.43}s`);
            layer.append(trail);
        }

        for (let index = 0; index < 14; index += 1) {
            const angle = (index / 14) * Math.PI * 2;
            const radius = 31 + (index % 4) * 9;
            const mote = document.createElement("span");
            mote.className = `weapon-vfx-mote mote-${index + 1}`;
            mote.style.setProperty("--mote-x", `${Math.cos(angle) * radius}%`);
            mote.style.setProperty("--mote-y", `${Math.sin(angle) * radius}%`);
            mote.style.setProperty("--mote-delay", `${index * -0.31}s`);
            mote.style.setProperty("--mote-duration", `${3.8 + (index % 5) * 0.7}s`);
            layer.append(mote);
        }
    }

    function analyzeImage(sourceImage) {
        const canvas = document.createElement("canvas");
        const context = canvas.getContext("2d", { willReadFrequently: true });
        if (!context) return null;

        canvas.width = 112;
        canvas.height = 112;

        try {
            const fitScale = Math.min(
                canvas.width / sourceImage.naturalWidth,
                canvas.height / sourceImage.naturalHeight,
            );
            const drawWidth = sourceImage.naturalWidth * fitScale;
            const drawHeight = sourceImage.naturalHeight * fitScale;
            const drawX = (canvas.width - drawWidth) / 2;
            const drawY = (canvas.height - drawHeight) / 2;
            context.drawImage(sourceImage, drawX, drawY, drawWidth, drawHeight);
            const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
            const buckets = new Map();

            for (let index = 0; index < pixels.length; index += 4) {
                const alpha = pixels[index + 3] / 255;
                if (alpha < 0.36) continue;

                const color = { r: pixels[index], g: pixels[index + 1], b: pixels[index + 2] };
                const hsl = rgbToHsl(color);
                if (hsl.l > 0.96 || hsl.l < 0.025) continue;
                if (hsl.s < 0.07 && hsl.l > 0.78) continue;

                const key = [color.r, color.g, color.b]
                    .map(channel => Math.min(255, Math.round(channel / 32) * 32))
                    .join("-");
                const weight = alpha
                    * (0.08 + hsl.s * hsl.s * 4.2)
                    * (0.65 + (1 - Math.abs(0.52 - hsl.l)));
                const bucket = buckets.get(key) || { r: 0, g: 0, b: 0, weight: 0, count: 0 };
                bucket.r += color.r * weight;
                bucket.g += color.g * weight;
                bucket.b += color.b * weight;
                bucket.weight += weight;
                bucket.count += 1;
                buckets.set(key, bucket);
            }

            const colors = [...buckets.values()]
                .map(bucket => {
                    const color = {
                        r: Math.round(bucket.r / bucket.weight),
                        g: Math.round(bucket.g / bucket.weight),
                        b: Math.round(bucket.b / bucket.weight),
                    };
                    const saturation = rgbToHsl(color).s;
                    return {
                        ...color,
                        score: bucket.weight * (0.2 + saturation * saturation * 3.2),
                    };
                })
                .sort((left, right) => right.score - left.score);
            const palette = ifPresent(colors[0], primary => [
                primary,
                colors.find(color => colorDistance(primary, color) > 92)
                    || rotateHue(primary, 42),
            ]);
            return {
                palette,
                bounds: findVisibleBounds(pixels, canvas.width, canvas.height),
            };
        } catch (_) {
            return null;
        }
    }

    function findVisibleBounds(pixels, width, height) {
        const xMass = new Float64Array(width);
        const yMass = new Float64Array(height);
        const background = cornerBackground(pixels, width, height);
        let totalMass = 0;

        for (let y = 0; y < height; y += 1) {
            for (let x = 0; x < width; x += 1) {
                const index = (y * width + x) * 4;
                const alpha = pixels[index + 3] / 255;
                let weight = Math.max(0, alpha - 0.08);

                if (background.opaque) {
                    const distance = colorDistance(
                        { r: pixels[index], g: pixels[index + 1], b: pixels[index + 2] },
                        background,
                    ) / 441.68;
                    weight *= Math.max(0, (distance - 0.035) / 0.2);
                }
                if (weight <= 0) continue;

                xMass[x] += weight;
                yMass[y] += weight;
                totalMass += weight;
            }
        }

        if (totalMass < width * height * 0.008) {
            return { left: 0, top: 0, right: 1, bottom: 1 };
        }

        const lowerMass = totalMass * 0.018;
        const upperMass = totalMass * 0.982;
        const left = weightedBoundary(xMass, lowerMass);
        const right = weightedBoundary(xMass, upperMass);
        const top = weightedBoundary(yMass, lowerMass);
        const bottom = weightedBoundary(yMass, upperMass);
        const padding = 0.018;
        return {
            left: Math.max(0, left / width - padding),
            top: Math.max(0, top / height - padding),
            right: Math.min(1, (right + 1) / width + padding),
            bottom: Math.min(1, (bottom + 1) / height + padding),
        };
    }

    function cornerBackground(pixels, width, height) {
        const samplePoints = [
            [0, 0],
            [width - 1, 0],
            [0, height - 1],
            [width - 1, height - 1],
        ];
        const result = samplePoints.reduce((color, [x, y]) => {
            const index = (y * width + x) * 4;
            color.r += pixels[index];
            color.g += pixels[index + 1];
            color.b += pixels[index + 2];
            color.alpha += pixels[index + 3] / 255;
            return color;
        }, { r: 0, g: 0, b: 0, alpha: 0 });
        return {
            r: result.r / samplePoints.length,
            g: result.g / samplePoints.length,
            b: result.b / samplePoints.length,
            opaque: result.alpha / samplePoints.length > 0.92,
        };
    }

    function weightedBoundary(values, target) {
        let accumulated = 0;
        for (let index = 0; index < values.length; index += 1) {
            accumulated += values[index];
            if (accumulated >= target) return index;
        }
        return values.length - 1;
    }

    function normalizeImage(sourceImage, bounds) {
        const contentWidth = bounds.right - bounds.left;
        const contentHeight = bounds.bottom - bounds.top;
        const contentSpan = Math.max(contentWidth, contentHeight);
        if (contentSpan <= 0.08) return;

        const scale = clamp(0.84 / contentSpan, 0.76, 2.2);
        const centerX = (bounds.left + bounds.right) / 2;
        const centerY = (bounds.top + bounds.bottom) / 2;
        const shiftX = clamp((0.5 - centerX) * scale * 100, -34, 34);
        const shiftY = clamp((0.5 - centerY) * scale * 100, -34, 34);

        sourceImage.style.setProperty("--weapon-art-scale", scale.toFixed(3));
        sourceImage.style.setProperty("--weapon-art-shift-x", `${shiftX.toFixed(2)}%`);
        sourceImage.style.setProperty("--weapon-art-shift-y", `${shiftY.toFixed(2)}%`);
        sourceImage.dataset.visibleBounds = [
            bounds.left,
            bounds.top,
            bounds.right,
            bounds.bottom,
        ].map(value => value.toFixed(3)).join(",");
        sourceImage.dataset.normalizedScale = scale.toFixed(3);
        hero.classList.add("weapon-size-normalized");
    }

    function clamp(value, minimum, maximum) {
        return Math.min(maximum, Math.max(minimum, value));
    }

    function ifPresent(value, transform) {
        return value == null ? null : transform(value);
    }

    function colorDistance(left, right) {
        return Math.hypot(left.r - right.r, left.g - right.g, left.b - right.b);
    }

    function toRgbChannels(color) {
        return `${color.r}, ${color.g}, ${color.b}`;
    }

    function toHex(color) {
        return `#${[color.r, color.g, color.b]
            .map(channel => Math.round(channel).toString(16).padStart(2, "0"))
            .join("")}`;
    }

    function rgbToHsl({ r, g, b }) {
        const red = r / 255;
        const green = g / 255;
        const blue = b / 255;
        const max = Math.max(red, green, blue);
        const min = Math.min(red, green, blue);
        const lightness = (max + min) / 2;
        const delta = max - min;
        if (delta === 0) return { h: 0, s: 0, l: lightness };

        const saturation = delta / (1 - Math.abs(2 * lightness - 1));
        let hue;
        if (max === red) hue = 60 * (((green - blue) / delta) % 6);
        else if (max === green) hue = 60 * ((blue - red) / delta + 2);
        else hue = 60 * ((red - green) / delta + 4);
        return { h: hue < 0 ? hue + 360 : hue, s: saturation, l: lightness };
    }

    function hslToRgb({ h, s, l }) {
        const chroma = (1 - Math.abs(2 * l - 1)) * s;
        const section = h / 60;
        const intermediate = chroma * (1 - Math.abs((section % 2) - 1));
        let channels;
        if (section < 1) channels = [chroma, intermediate, 0];
        else if (section < 2) channels = [intermediate, chroma, 0];
        else if (section < 3) channels = [0, chroma, intermediate];
        else if (section < 4) channels = [0, intermediate, chroma];
        else if (section < 5) channels = [intermediate, 0, chroma];
        else channels = [chroma, 0, intermediate];
        const match = l - chroma / 2;
        return {
            r: Math.round((channels[0] + match) * 255),
            g: Math.round((channels[1] + match) * 255),
            b: Math.round((channels[2] + match) * 255),
        };
    }

    function withLightness(color, lightness) {
        return hslToRgb({ ...rgbToHsl(color), l: lightness });
    }

    function rotateHue(color, degrees) {
        const hsl = rgbToHsl(color);
        return hslToRgb({ ...hsl, h: (hsl.h + degrees) % 360 });
    }

    const IMAGE_CROSSFADE_MS = 700;
})();
