import { LOW_PASS_CONSTANTS } from "@beatsync/shared";

/** iOS 18+ uses a non-standard "interrupted" state (e.g. phone call, Siri) */
export function isAudioContextPaused(state: AudioContextState | string | undefined | null): boolean {
  return state === "suspended" || state === "interrupted";
}

// Minimal silent WAV (1 sample, 44.1kHz, 16-bit mono, 46 bytes).
// Used on iOS < 16.4 to force WebAudio onto the media channel so audio
// plays through speakers even when the hardware mute switch is on.
// WAV is used instead of MP3 because some older iOS versions reject
// MP3 data URLs with NotSupportedError.
const SILENCE_DATA_URL = "data:audio/wav;base64,UklGRiYAAABXQVZFZm10IBAAAAABAAEARKwAAIhYAQACABAAZGF0YQIAAAAAAA==";

/**
 * Singleton AudioContext Manager
 *
 * Manages a single AudioContext instance for the entire application lifecycle.
 * This prevents AudioContext limit errors (especially on iOS which has a limit of 6)
 * and improves performance by avoiding repeated context creation.
 */
class AudioContextManager {
  private static instance: AudioContextManager | null = null;
  private audioContext: AudioContext | null = null;
  private masterGainNode: GainNode | null = null;
  private lowPassFilterNode: BiquadFilterNode | null = null;
  private stateChangeCallback: ((state: AudioContextState) => void) | null = null;
  private wakeLock: WakeLockSentinel | null = null;
  private hasVisibilityListener = false;
  private silentAudioElement: HTMLAudioElement | null = null;
  private hasRegisteredGestureListeners = false;

  private constructor() {
    // Private constructor to enforce singleton pattern
  }

  /**
   * Get the singleton instance of AudioContextManager
   */
  static getInstance(): AudioContextManager {
    if (!AudioContextManager.instance) {
      AudioContextManager.instance = new AudioContextManager();
    }
    return AudioContextManager.instance;
  }

  private isIOS(): boolean {
    const ua = navigator.userAgent.toLowerCase();
    return (
      ua.includes("iphone") ||
      ua.includes("ipad") ||
      ua.includes("ipod") ||
      // Newer iPads report as Mac but have touch
      (ua.includes("mac os x") && navigator.maxTouchPoints > 0)
    );
  }

