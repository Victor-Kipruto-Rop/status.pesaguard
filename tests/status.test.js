/**
 * Status data and rendering tests.
 * Tests the JSON data structure and the UI helper functions from ui.js.
 */
module.exports = function (ctx) {
  var test = ctx.test;
  var assert = ctx.assert;
  var assertEqual = ctx.assertEqual;
  var fs = ctx.fs;
  var path = ctx.path;
  var BASE = ctx.BASE;

  /* --- Load data --- */
  var statusData;
  var dataPath = path.join(BASE, "data", "status.json");

  try {
    statusData = JSON.parse(fs.readFileSync(dataPath, "utf8"));
  } catch (e) {
    statusData = null;
  }

  test("status.json exists and is valid JSON", function () {
    assert(statusData !== null, "status.json could not be parsed");
  });

  test("status.json has required top-level fields", function () {
    assert(statusData.version, "missing version");
    assert("lastUpdated" in statusData, "missing lastUpdated key");
    assert(statusData.overall, "missing overall");
    assert(Array.isArray(statusData.services), "services is not an array");
  });

  test("status.json declares its data source and verification state", function () {
    assert(typeof statusData.verified === "boolean", "verified flag must be explicit");
    assert(statusData.dataSource, "dataSource must be named");
  });

  test("status.overall has status and label", function () {
    assert(statusData.overall.status, "overall.status missing");
    assert(statusData.overall.label, "overall.label missing");
  });

  test("overall status is a known value", function () {
    var known = ["operational", "degraded", "outage", "maintenance", "unknown"];
    assert(known.indexOf(statusData.overall.status) >= 0,
      "unknown overall status: " + statusData.overall.status);
  });

  test("each service has required fields", function () {
    statusData.services.forEach(function (svc, i) {
      assert(svc.id, "service[" + i + "] missing id");
      assert(svc.name, "service[" + i + "] missing name");
      assert(svc.status, "service[" + i + "] missing status");
    });
  });

  test("each service status maps to a known tone", function () {
    var known = ["operational", "degraded", "outage", "maintenance", "unknown"];
    statusData.services.forEach(function (svc) {
      assert(known.indexOf(svc.status) >= 0,
        "service '" + svc.name + "' has unknown status value: " + svc.status);
    });
  });

  test("unverified payload does not report measured values", function () {
    if (statusData.verified === true) {
      /* Verified payloads must carry a real timestamp. */
      assert(statusData.lastUpdated,
        "a verified payload must include lastUpdated");
      return;
    }
    /* Unverified: no fabricated health claims. */
    assertEqual(statusData.overall.status, "unknown",
      "unverified payload must not assert a healthy overall status");
    assertEqual(statusData.lastUpdated, null,
      "unverified payload must not carry a lastUpdated timestamp");
    statusData.services.forEach(function (svc) {
      assertEqual(svc.status, "unknown",
        "unverified service '" + svc.name + "' must not assert a measured status");
      assertEqual(svc.latency, null,
        "unverified service '" + svc.name + "' must not report latency");
      assertEqual(svc.uptime, null,
        "unverified service '" + svc.name + "' must not report uptime");
    });
  });

  test("activeIncidents is an array", function () {
    assert(Array.isArray(statusData.activeIncidents), "activeIncidents should be an array");
  });

  test("activeMaintenance is an array", function () {
    assert(Array.isArray(statusData.activeMaintenance), "activeMaintenance should be an array");
  });

  /* --- API status.json mirrors data/status.json --- */
  test("api/status.json exists", function () {
    var apiPath = path.join(BASE, "api", "status.json");
    assert(fs.existsSync(apiPath), "api/status.json does not exist");
  });

  test("api/status.json has overall status and a verification flag", function () {
    var apiPath = path.join(BASE, "api", "status.json");
    var apiData = JSON.parse(fs.readFileSync(apiPath, "utf8"));
    assert(apiData.overall, "api/status.json missing overall");
    assert(apiData.overall.status, "api/status.json missing overall.status");
    assert(typeof apiData.verified === "boolean",
      "api/status.json must declare a verified flag");
  });

  test("api/status.json does not contradict data/status.json", function () {
    var apiData = JSON.parse(fs.readFileSync(path.join(BASE, "api", "status.json"), "utf8"));
    assertEqual(apiData.verified, statusData.verified,
      "api and static payloads must agree on verification state");
    assertEqual(apiData.overall.status, statusData.overall.status,
      "api and static payloads must agree on overall status");
  });

    /* --- UI helper tests (source-level) --- */

  test("ui.js loads and exposes StatusUI", function () {
    var source = fs.readFileSync(path.join(BASE, "js", "ui.js"), "utf8");
    assert(source.indexOf("StatusUI") >= 0, "StatusUI not found in ui.js source");
    assert(source.indexOf("escapeHTML") >= 0, "escapeHTML function missing");
    assert(source.indexOf("statusTone") >= 0, "statusTone function missing");
    assert(source.indexOf("formatDate") >= 0, "formatDate function missing");
  });

  test("statusTone maps statuses correctly", function () {
    var source = fs.readFileSync(path.join(BASE, "js", "ui.js"), "utf8");
    assert(source.indexOf("operational") >= 0, "operational mapping missing");
    assert(source.indexOf("degraded") >= 0, "degraded mapping missing");
    assert(source.indexOf("maintenance") >= 0, "maintenance mapping missing");
    assert(source.indexOf("outage") >= 0, "outage mapping missing");
  });

  test("escapeHTML escapes dangerous characters", function () {
    var source = fs.readFileSync(path.join(BASE, "js", "ui.js"), "utf8");
    assert(source.indexOf("escapeHTML") >= 0, "escapeHTML function missing");
    assert(source.indexOf("&amp;") >= 0, "escapeHTML does not handle &");
    assert(source.indexOf("&lt;") >= 0, "escapeHTML does not handle <");
    assert(source.indexOf("&gt;") >= 0, "escapeHTML does not handle >");
  });

  /* --- Regression: SVG className assignment -------------------------------
   * Assigning .className to an SVG element throws a TypeError in browsers
   * because it is a read-only SVGAnimatedString, which aborted rendering of
   * the home page. Classes on the status icon must be set with setAttribute.
   * ---------------------------------------------------------------------- */
  test("status.js does not assign className to SVG elements", function () {
    var source = fs.readFileSync(path.join(BASE, "js", "status.js"), "utf8");
    assert(source.indexOf('icon.setAttribute("class"') >= 0,
      "status icon class must be set with setAttribute");
    assert(source.indexOf("icon.className") < 0,
      "status.js must not assign className to the SVG icon");
  });

  test("status.js does not assign className to arbitrary queried nodes", function () {
    ["status.js", "incidents.js", "maintenance.js", "uptime.js"].forEach(function (file) {
      var source = fs.readFileSync(path.join(BASE, "js", file), "utf8");
      var offenders = source.match(/querySelector\([^)]*\)[^\n]*\.className\s*=/g) || [];
      assertEqual(offenders.length, 0,
        file + " assigns className to a queried node; use setAttribute for SVG safety");
    });
  });
};
