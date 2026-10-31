// Tests the NTP sync pure functions: min-RTT offset selection,
// wait time calculation, and measurement filtering behavior.

import { describe, expect, it, mock } from "bun:test";
import { calculateOffsetEstimate, calculateWaitTimeMilliseconds, type NTPMeasurement } from "@/utils/ntp";
import * as shared from "@beatsync/shared";

const FROZEN_TIME = 10000;

// Mock epochNow to return a fixed value so wait time math is exact
mock.module("@beatsync/shared", () => ({
  ...shared,
  epochNow: () => FROZEN_TIME,
}));

function createMeasurement(data: { roundTripDelay: number; clockOffset: number }): NTPMeasurement {
  return {
    t0: 1000,
    t1: 1000 + data.clockOffset + data.roundTripDelay / 2,
    t2: 1000 + data.clockOffset + data.roundTripDelay / 2,
    t3: 1000 + data.roundTripDelay,
    roundTripDelay: data.roundTripDelay,
    clockOffset: data.clockOffset,
  };
}

describe("calculateOffsetEstimate", () => {
  it("should select the offset from the minimum-RTT measurement", () => {
    const measurements: NTPMeasurement[] = [
      createMeasurement({ roundTripDelay: 10, clockOffset: 100 }),
      createMeasurement({ roundTripDelay: 20, clockOffset: 110 }),
      createMeasurement({ roundTripDelay: 200, clockOffset: 500 }),
      createMeasurement({ roundTripDelay: 300, clockOffset: 800 }),
    ];

    const result = calculateOffsetEstimate(measurements);

    // Min RTT is 10, its offset is 100
    expect(result.averageOffset).toBe(100);

    // Average round trip uses ALL measurements: (10 + 20 + 200 + 300) / 4 = 132.5
    expect(result.averageRoundTrip).toBe(132.5);
  });

  it("should ignore high-RTT spikes entirely", () => {
    const measurements: NTPMeasurement[] = [
      createMeasurement({ roundTripDelay: 18, clockOffset: 149 }),
      createMeasurement({ roundTripDelay: 22, clockOffset: 151 }),
      createMeasurement({ roundTripDelay: 20, clockOffset: 150 }),
      createMeasurement({ roundTripDelay: 500, clockOffset: 350 }),
      createMeasurement({ roundTripDelay: 800, clockOffset: -150 }),
    ];

    const result = calculateOffsetEstimate(measurements);

    // Min RTT is 18, its offset is 149 — spikes have zero influence
    expect(result.averageOffset).toBe(149);
  });

  it("should handle negative clock offsets (client ahead of server)", () => {
    const measurements: NTPMeasurement[] = [
      createMeasurement({ roundTripDelay: 12, clockOffset: -48 }),
      createMeasurement({ roundTripDelay: 10, clockOffset: -50 }),
      createMeasurement({ roundTripDelay: 15, clockOffset: -55 }),
      createMeasurement({ roundTripDelay: 500, clockOffset: -200 }),
    ];

    const result = calculateOffsetEstimate(measurements);

    // Min RTT is 10, its offset is -50
    expect(result.averageOffset).toBe(-50);
  });

  it("should handle a single measurement", () => {
    const measurements: NTPMeasurement[] = [createMeasurement({ roundTripDelay: 50, clockOffset: 200 })];

    const result = calculateOffsetEstimate(measurements);

    expect(result.averageOffset).toBe(200);
    expect(result.averageRoundTrip).toBe(50);
  });
});

describe("calculateWaitTimeMilliseconds", () => {
  // epochNow() is mocked to return FROZEN_TIME (10000)

  it("should return exact wait time when target is in the future", () => {
    // estimatedCurrentServerTime = 10000 + 500 = 10500
    // wait = 11000 - 10500 = 500
    expect(calculateWaitTimeMilliseconds(11000, 500)).toBe(500);
  });

  it("should return 0 when target time has already passed", () => {
    // estimatedCurrentServerTime = 10000 + 0 = 10000
    // wait = max(0, 5000 - 10000) = 0
    expect(calculateWaitTimeMilliseconds(5000, 0)).toBe(0);
  });

  it("should handle negative clock offset (client ahead of server)", () => {
    // estimatedCurrentServerTime = 10000 + (-200) = 9800
    // wait = 10300 - 9800 = 500
    expect(calculateWaitTimeMilliseconds(10300, -200)).toBe(500);
  });
});
