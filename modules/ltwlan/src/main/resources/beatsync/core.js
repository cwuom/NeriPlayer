/* BeatSync 94c38ab; Copyright (c) 2025 freeman-jiang. MIT. See LICENSE. Generated; do not edit. */
var console = {log: function(){}};
var LanBeatSync = (() => {
  var __defProp = Object.defineProperty;
  var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
  var __getOwnPropNames = Object.getOwnPropertyNames;
  var __hasOwnProp = Object.prototype.hasOwnProperty;
  var __export = (target, all) => {
    for (var name in all)
      __defProp(target, name, { get: all[name], enumerable: true });
  };
  var __copyProps = (to, from, except, desc) => {
    if (from && typeof from === "object" || typeof from === "function") {
      for (let key of __getOwnPropNames(from))
        if (!__hasOwnProp.call(to, key) && key !== except)
          __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
    }
    return to;
  };
  var __toCommonJS = (mod) => __copyProps(__defProp({}, "__esModule", { value: true }), mod);

  // js/production.ts
  var production_exports = {};
  __export(production_exports, {
    invoke: () => invoke
  });

  // js/upstream/constants.ts
  var STEADY_STATE_INTERVAL_MS = 2500;
  var NTP_CONSTANTS = {
    // Initial interval for rapid measurement collection
    INITIAL_INTERVAL_MS: 50,
    // Steady state interval after initial measurements
    STEADY_STATE_INTERVAL_MS,
    // Timeout before considering connection stale
    RESPONSE_TIMEOUT_MS: 1.5 * STEADY_STATE_INTERVAL_MS,
    // Maximum number of NTP measurements to collect initially
    MAX_MEASUREMENTS: 16,
    // Coded probes (Huygens) — inter-departure gap between probe pairs
    // Large enough gap to avoid TCP coalescing where browsers batch small writes into one segment
    PROBE_GAP_MS: 25,
    // Coded probes — client accepts server gap within ±this tolerance
    PROBE_GAP_TOLERANCE_MS: 5
  };

  // js/shared.ts
  var epochNow = () => globalThis.__hostNowMs;

  // js/upstream/ntp.ts
  var probeGroupCounter = 0;
  var pendingFirstProbe = null;
  var pendingFirstProbeGroupId = null;
  var pureCount = 0;
  var impureCount = 0;
  var resetProbeState = () => {
    probeGroupCounter = 0;
    pendingFirstProbe = null;
    pendingFirstProbeGroupId = null;
    pureCount = 0;
    impureCount = 0;
  };
  var validateProbePair = (data) => {
    const { measurement, probeGroupId, probeGroupIndex } = data;
    if (probeGroupIndex === 0) {
      pendingFirstProbe = measurement;
      pendingFirstProbeGroupId = probeGroupId;
      return null;
    }
    const first = pendingFirstProbe;
    const firstGroupId = pendingFirstProbeGroupId;
    if (!first || firstGroupId !== probeGroupId) {
      return null;
    }
    pendingFirstProbe = null;
    pendingFirstProbeGroupId = null;
    const clientGap = measurement.t0 - first.t0;
    const serverGap = measurement.t1 - first.t1;
    const gapDrift = Math.abs(serverGap - clientGap);
    const isPure = gapDrift <= NTP_CONSTANTS.PROBE_GAP_TOLERANCE_MS;
    if (isPure) {
      pureCount++;
    } else {
      impureCount++;
    }
    const total = pureCount + impureCount;
    const pureRate = total > 0 ? (pureCount / total * 100).toFixed(0) : "0";
    if (!isPure) {
      console.log(
        `[CodedProbe] IMPURE #${probeGroupId} | clientGap=${clientGap.toFixed(1)}ms serverGap=${serverGap.toFixed(1)}ms drift=${gapDrift.toFixed(1)}ms | pure: ${pureCount}/${total} (${pureRate}%) | sent: ${probeGroupCounter}`
      );
      return null;
    }
    const best = first.roundTripDelay <= measurement.roundTripDelay ? first : measurement;
    console.log(
      `[CodedProbe] PURE #${probeGroupId} | clientGap=${clientGap.toFixed(1)}ms serverGap=${serverGap.toFixed(1)}ms drift=${gapDrift.toFixed(1)}ms | bestRTT=${best.roundTripDelay.toFixed(1)}ms offset=${best.clockOffset.toFixed(1)}ms | pure: ${pureCount}/${total} (${pureRate}%) | sent: ${probeGroupCounter}`
    );
    return best;
  };
  var calculateOffsetEstimate = (measurements) => {
    let minRTT = Infinity;
    let bestOffset = 0;
    for (const m of measurements) {
      if (m.roundTripDelay < minRTT) {
        minRTT = m.roundTripDelay;
        bestOffset = m.clockOffset;
      }
    }
    const totalRoundTrip = measurements.reduce((sum, m) => sum + m.roundTripDelay, 0);
    const averageRoundTrip = measurements.length > 0 ? totalRoundTrip / measurements.length : 0;
    return { averageOffset: bestOffset, averageRoundTrip };
  };
  var handleNTPResponse = (response) => {
    const t3 = epochNow();
    const { t0, t1, t2, probeGroupId, probeGroupIndex } = response;
    const clockOffset = (t1 - t0 + (t2 - t3)) / 2;
    const roundTripDelay = t3 - t0 - (t2 - t1);
    const measurement = { t0, t1, t2, t3, roundTripDelay, clockOffset };
    return validateProbePair({ measurement, probeGroupId, probeGroupIndex });
  };

  // js/production.ts
  function invoke(json) {
    const request = JSON.parse(json);
    globalThis.__hostNowMs = request.now;
    if (request.op === "reset") {
      resetProbeState();
      return "null";
    }
    if (request.op === "response") return JSON.stringify(handleNTPResponse(request.response));
    if (request.op === "estimate") return JSON.stringify(calculateOffsetEstimate(request.measurements));
    throw new Error("Unknown host operation");
  }
  return __toCommonJS(production_exports);
})();
