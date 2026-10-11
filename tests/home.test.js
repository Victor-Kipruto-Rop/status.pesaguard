/**
 * Home-page status integration tests.
 * Tests the real markup contract and the public API -> rendered status flow.
 */
module.exports = function (ctx) {
  var test = ctx.test;
  var assert = ctx.assert;
  var assertEqual = ctx.assertEqual;
  var fs = ctx.fs;
  var path = ctx.path;
  var BASE = ctx.BASE;
  var STATUS_URL = "https://api.pesaguard.victorkipruto.com/public/status";

  function read(relative) {
    return fs.readFileSync(path.join(BASE, relative), "utf8");
  }

  var STATUS_JS = read("js/status.js");
  var UI_JS = read("js/ui.js");
  var INDEX = read("index.html");
  var HEADER = read("components/header.html");
  var SUBSCRIPTION_URL = STATUS_URL + "/subscriptions";

  function element(spec) {
    var attributes = {};
    var node = {
      tagName: String(spec.tag || "div").toUpperCase(),
      textContent: spec.text || "",
      innerHTML: "",
      value: "",
      disabled: false,
      listeners: {},
      setAttribute: function (name, value) {
        attributes[name] = String(value);
        if (name === "disabled") node.disabled = true;
      },
      getAttribute: function (name) {
        return Object.prototype.hasOwnProperty.call(attributes, name) ? attributes[name] : null;
      },
      removeAttribute: function (name) {
        delete attributes[name];
        if (name === "disabled") node.disabled = false;
      },
      hasAttribute: function (name) {
        return Object.prototype.hasOwnProperty.call(attributes, name);
      },
      addEventListener: function (type, handler) {
        (node.listeners[type] = node.listeners[type] || []).push(handler);
      },
      querySelector: function (selector) {
        return (spec.children || {})[selector] || null;
      }
    };
    Object.keys(spec.attributes || {}).forEach(function (name) {
      node.setAttribute(name, spec.attributes[name]);
    });
    if (spec.disabled) node.setAttribute("disabled", "");
    return node;
  }

  function createPage(payloads, responseStatuses, timeoutOnFetch, queryString) {
    var nodes = {};
    var selectors = {};
    var requestedUrls = [];
    var requests = [];

    function register(id, spec) {
      spec = spec || {};
      spec.attributes = spec.attributes || {};
      if (id) spec.attributes.id = id;
      var node = element(spec);
      if (id) nodes[id] = node;
      return node;
    }

    var glyph = register(null, { tag: "svg", attributes: { "class": "status-glyph" } });
    var note = register(null, { tag: "p", attributes: { "class": "js-unverified-note" } });
    var input = register("subscribe-email", { tag: "input", attributes: { type: "email" }, disabled: true });
    var button = register(null, { tag: "button", attributes: { type: "submit" }, disabled: true });
    var subscribeButton = button;
    var actionButton = register("email-action-button", { tag: "button" });
    register("email-action", { attributes: { "hidden": "" } });
    register("email-action-heading", { tag: "strong" });
    register("email-action-description", { tag: "p" });
    register("status-indicator", {
      tag: "span",
      attributes: { "data-state": "loading" },
      children: { ".status-glyph": glyph }
    });
    register("hero-status-heading", { tag: "span", text: "Checking status…" });
    register("status-summary", { tag: "p" });
    register("last-checked", { tag: "time", attributes: { datetime: "" } });
    register("last-checked-ago", { tag: "span" });
    register("autorefresh", { attributes: { "data-auto-refresh": "30", "data-paused": "false" } });
    register("autorefresh-label", { tag: "span" });
    register("refresh-button", { tag: "button" });
    register("refresh-hint", { tag: "p" });
    register("unverified-notice", {
      attributes: { "hidden": "" },
      children: { ".js-unverified-note": note }
    });
    register("status-notice-heading", { tag: "strong" });
    register("status-live", { tag: "p" });
    register("incident-banner", { attributes: { "hidden": "" } });
    register("service-list", { attributes: { "aria-busy": "true" } });
    register("subscription-form", {
      tag: "form",
      attributes: { "data-endpoint": SUBSCRIPTION_URL },
      children: { 'input[type="email"]': input, 'button[type="submit"]': button }
    });
    register("subscribe-status", { tag: "p" });
    selectors["#status-indicator .status-glyph"] = glyph;
    selectors[".js-unverified-note"] = note;
    selectors[".js-header-status"] = register(null, { attributes: { "class": "js-header-status" } });
    selectors[".status-indicator-mini .status-dot"] = register(null, {
      attributes: { "class": "status-dot unknown" }
    });

    var document = {
      readyState: "complete",
      visibilityState: "visible",
      listeners: {},
      getElementById: function (id) { return nodes[id] || null; },
      querySelector: function (selector) { return selectors[selector] || null; },
      addEventListener: function (type, handler) {
        (document.listeners[type] = document.listeners[type] || []).push(handler);
      }
    };
    var intervals = [];
    var warnings = [];
    var mockWindow = {
      console: {
        warn: function (message) { warnings.push(message); },
        error: function (message) { warnings.push(message); }
      },
      navigator: { language: "en-US" },
      location: { pathname: "/", hash: "", search: queryString || "" },
      matchMedia: function () { return { matches: false, addEventListener: function () {} }; },
      addEventListener: function () {},
      AbortController: AbortController,
      setTimeout: timeoutOnFetch
        ? function (callback) { return setImmediate(callback); }
        : setTimeout,
      clearTimeout: timeoutOnFetch ? clearImmediate : clearTimeout,
      setInterval: function (fn, ms) {
        intervals.push({ fn: fn, ms: ms });
        return intervals.length;
      },
      clearInterval: function () {},
      intervals: intervals,
      syncedTimestamp: null,
      syncLastUpdated: function (iso) { mockWindow.syncedTimestamp = iso; },
      fetch: function (url, options) {
        requestedUrls.push(url);
        requests.push({ url: url, options: options || {} });
        if (timeoutOnFetch && url === STATUS_URL) return new Promise(function () {});
        if (!Object.prototype.hasOwnProperty.call(payloads, url)) {
          return Promise.reject(new Error("no stub for " + url));
        }
        var configured = payloads[url];
        var payload = configured && configured.payload !== undefined ? configured.payload : configured;
        var status = configured && configured.payload !== undefined
          ? configured.status
          : ((responseStatuses && responseStatuses[url]) || 200);
        return Promise.resolve({
          ok: status >= 200 && status < 300,
          status: status,
          json: function () { return Promise.resolve(payload); }
        });
      }
    };
    return {
      document: document,
      window: mockWindow,
      nodes: nodes,
      requestedUrls: requestedUrls,
      requests: requests,
      warnings: warnings,
      input: input,
      button: button,
      subscribeButton: subscribeButton,
      actionButton: actionButton
    };
  }

  function loadPage(payloads, responseStatuses, assertions, timeoutOnFetch, queryString) {
    var page = createPage(payloads, responseStatuses || {}, timeoutOnFetch, queryString);
    var hadFetch = Object.prototype.hasOwnProperty.call(global, "fetch");
    var realFetch = global.fetch;
    var realWarn = console.warn;
    global.fetch = function (url) {
      page.requestedUrls.push(url);
      if (!Object.prototype.hasOwnProperty.call(payloads, url)) {
        return Promise.reject(new Error("no stub for " + url));
      }
      return Promise.resolve({
        ok: true,
        status: 200,
        json: function () { return Promise.resolve(payloads[url]); }
      });
    };
    console.warn = function () {};

    try {
      new Function("window", "document", UI_JS)(page.window, page.document);
      new Function("window", "document", STATUS_JS)(page.window, page.document);
    } catch (err) {
      console.warn = realWarn;
      if (hadFetch) global.fetch = realFetch;
      else delete global.fetch;
      throw err;
    }

    return new Promise(function (resolve) { setImmediate(resolve); })
      .then(function () { return new Promise(function (resolve) { setImmediate(resolve); }); })
      .then(function () {
        console.warn = realWarn;
        if (hadFetch) global.fetch = realFetch;
        else delete global.fetch;
        return assertions(page);
      }, function (err) {
        console.warn = realWarn;
        if (hadFetch) global.fetch = realFetch;
        else delete global.fetch;
        throw err;
      });
  }

  var LIVE_STATUS = {
    version: "1.0.0",
    generatedAt: "2026-10-03T08:00:00Z",
    lastUpdated: "2026-10-03T08:00:00Z",
    dataSource: "PesaGuard API health checks",
    verified: true,
    note: "Live API health checks only; incident, maintenance, and uptime feeds are not connected.",
    overall: {
      status: "operational",
      label: "Covered services operational",
      description: "All dependencies covered by the API health checks report operational."
    },
    services: [
      { id: "api", name: "PesaGuard API", status: "operational" },
      { id: "database", name: "Database", status: "operational" },
      { id: "kafka", name: "Event processing", status: "operational" },
      { id: "redis", name: "Cache", status: "operational" },
      { id: "daraja", name: "Payment provider", status: "operational" }
    ]
  };
  var UNVERIFIED_STATUS = JSON.parse(read("data/status.json"));

  test("homepage status script targets the public API and only reads elements in its markup", function () {
    assert(STATUS_JS.indexOf('https://api.pesaguard.victorkipruto.com/public/status') >= 0,
      "the runtime status source must be the public PesaGuard API endpoint");
    assert(STATUS_JS.indexOf('"data/status.json"') >= 0,
      "the local snapshot must remain available for offline messaging");
    var ids = STATUS_JS.match(/byId\("([^"]+)"\)/g) || [];
    ids.forEach(function (lookup) {
      var id = /byId\("([^"]+)"\)/.exec(lookup)[1];
      assert(INDEX.indexOf('id="' + id + '"') >= 0,
        "status.js looks up #" + id + ", which index.html does not contain");
    });
    [INDEX, HEADER].forEach(function (markup) {
      assert(/status-indicator/.test(markup) || markup === HEADER, "missing status indicator");
    });
  });

  test("status script never defaults an unknown measurement to operational", function () {
    assert(STATUS_JS.indexOf('"unknown"') >= 0, "unknown must stay a first-class state");
    assert(!/All [Ss]ystems [Oo]perational/.test(STATUS_JS),
      "status.js must not contain a hardcoded healthy claim");
    assert(!/\|\|\s*"operational"/.test(STATUS_JS),
      "operational must never be a fallback value");
  });

  test("live API response drives the homepage and records the server timestamp", function () {
    var payloads = {};
    payloads[STATUS_URL] = LIVE_STATUS;
    return loadPage(payloads, {}, function (page) {
      var doc = page.document;
      assertEqual(doc.getElementById("status-indicator").getAttribute("data-state"), "operational",
        "the hero must reflect a verified live API response");
      assertEqual(doc.getElementById("hero-status-heading").textContent, LIVE_STATUS.overall.label,
        "the hero must use the API's published label");
      assertEqual(doc.getElementById("status-summary").textContent, LIVE_STATUS.overall.description,
        "the summary must use the API's published description");
      assert(!doc.getElementById("unverified-notice").hasAttribute("hidden"),
        "a live response must keep limitations of the monitoring coverage visible");
      assertEqual(doc.getElementById("status-notice-heading").textContent, "Monitoring coverage",
        "the status notice must distinguish monitoring scope from connectivity");
      assertEqual(page.window.syncedTimestamp, LIVE_STATUS.lastUpdated,
        "the server measurement timestamp must reach the shared footer");
      assert(/^\d{4}-\d{2}-\d{2}T/.test(doc.getElementById("last-checked").getAttribute("datetime")),
        "last checked must record a completed API read");
      assertEqual((doc.getElementById("service-list").innerHTML.match(/service-status-card/g) || []).length,
        LIVE_STATUS.services.length, "all API health checks must be rendered");
      assert(doc.getElementById("service-list").innerHTML.indexOf("Database") >= 0,
        "API dependency names must be visible");
      assertEqual(doc.getElementById("service-list").getAttribute("aria-busy"), "false",
        "the service list must leave its loading state");
      assert(page.requestedUrls.indexOf(STATUS_URL) >= 0, "the real API endpoint must be requested");
      assertEqual(page.window.intervals[0].ms, 30000, "auto-refresh must honor the page interval");
      assertEqual(page.input.disabled, false, "a configured subscription endpoint must enable the email field");
      assertEqual(page.subscribeButton.disabled, false, "a configured endpoint must enable subscription submit");
    });
  });

  test("automatic and manual refresh both perform a new live API request", function () {
    var payloads = {};
    payloads[STATUS_URL] = LIVE_STATUS;
    return loadPage(payloads, {}, function (page) {
      assertEqual(page.requestedUrls.filter(function (url) { return url === STATUS_URL; }).length, 1,
        "the initial page load must read the live endpoint once");
      page.window.intervals[0].fn();
      return new Promise(function (resolve) { setImmediate(resolve); })
        .then(function () { return new Promise(function (resolve) { setImmediate(resolve); }); })
        .then(function () {
          assertEqual(page.requestedUrls.filter(function (url) { return url === STATUS_URL; }).length, 2,
            "the auto-refresh timer must read the live endpoint again");
          page.nodes["refresh-button"].listeners.click[0]();
          return new Promise(function (resolve) { setImmediate(resolve); })
            .then(function () { return new Promise(function (resolve) { setImmediate(resolve); }); });
        })
        .then(function () {
          assertEqual(page.requestedUrls.filter(function (url) { return url === STATUS_URL; }).length, 3,
            "the manual refresh button must read the same live endpoint");
          assertEqual(page.nodes["refresh-button"].disabled, false,
            "manual refresh must restore the button after the read completes");
        });

        test("automatic refresh pauses in hidden tabs and resumes on return", function () {
          var payloads = {};
          payloads[STATUS_URL] = LIVE_STATUS;
          return loadPage(payloads, {}, function (page) {
            var visibilityChange = page.document.listeners.visibilitychange[0];
            assertEqual(page.window.intervals.length, 1, "auto-refresh must start on initial load");
            page.document.visibilityState = "hidden";
            visibilityChange();
            assertEqual(page.window.intervals.length, 1, "hiding the tab must stop and not replace the timer");
            assertEqual(page.nodes.autorefresh.getAttribute("data-paused"), "true",
              "the page must expose its paused state");
            page.document.visibilityState = "visible";
            visibilityChange();
            assertEqual(page.window.intervals.length, 2, "returning to the tab must restart the timer");
            assertEqual(page.nodes.autorefresh.getAttribute("data-paused"), "false",
              "the page must expose its resumed state");
            return new Promise(function (resolve) { setImmediate(resolve); })
              .then(function () { return new Promise(function (resolve) { setImmediate(resolve); }); })
              .then(function () {
                assertEqual(page.requestedUrls.filter(function (url) { return url === STATUS_URL; }).length, 2,
                  "returning to the tab must immediately refresh live status");
              });
          });
        });
    });
  });

  test("HTTP 503 health payloads render their reported degradation instead of being discarded", function () {
    var payloads = {};
    payloads[STATUS_URL] = { status: 503, payload: {
      version: "1.0.0",
      generatedAt: LIVE_STATUS.generatedAt,
      lastUpdated: LIVE_STATUS.lastUpdated,
      dataSource: LIVE_STATUS.dataSource,
      verified: true,
      note: LIVE_STATUS.note,
      overall: { status: "outage", label: "Service disruption detected", description: "A health check failed." },
      services: [{ id: "database", name: "Database", status: "outage" }]
    } };
    return loadPage(payloads, {}, function (page) {
      assertEqual(page.document.getElementById("status-indicator").getAttribute("data-state"), "outage",
        "a valid 503 health response must be displayed as an outage");
      assertEqual(page.document.getElementById("hero-status-heading").textContent,
        "Service disruption detected", "the API label must be retained on a 503 response");
      assert(!page.document.getElementById("unverified-notice").hasAttribute("hidden"),
        "the API note about monitoring coverage must remain visible");
      assert(page.document.getElementById("service-list").innerHTML.indexOf('data-tone="outage"') >= 0,
        "failed dependencies must render as outages");
    });
  });

  test("failed live API reads fall back only to an explicitly unverified local snapshot", function () {
    var payloads = {};
    payloads["data/status.json"] = UNVERIFIED_STATUS;
    return loadPage(payloads, {}, function (page) {
      assertEqual(page.document.getElementById("status-indicator").getAttribute("data-state"), "unknown",
        "an unverified fallback must remain unknown");
      assertEqual(page.document.getElementById("last-checked").getAttribute("datetime"), "",
        "reading a static fallback must not imply that the live API was checked");
      assert(page.document.querySelector(".js-unverified-note").textContent.indexOf("live API status feed could not be reached") >= 0,
        "the notice must explain that the live API could not be reached");
      assert(page.warnings.some(function (message) {
        return message.indexOf("live API status feed") >= 0;
      }), "the network failure must also be recorded in the console");
    });
  });

  test("unreachable API without a local snapshot leaves status unavailable and not fresh", function () {
    return loadPage({}, {}, function (page) {
      var doc = page.document;
      assertEqual(doc.getElementById("hero-status-heading").textContent, "Status unavailable",
        "an unreadable endpoint must not render a state");
      assertEqual(doc.getElementById("status-indicator").getAttribute("data-state"), "error",
        "the hero must show that the live read failed");
      assertEqual(doc.getElementById("last-checked").getAttribute("datetime"), "",
        "a failed read must not appear fresh");
      assert(!doc.getElementById("unverified-notice").hasAttribute("hidden"),
        "the user must be told the live status feed is unavailable");
      assert(doc.getElementById("service-list").innerHTML.indexOf("Service states unavailable") >= 0,
        "the service section must explain that no payload was read");
    });
  });

  test("a stalled API request times out and reaches the unavailable state", function () {
    return loadPage({}, {}, function (page) {
      assertEqual(page.document.getElementById("hero-status-heading").textContent, "Status unavailable",
        "a stalled API request must not leave the page checking forever");
      assert(page.document.querySelector(".js-unverified-note").textContent.indexOf("live API status feed could not be reached") >= 0,
        "the timeout must be explained to the reader");
    }, true);
  });

  test("homepage keeps an explicitly published active incident visible", function () {
    var payload = JSON.parse(JSON.stringify(LIVE_STATUS));
    payload.activeIncidents = ["INC-2026-001"];
    var payloads = {};
    payloads[STATUS_URL] = payload;
    return loadPage(payloads, {}, function (page) {
      var banner = page.document.getElementById("incident-banner");
      assert(!banner.hasAttribute("hidden"), "an active incident must be surfaced");
      assert(banner.innerHTML.indexOf("Incident INC-2026-001") >= 0,
        "the published incident id must be shown without invented details");
    });
  });

  test("subscription area is accessible and uses the real backend endpoint", function () {
    assert(/id="subscribe"/.test(INDEX), "the header's subscribe link must resolve to the form section");
    assert(/id="subscribe-email"[^>]*aria-label=/.test(INDEX),
      "the subscription email field must have an accessible name");
    assert(INDEX.indexOf(SUBSCRIPTION_URL) >= 0,
      "the form must post to the production backend subscription route");
    assert(/confirm your email/i.test(INDEX),
      "the form must tell users that updates require email confirmation");
    assert(/id="email-action-button"/.test(INDEX),
      "email confirmation and unsubscribe links must have an actionable confirmation control");
  });

  test("subscription form sends the entered email to the backend and shows its response", function () {
    var payloads = {};
    payloads[STATUS_URL] = LIVE_STATUS;
    payloads[SUBSCRIPTION_URL] = {
      status: 202,
      payload: { message: "Check your email for a link to confirm your status subscription." }
    };
    return loadPage(payloads, {}, function (page) {
      page.input.value = "reader@example.com";
      page.nodes["subscription-form"].listeners.submit[0]({ preventDefault: function () {} });
      return new Promise(function (resolve) { setImmediate(resolve); })
        .then(function () { return new Promise(function (resolve) { setImmediate(resolve); }); })
        .then(function () {
          assertEqual(page.requestedUrls.indexOf(SUBSCRIPTION_URL) >= 0, true,
            "the form must call the subscription API");
          var request = page.requests.filter(function (entry) {
            return entry.url === SUBSCRIPTION_URL;
          })[0];
          assertEqual(request.options.method, "POST", "subscription must use POST");
          assertEqual(JSON.parse(request.options.body).email, "reader@example.com",
            "the submitted address must reach the API request");
          assertEqual(page.nodes["subscribe-status"].textContent,
            "Check your email for a link to confirm your status subscription.",
            "the API response must be shown instead of a fake success");
          assertEqual(page.input.value, "", "the email field should clear after request acceptance");
          assertEqual(page.subscribeButton.disabled, false, "the submit button must be restored after sending");
        });
    });
  });

  test("email confirmation link asks for consent and posts the token to the backend", function () {
    var payloads = {};
    payloads[STATUS_URL] = LIVE_STATUS;
    payloads[SUBSCRIPTION_URL + "/confirm"] = {
      status: 200,
      payload: { message: "Your PesaGuard status email subscription is confirmed." }
    };
    return loadPage(payloads, {}, function (page) {
      assertEqual(page.nodes["email-action-heading"].textContent, "Confirm status email updates",
        "confirmation link must identify its action");
      page.actionButton.listeners.click[0]();
      return new Promise(function (resolve) { setImmediate(resolve); })
        .then(function () { return new Promise(function (resolve) { setImmediate(resolve); }); })
        .then(function () {
          assert(page.requestedUrls.indexOf(SUBSCRIPTION_URL + "/confirm") >= 0,
            "confirmation must be sent to the backend");
          var request = page.requests.filter(function (entry) {
            return entry.url === SUBSCRIPTION_URL + "/confirm";
          })[0];
          assertEqual(JSON.parse(request.options.body).token, "one-time-confirmation-token",
            "the confirmation token must be sent to the API");
          assertEqual(page.nodes["email-action-description"].textContent,
            "Your PesaGuard status email subscription is confirmed.",
            "confirmation state must be based on the backend response");
        });
    }, false, "?confirm=one-time-confirmation-token");
  });

  test("unsubscribe link requires an explicit action and calls the backend", function () {
    var payloads = {};
    payloads[STATUS_URL] = LIVE_STATUS;
    payloads[SUBSCRIPTION_URL + "/unsubscribe"] = {
      status: 200,
      payload: { message: "This email address has been unsubscribed from status updates." }
    };
    return loadPage(payloads, {}, function (page) {
      assertEqual(page.nodes["email-action-heading"].textContent, "Unsubscribe from status emails",
        "unsubscribe link must identify its action");
      page.actionButton.listeners.click[0]();
      return new Promise(function (resolve) { setImmediate(resolve); })
        .then(function () { return new Promise(function (resolve) { setImmediate(resolve); }); })
        .then(function () {
          var request = page.requests.filter(function (entry) {
            return entry.url === SUBSCRIPTION_URL + "/unsubscribe";
          })[0];
          assert(request, "unsubscribe must call the backend");
          assertEqual(JSON.parse(request.options.body).token, "subscription-id.signature",
            "the signed unsubscribe token must reach the API");
          assertEqual(page.actionButton.hidden, true,
            "the action must be hidden only after backend success");
        });
    }, false, "?unsubscribe=subscription-id.signature");
  });
};
