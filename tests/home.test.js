/**
 * Home page (index.html) tests for js/status.js.
 *
 * Three layers:
 *  1. DOM contract - every id and class js/status.js looks up must exist in
 *     index.html or in a component. This is the regression guard for the bug
 *     where the home script rendered state into markup that no page contained.
 *  2. Honesty source guards - the module must gate on `verified` and must never
 *     fall back to a healthy value.
 *  3. Execution - js/status.js is actually run against a mock DOM with a
 *     stubbed fetch, and the rendered output is checked: unverified payloads
 *     stay unknown, an unreadable endpoint is never presented as fresh, and
 *     published measurements are shown exactly as published.
 */
module.exports = function (ctx) {
  var test = ctx.test;
  var assert = ctx.assert;
  var assertEqual = ctx.assertEqual;
  var fs = ctx.fs;
  var path = ctx.path;
  var BASE = ctx.BASE;

  function read(relative) {
    return fs.readFileSync(path.join(BASE, relative), "utf8");
  }

  var STATUS_JS = read("js/status.js");
  var UI_JS = read("js/ui.js");
  var INDEX = read("index.html");
  var COMPONENT_HTML = [
    "header.html", "footer.html", "status-overview.html",
    "service-list.html", "uptime-card.html", "incident-card.html", "maintenance-card.html"
  ].map(function (name) { return read("components/" + name); }).join("\n");

  /* --- 1. DOM contract --------------------------------------------------- */

  function matches(source, pattern) {
    var found = [];
    var regex = new RegExp(pattern, "g");
    var match;
    while ((match = regex.exec(source)) !== null) found.push(match[1]);
    return found;
  }

  function hasClass(haystack, className) {
    return new RegExp("(^|[\\s\"'])" + className + "($|[\\s\"'])").test(haystack);
  }

  test("status.js only looks up element ids that index.html contains", function () {
    var ids = matches(STATUS_JS, 'getElementById\\("([^"]+)"\\)')
      .concat(matches(STATUS_JS, 'byId\\("([^"]+)"\\)'));

    assert(ids.length >= 15, "expected the home script to resolve its containers by id");

    ids.forEach(function (id) {
      assert(INDEX.indexOf('id="' + id + '"') >= 0,
        "status.js looks up #" + id + ", which index.html does not contain");
    });
  });

  test("status.js only queries selectors that exist in the page or a component", function () {
    var selectors = matches(STATUS_JS, 'querySelector(?:All)?[(][ ]*"([^"]+)"');
    assert(selectors.length >= 3, "expected the home script to query the hero glyph and header chrome");

    var haystack = INDEX + "\n" + COMPONENT_HTML;

    selectors.forEach(function (selector) {
      (selector.match(/[#.][A-Za-z][\w-]*/g) || []).forEach(function (token) {
        var name = token.slice(1);
        var present = token.charAt(0) === "#"
          ? haystack.indexOf('id="' + name + '"') >= 0
          : hasClass(haystack, name);
        assert(present, "status.js queries " + token + " (" + selector +
          "), which no page or component defines");
      });
    });
  });

  test("status.js loads its data files relative to the site root", function () {
    assert(STATUS_JS.indexOf('"api/status.json"') >= 0, "the runtime endpoint must be the preferred source");
    assert(STATUS_JS.indexOf('"data/status.json"') >= 0, "the static snapshot must remain the fallback");
    assert(STATUS_JS.indexOf('url: "/') < 0 && STATUS_JS.indexOf('url: "http') < 0,
      "status.js must not hardcode an absolute or remote data URL");
  });

  /* --- 2. Honesty source guards ----------------------------------------- */

  test("status.js gates rendering on the payload's verified flag", function () {
    assert(/verified\s*===\s*true/.test(STATUS_JS),
      "status.js must read the payload's verified flag");
    assert(STATUS_JS.indexOf('"unknown"') >= 0,
      "unknown must stay a first-class state");
    assert(STATUS_JS.indexOf("Not measured") >= 0,
      "a missing measurement must be labelled, not implied");
  });

  test("status.js never falls back to an operational state", function () {
    assert(!/All [Ss]ystems [Oo]perational/.test(STATUS_JS),
      "status.js must not contain a healthy claim");
    assert(!/\|\|\s*"operational"/.test(STATUS_JS),
      "operational must never be a fallback value");
    assert(!/tone\s*[:=]\s*"operational"/.test(STATUS_JS),
      "the default tone must not be operational");
  });

  /* --- 3. Mock DOM -------------------------------------------------------
   * Small enough to read in one screen: element lookup, attributes, text and
   * the selector forms status.js uses. It is deliberately loose about ancestry
   * - the source guard above is what proves the selectors exist. */

  function createDom() {
    var byId = {};
    var all = [];

    function element(tag) {
      var attributes = {};
      var node = {
        tagName: String(tag || "div").toUpperCase(),
        textContent: "",
        innerHTML: "",
        value: "",
        disabled: false,
        listeners: {},
        setAttribute: function (name, value) { attributes[name] = String(value); },
        getAttribute: function (name) {
          return Object.prototype.hasOwnProperty.call(attributes, name) ? attributes[name] : null;
        },
        removeAttribute: function (name) { delete attributes[name]; },
        hasAttribute: function (name) {
          return Object.prototype.hasOwnProperty.call(attributes, name);
        },
        addEventListener: function (type, handler) {
          (node.listeners[type] = node.listeners[type] || []).push(handler);
        },
        querySelector: function (selector) { return resolve(selector); },
        querySelectorAll: function (selector) { return resolveAll(selector); }
      };
      return node;
    }

    function compoundMatches(node, compound) {
      var id = /#([A-Za-z][\w-]*)/.exec(compound);
      var tag = /^([A-Za-z][\w-]*)/.exec(compound);
      var classes = compound.match(/\.[A-Za-z][\w-]*/g) || [];
      var attribute = /\[([\w-]+)(?:=("|')?([^\]"']*)\2)?\]/.exec(compound);

      if (id && node.getAttribute("id") !== id[1]) return false;
      if (tag && node.tagName !== tag[1].toUpperCase()) return false;
      if (attribute) {
        var actual = node.getAttribute(attribute[1]);
        if (actual === null) return false;
        if (attribute[3] && actual !== attribute[3]) return false;
      }

      var declared = " " + (node.getAttribute("class") || "") + " ";
      for (var i = 0; i < classes.length; i++) {
        if (declared.indexOf(" " + classes[i].slice(1) + " ") < 0) return false;
      }
      return true;
    }

    function lastCompound(selector) {
      var parts = String(selector).trim().split(/\s+/);
      return parts[parts.length - 1];
    }

    function resolve(selector) {
      var compound = lastCompound(selector);
      for (var i = 0; i < all.length; i++) {
        if (compoundMatches(all[i], compound)) return all[i];
      }
      return null;
    }

    function resolveAll(selector) {
      var compound = lastCompound(selector);
      return all.filter(function (node) { return compoundMatches(node, compound); });
    }

    function register(spec) {
      var node = element(spec.tag);
      if (spec.id) node.setAttribute("id", spec.id);
      if (spec.classes) node.setAttribute("class", spec.classes);
      Object.keys(spec.attributes || {}).forEach(function (name) {
        node.setAttribute(name, spec.attributes[name]);
      });
      if (spec.id) byId[spec.id] = node;
      all.push(node);
      return node;
    }

    var mockDocument = {
      readyState: "complete",
      visibilityState: "visible",
      listeners: {},
      getElementById: function (id) {
        return Object.prototype.hasOwnProperty.call(byId, id) ? byId[id] : null;
      },
      querySelector: function (selector) { return resolve(selector); },
      querySelectorAll: function (selector) { return resolveAll(selector); },
      addEventListener: function (type, handler) {
        (mockDocument.listeners[type] = mockDocument.listeners[type] || []).push(handler);
      }
    };

    return { document: mockDocument, register: register };
  }

  /* The elements index.html hands to the home script. */
  function registerHomeElements(dom) {
    dom.register({ id: "status-indicator", tag: "span", classes: "status-state",
      attributes: { "data-state": "loading" } });
    dom.register({ tag: "svg", classes: "status-glyph" });
    dom.register({ id: "hero-status-heading", tag: "span" });
    dom.register({ id: "status-summary", tag: "p" });
    dom.register({ id: "last-checked", tag: "time", attributes: { datetime: "" } });
    dom.register({ id: "last-checked-ago", tag: "span" });
    dom.register({ id: "autorefresh", tag: "span", classes: "freshness-item",
      attributes: { "data-paused": "false", "data-auto-refresh": "30" } });
    dom.register({ id: "autorefresh-label", tag: "span" });
    dom.register({ id: "refresh-button", tag: "button" });
    dom.register({ id: "unverified-notice", tag: "div", classes: "status-unverified",
      attributes: { hidden: "" } });
    dom.register({ classes: "js-unverified-note" });
    dom.register({ id: "status-live", tag: "p" });
    dom.register({ id: "incident-banner", tag: "div", attributes: { hidden: "" } });
    dom.register({ id: "service-list", tag: "div", classes: "service-list",
      attributes: { "aria-busy": "true" } });
    dom.register({ id: "recent-incidents", tag: "div" });
    dom.register({ id: "scheduled-maintenance", tag: "div" });
    dom.register({ id: "uptime-summary", tag: "div", classes: "uptime-grid status-uptime-summary" });
    dom.register({ id: "uptime-strip", tag: "ul", classes: "uptime-strip" });
    dom.register({ id: "uptime-legend", tag: "ul", classes: "uptime-legend" });
    dom.register({ id: "uptime-viz-caption", tag: "span", classes: "uptime-viz-caption" });
    dom.register({ id: "uptime-viz-note", tag: "p", classes: "uptime-viz-note" });
    dom.register({ id: "subscription-form", tag: "form", attributes: { "data-endpoint": "" } });
    dom.register({ id: "subscribe-email", tag: "input", attributes: { type: "email", disabled: "" } });
    dom.register({ tag: "button", attributes: { type: "submit" } });
    dom.register({ id: "subscribe-status", tag: "p" });
    dom.register({ classes: "js-header-status" });
    dom.register({ classes: "status-indicator-mini" });
    dom.register({ classes: "status-dot unknown" });
  }

  function createWindow() {
    var intervals = [];
    var mockWindow = {
      console: { warn: function () {}, error: function () {} },
      navigator: { language: "en-US" },
      location: { pathname: "/", hash: "" },
      matchMedia: function () { return { matches: false, addEventListener: function () {} }; },
      addEventListener: function () {},
      setInterval: function (fn, ms) {
        intervals.push({ fn: fn, ms: ms });
        return intervals.length;
      },
      clearInterval: function () { },
      intervals: intervals,
      syncedTimestamp: null,
      syncLastUpdated: function (iso) { mockWindow.syncedTimestamp = iso; }
    };
    return mockWindow;
  }

  /* Anything that is not stubbed behaves like a failed request. */
  function fetchStub(payloads) {
    return function (url) {
      if (!Object.prototype.hasOwnProperty.call(payloads, url)) {
        return Promise.reject(new Error("no stub for " + url));
      }
      return Promise.resolve({
        ok: true,
        status: 200,
        json: function () { return Promise.resolve(payloads[url]); }
      });
    };
  }

  /* The page uses promise chains only - no timers - so draining the microtask
     queue twice is enough to finish a render. */
  function settle() {
    return new Promise(function (resolve) { setImmediate(resolve); })
      .then(function () {
        return new Promise(function (resolve) { setImmediate(resolve); });
      });
  }

  /* Loads ui.js then status.js against a fresh mock DOM and stubbed fetch, and
     hands the rendered document to `assertions` once the page has settled. The
     real fetch is always restored, including when an assertion throws. */
  function loadPage(payloads, assertions) {
    var dom = createDom();
    registerHomeElements(dom);
    var mockWindow = createWindow();

    var hadFetch = Object.prototype.hasOwnProperty.call(global, "fetch");
    var realFetch = global.fetch;
    var realWarn = console.warn;
    global.fetch = fetchStub(payloads);
    console.warn = function () { };

    var restored = false;
    function restore() {
      if (restored) return;
      restored = true;
      console.warn = realWarn;
      if (hadFetch) global.fetch = realFetch;
      else delete global.fetch;
    }

    try {
      new Function("window", "document", UI_JS)(mockWindow, dom.document);
      new Function("window", "document", STATUS_JS)(mockWindow, dom.document);
    } catch (err) {
      restore();
      throw err;
    }

    return settle().then(function () {
      restore();
      assertions({ window: mockWindow, document: dom.document });
    }, function (err) {
      restore();
      throw err;
    });
  }

  /* --- 4. Execution ------------------------------------------------------ */

  /* The payloads this site actually ships, plus the empty incident,
     maintenance and uptime files. Rendering them must never look healthy. */
  var UNVERIFIED_STATUS = JSON.parse(read("data/status.json"));
  var EMPTY_INCIDENTS = JSON.parse(read("data/incidents.json"));
  var EMPTY_MAINTENANCE = JSON.parse(read("data/maintenance.json"));
  var EMPTY_UPTIME = JSON.parse(read("data/uptime.json"));

  function count(text, needle) {
    return String(text).split(needle).length - 1;
  }

  function cloneWith(payload, patch) {
    var copy = JSON.parse(JSON.stringify(payload));
    Object.keys(patch).forEach(function (key) { copy[key] = patch[key]; });
    return copy;
  }

  test("the shipped unverified payload renders unknown everywhere and says so", function () {
    return loadPage({
      "api/status.json": UNVERIFIED_STATUS,
      "data/incidents.json": EMPTY_INCIDENTS,
      "data/maintenance.json": EMPTY_MAINTENANCE,
      "data/uptime.json": EMPTY_UPTIME
    }, function (page) {
      var doc = page.document;
      var services = UNVERIFIED_STATUS.services.length;

      assertEqual(doc.getElementById("status-indicator").getAttribute("data-state"), "unknown",
        "an unverified payload must not render a healthy hero state");
      assertEqual(doc.getElementById("hero-status-heading").textContent,
        UNVERIFIED_STATUS.overall.label, "the hero must show the payload's own label");
      assertEqual(doc.getElementById("status-summary").textContent,
        UNVERIFIED_STATUS.overall.description, "the hero must show the payload's own description");
      assert(!doc.getElementById("unverified-notice").hasAttribute("hidden"),
        "the unverified notice must be shown");
      assertEqual(doc.querySelector(".js-unverified-note").textContent, UNVERIFIED_STATUS.note,
        "the notice must carry the payload's own explanation");

      /* The read completed, so this browser may record it - nothing else may. */
      assert(/^\d{4}-\d{2}-\d{2}T/.test(doc.getElementById("last-checked").getAttribute("datetime")),
        "#last-checked must carry the time of the completed read");
      assert(doc.getElementById("last-checked").textContent,
        "#last-checked must be readable as text as well as machine-readable");

      var list = doc.getElementById("service-list");
      assertEqual(count(list.innerHTML, 'class="service-status-card"'), services,
        "every published service needs a card");
      assertEqual(count(list.innerHTML, 'class="status-pill" data-tone="unknown"'), services,
        "every unverified service must render an unknown tone");
      assertEqual(count(list.innerHTML, 'data-tone="operational"'), 0,
        "no service may render as operational while the payload is unverified");
      assert(list.innerHTML.indexOf("Not measured") >= 0,
        "an absent latency or uptime measurement must be labelled");
      assertEqual(list.getAttribute("aria-busy"), "false",
        "the services container must stop reporting itself busy");

      assert(doc.getElementById("recent-incidents").innerHTML.indexOf("No incidents recorded") >= 0,
        "an empty incident log is an empty state, not an error");
      assert(doc.getElementById("scheduled-maintenance").innerHTML.indexOf("No maintenance scheduled") >= 0,
        "an empty schedule is an empty state, not an error");

      var summary = doc.getElementById("uptime-summary").innerHTML;
      assertEqual(count(summary, 'class="uptime-card"'), 4, "four availability windows are expected");
      assertEqual(count(summary, "Not available"), 4, "unmeasured windows must say Not available");
      assertEqual(count(summary, 'style="width:0%"'), 4,
        "unmeasured windows must not be drawn as a filled bar");
      assertEqual(doc.getElementById("uptime-strip").innerHTML, "",
        "no availability segment may be invented");
      assert(doc.getElementById("uptime-viz-note").textContent.indexOf("No daily availability measurement") >= 0,
        "the strip must explain that nothing is measured");
      assertEqual(count(doc.getElementById("uptime-legend").innerHTML, "<li>"), 5,
        "the legend explains the colours with fixed labels");

      assert(doc.querySelector(".js-header-status").textContent.indexOf("Status unavailable") >= 0,
        "the header indicator must repeat the payload's state");
      assertEqual(doc.querySelector(".status-indicator-mini .status-dot").getAttribute("class"),
        "status-dot unknown", "the header dot must not look healthy");

      assertEqual(doc.getElementById("autorefresh-label").textContent, "Auto-refresh every 30s",
        "the auto-refresh label must state the interval actually used");
      assertEqual(page.window.intervals.length, 1, "exactly one refresh timer is expected");
      assertEqual(page.window.intervals[0].ms, 30000,
        "the timer must use the interval published in the markup");

      assert(doc.getElementById("incident-banner").hasAttribute("hidden"),
        "with no active incident the banner must stay hidden");
      assertEqual(doc.getElementById("subscribe-email").disabled, true,
        "the subscription input must stay disabled while no endpoint is configured");
      assert(doc.getElementById("subscribe-status").textContent.indexOf("not available yet") >= 0,
        "the page must state that subscriptions are unavailable");
    });
  });

  test("an unreadable endpoint is reported as unavailable, never as fresh", function () {
    /* Nothing is stubbed, so every request fails. */
    return loadPage({}, function (page) {
      var doc = page.document;

      assertEqual(doc.getElementById("hero-status-heading").textContent, "Status unavailable",
        "an unreadable endpoint must not render a state");
      assertEqual(doc.getElementById("status-indicator").getAttribute("data-state"), "error",
        "the hero must show that the read failed");
      assertEqual(doc.getElementById("last-checked").getAttribute("datetime"), "",
        "no read completed, so no check time may be recorded");
      assertEqual(doc.getElementById("last-checked").textContent, "",
        "#last-checked must stay blank rather than implying a fresh read");
      assert(doc.getElementById("unverified-notice").hasAttribute("hidden"),
        "the unverified notice describes a connected-but-unmeasured page, not a failed read");

      assert(doc.getElementById("service-list").innerHTML.indexOf("Service states unavailable") >= 0,
        "the services section must say the payload was not read");
      assertEqual(doc.getElementById("service-list").getAttribute("aria-busy"), "false",
        "the skeleton must not be left shimmering forever");
      assert(doc.getElementById("recent-incidents").innerHTML.indexOf("Incident log unavailable") >= 0,
        "an unreadable incident log must not be described as empty");
      assert(doc.getElementById("scheduled-maintenance").innerHTML.indexOf("Maintenance schedule unavailable") >= 0,
        "an unreadable schedule must not be described as free");
      assert(doc.getElementById("uptime-viz-note").textContent.indexOf("could not be read") >= 0,
        "the absence of a measurement must be explained");
      assert(doc.getElementById("status-live").textContent.indexOf("could not be read") >= 0,
        "the failure must reach the live region");
      assertEqual(page.window.intervals.length, 1, "the page must keep retrying on its timer");
    });
  });

  /* A verified payload, constructed here on purpose: these numbers are test
     fixtures, never production data. */
  var VERIFIED_STATUS = {
    version: "1.0.0",
    generatedAt: "2026-09-22T14:30:00Z",
    dataSource: "test-fixture",
    verified: true,
    lastUpdated: "2026-09-22T14:30:00Z",
    overall: {
      status: "degraded",
      label: "Degraded Performance",
      description: "Reduced performance measured on the API."
    },
    services: [
      { id: "api", name: "API", description: "REST API.",
        status: "degraded", latency: 812, uptime: "99.2%" },
      { id: "webhooks", name: "Webhooks", description: "Outbound webhooks.",
        status: "operational", latency: 120, uptime: "99.99%" }
    ],
    activeIncidents: [],
    activeMaintenance: []
  };

  var VERIFIED_UPTIME = {
    version: "1.0.0",
    generatedAt: "2026-09-22T14:30:00Z",
    dataSource: "test-fixture",
    verified: true,
    overall: "99.1%",
    periods: { "24h": "99.9%", "7d": "99.5%", "30d": "99.1%", "90d": "98.8%" },
    services: [],
    history: [
      { date: "2026-09-21", status: "operational", uptime: "100%" },
      { date: "2026-09-22", status: "degraded", uptime: "98.5%" }
    ],
    recentDowntime: []
  };

  test("a verified payload renders exactly the measurements it publishes", function () {
    return loadPage({
      "api/status.json": VERIFIED_STATUS,
      "data/incidents.json": EMPTY_INCIDENTS,
      "data/maintenance.json": EMPTY_MAINTENANCE,
      "data/uptime.json": VERIFIED_UPTIME
    }, function (page) {
      var doc = page.document;

      assert(doc.getElementById("unverified-notice").hasAttribute("hidden"),
        "a verified payload must hide the unverified notice");
      assertEqual(doc.getElementById("status-indicator").getAttribute("data-state"), "degraded",
        "the hero must use the payload's published status");
      assertEqual(page.window.syncedTimestamp, VERIFIED_STATUS.lastUpdated,
        "the payload's own measurement time must reach the shared footer hook");

      var list = doc.getElementById("service-list").innerHTML;
      assert(list.indexOf('data-tone="degraded"') >= 0,
        "the degraded service must render as degraded");
      assert(list.indexOf('data-tone="operational"') >= 0,
        "the operational service must render as operational");
      assert(list.indexOf("812 ms latency") >= 0, "a published latency must be shown");
      assert(list.indexOf("99.99% uptime") >= 0, "a published availability figure must be shown");

      var summary = doc.getElementById("uptime-summary").innerHTML;
      assert(summary.indexOf("99.9%") >= 0, "published period figures must be shown");
      assertEqual(count(summary, "Not available"), 0, "a measured window must not say Not available");
      assert(summary.indexOf('style="width:99.9%"') >= 0, "a measured window must scale its bar");

      var strip = doc.getElementById("uptime-strip").innerHTML;
      assertEqual(count(strip, '<li role="listitem">'), 2, "one segment per measured day");
      assertEqual(count(strip, 'class="uptime-seg"'), 2, "each segment must be a real control");
      assert(strip.indexOf('data-status="degraded"') >= 0,
        "a degraded day must not be drawn as healthy");
      assert(strip.indexOf('aria-label="2026-09-22: 98.5% uptime"') >= 0,
        "each segment must expose its reading to assistive technology");
      assertEqual(doc.getElementById("uptime-viz-caption").textContent, "Last 2 measured days",
        "the caption must describe what is actually shown");
    });
  });

  test("an active incident published by the payload is never hidden", function () {
    var withIncident = cloneWith(UNVERIFIED_STATUS, { activeIncidents: ["INC-2026-001"] });

    return loadPage({
      "api/status.json": withIncident,
      "data/incidents.json": EMPTY_INCIDENTS,
      "data/maintenance.json": EMPTY_MAINTENANCE,
      "data/uptime.json": EMPTY_UPTIME
    }, function (page) {
      var banner = page.document.getElementById("incident-banner");

      assert(!banner.hasAttribute("hidden"),
        "an active incident must surface even when the incident log has no record");
      assert(banner.innerHTML.indexOf("Incident INC-2026-001") >= 0,
        "the published id must be shown verbatim");
      assert(banner.innerHTML.indexOf("Details are not shown on this page") >= 0,
        "the page must admit that it holds no details for it");
      assert(banner.innerHTML.indexOf('data-severity="unknown"') >= 0,
        "no severity may be invented for a record that was not read");
      assertEqual(count(banner.innerHTML, 'class="active-incident"'), 1,
        "one active incident produces one banner block");
    });
  });
};
