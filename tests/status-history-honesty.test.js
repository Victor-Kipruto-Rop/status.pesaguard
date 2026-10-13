/**
 * Status history honesty tests.
 * Empty unverified data must never be rendered as an all-clear.
 */
module.exports = function (ctx) {
  var test = ctx.test;
  var assert = ctx.assert;
  var fs = ctx.fs;
  var path = ctx.path;
  var BASE = ctx.BASE;

  function read(file) {
    return fs.readFileSync(path.join(BASE, file), "utf8");
  }

  test("incident empty state never implies all systems are operational", function () {
    var source = read("js/incidents.js");
    assert(!/No active incidents\. All systems operational\./i.test(source),
      "unverified empty incident data must not claim all systems are operational");
    assert(/verified incident publishing is not connected/i.test(source),
      "empty incident state must explain that verified incident data is unavailable");
  });

  test("maintenance empty states disclose missing verification", function () {
    var source = read("js/maintenance.js");
    assert(/verified maintenance source is connected/i.test(source),
      "maintenance empty states must disclose missing verified data");
    assert(/verified \? "No maintenance is currently scheduled\." : "Scheduled maintenance data is unavailable/i.test(source),
      "only verified empty maintenance data may show an empty schedule");
  });

  test("uptime empty state does not claim no downtime occurred", function () {
    var source = read("js/uptime.js");
    assert(/Downtime history is unavailable until a verified monitoring source is connected\./i.test(source),
      "empty uptime history must disclose missing verified measurements");
    assert(/isVerified\(data\) \? "No downtime has been recorded by the monitoring source\." : "Downtime history is unavailable/i.test(source),
      "only verified uptime data may report no recorded downtime");
  });

  test("unverified incident and maintenance records are discarded before rendering", function () {
    var incidents = read("js/incidents.js");
    var maintenance = read("js/maintenance.js");
    assert(/payload\.verified === true/.test(incidents),
      "incident records must require verified source data");
    assert(/payload\.verified === true/.test(maintenance),
      "maintenance records must require verified source data");
  });

  test("unverified uptime values and history are suppressed", function () {
    var source = read("js/uptime.js");
    assert(/!isVerified\(data\) \|\| !data\.periods/.test(source),
      "uptime percentages must require verified measurements");
    assert(/isVerified\(data\) \? \(data\.history \|\| \[\]\) : \[\]/.test(source),
      "uptime history must require a verified source");
    assert(/isVerified\(data\) \? \(data\.recentDowntime \|\| \[\]\) : \[\]/.test(source),
      "downtime records must require a verified source");
  });

  test("status source files remain explicitly unverified until configured", function () {
    ["incidents", "maintenance", "uptime"].forEach(function (name) {
      var payload = JSON.parse(read("data/" + name + ".json"));
      assert(payload.verified === false, name + " must not be marked verified without a source");
      assert(payload.dataSource === "unconfigured", name + " source must remain unconfigured until connected");
    });
  });
};
