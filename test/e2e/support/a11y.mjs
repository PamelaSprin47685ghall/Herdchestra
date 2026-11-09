// Accessibility probes for the E2E suite: contrast, clipping, hot zones,
// focus order, the accessibility tree and IME input.
//
// Every browser-side probe is a self-contained function passed to
// `page.evaluate`; it cannot close over this module.  The Node-side helpers
// only turn probe results into readable assertions.
import { assert } from './ui.mjs';

// ---------------------------------------------------------------------------
// Node-side colour maths.  The same formulas run in the page probe below;
// keeping a Node copy lets a spec explain a ratio without another round trip.

export function parseCssColor(value) {
  if (typeof value !== 'string') return null;
  const match = value.trim().match(/^rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)(?:[,\s/]+([\d.]+))?\s*\)$/i);
  if (!match) return null;
  return {
    r: Number(match[1]),
    g: Number(match[2]),
    b: Number(match[3]),
    a: match[4] === undefined ? 1 : Number(match[4]),
  };
}

export function relativeLuminance(color) {
  const channel = (value) => {
    const c = Math.max(0, Math.min(255, value)) / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * channel(color.r) + 0.7152 * channel(color.g) + 0.0722 * channel(color.b);
}

export function contrastRatio(foreground, background) {
  const a = relativeLuminance(foreground);
  const b = relativeLuminance(background);
  const lighter = Math.max(a, b);
  const darker = Math.min(a, b);
  return (lighter + 0.05) / (darker + 0.05);
}

export function requiredContrast(fontSizePx, fontWeight) {
  const large = fontSizePx >= 24
    || (fontSizePx >= 18.66 && Number(fontWeight) >= 700);
  return large ? 3 : 4.5;
}

// ---------------------------------------------------------------------------
// Contrast

// specs: [{name, selector, kind: 'text'|'graphic'}]
// Returns one row per spec: {name, found, skipped?, reason?, fg, bg, ratio,
// fontSize, fontWeight, required, ok}.  Transparent, gradient and image
// backgrounds are reported as skipped with a reason instead of a guess.
export async function collectContrast(page, specs) {
  return page.evaluate((probes) => {
    const parse = (value) => {
      if (typeof value !== 'string') return null;
      const match = value.trim().match(/^rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)(?:[,\s/]+([\d.]+))?\s*\)$/i);
      if (!match) return null;
      return {
        r: Number(match[1]),
        g: Number(match[2]),
        b: Number(match[3]),
        a: match[4] === undefined ? 1 : Number(match[4]),
      };
    };
    const channel = (value) => {
      const c = Math.max(0, Math.min(255, value)) / 255;
      return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
    };
    const luminance = (color) =>
      0.2126 * channel(color.r) + 0.7152 * channel(color.g) + 0.0722 * channel(color.b);
    const ratio = (foreground, background) => {
      const a = luminance(foreground);
      const b = luminance(background);
      return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    };
    const composite = (top, bottom) => {
      const alpha = top.a + bottom.a * (1 - top.a);
      if (alpha <= 0) return { r: 255, g: 255, b: 255, a: 1 };
      return {
        r: (top.r * top.a + bottom.r * bottom.a * (1 - top.a)) / alpha,
        g: (top.g * top.a + bottom.g * bottom.a * (1 - top.a)) / alpha,
        b: (top.b * top.a + bottom.b * bottom.a * (1 - top.a)) / alpha,
        a: alpha,
      };
    };
    const describe = (color) => `rgba(${Math.round(color.r)}, ${Math.round(color.g)}, ${Math.round(color.b)}, ${color.a})`;

    // Collect the opaque background stack.  Any gradient, image or filter in
    // the ancestry makes the computed colour an unreliable answer.
    const backgroundOf = (element, { skipSelf = false } = {}) => {
      const layers = [];
      let node = skipSelf ? element.parentElement : element;
      let guard = 0;
      while (node && guard < 40) {
        guard += 1;
        const style = getComputedStyle(node);
        if (style.backgroundImage && style.backgroundImage !== 'none') {
          return { skipped: 'background-image' };
        }
        if (style.backdropFilter && style.backdropFilter !== 'none') {
          return { skipped: 'backdrop-filter' };
        }
        const color = parse(style.backgroundColor);
        if (color && color.a > 0.01) {
          layers.push(color);
          if (color.a >= 0.999) break;
        }
        node = node.parentElement;
      }
      if (layers.length === 0) layers.push({ r: 255, g: 255, b: 255, a: 1 });
      let base = { r: 255, g: 255, b: 255, a: 1 };
      for (let index = layers.length - 1; index >= 0; index -= 1) {
        base = composite(layers[index], base);
      }
      return { color: base };
    };

    return probes.map((probe) => {
      const element = document.querySelector(probe.selector);
      if (!element) {
        return { name: probe.name, selector: probe.selector, kind: probe.kind, found: false, ok: false, skipped: 'missing-element' };
      }
      const style = getComputedStyle(element);
      const rect = element.getBoundingClientRect();
      if (rect.width <= 0 || rect.height <= 0 || style.visibility === 'hidden' || style.display === 'none') {
        return { name: probe.name, selector: probe.selector, kind: probe.kind, found: true, ok: false, skipped: 'not-visible' };
      }
      const fg = parse(style.color);
      if (!fg) {
        return { name: probe.name, selector: probe.selector, kind: probe.kind, found: true, ok: false, skipped: 'unsupported-color', fg: style.color };
      }
      const fontSize = parseFloat(style.fontSize) || 16;
      const fontWeight = Number(style.fontWeight) || 400;
      const large = fontSize >= 24 || (fontSize >= 18.66 && fontWeight >= 700);
      const required = probe.kind === 'graphic' ? 3 : (large ? 3 : 4.5);

      let bgInfo;
      if (probe.kind === 'graphic') {
        if (style.backgroundImage && style.backgroundImage !== 'none') {
          return { name: probe.name, selector: probe.selector, kind: probe.kind, found: true, ok: false, skipped: 'background-image' };
        }
        const own = parse(style.backgroundColor);
        if (!own || own.a <= 0.01) {
          return { name: probe.name, selector: probe.selector, kind: probe.kind, found: true, ok: false, skipped: 'transparent-graphic', fg: style.color };
        }
        bgInfo = backgroundOf(element, { skipSelf: true });
        if (bgInfo.skipped) {
          return { name: probe.name, selector: probe.selector, kind: probe.kind, found: true, ok: false, skipped: bgInfo.skipped };
        }
        const measured = ratio(own, bgInfo.color);
        return {
          name: probe.name,
          selector: probe.selector,
          kind: probe.kind,
          found: true,
          fg: describe(own),
          bg: describe(bgInfo.color),
          ratio: Number(measured.toFixed(2)),
          required,
          ok: measured >= required,
        };
      }

      bgInfo = backgroundOf(element);
      if (bgInfo.skipped) {
        return { name: probe.name, selector: probe.selector, kind: probe.kind, found: true, ok: false, skipped: bgInfo.skipped, fg: style.color };
      }
      const fgOnBg = composite(fg, bgInfo.color);
      const measured = ratio(fgOnBg, bgInfo.color);
      return {
        name: probe.name,
        selector: probe.selector,
        kind: probe.kind,
        found: true,
        fg: describe(fg),
        bg: describe(bgInfo.color),
        ratio: Number(measured.toFixed(2)),
        fontSize,
        fontWeight,
        required,
        ok: measured >= required,
      };
    });
  }, specs);
}

