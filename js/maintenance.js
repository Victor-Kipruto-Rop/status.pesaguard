/**
 * PesaGuard Status Site — Maintenance page logic.
 *
 * Responsibilities:
 *  - Fetch /data/maintenance.json
 *  - Render active maintenance (in-progress)
 *  - Render upcoming scheduled maintenance
 *  - Render completed maintenance history
 *  - Show empty states
 */
(function () {
  "use strict";

  var UI = window.StatusUI;

  function maintStatusLabel(status) {
    var labels = {
      scheduled: "Scheduled",
      "in-progress": "In Progress",
      completed: "Completed",
      cancelled: "Cancelled"
    };
    return labels[status] || status || "Unknown";
  }

  function maintStatusTone(status) {
    var tones = {
      scheduled: "degraded",
      "in-progress": "outage",
      completed: "operational",
      cancelled: "unknown"
    };
    return tones[status] || "unknown";
  }

  /* --- Render active maintenance ---------------------------------------- */

  function renderActiveMaintenance(items) {
    var container = document.getElementById("active-maintenance");
    if (!container) return;

    var active = items.filter(function (m) { return m.status === "in-progress"; });

    if (active.length === 0) {
      container.innerHTML =
        '<div class="maintenance-empty">' +
        "<p>No maintenance is currently in progress.</p>" +
        "</div>";
      return;
    }
    container.innerHTML = active.map(renderMaintenanceCard).join("");
  }

  function renderUpcoming(items) {
    var container = document.getElementById("upcoming-maintenance");
    if (!container) return;

    var upcoming = items.filter(function (m) { return m.status === "scheduled"; });

    if (upcoming.length === 0) {
      container.innerHTML =
        '<div class="maintenance-empty">' +
        "<p>No maintenance is currently scheduled.</p>" +
        "</div>";
      return;
    }
    container.innerHTML = upcoming.map(renderMaintenanceCard).join("");
  }

  function renderHistory(items) {
    var container = document.getElementById("maintenance-history");
    if (!container) return;

    var completed = items.filter(function (m) { return m.status === "completed"; });

    if (completed.length === 0) {
      container.innerHTML =
        '<div class="maintenance-empty">' +
        "<strong>No maintenance history</strong>" +
        "<p>There are no past maintenance windows recorded.</p>" +
        "</div>";
      return;
    }

    var html = '<div class="maintenance-history-table-wrapper">';
    html += '<table class="maintenance-history-table">';
    html += "<thead><tr><th>Date</th><th>Service</th><th>Duration</th><th>Status</th></tr></thead><tbody>";
    completed.forEach(function (m) {
      html += "<tr>";
      html += "<td>" + UI.formatDate(m.scheduledStart, { year: "numeric", month: "short", day: "numeric" }) + "</td>";
      html += "<td>" + (m.services ? m.services.join(", ") : "—") + "</td>";
      html += "<td>" + UI.escapeHTML(m.expectedDuration || "—") + "</td>";
      var tone = maintStatusTone(m.status);
      html += '<td><span class="status-pill" data-tone="' + tone + '"><i></i>' + maintStatusLabel(m.status) + "</span></td>";
      html += "</tr>";
    });
    html += "</tbody></table></div>";
        container.innerHTML = html;
  }

  function renderMaintenanceCard(m) {
    var tone = maintStatusTone(m.status);
    var html = "";
    html += '<div class="maintenance-card" data-maintenance-id="' + UI.escapeHTML(m.id) + '" data-status="' + UI.escapeHTML(m.status) + '">';
    html += '<div class="maintenance-card-header">';
    html += '<span class="status-pill" data-tone="' + tone + '"><i></i>' + maintStatusLabel(m.status) + "</span>";
    html += '<time class="maintenance-card-meta" datetime="' + UI.escapeHTML(m.scheduledStart) + '">';
    html += UI.formatDate(m.scheduledStart, { year: "numeric", month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
    html += " · " + UI.escapeHTML(m.expectedDuration || "—");
    html += "</time></div>";
    html += '<h3 class="maintenance-card-title">' + UI.escapeHTML(m.title) + "</h3>";
    html += '<div class="maintenance-card-body">';
    html += "<p>" + UI.escapeHTML(m.description || "") + "</p>";
    if (m.services && m.services.length > 0) {
      html += '<div class="maintenance-card-services"><span class="service-label" style="font-weight:600;color:var(--text-faint);font-size:10px;letter-spacing:0.08em;text-transform:uppercase;">Affected services:</span>';
      m.services.forEach(function (s) {
        html += '<span style="background:var(--paper-sunken);border:1px solid var(--line);padding:3px 9px;border-radius:999px;font-size:11.5px;">' + UI.escapeHTML(s) + "</span>";
      });
      html += "</div>";
    }
    /* Timeline for in-progress items */
    if (m.timeline && m.timeline.length > 0 && m.status === "in-progress") {
      html += '<div class="maintenance-timeline" style="margin-top:16px;padding:0 0 0 22px;border-left:2px solid var(--line-soft);list-style:none;">';
      m.timeline.forEach(function (entry) {
        html += '<div style="position:relative;padding:10px 0 10px 14px;border-bottom:1px solid var(--line-soft);font-size:13px;">';
        html += '<div class="timeline-timestamp" style="font:500 10.5px/1.4 var(--font-mono);letter-spacing:0.06em;text-transform:uppercase;color:var(--text-faint);">';
        html += UI.formatDate(entry.timestamp, { year: "numeric", month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
        html += "</div>";
        html += '<div class="timeline-desc" style="color:var(--text-muted);margin:2px 0 0;">' + UI.escapeHTML(entry.description || "") + "</div>";
        html += "</div>";
      });
      html += "</div>";
    }
    html += "</div></div>";
    return html;
  }

  /* --- Main init ---------------------------------------------------------- */

  function init() {
    var activeContainer = document.getElementById("active-maintenance");
    if (activeContainer) {
      activeContainer.innerHTML = '<div class="skeleton" style="height:120px;width:100%;margin-bottom:14px;"></div>';
      activeContainer.innerHTML += '<div class="skeleton" style="height:120px;width:100%;"></div>';
    }

    UI.fetchJSON("data/maintenance.json", null).then(function (payload) {
      /* Payload is an object: { verified, note, maintenance: [...] }. */
      var data = (payload && payload.maintenance) || [];
      renderActiveMaintenance(data);
      renderUpcoming(data);
      renderHistory(data);
    });
  }

  document.addEventListener("status:components-loaded", init);
  document.addEventListener("DOMContentLoaded", function () {
    setTimeout(function () {
      if (typeof window.StatusUI !== "undefined") init();
    }, 1000);
  });
})();