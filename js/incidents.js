/**
 * PesaGuard Status Site — Incidents page logic.
 *
 * Responsibilities:
 *  - Fetch /data/incidents.json
 *  - Render active incidents
 *  - Render past incidents grouped by year
 *  - Apply year / service / status filters
 *  - Support URL anchor links to individual incidents
 */
(function () {
  "use strict";

  var UI = window.StatusUI;
  var incidentsData = [];
  var currentFilters = { year: "all", service: "all", status: "all" };

  /* --- Render active incidents -------------------------------------------- */

  function renderActiveIncidents(incidents) {
    var container = document.getElementById("active-incidents");
    if (!container) return;

    var active = incidents.filter(function (i) {
      return i.status === "investigating" || i.status === "identified" || i.status === "monitoring";
    });

    if (active.length === 0) {
      container.innerHTML =
        '<div class="status-empty" style="text-align:center;padding:48px 24px;border:1px solid var(--line);border-radius:var(--r-md);background:var(--paper-raised);">' +
        '<p style="margin:0;color:var(--text-muted)">No active incidents. All systems operational.</p>' +
        "</div>";
      return;
    }

    container.innerHTML = active.map(renderIncidentCard).join("");
  }

  /* --- Render a single incident card (summary) ---------------------------- */

  function renderIncidentCard(incident) {
    var tone = UI.incidentStatusTone(incident.status);
    var html = "";

    html += '<article class="incident-card" data-severity="' + UI.escapeHTML(incident.severity) + '" data-incident-id="' + UI.escapeHTML(incident.id) + '">';
    html += '<div class="incident-card-header">';
    html += '<span class="status-pill" data-tone="' + tone + '">';
    html += '<i></i>' + UI.incidentStatusLabel(incident.status);
    html += "</span>";
    html += '<time class="incident-card-meta" datetime="' + UI.escapeHTML(incident.created) + '">';
    html += "Started: " + UI.formatDate(incident.created, {
      year: "numeric", month: "short", day: "numeric",
      hour: "2-digit", minute: "2-digit", timeZoneName: "short"
    });
    html += "</time>";
    html += "</div>";

    html += '<h2 class="incident-card-title" id="incident-title-' + UI.escapeHTML(incident.id) + '">';
    html += '<a href="incidents.html#incident-' + UI.escapeHTML(incident.id) + '" class="incident-link">';
    html += UI.escapeHTML(incident.title);
    html += "</a></h2>";

    html += '<div class="incident-card-body">';
    html += "<p>" + UI.escapeHTML(incident.description || "") + "</p>";
    if (incident.services && incident.services.length > 0) {
      html += '<div class="incident-affected-services">';
      html += '<span class="service-label" style="font-weight:600;color:var(--text-faint);font-size:12px;">Affected: </span>';
      html += incident.services.map(function (s) { return UI.escapeHTML(s); }).join(", ");
      html += "</div>";
    }
    html += "</div>";

    html += '<div class="incident-card-footer">';
    html += 'Last updated: <time>' + UI.formatDate(incident.updated || incident.created) + "</time>";
    html += "</div></article>";

        return html;
  }

  /* --- Filter application ------------------------------------------------- */

  function applyFilters(incident) {
    if (currentFilters.service !== "all") {
      var svcMatch = false;
      for (var i = 0; i < incident.services.length; i++) {
        if (incident.services[i] === currentFilters.service) { svcMatch = true; break; }
      }
      if (!svcMatch) return false;
    }
    if (currentFilters.status !== "all" && incident.status !== currentFilters.status) return false;
    return true;
  }

  /* --- Render past incidents grouped by year ------------------------------ */

  function renderPastIncidents(incidents) {
    var container = document.getElementById("past-incidents");
    if (!container) return;

    var years = {};
    incidents.forEach(function (inc) {
      var year = new Date(inc.created).getFullYear();
      if (!years[year]) years[year] = [];
      years[year].push(inc);
    });

    var yearKeys = Object.keys(years).sort(function (a, b) { return b - a; });

    if (yearKeys.length === 0) {
      container.innerHTML =
        '<div class="status-empty" style="text-align:center;padding:48px 24px;border:1px solid var(--line);border-radius:var(--r-md);background:var(--paper-raised);">' +
        "<h2>No incident history</h2>" +
        "<p style=\"color:var(--text-muted);max-width:48ch;margin:0 auto;\">There are no past incidents recorded.</p>" +
        "</div>";
      return;
    }

    var html = "";
    yearKeys.forEach(function (year) {
      if (currentFilters.year !== "all" && String(year) !== currentFilters.year) return;
      var yearIncidents = years[year].filter(function (inc) { return applyFilters(inc); });
      if (yearIncidents.length === 0) return;

      html += '<div class="incident-year" data-year="' + year + '">';
      html += '<span class="section-label">' + year + " incidents</span>";
      html += '<div class="incident-list">';
      yearIncidents.forEach(function (inc) { html += renderIncidentDetail(inc); });
      html += "</div></div>";
    });

    if (html === "") {
      html = '<div class="status-empty" style="text-align:center;padding:48px 24px;">' +
        '<p style="color:var(--text-muted)">No incidents match the selected filters.</p>' +
        "</div>";
    }
    container.innerHTML = html;
  }

  /* --- Render incident detail card --------------------------------------- */

  function renderIncidentDetail(incident) {
    var tone = UI.incidentStatusTone(incident.status);
    var html = "";
    html += '<article class="incident-card" data-severity="' + UI.escapeHTML(incident.severity) + '" data-incident-id="' + UI.escapeHTML(incident.id) + '">';
    html += '<div class="incident-card-header">';
    html += '<span class="status-pill" data-tone="' + tone + '"><i></i>' + UI.statusLabel(incident.status) + "</span>";
    html += '<time class="incident-card-meta" datetime="' + UI.escapeHTML(incident.created) + '">';
    html += UI.formatDate(incident.created, { year: "numeric", month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
    html += "</time></div>";
    html += '<h3 class="incident-card-title" id="incident-title-' + UI.escapeHTML(incident.id) + '">';
    html += '<a href="incidents.html#incident-' + UI.escapeHTML(incident.id) + '" class="incident-link">' + UI.escapeHTML(incident.title) + "</a></h3>";
    if (incident.timeline && incident.timeline.length > 0) {
      html += '<div class="incident-timeline">';
      incident.timeline.forEach(function (entry) {
        html += '<div class="incident-timeline-item">';
        html += '<div class="timeline-timestamp">' + UI.formatDate(entry.timestamp, { year: "numeric", month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" }) + "</div>";
        html += '<div class="timeline-status">' + UI.incidentStatusLabel(entry.status) + "</div>";
        html += '<div class="timeline-desc">' + UI.escapeHTML(entry.description || "") + "</div>";
        html += "</div>";
      });
      html += "</div>";
    }
    html += "<hr style=\"border:0;border-top:1px solid var(--line-soft);margin:14px 0;\">";
    html += '<div style="display:flex;justify-content:space-between;gap:12px;font-size:12.5px;color:var(--text-faint);">';
    html += '<span>Duration: ' + UI.escapeHTML(incident.duration || "—") + "</span>";
    if (incident.services && incident.services.length > 0) {
      html += '<span>' + incident.services.map(function (s) { return UI.escapeHTML(s); }).join(", ") + "</span>";
    }
        html += "</div></article>";
    return html;
  }

  /* --- Init filters ------------------------------------------------------- */

  function initFilters(incidents) {
    var yearSelect = document.getElementById("filter-year");
    var serviceSelect = document.getElementById("filter-service");
    var statusSelect = document.getElementById("filter-status");

    var years = {};
    incidents.forEach(function (i) {
      years[new Date(i.created).getFullYear()] = true;
    });
    Object.keys(years).sort(function (a, b) { return b - a; }).forEach(function (y) {
      var opt = document.createElement("option");
      opt.value = y;
      opt.textContent = y;
      if (yearSelect) yearSelect.appendChild(opt);
    });

    var services = {};
    incidents.forEach(function (i) {
      (i.services || []).forEach(function (s) { services[s] = true; });
    });
    Object.keys(services).sort().forEach(function (s) {
      var opt = document.createElement("option");
      opt.value = s;
      opt.textContent = s.charAt(0).toUpperCase() + s.slice(1);
      if (serviceSelect) serviceSelect.appendChild(opt);
    });

    var statuses = ["investigating", "identified", "monitoring", "resolved", "closed"];
    statuses.forEach(function (s) {
      var opt = document.createElement("option");
      opt.value = s;
      opt.textContent = UI.incidentStatusLabel(s);
      if (statusSelect) statusSelect.appendChild(opt);
    });

    var selects = [yearSelect, serviceSelect, statusSelect];
    var names = ["year", "service", "status"];
    selects.forEach(function (sel, idx) {
      if (!sel) return;
      sel.addEventListener("change", function () {
        currentFilters[names[idx]] = sel.value;
        renderPastIncidents(incidents);
      });
    });
  }

  /* --- Handle anchor links ------------------------------------------------ */

  function handleAnchor(incidents) {
    var hash = window.location.hash;
    if (!hash || hash.indexOf("#incident-") !== 0) return;
    var id = hash.substring(9);
    var incident = incidents.find(function (i) { return i.id === id; });
    if (!incident) return;
    var detailContainer = document.getElementById("incident-detail");
    if (!detailContainer) return;
    detailContainer.style.display = "block";
    detailContainer.innerHTML = renderIncidentDetailView(incident);
    setTimeout(function () { detailContainer.scrollIntoView({ behavior: "smooth" }); }, 100);
  }

  function renderIncidentDetailView(incident) {
    var tone = UI.incidentStatusTone(incident.status);
    var html = "";
    html += '<article class="incident-detail" data-incident-id="' + UI.escapeHTML(incident.id) + '">';
    html += '<div class="incident-card-header">';
    html += '<span class="status-pill" data-tone="' + tone + '"><i></i>' + UI.incidentStatusLabel(incident.status) + "</span>";
    html += '<time class="incident-card-meta" datetime="' + UI.escapeHTML(incident.created) + '">';
    html += UI.formatDate(incident.created, { year: "numeric", month: "short", day: "numeric", hour: "2-digit", minute: "2-digit", timeZoneName: "short" });
    html += "</time></div>";
    html += '<h2 style="font-size:1.4rem;margin:12px 0;">' + UI.escapeHTML(incident.title) + "</h2>";
    html += '<p style="color:var(--text-muted);margin-bottom:16px;">' + UI.escapeHTML(incident.description || "") + "</p>";
    if (incident.services && incident.services.length > 0) {
      html += '<div class="maintenance-card-services" style="margin-bottom:18px;">';
      incident.services.forEach(function (s) {
        html += '<span style="background:var(--paper-sunken);border:1px solid var(--line);padding:3px 9px;border-radius:999px;font-size:11.5px;">' + UI.escapeHTML(s) + "</span>";
      });
      html += "</div>";
    }
    if (incident.timeline && incident.timeline.length > 0) {
      html += '<h3 style="font-size:1rem;margin:24px 0 12px;">Timeline</h3>';
      html += '<div class="incident-timeline">';
      incident.timeline.forEach(function (entry) {
        html += '<div class="incident-timeline-item">';
        html += '<div class="timeline-timestamp">' + UI.formatDate(entry.timestamp, { year: "numeric", month: "short", day: "numeric", hour: "2-digit", minute: "2-digit", timeZoneName: "short" }) + "</div>";
        html += '<div class="timeline-status">' + UI.incidentStatusLabel(entry.status) + "</div>";
        html += '<div class="timeline-desc">' + UI.escapeHTML(entry.description || "") + "</div>";
        html += "</div>";
      });
      html += "</div>";
    }
    html += '<div style="margin-top:20px;padding-top:14px;border-top:1px solid var(--line-soft);font-size:12.5px;color:var(--text-faint);">';
    html += "<strong style=\"color:var(--text)\">Incident ID:</strong> " + UI.escapeHTML(incident.id);
    if (incident.duration) html += " · <strong style=\"color:var(--text)\">Duration:</strong> " + UI.escapeHTML(incident.duration);
    if (incident.resolved) html += " · <strong style=\"color:var(--text)\">Resolved:</strong> " + UI.formatDate(incident.resolved);
    html += "</div></article>";
    return html;
  }

  /* --- Main init ---------------------------------------------------------- */

  function init() {
    var container = document.getElementById("active-incidents");
    if (container) UI.renderSkeleton(container, 2, "incident");

    UI.fetchJSON("data/incidents.json", null).then(function (payload) {
      /* Payload is an object: { verified, note, incidents: [...] }.
       * An empty list is a legitimate, honest state — not an error. */
      var data = (payload && payload.incidents) || [];
      if (data.length === 0) {
        renderActiveIncidents([]);
        renderPastIncidents([]);
        return;
      }
      renderActiveIncidents(data);
      renderPastIncidents(data);
      initFilters(data);
      handleAnchor(data);
      incidentsData = data;
    });
  }

  window.addEventListener("hashchange", function () {
    if (incidentsData.length > 0) handleAnchor(incidentsData);
  });

  document.addEventListener("status:components-loaded", init);
  document.addEventListener("DOMContentLoaded", function () {
    setTimeout(function () {
      if (typeof window.StatusUI !== "undefined") init();
    }, 1000);
  });
})();