// ---------------------------------------------------------------------------
// Target geometry: clipping and viewport containment

// specs: [{name, selector}]
export async function measureTargets(page, specs) {
  return page.evaluate((probes) => {
    const viewport = { width: window.innerWidth, height: window.innerHeight };
    return probes.map((probe) => {
      const element = document.querySelector(probe.selector);
      if (!element) {
        return { name: probe.name, selector: probe.selector, found: false, visible: false, ok: true, skipped: 'missing-element', viewport };
      }
      const style = getComputedStyle(element);
      const rect = element.getBoundingClientRect();
      const visible = style.display !== 'none'
        && style.visibility !== 'hidden'
        && rect.width > 0
        && rect.height > 0;
      if (!visible) {
        return {
          name: probe.name,
          selector: probe.selector,
          found: true,
          visible: false,
          ok: true,
          skipped: 'not-visible',
          display: style.display,
          rect: { x: rect.x, y: rect.y, width: rect.width, height: rect.height },
          viewport,
        };
      }
      const clippedX = element.scrollWidth > element.clientWidth + 1;
      const clippedY = element.scrollHeight > element.clientHeight + 1;
      const inViewport = rect.left >= -1
        && rect.top >= -1
        && rect.right <= viewport.width + 1
        && rect.bottom <= viewport.height + 1;
      return {
        name: probe.name,
        selector: probe.selector,
        found: true,
        visible: true,
        width: Number(rect.width.toFixed(1)),
        height: Number(rect.height.toFixed(1)),
        rect: {
          x: Number(rect.x.toFixed(1)),
          y: Number(rect.y.toFixed(1)),
          width: Number(rect.width.toFixed(1)),
          height: Number(rect.height.toFixed(1)),
          right: Number(rect.right.toFixed(1)),
          bottom: Number(rect.bottom.toFixed(1)),
        },
        clippedX,
        clippedY,
        inViewport,
        ok: !clippedX && !clippedY && inViewport,
        viewport,
      };
    });
  }, specs);
}

