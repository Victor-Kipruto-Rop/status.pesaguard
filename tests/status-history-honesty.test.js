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
    assert(!/No maintenance is currently scheduled\./i.test(source),
      "unverified maintenance data must not be presented as a confirmed empty schedule");
  });

  test("uptime empty state does not claim no downtime occurred", function () {
    var source = read("js/uptime.js");
    assert(/Downtime history is unavailable until a verified monitoring source is connected\./i.test(source),
      "empty uptime history must disclose missing verified measurements");
    assert(!/No downtime has been recorded by the monitoring source\./i.test(source),
      "unverified uptime data must not imply no downtime occurred");
  });

  test("status source files remain explicitly unverified until configured", function () {
    ["incidents", "maintenance", "uptime"].forEach(function (name) {
      var payload = JSON.parse(read("data/" + name + ".json"));
      assert(payload.verified === false, name + " must not be marked verified without a source");
      assert(payload.dataSource === "unconfigured", name + " source must remain unconfigured until connected");
    });
  });
};
