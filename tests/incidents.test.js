/**
 * Incidents data tests.
 *
 * The payload is an object: { version, generatedAt, dataSource, verified, note, incidents }.
 * These tests enforce the honesty contract: an unverified payload must be empty
 * rather than filled with example incidents.
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
    payload = JSON.parse(fs.readFileSync(path.join(BASE, "data", "incidents.json"), "utf8"));
  } catch (e) {
    payload = null;
  }

  test("incidents.json exists and is valid JSON", function () {
    assert(payload !== null, "incidents.json could not be parsed");
  });

  test("incidents.json is an object with an incidents array", function () {
    assert(!Array.isArray(payload), "payload should be an object, not a bare array");
    assert(Array.isArray(payload.incidents), "incidents must be an array");
  });

  test("incidents.json declares data source and verification state", function () {
    assert(typeof payload.verified === "boolean", "verified flag must be explicit");
    assert(payload.dataSource, "dataSource must be named");
  });

  test("unverified payload contains no example incidents", function () {
    if (payload.verified === true) return;
    assertEqual(payload.incidents.length, 0,
      "an unverified incidents payload must not contain example incidents");
  });

  var incidents = payload.incidents;

  test("each incident has required fields", function () {
    incidents.forEach(function (inc, i) {
      assert(inc.id, "incident[" + i + "] missing id");
      assert(inc.title, "incident[" + i + "] missing title");
      assert(inc.severity, "incident[" + i + "] missing severity");
      assert(inc.status, "incident[" + i + "] missing status");
      assert(inc.created, "incident[" + i + "] missing created");
    });
  });

  test("incident severity is a known value", function () {
    var known = ["low", "medium", "high", "critical", "major"];
    incidents.forEach(function (inc) {
      assert(known.indexOf(inc.severity) >= 0,
        "incident '" + inc.id + "' has unknown severity: " + inc.severity);
    });
  });

  test("incident status is a known value", function () {
    var known = ["investigating", "identified", "monitoring", "resolved", "closed"];
    incidents.forEach(function (inc) {
      assert(known.indexOf(inc.status) >= 0,
        "incident '" + inc.id + "' has unknown status: " + inc.status);
    });
  });

  test("resolved incidents carry a duration", function () {
    incidents
      .filter(function (i) { return i.status === "resolved" || i.status === "closed"; })
      .forEach(function (inc) {
        assert(inc.duration, "resolved incident '" + inc.id + "' missing duration");
      });
  });

  test("timeline entries are complete", function () {
    incidents.forEach(function (inc) {
      (inc.timeline || []).forEach(function (entry, i) {
        assert(entry.timestamp, inc.id + " timeline[" + i + "] missing timestamp");
        assert(entry.status, inc.id + " timeline[" + i + "] missing status");
        assert(entry.description, inc.id + " timeline[" + i + "] missing description");
      });
    });
  });

  test("incident IDs are unique", function () {
    var ids = incidents.map(function (i) { return i.id; });
    assertEqual(new Set(ids).size, ids.length, "duplicate incident IDs found");
  });

  test("incidents are ordered newest first", function () {
    for (var i = 0; i < incidents.length - 1; i++) {
      var a = new Date(incidents[i].created).getTime();
      var b = new Date(incidents[i + 1].created).getTime();
      assert(a >= b, "incidents not sorted newest-first at index " + i);
    }
  });

  test("service references resolve to known service ids", function () {
    var status = JSON.parse(fs.readFileSync(path.join(BASE, "data", "status.json"), "utf8"));
    var knownIds = status.services.map(function (s) { return s.id; });
    incidents.forEach(function (inc) {
      (inc.services || []).forEach(function (svc) {
        assert(knownIds.indexOf(svc) >= 0,
          "incident '" + inc.id + "' references unknown service '" + svc + "'");
      });
    });
  });
};
