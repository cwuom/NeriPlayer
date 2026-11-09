import { ClientActionEnum, epochNow, NTP_CONSTANTS, type NTPResponseMessageType } from "@beatsync/shared";
import { sendWSRequest } from "./ws";

// ── Types ──────────────────────────────────────────────────────────

export interface NTPMeasurement {
  t0: number;
  t1: number;
  t2: number;
  t3: number;
  roundTripDelay: number;
  clockOffset: number;
}

// ── Probe pair sending ─────────────────────────────────────────────

let probeGroupCounter = 0;
let pendingFirstProbe: NTPMeasurement | null = null;
let pendingFirstProbeGroupId: number | null = null;
let pureCount = 0;
let impureCount = 0;

/** Reset probe state (call on connection reset) */
export const resetProbeState = () => {
  probeGroupCounter = 0;
  pendingFirstProbe = null;
  pendingFirstProbeGroupId = null;
  pureCount = 0;
  impureCount = 0;
};

/** Get probe pair stats for debugging */
export const getProbeStats = () => ({
  totalPairs: pureCount + impureCount,
  pureCount,
  impureCount,
  totalSent: probeGroupCounter,
});

/**
 * Send a coded probe pair (Huygens). Two NTP requests sent with a known
 * inter-departure gap. The client later validates that the server-side
 * inter-arrival gap matches, filtering out measurements corrupted by
 * queuing, TCP HOL blocking, or GC pauses.
 */
export const sendProbePair = (data: {
  ws: WebSocket;
  currentRTT: number | undefined;
  compensationMs: number | undefined;
  nudgeMs: number | undefined;
}) => {
  const { ws, currentRTT, compensationMs, nudgeMs } = data;
  if (ws.readyState !== WebSocket.OPEN) {
    throw new Error("Cannot send NTP request: WebSocket is not open");
  }

  const probeGroupId = probeGroupCounter++;

  // First probe — sent immediately
  sendWSRequest({
    ws,
    request: {
      type: ClientActionEnum.enum.NTP_REQUEST,
      t0: epochNow(),
      clientRTT: currentRTT,
      clientCompensationMs: compensationMs,
      clientNudgeMs: nudgeMs,
      probeGroupId,
      probeGroupIndex: 0,
    },
  });

  // Second probe — sent after PROBE_GAP_MS
  setTimeout(() => {
    if (ws.readyState !== WebSocket.OPEN) return;
    sendWSRequest({
      ws,
      request: {
        type: ClientActionEnum.enum.NTP_REQUEST,
        t0: epochNow(),
        clientRTT: currentRTT,
        clientCompensationMs: compensationMs,
        clientNudgeMs: nudgeMs,
        probeGroupId,
        probeGroupIndex: 1,
      },
    });
  }, NTP_CONSTANTS.PROBE_GAP_MS);
};

// ── Probe pair collection ──────────────────────────────────────────

/**
 * Feed an individual probe response into the pair validator.
 *
 * Buffers the first probe (index 0). When the second probe (index 1)
 * arrives, validates gap purity and returns the best measurement
 * (lowest RTT) from the pair. Returns null if still waiting for the
 * second probe, the pair was impure, or the group ID didn't match.
 */
export const validateProbePair = (data: {
  measurement: NTPMeasurement;
  probeGroupId: number;
  probeGroupIndex: number;
}): NTPMeasurement | null => {
  const { measurement, probeGroupId, probeGroupIndex } = data;

  if (probeGroupIndex === 0) {
    pendingFirstProbe = measurement;
    pendingFirstProbeGroupId = probeGroupId;
    return null;
  }

  // probeGroupIndex === 1: try to complete the pair
  const first = pendingFirstProbe;
  const firstGroupId = pendingFirstProbeGroupId;

  if (!first || firstGroupId !== probeGroupId) {
    return null;
  }

  // Clear pending state
  pendingFirstProbe = null;
  pendingFirstProbeGroupId = null;

  // Validate gap purity: compare server inter-arrival gap against client inter-departure gap
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
  const pureRate = total > 0 ? ((pureCount / total) * 100).toFixed(0) : "0";

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

// ── Offset estimation ──────────────────────────────────────────────

/**
 * Estimate clock offset using min-RTT selection.
 *
 * Queuing delays can only ADD to RTT, never subtract. So the lowest-RTT
 * measurement is closest to the true propagation delay, and its offset
 * has the least asymmetric queuing contamination (RFC 5905 §10).
 */
export const calculateOffsetEstimate = (measurements: NTPMeasurement[]) => {
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

export const calculateWaitTimeMilliseconds = (targetServerTime: number, clockOffset: number): number => {
  const estimatedCurrentServerTime = epochNow() + clockOffset;
  return Math.max(0, targetServerTime - estimatedCurrentServerTime);
};

/**
 * Process an NTP_RESPONSE into a measurement and attempt to complete a probe pair.
 * Returns a ProbePairResult if both probes in the pair have been received and validated,
 * or null if still waiting for the second probe or the pair was impure.
 */
export const handleNTPResponse = (response: NTPResponseMessageType): NTPMeasurement | null => {
  const t3 = epochNow();
  const { t0, t1, t2, probeGroupId, probeGroupIndex } = response;

  const clockOffset = (t1 - t0 + (t2 - t3)) / 2;
  const roundTripDelay = t3 - t0 - (t2 - t1);
  const measurement: NTPMeasurement = { t0, t1, t2, t3, roundTripDelay, clockOffset };

  return validateProbePair({ measurement, probeGroupId, probeGroupIndex });
};
