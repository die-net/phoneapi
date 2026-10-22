// Turns a page into blocks for browser_read and browser_find. Runs in an isolated world after
// Readability.js and page-pending.js, as the body of a function given `options` ({scope,
// selector}). Rendering, offsets, and searching happen in Kotlin, so this only walks the DOM.

const MAX_CHARS = 2000000;
const SKIP = new Set([
  "SCRIPT", "STYLE", "NOSCRIPT", "TEMPLATE", "SVG", "CANVAS", "IFRAME", "OBJECT", "EMBED",
  "BUTTON", "INPUT", "SELECT", "TEXTAREA", "IMG", "PICTURE", "VIDEO", "AUDIO", "MATH", "HEAD",
  "DIALOG",
]);
const BLOCK = new Set([
  "ADDRESS", "ARTICLE", "ASIDE", "BODY", "CAPTION", "CENTER", "DD", "DETAILS", "DIV", "DL", "DT",
  "FIELDSET", "FIGCAPTION", "FIGURE", "FOOTER", "FORM", "HEADER", "HGROUP", "LEGEND", "MAIN",
  "MENU", "NAV", "P", "SECTION", "SUMMARY", "TBODY", "TD", "TFOOT", "TH", "THEAD", "TR", "BR",
]);
const STYLE = { STRONG: "b", B: "b", EM: "i", I: "i", CODE: "code", KBD: "code", SAMP: "code" };

function walk(root, live) {
  const blocks = [];
  let runs = [];
  let kind = { k: "p" };
  let chars = 0;
  let tables = 0;

  function push(text, href, style) {
    if (!text || chars > MAX_CHARS) return;
    chars += text.length;
    const last = runs[runs.length - 1];
    if (last && last.h === href && last.s === style) {
      last.t += text;
    } else {
      const run = { t: text };
      if (href) run.h = href;
      if (style) run.s = style;
      runs.push(run);
    }
  }

  function flush() {
    if (runs.some((r) => r.t.trim())) blocks.push(Object.assign({}, kind, { r: runs }));
    runs = [];
  }

  function hidden(el) {
    if (el.getAttribute("aria-hidden") === "true" || el.hidden) return true;
    if (!live || !el.checkVisibility) return false;
    return !el.checkVisibility({ checkVisibilityCSS: true, visibilityProperty: true });
  }

  function cellText(cell) {
    return (live ? cell.innerText : cell.textContent).replace(/\s+/g, " ").trim();
  }

  function table(el, ctx) {
    const rows = Array.from(el.rows || []);
    if (!rows.some((row) => row.cells.length > 1)) return false;
    flush();
    const id = tables++;
    for (const row of rows) {
      const cells = Array.from(row.cells);
      const th = cells.length > 0 && cells.every((c) => c.tagName === "TH");
      blocks.push({ k: "tr", t: id, c: cells.map(cellText), th: th, q: ctx.q || undefined });
      chars += cells.reduce((n, c) => n + c.textContent.length, 0);
    }
    return true;
  }

  function within(next, fn) {
    flush();
    const saved = kind;
    kind = next;
    fn();
    flush();
    kind = saved;
  }

  function visit(node, ctx) {
    if (chars > MAX_CHARS) return;
    if (node.nodeType === 3) {
      push(node.data.replace(/\s+/g, " "), ctx.h, ctx.s);
      return;
    }
    if (node.nodeType !== 1) return;
    const tag = node.tagName.toUpperCase();
    if (SKIP.has(tag) || hidden(node)) return;
    const children = () => node.childNodes.forEach((child) => visit(child, ctx));
    const quoted = (k) => (ctx.q ? Object.assign(k, { q: 1 }) : k);
    if (/^H[1-6]$/.test(tag)) {
      within(quoted({ k: "h", l: Number(tag[1]) }), children);
    } else if (tag === "UL" || tag === "OL") {
      flush();
      const list = { d: (ctx.list ? ctx.list.d : 0) + 1, n: tag === "OL" ? 1 : 0 };
      const inner = Object.assign({}, ctx, { list: list });
      node.childNodes.forEach((child) => visit(child, inner));
      flush();
    } else if (tag === "LI") {
      const list = ctx.list || { d: 1, n: 0 };
      const item = quoted({ k: "li", d: list.d });
      if (list.n) item.o = list.n++;
      within(item, children);
    } else if (tag === "BLOCKQUOTE") {
      flush();
      const inner = Object.assign({}, ctx, { q: 1 });
      within({ k: "p", q: 1 }, () => node.childNodes.forEach((child) => visit(child, inner)));
    } else if (tag === "PRE") {
      flush();
      const text = live ? node.innerText : node.textContent;
      if (text.trim()) blocks.push(quoted({ k: "pre", x: text.replace(/\n+$/, "") }));
      chars += text.length;
    } else if (tag === "HR") {
      flush();
      blocks.push({ k: "hr" });
    } else if (tag === "TABLE") {
      if (!table(node, ctx)) within(quoted({ k: "p" }), children);
    } else if (tag === "A") {
      const href = /^(https?|mailto):/i.test(node.href || "") ? node.href : undefined;
      const inner = Object.assign({}, ctx, { h: href || ctx.h });
      node.childNodes.forEach((child) => visit(child, inner));
    } else if (STYLE[tag]) {
      const inner = Object.assign({}, ctx, { s: ctx.s || STYLE[tag] });
      node.childNodes.forEach((child) => visit(child, inner));
    } else if (BLOCK.has(tag)) {
      flush();
      children();
      flush();
    } else {
      children();
    }
  }

  visit(root, {});
  flush();
  return { blocks: blocks, clipped: chars > MAX_CHARS };
}

