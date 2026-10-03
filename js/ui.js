/**
 * PesaGuard Status Site — UI utilities and component loader.
 *
 * Provides:
 *  - Component loading from /components/*.html (header, footer, etc.)
 *  - Date/time formatting helpers
 *  - Status tone mapping and icon resolution
 *  - Skeleton (loading) state rendering
 *  - Safe error containment pattern (from the docs app.js)
 *
 * No frameworks, no build step. Component loading requires the site to be
 * served over http(s).
 */
(function (global) {
  "use strict";

  /* Expose shared helpers on window.StatusUI */
  global.StatusUI = {
    reduceMotion: window.matchMedia("(prefers-reduced-motion: reduce)").matches,

    /* --- Safe execution: one failing feature doesn't break others --- */
    safe: function (label, fn) {
      try { fn(); } catch (err) {
        if (window.console && window.console.error)
          console.error(label + ":", err);
      }
    },

    /* --- Fetch a JSON data file, falling back gracefully --- */
    fetchJSON: function (url, fallback) {
      return fetch(url, { cache: "no-store" })
        .then(function (r) {
          if (!r.ok) throw new Error("HTTP " + r.status);
          return r.json();
        })
        .catch(function (err) {
          console.warn("StatusUI: failed to fetch " + url + ":", err);
          return fallback || null;
        });
    },

    /* --- Fetch an HTML component fragment --- */
    fetchHTML: function (url) {
      return fetch(url, { cache: "no-cache" })
        .then(function (r) {
          if (!r.ok) throw new Error("HTTP " + r.status);
          return r.text();
        });
    },

    /* --- Load and inject a component into a target element --- */
    loadComponent: function (targetSelector, componentPath) {
      var target = document.querySelector(targetSelector);
      if (!target) return Promise.resolve(null);
      return this.fetchHTML(componentPath)
        .then(function (html) {
          if (html) target.innerHTML = html;
        })
        .catch(function (err) {
          console.warn("StatusUI: could not load " + componentPath, err);
        });
    },

    /* --- Date formatting --- */
    formatDate: function (iso, opts) {
      opts = opts || {
        year: "numeric", month: "short", day: "numeric",
        hour: "2-digit", minute: "2-digit", timeZoneName: "short"
      };
      try {
        return new Date(iso).toLocaleString(navigator.language || "en-US", opts);
      } catch (e) { return iso || "—"; }
    },

    formatTimeAgo: function (iso) {
      try {
        var then = new Date(iso).getTime();
        var now = Date.now();
        var diff = Math.floor((now - then) / 1000);
        if (diff < 60) return diff + "s ago";
        if (diff < 3600) return Math.floor(diff / 60) + "m ago";
        if (diff < 86400) return Math.floor(diff / 3600) + "h ago";
        return Math.floor(diff / 86400) + "d ago";
      } catch (e) { return "—"; }
    },

        formatDuration: function (ms) {
      if (ms < 60000) return Math.floor(ms / 1000) + "s";
      if (ms < 3600000) return Math.floor(ms / 60000) + "m";
      if (ms < 86400000) return Math.floor(ms / 3600000) + "h " + Math.floor((ms % 3600000) / 60000) + "m";
      return Math.floor(ms / 86400000) + "d " + Math.floor((ms % 86400000) / 3600000) + "h";
    },

    /* --- Status mapping --- */
    statusTone: function (status) {
      var map = {
        operational: "operational",
        operational_or_degraded: "operational",
        degraded: "degraded",
        partial_outage: "outage",
        major_outage: "outage",
        outage: "outage",
        maintenance: "maintenance",
        under_maintenance: "maintenance"
      };
      return map[status] || "unknown";
    },

    statusLabel: function (status) {
      var labels = {
        operational: "Operational",
        degraded: "Degraded Performance",
        partial_outage: "Partial Outage",
        major_outage: "Major Outage",
        outage: "Outage",
        maintenance: "Maintenance",
        under_maintenance: "Under Maintenance",
        unknown: "Unknown"
      };
      return labels[status] || "Unknown";
    },

    severityLabel: function (severity) {
      var labels = {
        low: "Minor",
        medium: "Partial Outage",
        high: "Major Outage",
        critical: "Major Outage",
        major: "Major Outage"
      };
      return labels[severity] || severity || "Unknown";
    },

    severityTone: function (severity) {
      var tones = { low: "medium", medium: "degraded", high: "outage", critical: "outage", major: "outage" };
      return tones[severity] || "outage";
    },

    incidentStatusLabel: function (status) {
      var labels = {
        investigating: "Investigating",
        identified: "Identified",
        monitoring: "Monitoring",
        resolved: "Resolved",
        closed: "Closed"
      };
      return labels[status] || status || "Unknown";
    },

    incidentStatusTone: function (status) {
      var tones = {
        investigating: "outage",
        identified: "degraded",
        monitoring: "operational",
        resolved: "operational",
        closed: "operational"
      };
      return tones[status] || "unknown";
    },

    /* --- Escape HTML to prevent injection from data files --- */
    escapeHTML: function (str) {
      if (!str) return "";
      return String(str)
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;");
    },

    /* --- Announce a polite status message ---------------------------------- */
    announce: function (message) {
      var live = document.getElementById("status-live");
      if (live) live.textContent = message || "";
    },

    /* --- Relative "time ago" label for freshness rows --------------------- */
    freshnessAgo: function (when) {
      try {
        var diff = Date.now() - when;
        if (diff < 0) diff = 0;
        if (diff < 45000) return "just now";
        if (diff < 3600000) {
          var mins = Math.max(1, Math.round(diff / 60000));
          return mins + (mins === 1 ? " min ago" : " mins ago");
        }
        var hours = Math.round(diff / 3600000);
        return hours + (hours === 1 ? " hr ago" : " hrs ago");
      } catch (e) { return ""; }
    },

    /* --- Render skeleton placeholder rows --- */
    renderSkeleton: function (container, count, type) {
      var html = "";
      for (var i = 0; i < count; i++) {
        html += '<div class="skeleton status-card">';
        if (type === "service") {
          html += '<div style="height:14px;width:70%;margin-bottom:8px;background:var(--paper-sunken);border-radius:var(--r-sm);"></div>';
          html += '<div style="height:12px;width:50%;background:var(--paper-sunken);border-radius:var(--r-sm);"></div>';
        } else {
          html += '<div style="height:22px;width:60%;margin-bottom:8px;background:var(--paper-sunken);border-radius:var(--r-sm);"></div>';
          html += '<div style="height:14px;width:100%;margin-bottom:6px;background:var(--paper-sunken);border-radius:var(--r-sm);"></div>';
          html += '<div style="height:14px;width:80%;background:var(--paper-sunken);border-radius:var(--r-sm);"></div>';
        }
        html += "</div>";
      }
      container.innerHTML = html;
    },

    /* --- Show / hide loading state --- */
    showLoading: function (container, message) {
      message = message || "Loading...";
      container.innerHTML =
        '<div class="status-loading" role="status" aria-live="polite">' +
        '<div style="height:14px;width:100%;margin-bottom:10px;background:var(--paper-sunken);border-radius:var(--r-sm);"></div>' +
        '<div style="height:14px;width:80%;margin-bottom:10px;background:var(--paper-sunken);border-radius:var(--r-sm);"></div>' +
        '<div style="height:14px;width:60%;background:var(--paper-sunken);border-radius:var(--r-sm);margin-bottom:10px;"></div>' +
        '<p style="color:var(--text-faint);margin-top:10px">' +
        this.escapeHTML(message) + "</p></div>";
    },

    showError: function (container, message) {
      message = message || "Failed to load data. Please try again.";
      var html =
        '<div class="status-error" role="alert" aria-live="polite">' +
        '<div class="status-empty" style="text-align:center;padding:56px 24px;border:1px solid var(--line);border-radius:var(--r-md);background:var(--paper-raised);">' +
        '<p style="margin:0;color:var(--text-muted)">' + this.escapeHTML(message) + "</p>" +
        '<button class="status-retry" style="margin-top:12px;padding:6px 16px;border:1px solid var(--line);border-radius:var(--r-sm);background:var(--paper-raised);font:600 11px/1 var(--font-mono);cursor:pointer;">Retry</button>' +
        "</div></div>";
      container.innerHTML = html;
      var retryBtn = container.querySelector(".status-retry");
      if (retryBtn) {
        retryBtn.addEventListener("click", function () { window.location.reload(); });
      }
    },

    /* --- Truncate text --- */
    truncate: function (str, max) {
      if (!str) return "";
      if (str.length <= max) return str;
      return str.slice(0, max) + "…";
    }
  };
})(window);

