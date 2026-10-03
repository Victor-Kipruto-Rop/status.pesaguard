/**
 * PesaGuard Status Site - Home page logic (index.html).
 *
 * DATA HONESTY CONTRACT
 * ---------------------
 * This module renders exactly what the JSON payloads contain, and nothing else.
 *
 *   - `verified !== true`: every state renders as Unknown, every unmeasured
 *     number renders as "Not measured" / "Not available", and the page shows
 *     the unverified notice. An unverified payload is never shown as healthy.
 *   - `verified === true`: only fields the payload actually carries are shown.
 *     A missing state, latency or availability figure still renders
 *     "Unknown" / "Not measured".
 *   - `#last-checked` is the time THIS BROWSER completed a read of the status
 *     endpoint. It is set only after a successful read, never after a failure,
 *     and it is never used as a stand-in for the payload's own `lastUpdated`.
 *   - An unreadable file is reported as unavailable. It is never reported as
 *     "empty", and an empty list is never reported as an error.
 *   - An active incident id published in the status payload is never hidden,
 *     even when the incident log has no record for it.
 *
 * Sources (relative to the site root):
 *   api/status.json        preferred runtime status endpoint
 *   data/status.json       static fallback when the endpoint is unreadable
 *   data/incidents.json    incident records
 *   data/maintenance.json  maintenance windows
 *   data/uptime.json       availability measurements
 *
 * Each section degrades on its own: one unreadable file never blanks the page.
 * No framework and no build step - same plain-JS style as the sibling page
 * scripts (incidents.js, maintenance.js, uptime.js).
 */