function published() {
  const metas = [
    'meta[property="article:published_time"]',
    'meta[name="article:published_time"]',
    'meta[itemprop="datePublished"]',
    'meta[name="date"]',
    'meta[name="pubdate"]',
    'meta[name="publish-date"]',
    'meta[name="dc.date"]',
    'meta[name="DC.date.issued"]',
    'meta[property="og:published_time"]',
  ];
  for (const selector of metas) {
    const value = document.querySelector(selector)?.getAttribute("content");
    if (value && value.trim()) return value.trim();
  }
  for (const script of document.querySelectorAll('script[type="application/ld+json"]')) {
    try {
      const found = datePublished(JSON.parse(script.textContent));
      if (found) return found;
    } catch (e) {
      // Malformed JSON-LD is common; skip it.
    }
  }
  return document.querySelector("time[datetime]")?.getAttribute("datetime") || null;
}

function datePublished(value) {
  if (!value || typeof value !== "object") return null;
  if (Array.isArray(value)) {
    for (const item of value) {
      const found = datePublished(item);
      if (found) return found;
    }
    return null;
  }
  if (typeof value.datePublished === "string") return value.datePublished;
  return datePublished(value["@graph"]);
}

function blocked(status, text) {
  const captcha =
    'iframe[src*="recaptcha"], iframe[src*="hcaptcha"], iframe[src*="challenges.cloudflare.com"],' +
    ' .g-recaptcha, .h-captcha, .cf-turnstile, #challenge-form, #challenge-running';
  if (document.querySelector(captcha)) return "captcha";
  const challenge =
    /just a moment|attention required|access denied|are you a robot|verify you are (a )?human|unusual traffic|request (was )?blocked|security check/i;
  if (text.length < 3000 && challenge.test(document.title + " " + text)) return "challenge page";
  if (status === 401 || status === 403 || status === 429) return "http " + status;
  return null;
}

const nav = performance.getEntriesByType("navigation")[0];
const status = (nav && nav.responseStatus) || 0;
const bodyText = document.body ? document.body.innerText : "";
let extracted = options.scope;
let walked = null;
let articlePublished = null;
if (options.selector) {
  const el = document.querySelector(options.selector);
  if (!el) return { missing: true };
  walked = walk(el, true);
  extracted = "selector";
} else if (options.scope === "article") {
  try {
    const article = new Readability(document.cloneNode(true), { serializer: (el) => el }).parse();
    if (article && article.content && article.textContent.trim()) {
      walked = walk(article.content, false);
      articlePublished = article.publishedTime || null;
    }
  } catch (e) {
    // Readability can fail on unusual documents; fall back to the whole page.
  }
  if (!walked || walked.blocks.length === 0) {
    walked = null;
    extracted = "page";
  }
}
if (!walked && document.body) walked = walk(document.body, true);
if (!walked) walked = { blocks: [], clipped: false };

return {
  title: document.title || null,
  url: location.href,
  readyState: document.readyState,
  visibility: document.visibilityState,
  pending: pagePending(),
  status: status,
  published: published() || articlePublished,
  blocked: blocked(status, bodyText),
  pageChars: bodyText.trim().length,
  links: document.links.length,
  tables: document.querySelectorAll("table").length,
  forms: document.forms.length,
  extracted: extracted,
  clipped: walked.clipped,
  blocks: walked.blocks,
};