export function targetsOk(rows, { minSize = null } = {}) {
  return rows.every((row) => {
    if (!row.visible) return true;
    if (!row.ok) return false;
    if (minSize !== null) {
      return row.width >= minSize && row.height >= minSize;
    }
    return true;
  });
}

export function targetFailures(rows, options = {}) {
  return rows.filter((row) => !targetsOk([row], options));
}

// Wait until a target's box is stable across consecutive animation frames and
// (optionally) fully inside the viewport.  A drawer's `data-open` flips before
// its 160ms transform finishes, so measuring on the attribute alone can catch
// the close button still off-screen.
export async function waitForSettledTarget(page, selector, options = {}) {
  const { timeout = 5000, requireInViewport = true } = options;
  await page.waitForFunction(({ selector, requireInViewport }) => {
    const element = document.querySelector(selector);
    if (!element) return false;
    const rect = element.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) return false;
    const state = window.__hcE2ESettleState || (window.__hcE2ESettleState = {});
    const current = {
      x: Math.round(rect.x * 10) / 10,
      y: Math.round(rect.y * 10) / 10,
      width: Math.round(rect.width * 10) / 10,
      height: Math.round(rect.height * 10) / 10,
    };
    const previous = state[selector];
    state[selector] = current;
    const stable = Boolean(previous)
      && ['x', 'y', 'width', 'height']
        .every((key) => Math.abs(previous[key] - current[key]) < 0.5);
    const inViewport = !requireInViewport
      || (rect.left >= -1 && rect.top >= -1
          && rect.right <= window.innerWidth + 1
          && rect.bottom <= window.innerHeight + 1);
    return stable && inViewport;
  }, { selector, requireInViewport }, { timeout });
}

// ---------------------------------------------------------------------------
// Focus order and focus ring

// Press Tab `limit` times and record the focused control.  A starting
// selector lets a spec measure one region without walking the whole chrome.
export async function collectFocusSequence(page, options = {}) {
  const { limit = 12, startSelector = null } = options;
  if (startSelector) {
    await page.evaluate((selector) => {
      const element = document.querySelector(selector);
      if (element) element.focus();
      else document.body.focus();
    }, startSelector);
  } else {
    // `body.focus()` alone does not move focus in Chromium because body is not
    // focusable; the temporary tabindex makes Tab start from the top of the
    // document instead of continuing inside the last message.
    await page.evaluate(() => {
      if (document.activeElement && document.activeElement.blur) {
        document.activeElement.blur();
      }
      document.body.setAttribute('tabindex', '-1');
      document.body.focus();
      document.body.removeAttribute('tabindex');
    });
  }
  const stops = [];
  for (let index = 0; index < limit; index += 1) {
    await page.evaluate(() => {
      document.querySelectorAll('[data-e2e-tab-marker]').forEach((node) => {
        node.removeAttribute('data-e2e-tab-marker');
      });
      const active = document.activeElement;
      if (active && active.setAttribute) active.setAttribute('data-e2e-tab-marker', '1');
    });
    await page.keyboard.press('Tab');
    await page.waitForFunction(() => {
      const active = document.activeElement;
      return Boolean(active && !active.hasAttribute('data-e2e-tab-marker'));
    }, { timeout: 2000 }).catch(() => {});
    await page.evaluate(() => {
      document.querySelectorAll('[data-e2e-tab-marker]').forEach((node) => {
        node.removeAttribute('data-e2e-tab-marker');
      });
    });
    const stop = await page.evaluate(() => {
      const element = document.activeElement;
      if (!element || element === document.body) {
        return { tag: 'BODY', id: null, text: '', label: '', rect: null };
      }
      const rect = element.getBoundingClientRect();
      const style = getComputedStyle(element);
      const regionNode = element.closest('.wb-topbar, .hc-composer, .hc-message-actions, .wb-nav, .hc-settings, .wb-session-list, .wb-aux');
      return {
        tag: element.tagName,
        id: element.id || null,
        text: (element.innerText || element.textContent || '').trim().slice(0, 60),
        label: element.getAttribute('aria-label') || '',
        role: element.getAttribute('role') || '',
        region: regionNode ? (regionNode.id || regionNode.className || 'region') : 'page',
        rect: {
          // Document coordinates: focusing a control can scroll the page, and
          // viewport coordinates would then look like a backwards jump.
          x: Number((rect.x + window.scrollX).toFixed(1)),
          y: Number((rect.y + window.scrollY).toFixed(1)),
          width: Number(rect.width.toFixed(1)),
          height: Number(rect.height.toFixed(1)),
        },
        outlineWidth: parseFloat(style.outlineWidth) || 0,
        outlineStyle: style.outlineStyle,
        focusVisible: element.matches(':focus-visible'),
      };
    });
    stops.push(stop);
    if (stop.tag === 'BODY') break;
  }
  return stops;
}