(function (window, document) {
  "use strict";

  var UI = window.StatusUI;

  if (!UI || typeof UI.fetchJSON !== "function") {
    /* Without ui.js there are no helpers to render with. Say so and stop. */
    if (window.console && window.console.error) {
      window.console.error("status: StatusUI is unavailable, so the home page was not rendered.");
    }
    return;
  }

  /* --- Endpoints and published limits ------------------------------------ */

  var STATUS_ENDPOINT = "api/status.json";
  var STATUS_FALLBACK = "data/status.json";
  var INCIDENTS_URL = "data/incidents.json";
  var MAINTENANCE_URL = "data/maintenance.json";
  var UPTIME_URL = "data/uptime.json";

  var HOME_INCIDENT_LIMIT = 3;
  var HOME_MAINTENANCE_LIMIT = 3;
  var STRIP_DAYS = 30;
  var DEFAULT_REFRESH_SECONDS = 30;
  var MIN_REFRESH_SECONDS = 5;

  /* Shown wherever a measurement does not exist. Never a zero, never a guess. */
  var NOT_MEASURED = "Not measured";
  var NOT_AVAILABLE = "Not available";

  /* Availability windows published by the uptime payload. */
  var UPTIME_WINDOWS = ["24h", "7d", "30d", "90d"];
  var WINDOW_LABELS = {
    "24h": "Last 24 hours",
    "7d": "Last 7 days",
    "30d": "Last 30 days",
    "90d": "Last 90 days"
  };

  /* Incident statuses that mean "still active" - the same set incidents.js uses. */
  var ACTIVE_INCIDENT_STATES = ["investigating", "identified", "monitoring"];

  /* Maintenance statuses the home page lists: planned or currently running. */
  var PLANNED_MAINTENANCE_STATES = ["scheduled", "in-progress"];

  /* Legend for the availability strip. Fixed labels only - no data claims. */
  var LEGEND_ENTRIES = [
    { status: "operational", label: "Operational" },
    { status: "degraded", label: "Degraded performance" },
    { status: "outage", label: "Outage" },
    { status: "maintenance", label: "Maintenance" },
    { status: "unknown", label: "No measurement" }
  ];

  /* Inline hero glyphs. They are drawn with currentColor, so the colour is
     driven by the hero's data-state attribute; no image is fetched to change
     the state icon. */
  var GLYPHS = {
    loading: '<circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" stroke-width="2" stroke-dasharray="3 3"/>',
    operational: '<circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" stroke-width="2"/>' +
      '<path d="M7.8 12.4l2.9 2.8 5.5-6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>',
    degraded: '<circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" stroke-width="2"/>' +
      '<path d="M7 12.5h2.4l1.4-3.4 1.9 6.2 1.4-2.8H17" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>',
    outage: '<circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" stroke-width="2"/>' +
      '<path d="M8.6 8.6l6.8 6.8M15.4 8.6l-6.8 6.8" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
    maintenance: '<circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" stroke-width="2"/>' +
      '<path d="M12 7.4V12l3 1.9" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>',
    unknown: '<circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" stroke-width="2" stroke-dasharray="3 3"/>' +
      '<circle cx="12" cy="12" r="2" fill="currentColor"/>',
    error: '<circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" stroke-width="2" stroke-dasharray="3 3"/>' +
      '<path d="M12 7.6v5.2" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>' +
      '<circle cx="12" cy="16.3" r="1.1" fill="currentColor"/>'
  };

  /* --- Page state for this load only -------------------------------------
   * Nothing here is persisted or cached. Every value is either read from a
   * payload or recorded because the browser really did the read. */

  var view = {
    tone: "loading",
    label: "Checking status",
    lastCheckedAt: null,
    refreshSeconds: DEFAULT_REFRESH_SECONDS,
    timerId: null,
    refreshing: false,
    initialised: false,
    statusPayload: null,
    incidentRecords: null,
    maintenancePayload: null
  };


  /* --- Small helpers ------------------------------------------------------ */

  function byId(id) {
    return document.getElementById(id);
  }

  /* ui.fetchJSON resolves to the fallback when a read fails, but a fetch() that
     throws before returning a promise (malformed URL, blocked scheme) would
     escape it. Both cases become "the file could not be read" here, so every
     caller has exactly one failure value to handle: null. */
  function readJSON(url) {
    try {
      return Promise.resolve(UI.fetchJSON(url, null)).catch(function () {
        return null;
      });
    } catch (err) {
      return Promise.resolve(null);
    }
  }

  function isVerified(payload) {
    return !!(payload && payload.verified === true);
  }

  /* Maps any published status value onto the four tones the CSS styles. An
     absent or unrecognised status maps to "unknown", never to "operational". */
  function toneOf(status) {
    return UI.statusTone(status);
  }

  function statusText(status) {
    return UI.statusLabel(status);
  }

  function setText(element, text) {
    if (element) element.textContent = text;
  }

  function show(element) {
    if (element) element.removeAttribute("hidden");
  }

  function hide(element) {
    if (element) element.setAttribute("hidden", "");
  }

  /* "Not measured" is a real answer; a zero or an estimate is not. */
  function percentText(value) {
    if (value === null || value === undefined || value === "") return null;
    if (typeof value === "number") return isFinite(value) ? value + "%" : null;
    return String(value);
  }

  /* Bar fills only ever scale a parsed percentage. Anything unparsable is
     treated as unmeasured rather than drawn as 0% or 100%. */
  function percentWidth(value) {
    var parsed = parseFloat(value);
    if (isNaN(parsed)) return 0;
    if (parsed < 0) return 0;
    if (parsed > 100) return 100;
    return parsed;
  }

  function measuredList(records) {
    return (records || []).filter(function (record) {
      return record && (record.uptime || record.status);
    });
  }

  function emptyState(title, body, link) {
    var linkHTML = link
      ? ' <a href="' + UI.escapeHTML(link.href) + '">' + UI.escapeHTML(link.label) + "</a>"
      : "";
    return '<div class="status-empty">' +
      '<svg class="status-empty-glyph" viewBox="0 0 24 24" aria-hidden="true" focusable="false">' +
      '<circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" stroke-width="1.6" stroke-dasharray="3 3"/></svg>' +
      "<div>" +
      "<strong>" + UI.escapeHTML(title) + "</strong>" +
      "<p>" + UI.escapeHTML(body) + linkHTML + "</p>" +
      "</div></div>";
  }

  /* Colour is never the only signal: every state also carries text. */
  function statusPill(tone, label) {
    return '<span class="status-pill" data-tone="' + tone + '"><i></i>' +
      UI.escapeHTML(label) + "</span>";
  }

  function timeHTML(value, options) {
    if (!value) return "<time>" + NOT_AVAILABLE + "</time>";
    return '<time datetime="' + UI.escapeHTML(value) + '">' +
      UI.escapeHTML(UI.formatDate(value, options)) + "</time>";
  }

  /* --- Hero: current state ------------------------------------------------ */

  function applyHeroState() {
    var icon = document.querySelector("#status-indicator .status-glyph");
    if (icon) {
      /* Assigning .className to an SVG element throws (className is a
         read-only SVGAnimatedString there), so the class goes through
         setAttribute and the shape is replaced with the state glyph. */
      icon.setAttribute("class", "status-glyph");
      icon.innerHTML = GLYPHS[view.tone] || GLYPHS.unknown;
    }

    var indicator = byId("status-indicator");
    if (indicator) indicator.setAttribute("data-state", view.tone);

    var heading = byId("hero-status-heading");
    if (heading) heading.textContent = view.label;
  }

  /* The payload's own words win. The fallbacks below only restate the state
     the payload published - they never upgrade it. */
  function defaultSummary(tone) {
    if (tone === "operational") return "PesaGuard reports all covered services as operational.";
    if (tone === "degraded") return "PesaGuard reports reduced performance on one or more services.";
    if (tone === "outage") return "PesaGuard reports an outage affecting one or more services.";
    if (tone === "maintenance") return "PesaGuard is inside a maintenance window.";
    return "PesaGuard cannot confirm the current state of its services.";
  }

  function setHero(tone, label, summary) {
    view.tone = tone || "unknown";
    view.label = label || statusText("unknown");
    applyHeroState();
    if (typeof summary === "string" && summary) setText(byId("status-summary"), summary);
  }

  /* --- Hero: freshness ----------------------------------------------------
   * `#last-checked` answers "when did this browser last read the endpoint?".
   * It is written only by markChecked(), which is only reached after a read
   * really succeeded, so a failed refresh can never look freshly checked. */

  function markChecked() {
    view.lastCheckedAt = Date.now();
    var stamp = byId("last-checked");
    if (stamp) {
      var iso = new Date(view.lastCheckedAt).toISOString();
      stamp.setAttribute("datetime", iso);
      stamp.textContent = UI.formatDate(iso);
    }
    refreshAgo();
  }

  function refreshAgo() {
    var ago = byId("last-checked-ago");
    if (!ago) return;
    ago.textContent = view.lastCheckedAt === null
      ? ""
      : "(" + UI.freshnessAgo(view.lastCheckedAt) + ")";
  }

  /* --- Hero: unverified notice ------------------------------------------- */

  function setUnverifiedNotice(shouldShow, note) {
    var notice = byId("unverified-notice");
    if (!notice) return;

    if (!shouldShow) {
      hide(notice);
      return;
    }

    /* The payload's own note explains why it is unverified; show it verbatim
       rather than inventing an explanation. */
    var noteEl = notice.querySelector(".js-unverified-note");
    if (noteEl && note) noteEl.textContent = note;

    show(notice);
  }

  /* --- Header mini indicator ---------------------------------------------
   * The header component loads asynchronously, so this is re-applied once the
   * chrome is in the document. */

  function applyHeaderIndicator() {
    setText(document.querySelector(".js-header-status"), view.label);
    var dot = document.querySelector(".status-indicator-mini .status-dot");
    if (dot) dot.setAttribute("class", "status-dot " + view.tone);
  }

  /* --- Status payload ----------------------------------------------------- */

  function applyStatus(payload) {
    view.statusPayload = payload || null;

    if (!view.statusPayload) {
      /* Nothing was read. Report an unreadable endpoint as exactly that, and
         leave `#last-checked` untouched so no read is implied. */
      setHero("error", "Status unavailable",
        "The status endpoint could not be read, so the current state of PesaGuard services cannot be confirmed.");
      setUnverifiedNotice(false, null);
      applyHeaderIndicator();
      renderServices(null);
      UI.announce("Status unavailable: the status endpoint could not be read.");
      return;
    }

    var data = view.statusPayload;
    var overall = data.overall || {};
    var verified = isVerified(data);
    var tone = toneOf(overall.status);

    view.incidentIds = activeIds(data.activeIncidents);
    view.maintenanceIds = activeIds(data.activeMaintenance);

    setHero(tone, overall.label || statusText(overall.status),
      overall.description || defaultSummary(tone));
    setUnverifiedNotice(!verified, data.note);
    markChecked();
    syncPayloadTimestamp(data);
    applyHeaderIndicator();

    renderServices(data.services);
    renderIncidentBanner();
  }

  /* `activeIncidents` / `activeMaintenance` are published as ids. Entries that
     are objects are tolerated, but only their id is trusted - everything else
     shown for an incident comes from the incident record. */
  function activeIds(entries) {
    var ids = [];
    (entries || []).forEach(function (entry) {
      if (typeof entry === "string" && entry) {
        ids.push(entry);
      } else if (entry && typeof entry === "object" && entry.id) {
        ids.push(String(entry.id));
      }
    });
    return ids;
  }

  /* The footer carries the payload's own measurement time. app.js exposes
     window.syncLastUpdated once the chrome has loaded; without it the footer
     keeps its "Not yet reported" default. */
  function syncPayloadTimestamp(data) {
    var updated = data && (data.lastUpdated || data.generatedAt);
    if (!updated) return;
    if (typeof window.syncLastUpdated === "function") window.syncLastUpdated(updated);
  }

  /* --- Services ----------------------------------------------------------- */

  function measurementText(service) {
    var parts = [];
    /* Latency and availability are shown only when the payload carries a
       number. A missing value says so instead of implying a healthy zero. */
    if (typeof service.latency === "number" && isFinite(service.latency)) {
      parts.push(service.latency + " ms latency");
    }
    if (service.uptime !== null && service.uptime !== undefined && service.uptime !== "") {
      parts.push(String(service.uptime) + " uptime");
    }
    if (parts.length === 0) return NOT_MEASURED;
    return parts.join(" · ");
  }

  function renderServiceCard(service) {
    var tone = toneOf(service.status);
    var name = service.name || service.id;
    var html = '<div class="service-status-card" data-service-id="' +
      UI.escapeHTML(service.id) + '" data-tone="' + tone + '">';

    html += '<span class="service-name">';
    html += '<span class="status-dot ' + tone + '" aria-hidden="true"></span>';
    html += '<span class="service-name-text">' + UI.escapeHTML(name);
    if (service.description) {
      html += '<span class="service-name-sub">' + UI.escapeHTML(service.description) + "</span>";
    }
    html += "</span></span>";

    html += '<span class="service-meta">';
    html += statusPill(tone, statusText(service.status));
    html += '<span class="service-meta-sub">' + UI.escapeHTML(measurementText(service)) + "</span>";
    html += "</span></div>";

    return html;
  }

  function renderServices(services) {
    var container = byId("service-list");
    if (!container) return;

    /* A missing list is a payload that was not read; an empty list is a
       payload that read fine and lists nothing. They are not the same. */
    if (services === null || services === undefined) {
      container.innerHTML = emptyState("Service states unavailable",
        "The status payload was not read, so no per-service state can be shown.");
      container.setAttribute("aria-busy", "false");
      return;
    }

    var listed = services.filter(function (service) {
      return service && service.id;
    });

    if (listed.length === 0) {
      container.innerHTML = emptyState("No services published",
        "The status payload does not list any services, so there is nothing to report on.");
      container.setAttribute("aria-busy", "false");
      return;
    }

    container.innerHTML = listed.map(renderServiceCard).join("");
    container.setAttribute("aria-busy", "false");
  }

  /* --- Active incident banner -------------------------------------------- */

  function activeRecords(records) {
    return (records || []).filter(function (record) {
      return record && ACTIVE_INCIDENT_STATES.indexOf(record.status) >= 0;
    });
  }

  function renderActiveIncidentCard(incident) {
    var tone = UI.incidentStatusTone(incident.status);
    var affected = (incident.services || []).map(function (service) {
      return UI.escapeHTML(service);
    }).join(", ");

    var html = '<div class="active-incident" data-severity="' +
      UI.escapeHTML(incident.severity || "unknown") + '" role="status">';

    html += '<div class="active-incident-top">';
    html += statusPill(tone, UI.incidentStatusLabel(incident.status));
    html += "</div>";

    html += "<h2>" + UI.escapeHTML(incident.title || "Untitled incident") + "</h2>";
    if (incident.description) html += "<p>" + UI.escapeHTML(incident.description) + "</p>";

    html += "<dl>";
    html += "<div><dt>Started</dt><dd>" + timeHTML(incident.created) + "</dd></div>";
    if (incident.updated) {
      html += "<div><dt>Updated</dt><dd>" + timeHTML(incident.updated) + "</dd></div>";
    }
    html += "<div><dt>Affected</dt><dd>" + (affected || NOT_AVAILABLE) + "</dd></div>";
    html += "<div><dt>Severity</dt><dd>" +
      UI.escapeHTML(UI.severityLabel(incident.severity)) + "</dd></div>";
    html += "</dl>";

    html += '<a class="incident-link" href="incidents.html#incident-' +
      UI.escapeHTML(incident.id) + '">View incident details</a>';
    html += "</div>";

    return html;
  }

  /* Shown when the status payload reports an active incident that the incident
     log has no record for. The id is a published fact; nothing else is added. */
  function renderActiveIncidentPlaceholder(id) {
    return '<div class="active-incident" data-severity="unknown" role="status">' +
      '<div class="active-incident-top">' + statusPill("outage", "Active incident") + "</div>" +
      "<h2>Incident " + UI.escapeHTML(id) + "</h2>" +
      "<p>The status payload lists this incident as active. Details are not " +
      "shown on this page.</p>" +
      "<dl><div><dt>Record</dt><dd>Not available on this page</dd></div></dl>" +
      '<a class="incident-link" href="incidents.html#incident-' + UI.escapeHTML(id) +
      '">Open the incident log</a>' +
      "</div>";
  }

  function renderIncidentBanner() {
    var banner = byId("incident-banner");
    if (!banner) return;

    var records = activeRecords(view.incidentRecords);
    var ids = activeIds(view.statusPayload ? view.statusPayload.activeIncidents : null);

    var known = {};
    records.forEach(function (record) { known[String(record.id)] = true; });

    var blocks = records.slice(0, HOME_INCIDENT_LIMIT).map(renderActiveIncidentCard);
    var unmatched = ids.filter(function (id) { return !known[id]; });
    var room = Math.max(0, HOME_INCIDENT_LIMIT - blocks.length);
    unmatched.slice(0, room).forEach(function (id) {
      blocks.push(renderActiveIncidentPlaceholder(id));
    });

    if (blocks.length === 0) {
      /* No active incident: the container stays empty and hidden. No empty
         red box, and no "all clear" claim either. */
      banner.innerHTML = "";
      hide(banner);
      return;
    }

    var html = blocks.join("");
    var hidden = Math.max(0, records.length - HOME_INCIDENT_LIMIT) +
      Math.max(0, unmatched.length - room);
    if (hidden > 0) {
      html += '<p class="status-section-sub">' + hidden +
        " more active " + (hidden === 1 ? "incident is" : "incidents are") +
        " listed in the incident log.</p>";
    }

    banner.innerHTML = html;
    show(banner);
  }

  /* --- Subscription form --------------------------------------------------
   * `data-endpoint` is empty until a notification service exists. While it is
   * empty the controls stay disabled and the page says so: nothing is sent,
   * no address is stored, and no "check your inbox" confirmation is produced.
   * The form is only enabled when a real endpoint is configured in the markup. */

  function initSubscription() {
    var form = byId("subscription-form");
    if (!form) return;

    var input = form.querySelector('input[type="email"]');
    var button = form.querySelector('button[type="submit"]');
    var status = byId("subscribe-status");
    var endpoint = (form.getAttribute("data-endpoint") || "").trim();

    if (!endpoint) {
      if (input) input.disabled = true;
      if (button) button.disabled = true;
      setText(status, "Email subscriptions are not available yet. This form is inert: it sends nothing and stores no address.");
      return;
    }

    if (input) input.disabled = false;
    if (button) button.disabled = false;

    form.addEventListener("submit", function (event) {
      event.preventDefault();
      if (!input) return;

      var address = (input.value || "").trim();
      if (!address || (typeof input.checkValidity === "function" && !input.checkValidity())) {
        input.setAttribute("aria-invalid", "true");
        setText(status, "Enter a valid email address.");
        return;
      }

      input.removeAttribute("aria-invalid");
      if (button) button.disabled = true;
      setText(status, "Sending subscription request...");

      window.fetch(endpoint, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email: address })
      }).then(function (response) {
        if (!response.ok) throw new Error("HTTP " + response.status);
        setText(status, "Subscription request accepted.");
        input.value = "";
      }).catch(function () {
        setText(status, "The subscription request could not be sent. Please try again later.");
      }).then(function () {
        if (button) button.disabled = false;
      });
    });
  }

  /* --- Refresh controls ---------------------------------------------------
   * Only the page read runs on a timer. A background refresh never announces
   * itself, so the live region stays quiet while a reader is using the page. */

  function refreshSecondsFromMarkup() {
    var holder = byId("autorefresh");
    var raw = holder ? holder.getAttribute("data-auto-refresh") : null;
    var seconds = parseInt(raw, 10);
    if (!raw || isNaN(seconds) || seconds < MIN_REFRESH_SECONDS) return DEFAULT_REFRESH_SECONDS;
    return seconds;
  }

  function setAutoRefreshState(refreshing, paused) {
    var holder = byId("autorefresh");
    var label = byId("autorefresh-label");
    if (holder) holder.setAttribute("data-paused", paused ? "true" : "false");

    if (refreshing) setText(label, "Refreshing...");
    else if (paused) setText(label, "Auto-refresh paused");
    else setText(label, "Auto-refresh every " + view.refreshSeconds + "s");
  }

  function stopAutoRefresh() {
    if (view.timerId !== null) {
      window.clearInterval(view.timerId);
      view.timerId = null;
    }
  }

  function startAutoRefresh() {
    stopAutoRefresh();
    view.refreshSeconds = refreshSecondsFromMarkup();
    setAutoRefreshState(false, false);
    view.timerId = window.setInterval(function () {
      refresh({ announce: false });
    }, view.refreshSeconds * 1000);
  }

  function initRefreshControls() {
    var button = byId("refresh-button");
    if (button) {
      button.addEventListener("click", function () {
        refresh({ announce: true });
      });
    }

    document.addEventListener("visibilitychange", function () {
      if (document.visibilityState === "hidden") {
        stopAutoRefresh();
        setAutoRefreshState(false, true);
        return;
      }
      startAutoRefresh();
      refresh({ announce: false });
    });
  }

  function refresh(options) {
    options = options || {};
    if (view.refreshing) return;

    view.refreshing = true;
    var button = byId("refresh-button");
    if (button) {
      button.setAttribute("aria-busy", "true");
      button.disabled = true;
    }
    setAutoRefreshState(true, false);
    if (options.announce) UI.announce("Refreshing status...");

    loadAll().then(function () {
      view.refreshing = false;
      if (button) {
        button.removeAttribute("aria-busy");
        button.disabled = false;
      }
      setAutoRefreshState(false, document.visibilityState === "hidden");
      refreshAgo();

      /* Report the time the read really completed, not the time it started,
         and say plainly when nothing could be read. */
      if (options.announce) {
        UI.announce(view.lastCheckedAt === null
          ? "Status could not be refreshed: the status endpoint was not read."
          : "Status refreshed at " + UI.formatDate(new Date(view.lastCheckedAt).toISOString()));
      }
    });
  }

  /* --- Loaders ------------------------------------------------------------ */

  function loadStatus() {
    var services = byId("service-list");
    if (services) {
      services.setAttribute("aria-busy", "true");
      UI.renderSkeleton(services, 4, "service");
    }

    return readJSON(STATUS_ENDPOINT).then(function (payload) {
      if (payload) return payload;
      /* The runtime endpoint was unreadable; try the static snapshot before
         declaring the status unavailable. */
      return readJSON(STATUS_FALLBACK);
    }).then(function (payload) {
      applyStatus(payload);
    });
  }

  function loadIncidents() {
    var recent = byId("recent-incidents");
    if (recent) UI.renderSkeleton(recent, 2, "incident");

    return readJSON(INCIDENTS_URL).then(function (payload) {
      /* The payload is an object: { verified, note, incidents: [...] }. An
         unreadable file is reported as unavailable; an empty list is reported
         as empty. The two are never conflated. */
      view.incidentRecords = payload && Array.isArray(payload.incidents)
        ? payload.incidents
        : null;
      renderRecentIncidents(view.incidentRecords);
      renderIncidentBanner();
    });
  }

  function loadMaintenance() {
    var scheduled = byId("scheduled-maintenance");
    if (scheduled) UI.renderSkeleton(scheduled, 2, "maintenance");

    return readJSON(MAINTENANCE_URL).then(function (payload) {
      view.maintenancePayload = payload && Array.isArray(payload.maintenance) ? payload : null;
      renderScheduledMaintenance();
    });
  }

  function loadUptime() {
    var summary = byId("uptime-summary");
    if (summary) UI.renderSkeleton(summary, 4, "uptime");

    return readJSON(UPTIME_URL).then(function (payload) {
      renderUptime(payload);
    });
  }

  function loadAll() {
    return Promise.all([loadStatus(), loadIncidents(), loadMaintenance(), loadUptime()]);
  }

  /* --- Init --------------------------------------------------------------- */

  function init() {
    if (view.initialised) {
      /* The chrome finished loading after the first render. Re-apply only the
         labels it owns; never re-read the data. */
      applyHeaderIndicator();
      setAutoRefreshState(view.refreshing, document.visibilityState === "hidden");
      refreshAgo();
      return;
    }

    view.initialised = true;
    renderUptimeLegend();
    initSubscription();
    initRefreshControls();
    loadAll();
    startAutoRefresh();
  }

  document.addEventListener("status:components-loaded", function () {
    UI.safe("status-chrome", init);
  });

  if (document.readyState === "complete" || document.readyState === "interactive") {
    UI.safe("status-init", init);
  } else {
    document.addEventListener("DOMContentLoaded", function () {
      UI.safe("status-init", init);
    });
  }
})(window, document);