  /**
   * Get or create the AudioContext
   * Will reuse existing context unless it's closed
   */
  getContext(): AudioContext {
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
  getMasterGain(): GainNode {
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
  async resume(): Promise<void> {
    const state = this.audioContext?.state;
    // Handle both "suspended" and the non-standard iOS "interrupted" state
    if (isAudioContextPaused(state)) {
      try {
        await this.audioContext!.resume();
        console.log(`[AudioContextManager] AudioContext resumed from ${state}`);
      } catch (error) {
        console.error("[AudioContextManager] Failed to resume AudioContext:", error);
        throw error;
      }
    }

    // Request wake lock to prevent device sleep and WiFi power-save mode
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
  private registerSilentAudioBypass(): void {
    if (this.hasRegisteredGestureListeners) return;
    // audioSession API (iOS 16.4+) handles mute-switch natively — no hack needed
    // @ts-expect-error audioSession only exists on iOS Safari 16.4+
    if (navigator.audioSession) return;
    // Only needed on iOS — Android/desktop don't have the ringer/media channel split
    if (!this.isIOS()) return;
    this.hasRegisteredGestureListeners = true;

    // Create the element once — reuse across gesture attempts
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
      if (this.silentAudioElement) return; // Already playing

      const p = audio.play();
      if (p) {
        p.then(() => {
          this.silentAudioElement = audio;
          console.log("[AudioContextManager] Silent audio playing — mute-switch bypass active");
          // Stop listening once successful
          for (const evt of gestureEvents) {
            window.removeEventListener(evt, tryPlay, true);
          }
        }).catch(() => {
          // Will retry on next gesture
        });
      }
    };

    // Listen in capture phase (like iosunmute) — fires before React's synthetic events
    for (const evt of gestureEvents) {
      window.addEventListener(evt, tryPlay, { capture: true, passive: true });
    }

    console.log("[AudioContextManager] Registered silent audio bypass gesture listeners (legacy iOS)");
  }

  private destroySilentAudioBypass(): void {
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
  private async requestWakeLock(): Promise<void> {
    if (this.wakeLock) return; // Already held

    try {
      if ("wakeLock" in navigator) {
        this.wakeLock = await navigator.wakeLock.request("screen");
        console.log("[AudioContextManager] Wake lock acquired");

        // Re-acquire on visibility change (lock is released when page is hidden)
        this.wakeLock.addEventListener("release", () => {
          console.log("[AudioContextManager] Wake lock released");
          this.wakeLock = null;
        });

        // Register visibility listener once to re-acquire wake lock after tab becomes visible
        if (!this.hasVisibilityListener) {
          this.hasVisibilityListener = true;
          document.addEventListener("visibilitychange", () => {
            if (document.visibilityState === "visible" && !this.wakeLock) {
              this.requestWakeLock().catch(() => {
                // Silently fail — wake lock is best-effort
              });
            }
          });
        }
      }
    } catch {
      // Wake lock request can fail (e.g., low battery, unsupported browser)
      console.warn("[AudioContextManager] Wake lock not available");
    }
  }

  /**
   * Get the current state of the AudioContext
   */
  getState(): AudioContextState | null {
    return this.audioContext?.state || null;
  }

  /**
   * Get the current time from the AudioContext
   */
  getCurrentTime(): number {
    return this.audioContext?.currentTime || 0;
  }

  /**
   * Set a callback for state changes
   */
  setStateChangeCallback(callback: (state: AudioContextState) => void): void {
    this.stateChangeCallback = callback;
  }

  /**
   * Setup listener for AudioContext state changes
   */
  private setupStateChangeListener(): void {
    if (!this.audioContext) return;

    this.audioContext.onstatechange = () => {
      const state = this.audioContext?.state;
      console.log(`[AudioContextManager] State changed to: ${state}`);

      if (state && this.stateChangeCallback) {
        this.stateChangeCallback(state);
      }

      // Handle iOS suspension and the non-standard "interrupted" state (iOS 18+)
      if (isAudioContextPaused(state)) {
        console.warn(`[AudioContextManager] AudioContext ${state} - user interaction required to resume`);
      }
    };
  }

  /**
   * Setup the audio graph: lowPassFilter → masterGain → destination
   */
  private setupMasterGain(): void {
    if (!this.audioContext) return;

    // Chain: source → lowPassFilter → masterGain → destination
    this.lowPassFilterNode = this.audioContext.createBiquadFilter();
    this.lowPassFilterNode.type = "lowpass";
    this.lowPassFilterNode.frequency.value = LOW_PASS_CONSTANTS.MAX_FREQ; // Bypassed by default
    this.lowPassFilterNode.Q.value = 0.707; // Butterworth (no resonance peak)

    this.masterGainNode = this.audioContext.createGain();
    this.masterGainNode.gain.value = 1.0;

    this.lowPassFilterNode.connect(this.masterGainNode);
    this.masterGainNode.connect(this.audioContext.destination);

    // Bluetooth keepalive: prevents A2DP buffer from resettling between pause/play
    // cycles. Uses inaudibly quiet signal (-80dB) instead of gain=0, because browsers
    // optimize away gain=0 subgraphs and Bluetooth stacks treat that as silence.
    const keepalive = this.audioContext.createOscillator();
    keepalive.frequency.value = 1; // 1Hz — below audible range
    const keepaliveGain = this.audioContext.createGain();
    keepaliveGain.gain.value = 0.0001; // -80dB, inaudible but not optimized away
    keepalive.connect(keepaliveGain);
    keepaliveGain.connect(this.lowPassFilterNode);
    keepalive.start();
  }

  /**
   * Get the input node that audio sources should connect to.
   * This is the entry point of the effect chain (currently: lowPassFilter → masterGain → destination).
   */
  getInputNode(): AudioNode {
    if (!this.lowPassFilterNode) {
      // Ensure context and nodes are initialized
      this.getContext();
    }
    return this.lowPassFilterNode!;
  }

  /**
   * Update the low-pass filter cutoff frequency
   */
  setLowPassFreq(freq: number, rampTime?: number): void {
    if (!this.lowPassFilterNode || !this.audioContext) return;

    const clampedFreq = Math.max(LOW_PASS_CONSTANTS.MIN_FREQ, Math.min(LOW_PASS_CONSTANTS.MAX_FREQ, freq));

    if (rampTime && rampTime > 0) {
      const now = this.audioContext.currentTime;
      this.lowPassFilterNode.frequency.cancelScheduledValues(now);
      this.lowPassFilterNode.frequency.setValueAtTime(this.lowPassFilterNode.frequency.value, now);
      // Use exponential ramp for frequency — perceptually linear
      this.lowPassFilterNode.frequency.exponentialRampToValueAtTime(clampedFreq, now + rampTime);
    } else {
      this.lowPassFilterNode.frequency.value = clampedFreq;
    }
  }

  /**
   * Update the master gain value
   */
  setMasterGain(value: number, rampTime?: number): void {
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
  perfTimeToAudioTime(perfTimeMs: number): number {
    const ctx = this.audioContext;
    if (!ctx) return perfTimeMs / 1000;

    const ts = ctx.getOutputTimestamp();
    if (!ts.contextTime || !ts.performanceTime) {
      // Fallback: assume clocks are aligned
      return ctx.currentTime + (perfTimeMs - performance.now()) / 1000;
    }

    // Linear mapping: audioTime = contextTime + (perfTime - performanceTime) / 1000
    return ts.contextTime + (perfTimeMs - ts.performanceTime) / 1000;
  }

  /**
   * Check if AudioContext is in a usable state
   */
  isReady(): boolean {
    return this.audioContext?.state === "running";
  }

  /**
   * Decode audio data using the shared context
   */
  async decodeAudioData(arrayBuffer: ArrayBuffer): Promise<AudioBuffer> {
    const ctx = this.getContext();
    return await ctx.decodeAudioData(arrayBuffer);
  }

  /**
   * Create a new buffer source node
   * Note: BufferSourceNodes are one-time use only
   */
  createBufferSource(): AudioBufferSourceNode {
    const ctx = this.getContext();
    return ctx.createBufferSource();
  }
}

export const audioContextManager = AudioContextManager.getInstance();
export { AudioContextManager };
