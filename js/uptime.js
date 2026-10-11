/**
 * PesaGuard Status Site — Uptime page logic.
 *
 * DATA HONESTY CONTRACT
 * ---------------------
 * This module renders only what /data/uptime.json actually contains. When the
 * payload is unverified (`verified !== true`) or a measurement is missing, the
 * page reports that plainly ("Not available", "No verified uptime data").
 * It never derives, estimates, or interpolates an uptime percentage.
 *
 * Expected payload shape:
 *   {
 *     "version": "1.0.0",
 *     "generatedAt": "2026-09-22T14:30:00Z" | null,
 *     "dataSource": "unconfigured" | "<monitoring system>",
 *     "verified": false,
 *     "periods": { "24h": "99.99%", "7d": ..., "30d": ..., "90d": ... },  // may be {}
 *     "overall": "99.98%" | null,
 *     "services": [{ id, name, status, uptime, periods: {} }],
 *     "history": [{ date, status, uptime }],
 *     "recentDowntime": [{ service, date, duration, description }]
 *   }
 */
(function () {
  "use strict";

  var UI = window.StatusUI;
  var currentRange = "90d";

  /* Placeholder shown wherever a real measurement does not exist. */
  var NOT_AVAILABLE = "Not available";

  function isVerified(data) {
    return !!(data && data.verified === true);
  }

  function periodValue(data, range) {
    if (!data || !data.periods) return null;
    return data.periods[range] || null;
  }

  /* --- Overall uptime ----------------------------------------------------- */

  function renderOverall(data) {
    var container = document.getElementById("uptime-overall");
    if (!container) return;

    var pct = periodValue(data, currentRange);
    var rangeLabel = currentRange.replace("d", "-day");

    if (!pct) {
      container.innerHTML =
        '<div class="uptime-percent">' + NOT_AVAILABLE + "</div>" +
        '<div class="uptime-period-label">' + UI.escapeHTML(rangeLabel) + " uptime</div>" +
        '<div class="uptime-subtext">No verified uptime measurement is available for this period.</div>';
      return;
    }

    container.innerHTML =
      '<div class="uptime-percent">' + UI.escapeHTML(pct) + "</div>" +
      '<div class="uptime-period-label">' + UI.escapeHTML(rangeLabel) + " uptime</div>" +
      '<div class="uptime-subtext">Measured by ' +
      UI.escapeHTML(data.dataSource || "the monitoring source") + ".</div>";
  }

  /* --- Service uptime cards ----------------------------------------------- */

  function renderServiceUptime(data) {
    var container = document.getElementById("service-uptime-grid");
    if (!container) return;

    var services = (data && data.services) || [];
    if (services.length === 0) {
      container.innerHTML =
        '<p class="text-muted" style="padding:8px 0;">No per-service uptime data is available.</p>';
      return;
    }

    var html = "";
    services.forEach(function (svc) {
      var tone = UI.statusTone(svc.status);
      var measured = svc.uptime || null;
      var rangeValue = (svc.periods && svc.periods[currentRange]) || null;

      html += '<div class="uptime-status-card">';
      html += '<div class="service-name">';
      html += '<span class="uptime-dot ' + tone + '" aria-hidden="true"></span>';
      html += "<span>" + UI.escapeHTML(svc.name) + "</span>";
      html += "</div>";
      html += '<div class="uptime-summary">';
      html += '<span class="uptime-percent">' +
        (measured ? UI.escapeHTML(measured) : NOT_AVAILABLE) + "</span>";
      html += '<span class="uptime-meta">' +
        (rangeValue ? UI.escapeHTML(rangeValue) + " (" + UI.escapeHTML(currentRange) + ")" : "not measured") +
        "</span>";
      html += "</div>";
      html += "</div>";
    });
    container.innerHTML = html;
  }
  /* --- Daily uptime chart -------------------------------------------------
   * Bar heights scale the recorded percentage directly. Bars are drawn only
   * for days that carry a measurement; nothing is interpolated or estimated.
   * ----------------------------------------------------------------------- */

  function renderChart(data) {
    var container = document.getElementById("uptime-bar-chart");
    if (!container) return;

    var history = (data && data.history) || [];
    var rangeMap = { "24h": 1, "7d": 7, "30d": 30, "90d": 90 };
    var days = rangeMap[currentRange] || 90;
    var measured = history.filter(function (d) {
      return d && (d.uptime || d.status);
    });
    var filtered = measured.slice(-days);

    if (filtered.length === 0) {
      container.innerHTML =
        '<p class="text-muted" style="padding:16px 0;">' +
        "No verified daily uptime data is available yet.</p>";
      return;
    }

    var html = "";
    filtered.forEach(function (day) {
      var pct = parseFloat(day.uptime);
      var hasPct = !isNaN(pct);
      var barClass = day.status === "operational" ? "" :
                     day.status === "degraded" ? " degraded" :
                     day.status === "maintenance" ? " maintenance" : " outage";
      var height = hasPct ? Math.max((pct / 100) * 100, 2) : 2;

      html += '<div class="bar-group">';
      html += '<div class="bar' + barClass + '" style="height:' + height + '%"' +
        ' role="img" aria-label="' + UI.escapeHTML(day.date) + ": " +
        (hasPct ? UI.escapeHTML(day.uptime) + " uptime" : "no measurement") + '"></div>';
      html += '<span class="bar-label">' + UI.escapeHTML(day.date) + "</span>";
      html += "</div>";
    });

    container.innerHTML = html;
  }

  /* --- Availability summary ----------------------------------------------- */

  function renderSummary(data) {
    var container = document.getElementById("availability-summary");
    if (!container) return;

    var history = (data && data.history) || [];
    var period = periodValue(data, currentRange);
    var hasDowntimeData = isVerified(data) && history.length > 0;

    var downtimeDays = hasDowntimeData
      ? history.filter(function (h) { return h.status && h.status !== "operational"; }).length
      : null;

    var items = [
      { value: period || NOT_AVAILABLE, label: currentRange.replace("d", "-day") + " uptime" },
      { value: hasDowntimeData ? String(downtimeDays) : NOT_AVAILABLE, label: "Days with downtime" },
      { value: hasDowntimeData ? String(history.length) : NOT_AVAILABLE, label: "Days tracked" }
    ];

    var html = "";
    items.forEach(function (item) {
      html += '<div class="summary-item">';
      html += "<strong>" + UI.escapeHTML(item.value) + "</strong>";
      html += '<span class="summary-label">' + UI.escapeHTML(item.label) + "</span>";
      html += "</div>";
    });
    container.innerHTML = html;
  }

  /* --- Recent downtime ---------------------------------------------------- */

  function renderRecentDowntime(data) {
    var container = document.getElementById("recent-downtime");
    if (!container) return;

    var downtime = (data && data.recentDowntime) || [];
    if (downtime.length === 0) {
      container.innerHTML =
        '<p class="text-muted" style="padding:8px 0;">' +
        "No downtime has been recorded by the monitoring source.</p>";
      return;
    }

    var html = "";
    downtime.forEach(function (item) {
      html += '<div class="downtime-item">';
      html += '<div class="downtime-service">' + UI.escapeHTML(item.service) + "</div>";
      html += '<div class="downtime-desc">' + UI.escapeHTML(item.description || "") + "</div>";
      html += '<div class="downtime-duration"><strong>Date:</strong> ' +
        UI.escapeHTML(item.date) + " · <strong>Duration:</strong> " +
        UI.escapeHTML(item.duration || NOT_AVAILABLE) + "</div>";
      html += "</div>";
    });
    container.innerHTML = html;
  }

  /* --- Date-range selector ------------------------------------------------ */

  function initRangeSelector(data) {
    var rangeButtons = document.querySelectorAll("[data-range]");
    if (rangeButtons.length === 0) return;

    function select(btn, apply) {
      rangeButtons.forEach(function (b) { b.removeAttribute("aria-current"); });
      btn.setAttribute("aria-current", "true");
      currentRange = btn.getAttribute("data-range");
      if (apply) renderAll(data);
    }

    rangeButtons.forEach(function (btn) {
      btn.addEventListener("click", function (e) {
        e.preventDefault();
        select(btn, true);
      });
    });

    var defaultBtn = document.querySelector('[data-range="' + currentRange + '"]');
    if (defaultBtn) select(defaultBtn, false);
  }

  /* --- Service selector --------------------------------------------------- */

  function initServiceSelector(data) {
    var select = document.getElementById("service-selector");
    if (!select) return;

    var services = (data && data.services) || [];
    var html = '<option value="all">All services</option>';
    services.forEach(function (svc) {
      html += '<option value="' + UI.escapeHTML(svc.id) + '">' +
        UI.escapeHTML(svc.name) + "</option>";
    });
    select.innerHTML = html;
  }

  /* --- Render everything -------------------------------------------------- */

  function renderAll(data) {
    renderOverall(data);
    renderServiceUptime(data);
    renderChart(data);
    renderSummary(data);
    renderRecentDowntime(data);
    var label = document.getElementById("chart-range-label");
    if (label) label.textContent = "Last " + currentRange;
  }

  /* --- Init --------------------------------------------------------------- */

  function init() {
    var chartContainer = document.getElementById("uptime-bar-chart");
    if (chartContainer) UI.showLoading(chartContainer, "Loading uptime data…");

    UI.fetchJSON("data/uptime.json", null).then(function (data) {
      if (!data) {
        UI.showError(chartContainer, "Failed to load uptime data. Please try again.");
        renderAll(null);
        return;
      }
      renderAll(data);
      initRangeSelector(data);
      initServiceSelector(data);
    });
  }

  document.addEventListener("status:components-loaded", init);
  document.addEventListener("DOMContentLoaded", function () {
    setTimeout(function () {
      if (window.StatusUI) init();
    }, 1200);
  });
})();

