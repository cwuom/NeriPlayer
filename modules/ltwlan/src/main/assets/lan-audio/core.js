/* BeatSync 94c38ab16ec861835ce0b4bc3a7db82dabbb9b88. Copyright (c) 2025 freeman-jiang. MIT. See LICENSE. */
var NeriBeatSyncAudio = (() => {
  var __defProp = Object.defineProperty;
  var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
  var __getOwnPropNames = Object.getOwnPropertyNames;
  var __hasOwnProp = Object.prototype.hasOwnProperty;
  var __defNormalProp = (obj, key, value) => key in obj ? __defProp(obj, key, { enumerable: true, configurable: true, writable: true, value }) : obj[key] = value;
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
  var __publicField = (obj, key, value) => __defNormalProp(obj, typeof key !== "symbol" ? key + "" : key, value);

  // js/audio/entry.ts
  var entry_exports = {};
  __export(entry_exports, {
    dispatch: () => dispatch
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
  var LOW_PASS_CONSTANTS = {
    MIN_FREQ: 20,
    MAX_FREQ: 2e4
  };

  // js/audio/shared.ts
  var ClientActionEnum = { enum: { NTP_REQUEST: "NTP_REQUEST", SYNC: "SYNC" } };
  var epochNow = () => {
    const host2 = globalThis;
    if (host2.__ntpReceiveAt !== void 0) return host2.__ntpReceiveAt;
    if (!Number.isFinite(host2.__nativeOffset)) throw Error("CLOCK");
    return performance.now() + host2.__nativeOffset;
  };

  // js/upstream/audioContextManager.ts
  function isAudioContextPaused(state2) {
    return state2 === "suspended" || state2 === "interrupted";
  }
  var SILENCE_DATA_URL = "data:audio/wav;base64,UklGRiYAAABXQVZFZm10IBAAAAABAAEARKwAAIhYAQACABAAZGF0YQIAAAAAAA==";
  var _AudioContextManager = class _AudioContextManager {
    constructor() {
      __publicField(this, "audioContext", null);
      __publicField(this, "masterGainNode", null);
      __publicField(this, "lowPassFilterNode", null);
      __publicField(this, "stateChangeCallback", null);
      __publicField(this, "wakeLock", null);
      __publicField(this, "hasVisibilityListener", false);
      __publicField(this, "silentAudioElement", null);
      __publicField(this, "hasRegisteredGestureListeners", false);
    }
    /**
     * Get the singleton instance of AudioContextManager
     */
    static getInstance() {
      if (!_AudioContextManager.instance) {
        _AudioContextManager.instance = new _AudioContextManager();
      }
      return _AudioContextManager.instance;
    }
    isIOS() {
      const ua = navigator.userAgent.toLowerCase();
      return ua.includes("iphone") || ua.includes("ipad") || ua.includes("ipod") || // Newer iPads report as Mac but have touch
      ua.includes("mac os x") && navigator.maxTouchPoints > 0;
    }
    /**
     * Get or create the AudioContext
     * Will reuse existing context unless it's closed
     */
    getContext() {
      if (!this.audioContext || this.audioContext.state === "closed") {
        console.log("[AudioContextManager] Creating new AudioContext");
        this.audioContext = new AudioContext();
        this.setupStateChangeListener();
        this.setupMasterGain();
        this.registerSilentAudioBypass();
      }
      return this.audioContext;
    }
    /**
     * Get the master gain node for volume control
     */
    getMasterGain() {
      if (!this.masterGainNode) {
        const ctx = this.getContext();
        this.masterGainNode = ctx.createGain();
        this.masterGainNode.connect(ctx.destination);
      }
      return this.masterGainNode;
    }
    /**
     * Resume the AudioContext if it's suspended or interrupted.
     * iOS 18+ can put contexts into a non-standard "interrupted" state
     * (e.g. phone call, Siri). We handle both.
     * Also starts the silent <audio> fallback for iOS < 16.4 mute-switch bypass.
     */
    async resume() {
      var _a;
      const state2 = (_a = this.audioContext) == null ? void 0 : _a.state;
      if (isAudioContextPaused(state2)) {
        try {
          await this.audioContext.resume();
          console.log(`[AudioContextManager] AudioContext resumed from ${state2}`);
        } catch (error) {
          console.error("[AudioContextManager] Failed to resume AudioContext:", error);
          throw error;
        }
      }
      await this.requestWakeLock();
    }
    /**
     * iOS silent-mode bypass via a looping silent <audio> element.
     *
     * On iOS < 16.4, WebAudio routes through the ringer channel and is muted
     * by the hardware switch. Playing any <audio> element forces the system to
     * allocate the media channel, which WebAudio then piggy-backs on.
     *
     * On iOS 16.4+, navigator.audioSession.type = "playback" handles this
     * natively, so the silent element is unnecessary.
     *
     * iOS requires audio.play() to be called directly from a raw DOM event
     * handler (touchend, click, etc.). React's synthetic events and async
     * boundaries break the gesture chain. So we register capture-phase
     * listeners on window and attempt play on every user interaction until
     * it succeeds.
     */
    registerSilentAudioBypass() {
      if (this.hasRegisteredGestureListeners) return;
      if (navigator.audioSession) return;
      if (!this.isIOS()) return;
      this.hasRegisteredGestureListeners = true;
      const audio = document.createElement("audio");
      audio.setAttribute("x-webkit-airplay", "deny");
      audio.controls = false;
      audio.disableRemotePlayback = true;
      audio.preload = "auto";
      audio.loop = true;
      audio.src = SILENCE_DATA_URL;
      audio.load();
      const gestureEvents = ["click", "touchend", "keydown"];
      const tryPlay = () => {
        if (this.silentAudioElement) return;
        const p = audio.play();
        if (p) {
          p.then(() => {
            this.silentAudioElement = audio;
            console.log("[AudioContextManager] Silent audio playing \u2014 mute-switch bypass active");
            for (const evt of gestureEvents) {
              window.removeEventListener(evt, tryPlay, true);
            }
          }).catch(() => {
          });
        }
      };
      for (const evt of gestureEvents) {
        window.addEventListener(evt, tryPlay, { capture: true, passive: true });
      }
      console.log("[AudioContextManager] Registered silent audio bypass gesture listeners (legacy iOS)");
    }
    destroySilentAudioBypass() {
      if (!this.silentAudioElement) return;
      this.silentAudioElement.pause();
      this.silentAudioElement.src = "";
      this.silentAudioElement = null;
    }
    /**
     * Request a screen wake lock to prevent WiFi Power Save Mode (PSM).
     * PSM buffers incoming packets at the AP for 100-300ms, destroying sync.
     * Re-acquires automatically when the page becomes visible again.
     */
    async requestWakeLock() {
      if (this.wakeLock) return;
      try {
        if ("wakeLock" in navigator) {
          this.wakeLock = await navigator.wakeLock.request("screen");
          console.log("[AudioContextManager] Wake lock acquired");
          this.wakeLock.addEventListener("release", () => {
            console.log("[AudioContextManager] Wake lock released");
            this.wakeLock = null;
          });
          if (!this.hasVisibilityListener) {
            this.hasVisibilityListener = true;
            document.addEventListener("visibilitychange", () => {
              if (document.visibilityState === "visible" && !this.wakeLock) {
                this.requestWakeLock().catch(() => {
                });
              }
            });
          }
        }
      } catch (e) {
        console.warn("[AudioContextManager] Wake lock not available");
      }
    }
    /**
     * Get the current state of the AudioContext
     */
    getState() {
      var _a;
      return ((_a = this.audioContext) == null ? void 0 : _a.state) || null;
    }
    /**
     * Get the current time from the AudioContext
     */
    getCurrentTime() {
      var _a;
      return ((_a = this.audioContext) == null ? void 0 : _a.currentTime) || 0;
    }
    /**
     * Set a callback for state changes
     */
    setStateChangeCallback(callback) {
      this.stateChangeCallback = callback;
    }
    /**
     * Setup listener for AudioContext state changes
     */
    setupStateChangeListener() {
      if (!this.audioContext) return;
      this.audioContext.onstatechange = () => {
        var _a;
        const state2 = (_a = this.audioContext) == null ? void 0 : _a.state;
        console.log(`[AudioContextManager] State changed to: ${state2}`);
        if (state2 && this.stateChangeCallback) {
          this.stateChangeCallback(state2);
        }
        if (isAudioContextPaused(state2)) {
          console.warn(`[AudioContextManager] AudioContext ${state2} - user interaction required to resume`);
        }
      };
    }
    /**
     * Setup the audio graph: lowPassFilter → masterGain → destination
     */
    setupMasterGain() {
      if (!this.audioContext) return;
      this.lowPassFilterNode = this.audioContext.createBiquadFilter();
      this.lowPassFilterNode.type = "lowpass";
      this.lowPassFilterNode.frequency.value = LOW_PASS_CONSTANTS.MAX_FREQ;
      this.lowPassFilterNode.Q.value = 0.707;
      this.masterGainNode = this.audioContext.createGain();
      this.masterGainNode.gain.value = 1;
      this.lowPassFilterNode.connect(this.masterGainNode);
      this.masterGainNode.connect(this.audioContext.destination);
      const keepalive = this.audioContext.createOscillator();
      keepalive.frequency.value = 1;
      const keepaliveGain = this.audioContext.createGain();
      keepaliveGain.gain.value = 1e-4;
      keepalive.connect(keepaliveGain);
      keepaliveGain.connect(this.lowPassFilterNode);
      keepalive.start();
    }
    /**
     * Get the input node that audio sources should connect to.
     * This is the entry point of the effect chain (currently: lowPassFilter → masterGain → destination).
     */
    getInputNode() {
      if (!this.lowPassFilterNode) {
        this.getContext();
      }
      return this.lowPassFilterNode;
    }
    /**
     * Update the low-pass filter cutoff frequency
     */
    setLowPassFreq(freq, rampTime) {
      if (!this.lowPassFilterNode || !this.audioContext) return;
      const clampedFreq = Math.max(LOW_PASS_CONSTANTS.MIN_FREQ, Math.min(LOW_PASS_CONSTANTS.MAX_FREQ, freq));
      if (rampTime && rampTime > 0) {
        const now = this.audioContext.currentTime;
        this.lowPassFilterNode.frequency.cancelScheduledValues(now);
        this.lowPassFilterNode.frequency.setValueAtTime(this.lowPassFilterNode.frequency.value, now);
        this.lowPassFilterNode.frequency.exponentialRampToValueAtTime(clampedFreq, now + rampTime);
      } else {
        this.lowPassFilterNode.frequency.value = clampedFreq;
      }
    }
    /**
     * Update the master gain value
     */
    setMasterGain(value, rampTime) {
      if (!this.masterGainNode || !this.audioContext) return;
      const clampedValue = Math.max(0, Math.min(1, value));
      if (rampTime && rampTime > 0) {
        const now = this.audioContext.currentTime;
        this.masterGainNode.gain.cancelScheduledValues(now);
        this.masterGainNode.gain.setValueAtTime(this.masterGainNode.gain.value, now);
        this.masterGainNode.gain.linearRampToValueAtTime(clampedValue, now + rampTime);
      } else {
        this.masterGainNode.gain.value = clampedValue;
      }
    }
    /**
     * Convert a performance.now() timestamp to AudioContext.currentTime.
     * Uses getOutputTimestamp() to bridge the two clock domains, correcting
     * for drift between the system oscillator and audio hardware clock.
     */
    perfTimeToAudioTime(perfTimeMs) {
      const ctx = this.audioContext;
      if (!ctx) return perfTimeMs / 1e3;
      const ts = ctx.getOutputTimestamp();
      if (!ts.contextTime || !ts.performanceTime) {
        return ctx.currentTime + (perfTimeMs - performance.now()) / 1e3;
      }
      return ts.contextTime + (perfTimeMs - ts.performanceTime) / 1e3;
    }
    /**
     * Check if AudioContext is in a usable state
     */
    isReady() {
      var _a;
      return ((_a = this.audioContext) == null ? void 0 : _a.state) === "running";
    }
    /**
     * Decode audio data using the shared context
     */
    async decodeAudioData(arrayBuffer) {
      const ctx = this.getContext();
      return await ctx.decodeAudioData(arrayBuffer);
    }
    /**
     * Create a new buffer source node
     * Note: BufferSourceNodes are one-time use only
     */
    createBufferSource() {
      const ctx = this.getContext();
      return ctx.createBufferSource();
    }
  };
  __publicField(_AudioContextManager, "instance", null);
  var AudioContextManager = _AudioContextManager;
  var audioContextManager = AudioContextManager.getInstance();

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
  var calculateWaitTimeMilliseconds = (targetServerTime, clockOffset) => {
    const estimatedCurrentServerTime = epochNow() + clockOffset;
    return Math.max(0, targetServerTime - estimatedCurrentServerTime);
  };
  var handleNTPResponse = (response) => {
    const t3 = epochNow();
    const { t0, t1, t2, probeGroupId, probeGroupIndex } = response;
    const clockOffset = (t1 - t0 + (t2 - t3)) / 2;
    const roundTripDelay = t3 - t0 - (t2 - t1);
    const measurement = { t0, t1, t2, t3, roundTripDelay, clockOffset };
    return validateProbePair({ measurement, probeGroupId, probeGroupIndex });
  };

  // js/generated/audio-upstream.ts
  var AudioPlayerError = { NotInitialized: "NotInitialized" };
  var IS_DEMO_MODE = false;
  var extractFileNameFromUrl = (url) => url;
  var toast = { error: (message) => {
    throw Error(message);
  }, warning: () => {
  } };
  var sendWSRequest2 = ({ ws, request }) => ws.send(JSON.stringify(request));
  var getAudioPlayer = (state2) => {
    if (!state2.audioPlayer) {
      throw new Error(AudioPlayerError.NotInitialized);
    }
    return state2.audioPlayer;
  };
  var getSocket = (state2) => {
    if (!state2.socket) {
      throw new Error("Socket not initialized");
    }
    return {
      socket: state2.socket
    };
  };
  var MAX_TRUSTWORTHY_OUTPUT_LATENCY_MS = 100;
  var getFilteredOutputLatencyMs = () => {
    var _a;
    const rawMs = ((_a = audioContextManager.getContext().outputLatency) != null ? _a : 0) * 1e3;
    if (rawMs > MAX_TRUSTWORTHY_OUTPUT_LATENCY_MS) {
      console.warn(`[OutputLatency] ignoring ${rawMs.toFixed(0)}ms (likely Bluetooth garbage \u2014 use nudge)`);
      return 0;
    }
    return rawMs;
  };
  var getWaitTimeSeconds = (state2, targetServerTime) => {
    const effectiveOffset = state2.offsetEstimate + state2.nudgeOffsetMs;
    const waitTimeMilliseconds = calculateWaitTimeMilliseconds(targetServerTime, effectiveOffset);
    const outputLatencyMs = getFilteredOutputLatencyMs();
    return Math.max(0, (waitTimeMilliseconds - outputLatencyMs) / 1e3);
  };
  function upstreamActions(get, set) {
    return {
      schedulePlay: async (data) => {
        const state2 = get();
        if (state2.isInitingSystem) {
          console.log("Not playing audio, still loading");
          return;
        }
        let waitTimeSeconds = getWaitTimeSeconds(state2, data.targetServerTime);
        const _olMs = getFilteredOutputLatencyMs();
        const _effectiveOffset = state2.offsetEstimate + state2.nudgeOffsetMs;
        const _rawWaitMs = calculateWaitTimeMilliseconds(data.targetServerTime, _effectiveOffset);
        console.log(
          `[Schedule] wait=${waitTimeSeconds.toFixed(3)}s = max(0, (${_rawWaitMs.toFixed(1)}ms - ${_olMs.toFixed(1)}ms OL) / 1000) | offset=${state2.offsetEstimate.toFixed(1)}ms nudge=${state2.nudgeOffsetMs}ms`
        );
        if (waitTimeSeconds < 0.05) {
          const rawWaitMs = calculateWaitTimeMilliseconds(data.targetServerTime, state2.offsetEstimate);
          if (rawWaitMs < 50) {
            const missedByMs = 50 - rawWaitMs;
            const retryDelayMs = Math.min(missedByMs + 200, 2e3);
            const elapsedSinceTargetMs = epochNow() + state2.offsetEstimate - data.targetServerTime;
            const trackPositionAtRetry = data.trackTimeSeconds + (elapsedSinceTargetMs + retryDelayMs) / 1e3;
            console.warn(
              `[Schedule] Missed by ${missedByMs.toFixed(0)}ms, rescheduling in ${retryDelayMs.toFixed(0)}ms at track position ${trackPositionAtRetry.toFixed(2)}s`
            );
            if (data.audioSource !== state2.selectedAudioUrl) {
              set({ selectedAudioUrl: data.audioSource });
            }
            const audioIndex2 = state2.findAudioIndexByUrl(data.audioSource);
            if (audioIndex2 === null) return;
            const absoluteStartTime2 = audioContextManager.getContext().currentTime + retryDelayMs / 1e3;
            state2.playAudio({
              offset: trackPositionAtRetry,
              when: retryDelayMs / 1e3,
              absoluteStartTime: absoluteStartTime2,
              audioIndex: audioIndex2
            });
            return;
          }
          console.log(`[Nudge] Playing immediately (local compensation consumed scheduling buffer)`);
          waitTimeSeconds = 0;
        }
        console.log(`Playing track ${data.audioSource} at ${data.trackTimeSeconds} seconds in ${waitTimeSeconds}`);
        if (data.audioSource !== state2.selectedAudioUrl) {
          set({ selectedAudioUrl: data.audioSource });
        }
        const audioIndex = state2.findAudioIndexByUrl(data.audioSource);
        if (audioIndex === null) {
          if (state2.isPlaying) {
            state2.pauseAudio({ when: 0 });
          }
          console.warn(`Cannot play audio: Track not found in audioSources: ${data.audioSource}`);
          return;
        }
        const audioSourceState = state2.audioSources[audioIndex];
        if (IS_DEMO_MODE && (audioSourceState == null ? void 0 : audioSourceState.status) === "loaded" && audioSourceState.buffer) {
          const ctx = audioContextManager.getContext();
          const inputNode = audioContextManager.getInputNode();
          try {
            const { sourceNode } = getAudioPlayer(state2);
            sourceNode.onended = null;
            sourceNode.disconnect();
            sourceNode.stop();
          } catch (_) {
          }
          const startTime = ctx.currentTime + waitTimeSeconds;
          const newSourceNode = ctx.createBufferSource();
          newSourceNode.buffer = audioSourceState.buffer;
          newSourceNode.connect(inputNode);
          newSourceNode.start(startTime, data.trackTimeSeconds);
          console.log(
            `[DemoPlay] sync start: ctx=${ctx.currentTime.toFixed(3)} startTime=${startTime.toFixed(3)} offset=${data.trackTimeSeconds}`
          );
          set({
            audioPlayer: { ...get().audioPlayer, sourceNode: newSourceNode },
            isPlaying: true,
            selectedAudioUrl: data.audioSource,
            playbackStartTime: startTime,
            playbackOffset: data.trackTimeSeconds,
            duration: audioSourceState.buffer.duration
          });
          return;
        }
        if ((audioSourceState == null ? void 0 : audioSourceState.status) === "loading") {
          if (state2.isPlaying) {
            state2.pauseAudio({ when: 0 });
          }
          console.warn(`Cannot play audio: Track still loading: ${data.audioSource}`);
          toast.warning(`"${extractFileNameFromUrl(data.audioSource)}" not loaded yet...`, { id: "schedulePlay" });
          const { socket } = getSocket(state2);
          setTimeout(() => {
            sendWSRequest2({
              ws: socket,
              request: { type: ClientActionEnum.enum.SYNC }
            });
          }, 1e3);
          return;
        }
        const absoluteStartTime = audioContextManager.getContext().currentTime + waitTimeSeconds;
        state2.playAudio({
          offset: data.trackTimeSeconds,
          when: waitTimeSeconds,
          absoluteStartTime,
          audioIndex
        });
      },
      schedulePause: ({ targetServerTime }) => {
        const state2 = get();
        const waitTimeSeconds = getWaitTimeSeconds(state2, targetServerTime);
        console.log(`Pausing track in ${waitTimeSeconds}`);
        state2.pauseAudio({
          when: waitTimeSeconds
        });
      },
      playAudio: async (data) => {
        var _a, _b;
        const state2 = get();
        const { sourceNode, audioContext } = getAudioPlayer(state2);
        if (audioContext.state !== "running") {
          console.log("AudioContext still suspended, aborting play");
          toast.error("Audio context is suspended. Please try again.");
          return;
        }
        try {
          sourceNode.onended = null;
          sourceNode.disconnect();
          sourceNode.stop();
        } catch (_) {
        }
        const startTime = (_a = data.absoluteStartTime) != null ? _a : audioContext.currentTime + data.when;
        const audioIndex = (_b = data.audioIndex) != null ? _b : 0;
        const audioSourceState = state2.audioSources[audioIndex];
        if (!audioSourceState) {
          console.error(`No audio source at index ${audioIndex}`);
          return;
        }
        if (audioSourceState.status === "loading") {
          toast.error("Track is still loading, please wait...");
          return;
        }
        if (audioSourceState.status === "error") {
          toast.error(`Track failed to load: ${audioSourceState.error || "Unknown error"}`);
          return;
        }
        if (audioSourceState.status === "idle") {
          console.error("Track is in idle state");
          return;
        }
        const audioBuffer = audioSourceState.buffer;
        if (!audioBuffer) {
          console.error(`No audio buffer for url: ${audioSourceState.source.url}`);
          return;
        }
        if (data.offset >= audioBuffer.duration) {
          console.error(
            `Sync offset ${data.offset.toFixed(2)}s is beyond track duration ${audioBuffer.duration.toFixed(
              2
            )}s. Aborting playback.`
          );
          return;
        }
        const newSourceNode = audioContextManager.createBufferSource();
        newSourceNode.buffer = audioBuffer;
        newSourceNode.connect(audioContextManager.getInputNode());
        newSourceNode.onended = () => {
          const currentState = get();
          const { audioPlayer: currentPlayer, isPlaying: currentlyIsPlaying } = currentState;
          if (currentlyIsPlaying && (currentPlayer == null ? void 0 : currentPlayer.sourceNode) === newSourceNode) {
            const { audioContext: audioContext2 } = currentPlayer;
            const expectedEndTime = currentState.playbackStartTime + (currentState.duration - currentState.playbackOffset);
            const endedNaturally = Math.abs(audioContext2.currentTime - expectedEndTime) < 0.5;
            if (endedNaturally) {
              console.log("Track ended naturally, skipping to next via autoplay.");
              set({ currentTime: currentState.duration });
              currentState.skipToNextTrack(true);
            } else {
              console.log(
                "onended fired but not deemed a natural end (likely manual stop/skip). State should be handled elsewhere."
              );
            }
          } else {
            console.log("onended fired but player was already stopped/paused or source node changed.");
          }
        };
        newSourceNode.start(startTime, data.offset);
        console.log("Started playback at offset:", data.offset, "with delay:", data.when, "audio index:", audioIndex);
        set((state3) => ({
          ...state3,
          audioPlayer: {
            ...state3.audioPlayer,
            sourceNode: newSourceNode
          },
          isPlaying: true,
          playbackStartTime: startTime,
          playbackOffset: data.offset,
          duration: audioBuffer.duration
          // Set the duration
        }));
      },
      pauseAudio: (data) => {
        const state2 = get();
        const { sourceNode, audioContext } = getAudioPlayer(state2);
        const stopTime = audioContext.currentTime + data.when;
        sourceNode.stop(stopTime);
        const elapsedSinceStart = stopTime - state2.playbackStartTime;
        const currentTrackPosition = state2.playbackOffset + elapsedSinceStart;
        console.log("Stopping at:", data.when, "Current track position:", currentTrackPosition);
        set((state3) => ({
          ...state3,
          isPlaying: false,
          currentTime: currentTrackPosition
        }));
      },
      getCurrentTrackPosition: () => {
        const state2 = get();
        const { audioPlayer, isPlaying, currentTime, playbackStartTime, playbackOffset } = state2;
        if (!isPlaying || !audioPlayer) {
          return currentTime;
        }
        const { audioContext } = audioPlayer;
        const elapsedSinceStart = audioContext.currentTime - playbackStartTime;
        return Math.min(playbackOffset + elapsedSinceStart, state2.duration);
      }
    };
  }

  // js/audio/entry.ts
  var host = globalThis;
  var loadGeneration = 0;
  var revision = -1;
  var closed = false;
  var timer = 0;
  var samples = [];
  var buffer = null;
  var stopAt = null;
  var clockRtt = Infinity;
  var diagnostics = [];
  var post = (event, data = {}) => {
    if (!closed) LanAudioHost.post(JSON.stringify({ event, ...data }));
  };
  function diagnostic(kind, data = {}) {
    const entry = { kind, localMs: Number.isFinite(host.__nativeOffset) ? epochNow() : null, ...data };
    diagnostics.push(entry);
    if (diagnostics.length > 128) diagnostics.shift();
    post("diagnostic", { detail: entry });
  }
  var state = {
    isInitingSystem: true,
    isPlaying: false,
    currentTime: 0,
    playbackStartTime: 0,
    playbackOffset: 0,
    duration: 0,
    offsetEstimate: 0,
    nudgeOffsetMs: 0,
    selectedAudioUrl: null,
    audioSources: [],
    audioPlayer: null,
    socket: { send: () => post("resync") },
    findAudioIndexByUrl: (url) => state.audioSources.findIndex((s) => s.source.url === url) < 0 ? null : 0,
    skipToNextTrack: () => {
      state.isPlaying = false;
      state.currentTime = state.duration;
      post("ended");
    }
  };
  var actions = upstreamActions(() => state, (change) => {
    state = { ...state, ...typeof change === "function" ? change(state) : change };
  });
  Object.assign(state, actions);
  function synchronizeClock() {
    let best = Infinity, offset = 0;
    for (let i = 0; i < 16; i++) {
      const before = performance.now(), native = LanAudioHost.nowMs(), after = performance.now();
      if (after - before < best) {
        best = after - before;
        offset = native - (before + after) / 2;
      }
    }
    if (best > 5) throw Error("CLOCK");
    host.__nativeOffset = offset;
    clockRtt = best;
  }
  function stopNode() {
    var _a;
    const node = (_a = state.audioPlayer) == null ? void 0 : _a.sourceNode;
    if (node) {
      node.onended = null;
      try {
        node.stop();
      } catch (e) {
      }
      try {
        node.disconnect();
      } catch (e) {
      }
    }
    stopAt = null;
    state.isPlaying = false;
    if (state.audioPlayer) state.audioPlayer = { ...state.audioPlayer, sourceNode: audioContextManager.getContext().createBufferSource() };
  }
  function snapshot() {
    const ctx = audioContextManager.getContext();
    const playing = (state.isPlaying || stopAt !== null && ctx.currentTime < stopAt) && ctx.currentTime >= state.playbackStartTime && ctx.state === "running";
    const position = playing ? Math.min(state.playbackOffset + ctx.currentTime - state.playbackStartTime, state.duration) : state.currentTime;
    if (stopAt !== null && ctx.currentTime >= stopAt) stopAt = null;
    return {
      playing,
      positionSeconds: Math.max(0, position),
      durationSeconds: state.duration,
      contextState: ctx.state,
      sampleRate: ctx.sampleRate,
      outputLatencyMs: getFilteredOutputLatencyMs(),
      baseLatencyMs: (ctx.baseLatency || 0) * 1e3,
      clockRoundTripMs: clockRtt
    };
  }
  async function initialize() {
    synchronizeClock();
    const ctx = audioContextManager.getContext();
    await audioContextManager.resume();
    if (ctx.state !== "running") throw Error("AUDIO_SUSPENDED");
    state.audioPlayer = { sourceNode: ctx.createBufferSource(), audioContext: ctx };
    state.isInitingSystem = false;
    audioContextManager.setStateChangeCallback((s) => {
      post("context", { state: s });
      if (s !== "running") post("resync");
    });
    timer = window.setInterval(() => {
      try {
        post("state", snapshot());
      } catch (e) {
      }
    }, 200);
    return snapshot();
  }
  async function load(d) {
    const generation = ++loadGeneration;
    stopNode();
    state.audioSources = [];
    buffer = null;
    state.selectedAudioUrl = d.key;
    state.currentTime = d.position || 0;
    const response = await fetch(d.url, { cache: "no-store", credentials: "omit" });
    if (!response.ok) throw Error("SOURCE");
    const bytes = await response.arrayBuffer();
    if (bytes.byteLength > 200 * 1024 * 1024) throw Error("MEMORY_LIMIT");
    if (generation !== loadGeneration) throw Error("STALE");
    const decoded = await audioContextManager.decodeAudioData(bytes);
    if (generation !== loadGeneration || closed) throw Error("STALE");
    if (decoded.numberOfChannels > 2 || decoded.length * decoded.numberOfChannels * 4 > 512 * 1024 * 1024) throw Error("MEMORY_LIMIT");
    if (d.expectedDurationMs > 0 && Math.abs(decoded.duration * 1e3 - d.expectedDurationMs) > Math.max(1e3, d.expectedDurationMs * 0.01)) throw Error("AUDIO_DURATION");
    buffer = decoded;
    state.duration = decoded.duration;
    state.audioSources = [{ source: { url: d.key }, status: "loaded", buffer: decoded }];
    diagnostic("decoded", { key: d.key, duration: decoded.duration, sampleRate: decoded.sampleRate, channels: decoded.numberOfChannels });
    return { key: d.key, durationSeconds: decoded.duration };
  }
  function schedule(d) {
    if (d.revision < revision) return { accepted: false };
    if (!buffer || state.selectedAudioUrl !== d.key) throw Error("LOADING");
    synchronizeClock();
    if (!audioContextManager.isReady()) throw Error("AUDIO_SUSPENDED");
    revision = d.revision;
    state.offsetEstimate = d.offsetMs;
    state.nudgeOffsetMs = d.nudgeMs;
    if (d.type === "PLAY") {
      const position = Math.max(0, d.positionSeconds);
      if (position >= buffer.duration) throw Error("POSITION");
      stopAt = null;
      actions.schedulePlay({ audioSource: d.key, trackTimeSeconds: position, targetServerTime: d.serverTimeMs });
    } else {
      if (!state.isPlaying && stopAt === null) {
        state.currentTime = d.positionSeconds;
        return { accepted: true, ...snapshot() };
      }
      const ctx = audioContextManager.getContext();
      stopAt = ctx.currentTime + Math.max(0, Math.max(0, d.serverTimeMs - epochNow() - d.offsetMs - d.nudgeMs) - getFilteredOutputLatencyMs()) / 1e3;
      actions.schedulePause({ targetServerTime: d.serverTimeMs });
    }
    diagnostic("scheduled", { type: d.type, revision, targetServerMs: d.serverTimeMs, submittedServerMs: epochNow() + d.offsetMs, contextStart: state.playbackStartTime, position: state.playbackOffset, ...snapshot() });
    return { accepted: true, ...snapshot() };
  }
  async function command(op, d) {
    switch (op) {
      case "initialize":
        return initialize();
      case "clock":
        synchronizeClock();
        return snapshot();
      case "load":
        return load(d);
      case "schedule":
        return schedule(d);
      case "pause":
        state.currentTime = snapshot().positionSeconds;
        stopNode();
        loadGeneration++;
        return snapshot();
      case "position":
        stopNode();
        state.currentTime = Math.max(0, d.positionSeconds);
        return snapshot();
      case "nudge":
        state.nudgeOffsetMs = d.value;
        if (snapshot().playing) actions.playAudio({ offset: Math.max(0, Math.min(snapshot().positionSeconds + d.delta / 1e3, state.duration - 1e-3)), when: 0, audioIndex: 0 });
        return snapshot();
      case "resetNtp":
        samples = [];
        resetProbeState();
        return null;
      case "ntp": {
        host.__ntpReceiveAt = d.receivedAt;
        let sample;
        try {
          sample = handleNTPResponse(d.message);
        } finally {
          delete host.__ntpReceiveAt;
        }
        if (!sample || sample.roundTripDelay < 0) return null;
        if (samples.length === 16) samples.shift();
        samples.push(sample);
        const e = calculateOffsetEstimate(samples);
        return { offset: e.averageOffset, rtt: e.averageRoundTrip, synced: samples.length === 16 };
      }
      case "diagnostics":
        return diagnostics;
      case "dispose":
        loadGeneration++;
        stopNode();
        clearInterval(timer);
        buffer = null;
        state.audioSources = [];
        await audioContextManager.getContext().close();
        return null;
      default:
        throw Error("UNKNOWN");
    }
  }
  async function dispatch(message) {
    try {
      const result = await command(message.op, message.data || {});
      post("reply", { id: message.id, result });
    } catch (error) {
      post("reply", { id: message.id, error: String(error.message) });
    }
  }
  post("ready");
  return __toCommonJS(entry_exports);
})();
