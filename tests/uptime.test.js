/**
 * Uptime data tests.
 *
 * Enforces the honesty contract: an unverified payload must contain no
 * uptime percentages or history at all, because an estimated uptime figure
 * on a status page is a false operational claim.
 */
module.exports = function (ctx) {
  var test = ctx.test;
  var assert = ctx.assert;
  var assertEqual = ctx.assertEqual;
  var fs = ctx.fs;
  var path = ctx.path;
  var BASE = ctx.BASE;

  var payload = null;
  try {
    payload = JSON.parse(fs.readFileSync(path.join(BASE, "data", "uptime.json"), "utf8"));
  } catch (e) {
    payload = null;
  }

  test("uptime.json exists and is valid JSON", function () {
    assert(payload !== null, "uptime.json could not be parsed");
  });

  test("uptime.json declares data source and verification state", function () {
    assert(typeof payload.verified === "boolean", "verified flag must be explicit");
    assert(payload.dataSource, "dataSource must be named");
  });

  test("uptime.json exposes the expected collections", function () {
    assert(payload.periods && typeof payload.periods === "object", "periods must be an object");
    assert(Array.isArray(payload.services), "services must be an array");
    assert(Array.isArray(payload.history), "history must be an array");
    assert(Array.isArray(payload.recentDowntime), "recentDowntime must be an array");
  });

  test("unverified payload reports no uptime figures", function () {
    if (payload.verified === true) return;
    assertEqual(payload.overall, null,
      "unverified payload must not publish an overall uptime percentage");
    assertEqual(Object.keys(payload.periods).length, 0,
      "unverified payload must not publish period percentages");
    assertEqual(payload.services.length, 0,
      "unverified payload must not publish per-service uptime");
    assertEqual(payload.history.length, 0,
      "unverified payload must not publish uptime history");
  });

  test("verified payload publishes all four periods", function () {
    if (payload.verified !== true) return;
    ["24h", "7d", "30d", "90d"].forEach(function (range) {
      assert(payload.periods[range],
        "verified payload missing '" + range + "' period");
    });
  });

  test("published percentages are valid percentages", function () {
    var values = [];
    Object.keys(payload.periods).forEach(function (k) { values.push(payload.periods[k]); });
    payload.services.forEach(function (svc) {
      if (svc.uptime) values.push(svc.uptime);
      Object.keys(svc.periods || {}).forEach(function (k) { values.push(svc.periods[k]); });
    });
    values.forEach(function (v) {
      var pct = parseFloat(v);
      assert(!isNaN(pct) && pct >= 0 && pct <= 100,
        "invalid uptime percentage: " + v);
    });
  });

  test("history entries are complete", function () {
    payload.history.forEach(function (entry, i) {
      assert(entry.date, "history[" + i + "] missing date");
      assert(entry.status, "history[" + i + "] missing status");
      assert(entry.uptime, "history[" + i + "] missing uptime");
    });
  });

  test("downtime entries are complete", function () {
    payload.recentDowntime.forEach(function (entry, i) {
      assert(entry.service, "recentDowntime[" + i + "] missing service");
      assert(entry.duration, "recentDowntime[" + i + "] missing duration");
    });
  });
};