// Consecutive stops in the same region must not jump backwards: either a
// later row, or the same row further right.  Flex rows vertically centre
// controls of different heights, so compare row membership by centre (or
// substantial vertical overlap), not by raw top edge.
export function tabOrderMatchesVisual(stops) {
  const violations = [];
  const usable = stops.filter((stop) => stop.rect && stop.tag !== 'BODY');
  const centre = (rect) => ({
    x: rect.x + rect.width / 2,
    y: rect.y + rect.height / 2,
    top: rect.y,
    bottom: rect.y + rect.height,
    height: rect.height,
  });
  for (let index = 1; index < usable.length; index += 1) {
    const previous = usable[index - 1];
    const current = usable[index];
    if (previous.region !== current.region) continue;
    const prev = centre(previous.rect);
    const cur = centre(current.rect);
    const overlap = Math.min(prev.bottom, cur.bottom) - Math.max(prev.top, cur.top);
    const sameRow = Math.abs(cur.y - prev.y) <= 4
      || (overlap > 0 && overlap >= Math.min(prev.height, cur.height) * 0.5);
    const rowOrder = cur.y >= prev.y - 2;
    const columnOrder = cur.x >= prev.x - 2;
    if ((sameRow && !columnOrder) || (!sameRow && !rowOrder)) {
      violations.push({
        previous: { id: previous.id, label: previous.label, text: previous.text, rect: previous.rect },
        current: { id: current.id, label: current.label, text: current.text, rect: current.rect },
      });
    }
  }
  return { ok: violations.length === 0, violations };
}

// ---------------------------------------------------------------------------
// ARIA snapshot wrapper

export async function ariaSnapshot(locator) {
  assert(typeof locator.ariaSnapshot === 'function',
    'this Playwright build does not expose locator.ariaSnapshot()');
  return locator.ariaSnapshot();
}

// ---------------------------------------------------------------------------
// Accessibility tree via CDP

export async function axTree(cdp) {
  // Some Chromium builds answer getFullAXTree without enabling the domain;
  // enabling first is harmless and keeps the call deterministic.
  await cdp.send('Accessibility.enable').catch(() => {});
  return cdp.send('Accessibility.getFullAXTree');
}

export function axRoleCount(tree, role) {
  return (tree.nodes || []).filter((node) => !node.ignored && node.role?.value === role).length;
}

export function axButtonNames(tree) {
  return (tree.nodes || [])
    .filter((node) => !node.ignored && node.role?.value === 'button')
    .map((node) => (node.name && node.name.value ? String(node.name.value) : '').trim())
    .filter(Boolean);
}

export function axLandmarkNames(tree, role) {
  return (tree.nodes || [])
    .filter((node) => !node.ignored && node.role?.value === role)
    .map((node) => ({
      name: (node.name && node.name.value) || '',
      nodeId: node.nodeId,
    }));
}

// ---------------------------------------------------------------------------
// IME helpers.  Chromium's CDP composition is the closest automated stand-in
// for a real input method; the spec still lists real IME hardware as a
// manual residual.

export async function imeCompose(cdp, text) {
  await cdp.send('Input.imeSetComposition', {
    text,
    selectionStart: text.length,
    selectionEnd: text.length,
  });
}

export async function imeCommit(cdp) {
  await cdp.send('Input.imeSetComposition', {
    text: '',
    selectionStart: 0,
    selectionEnd: 0,
  });
}

export async function imePressEnter(cdp) {
  const key = {
    key: 'Enter',
    code: 'Enter',
    windowsVirtualKeyCode: 13,
    nativeVirtualKeyCode: 13,
  };
  await cdp.send('Input.dispatchKeyEvent', { type: 'keyDown', ...key });
  await cdp.send('Input.dispatchKeyEvent', { type: 'keyUp', ...key });
}

// CDP's Input.dispatchKeyEvent has no isComposing field and does not reliably
// make the keydown look like an IME Enter.  Dispatch a real DOM KeyboardEvent
// that carries isComposing=true; this tests the app's own guard, not whether
// Chromium happened to preserve composition state for a synthetic key.
export async function imePressEnterSynthetic(page) {
  return page.evaluate(() => {
    const node = document.querySelector('textarea[aria-label="消息输入"]')
      || document.activeElement;
    if (!node) throw new Error('composer textarea not found');
    const event = new KeyboardEvent('keydown', {
      key: 'Enter',
      code: 'Enter',
      bubbles: true,
      cancelable: true,
      isComposing: true,
    });
    // The KeyboardEvent constructor ignores keyCode/which; real IME Enters
    // still carry 13 on the legacy fields, so define them explicitly.
    Object.defineProperty(event, 'keyCode', { get: () => 13 });
    Object.defineProperty(event, 'which', { get: () => 13 });
    node.dispatchEvent(event);
    return { defaultPrevented: event.defaultPrevented };
  });
}
