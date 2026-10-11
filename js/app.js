/**
 * PesaGuard Status Site — Shared application behaviour.
 *
 * - Loads the shared header & footer components from /components/
 * - Toggles the mobile navigation menu
 * - Adds .is-scrolled to the header on scroll
 * - Highlights the current page link
 * - Synchronises "last updated" timestamps across components
 *
 * No frameworks, no build step. Every helper is dependency-free.
 *
 * NOTE: component loading below uses fetch(), which requires the site to be
 * served over http(s) (or file:// with a permissive browser). Use
 * `npm run serve` for local preview; plain file:// browsing will load content
 * but cannot fetch the shared header/footer fragments.
 */
(function () {
  "use strict";

  var UI = window.StatusUI || {};

  /* --- Load shared components -------------------------------------------- */

  function loadComponents() {
    var headerTarget = document.getElementById("site-header");
    var footerTarget = document.getElementById("site-footer");

    var promises = [];

    if (headerTarget) {
      promises.push(UI.loadComponent("#site-header", "components/header.html"));
    }

    if (footerTarget) {
      promises.push(UI.loadComponent("#site-footer", "components/footer.html"));
    }

    return Promise.all(promises)
      .catch(function (err) {
        /* A failed header/footer fetch must not block page behaviour. */
        console.warn("app: failed to load shared components:", err);
      });
  }

  /* --- Mobile nav toggle -------------------------------------------------- */

  function initNavToggle() {
    UI.safe("nav-toggle", function () {
      var toggle = document.querySelector(".status-nav-toggle");
      var nav = document.getElementById("primary-nav");

      if (!toggle || !nav) return;

      toggle.addEventListener("click", function () {
        var expanded = toggle.getAttribute("aria-expanded") === "true";
        toggle.setAttribute("aria-expanded", String(!expanded));
        nav.classList.toggle("is-open");
      });

      /* Close mobile menu when a link is clicked. */
      nav.addEventListener("click", function (e) {
        if (e.target.tagName === "A" && nav.classList.contains("is-open")) {
          nav.classList.remove("is-open");
          toggle.setAttribute("aria-expanded", "false");
        }
      });

      /* Close on Escape. */
      document.addEventListener("keydown", function (e) {
        if (e.key === "Escape" && nav.classList.contains("is-open")) {
          nav.classList.remove("is-open");
          toggle.setAttribute("aria-expanded", "false");
        }
      });
    });
  }

  /* --- Header scroll state ------------------------------------------------ */

  function initScrollState() {
    UI.safe("scroll-state", function () {
      var header = document.querySelector(".status-header");
      if (!header) return;

      var lastScroll = 0;
      var onScroll = function () {
        if (window.scrollY > 10) {
          header.classList.add("is-scrolled");
        } else {
          header.classList.remove("is-scrolled");
        }
      };

      window.addEventListener("scroll", onScroll, { passive: true });
    });
  }

  /* --- Highlight current page link --------------------------------------- */

  function initCurrentPage() {
    UI.safe("current-page", function () {
      var currentPath = window.location.pathname;
      var pageName = currentPath.substring(currentPath.lastIndexOf("/") + 1) || "index.html";

      var links = document.querySelectorAll(".status-header-links a[href]");
      links.forEach(function (link) {
        var href = link.getAttribute("href");
        if (href === pageName) {
          link.setAttribute("aria-current", "page");
        }
      });
    });
  }

  /* --- Sync "last updated" across components ------------------------------ */

  function syncLastUpdated(iso) {
    var display = UI.formatDate(iso);
    var elements = document.querySelectorAll(".js-last-updated, .js-last-updated-display");
    elements.forEach(function (el) {
      el.textContent = display;
    });
  }

  /* --- Initialise after components are loaded ----------------------------- */

  document.addEventListener("DOMContentLoaded", function () {
    if (typeof UI.fetchHTML !== "function") {
      /* ui.js not loaded — fall back gracefully */
      return;
    }

    loadComponents().then(function () {
      initNavToggle();
      initScrollState();
      initCurrentPage();

      /* Expose sync function globally so page scripts can use it. */
      window.syncLastUpdated = syncLastUpdated;

      /* Dispatch an event so page-specific scripts know components are ready. */
      document.dispatchEvent(new CustomEvent("status:components-loaded"));
    });
  });

  /* --- Reveal on scroll (if IntersectionObserver is available) -----------
   * Reveal targets live in the static page markup, so observation starts at
   * DOMContentLoaded — NOT after the shared header/footer finish loading.
   * Waiting for component loading left the whole page invisible (opacity: 0)
   * whenever the header/footer fetch failed, e.g. over file://. */

  UI.safe("reveal", function () {
    if (!("IntersectionObserver" in window) || UI.reduceMotion) {
      /* Just show everything immediately. */
      var reveals = document.querySelectorAll(".reveal");
      reveals.forEach(function (el) { el.style.opacity = 1; el.style.transform = "none"; });
      return;
    }

    var observer = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) {
          entry.target.classList.add("is-visible");
        }
      });
    }, { threshold: 0.1 });

    function observeReveals() {
      document.querySelectorAll(".reveal").forEach(function (el) {
        /* Observing an element twice is a no-op for IntersectionObserver. */
        observer.observe(el);
      });
    }

    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", observeReveals);
    } else {
      observeReveals();
    }

    /* Components may inject further .reveal elements; re-scan when ready. */
    document.addEventListener("status:components-loaded", observeReveals);

    /* Failsafe: if anything above still failed to observe, show content.
     * Visibility beats animation on a status page. */
    setTimeout(function () {
      document.querySelectorAll(".reveal:not(.is-visible)").forEach(function (el) {
        if (el.getBoundingClientRect().top < window.innerHeight) {
          el.classList.add("is-visible");
        }
      });
    }, 2500);
  });
})();
