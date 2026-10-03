#!/usr/bin/env node
/**
 * Test runner for the PesaGuard status site.
 *
 * Uses Node.js built-in test runner (Node >= 18).
 * Run:  node tests/run.js
 *
 * What we can validate without a browser:
 *  1. JSON data file structure and content correctness
 *  2. HTML page structure and accessibility attributes
 *  3. UI helper functions (via a lightweight mock environment)
 */
"use strict";

var fs = require("fs");
var path = require("path");

var BASE = path.join(__dirname, "..");

/* --- Minimal test helpers (no external deps) --- */

var passed = 0;
var failed = 0;
var failures = [];
var queue = [];

/* Tests are queued and then run in order, so a suite may register an async test
   (a page script executed against a mock DOM) alongside the sync ones. A sync
   test behaves exactly as it always has. */
function test(name, fn) {
  queue.push({ name: name, fn: fn });
}

function assert(condition, message) {
  if (!condition) throw new Error(message || "Assertion failed");
}

function assertEqual(actual, expected, message) {
  if (actual !== expected) {
    throw new Error((message || "Assertion failed") + ": expected " + JSON.stringify(expected) + " but got " + JSON.stringify(actual));
  }
}

/* --- Mock browser environment for JS module testing --- */

function createMockEnv() {
  var htmlElements = {};
  var eventListeners = {};

  var mockWindow = {
    matchMedia: function (q) {
      return { matches: false, addEventListener: function () {} };
    },
    addEventListener: function (type, fn) {
      (eventListeners[type] = eventListeners[type] || []).push(fn);
    },
    fetch: function () {
      return Promise.resolve({ ok: false });
    },
    location: { pathname: "/", hash: "" },
    Date: Date,
    navigator: { language: "en-US" }
  };

  var mockDocument = {
    getElementById: function (id) {
      return htmlElements[id] || null;
    },
    querySelector: function () { return null; },
    querySelectorAll: function () { return []; },
    addEventListener: function () { },
    body: { getAttribute: function () { return "/"; }, setAttribute: function () {} }
  };

  /* Create a minimal DOM element */
  function createElement(tag) {
    var el = {
      tagName: tag.toUpperCase(),
      className: "",
      style: {},
      innerHTML: "",
      children: [],
      dataset: {},
      appendChild: function (child) { el.children.push(child); },
      setAttribute: function (k, v) { el[k] = v; },
      getAttribute: function (k) { return el[k] || null; },
      addEventListener: function () {}
    };
    return el;
  }

  return { mockWindow: mockWindow, mockDocument: mockDocument, createElement: createElement, htmlElements: htmlElements };
}

function loadUIModule() {
  var env = createMockEnv();
  var global = { window: env.mockWindow };
  var code = fs.readFileSync(path.join(BASE, "js", "ui.js"), "utf8");

  /* Execute the IIFE in a sandboxed context */
  var fn = new (Function.constructor)("window", code);
  fn(env.mockWindow);

  return { UI: env.mockWindow.StatusUI, env: env };
}

/* --- Run tests --- */

var suite = process.argv[2] || "all";

/* Status tests */
if (suite === "all" || suite === "status") {
  require("./status.test.js")({ test: test, assert: assert, assertEqual: assertEqual, fs: fs, path: path, BASE: BASE });
}

/* Home page tests (DOM contract + rendered output) */
if (suite === "all" || suite === "home") {
  require("./home.test.js")({ test: test, assert: assert, assertEqual: assertEqual, fs: fs, path: path, BASE: BASE });
}

/* Incidents tests */
if (suite === "all" || suite === "incidents") {
  require("./incidents.test.js")({ test: test, assert: assert, assertEqual: assertEqual, fs: fs, path: path, BASE: BASE });
}

/* Uptime tests */
if (suite === "all" || suite === "uptime") {
  require("./uptime.test.js")({ test: test, assert: assert, assertEqual: assertEqual, fs: fs, path: path, BASE: BASE });
}

/* Accessibility tests */
if (suite === "all" || suite === "accessibility") {
  require("./accessibility.test.js")({ test: test, assert: assert, fs: fs, path: path, BASE: BASE });
}

/* --- Results --- */

var chain = Promise.resolve();

queue.forEach(function (entry) {
  chain = chain
    .then(function () {
      return entry.fn();
    })
    .then(function () {
      passed++;
    }, function (err) {
      failed++;
      failures.push({ name: entry.name, error: err && err.message ? err.message : String(err) });
      console.error("  ✗ " + entry.name);
    });
});

chain.then(function () {
  console.log("\n" + passed + " passed, " + failed + " failed");
  if (failures.length > 0) {
    console.error("\nFailures:");
    failures.forEach(function (f) {
      console.error("  ✗ " + f.name);
      console.error("    " + f.error);
    });
    process.exit(1);
  } else {
    console.log("All tests passed.\n");
  }
});
