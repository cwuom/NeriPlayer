/* eslint-disable @typescript-eslint/no-unused-vars */
import { audioContextManager, isAudioContextPaused } from "@/lib/audioContextManager";
import { getClientId } from "@/lib/clientId";
import { getKickBuffer } from "@/components/dashboard/Metronome";
import { IS_DEMO_MODE } from "@/lib/demo";
import { getApiUrl } from "@/lib/urls";
import { extractFileNameFromUrl } from "@/lib/utils";
import {
  calculateOffsetEstimate,
  calculateWaitTimeMilliseconds,
  getProbeStats,
  NTPMeasurement,
  resetProbeState,
  sendProbePair as sendProbePairWS,
} from "@/utils/ntp";
import { sendWSRequest } from "@/utils/ws";
import {
  AudioSourceSchema,
  AudioSourceType,
  ClientActionEnum,
  ClientDataType,
  GlobalVolumeConfigType,
  LowPassConfigType,
  MetronomeConfigType,
  GRID,
  LoadAudioSourceType,
  LOW_PASS_CONSTANTS,
  NTP_CONSTANTS,
  PlaybackControlsPermissionsEnum,
  PlaybackControlsPermissionsType,
  PositionType,
  SearchResponseType,
  SetAudioSourcesType,
  SpatialConfigType,
  epochNow,
} from "@beatsync/shared";
import { Mutex } from "async-mutex";
import { toast } from "sonner";
import { create } from "zustand";

export const MAX_NTP_MEASUREMENTS = NTP_CONSTANTS.MAX_MEASUREMENTS;

// LRU Cache configuration for audio buffers
const MAX_CACHED_BUFFERS = 3;

// https://webaudioapi.com/book/Web_Audio_API_Boris_Smus_html/ch02.html

interface AudioPlayerState {
  audioContext: AudioContext;
  sourceNode: AudioBufferSourceNode;
  gainNode: GainNode;
}

enum AudioPlayerError {
  NotInitialized = "NOT_INITIALIZED",
}

import { z } from "zod";

// Discriminated union for AudioSourceState using zod
export const AudioSourceStateSchema = z.discriminatedUnion("status", [
  z.object({
    source: AudioSourceSchema,
    status: z.literal("idle"),
  }),
  z.object({
    source: AudioSourceSchema,
    status: z.literal("loading"),
    /** Bytes downloaded so far */
    loadedBytes: z.number().optional(),
    /** Total bytes (from Content-Length) */
    totalBytes: z.number().optional(),
  }),
  z.object({
    source: AudioSourceSchema,
    status: z.literal("loaded"),
    buffer: z.custom<AudioBuffer>(),
  }),
  z.object({
    source: AudioSourceSchema,
    status: z.literal("error"),
    error: z.string(),
  }),
]);
export type AudioSourceState = z.infer<typeof AudioSourceStateSchema>;

// Interface for just the state values (without methods)
interface GlobalStateValues {
  // Audio Sources
  audioSources: AudioSourceState[]; // Playlist with loading states
  bufferAccessQueue: string[]; // Track URL access order for LRU eviction
  isInitingSystem: boolean;
  hasUserStartedSystem: boolean; // Track if user has clicked "Start System" at least once
  selectedAudioUrl: string;

  // Websocket
  socket: WebSocket | null;
  lastMessageReceivedTime: number | null;

  // Spatial audio
  spatialConfig?: SpatialConfigType;
  listeningSourcePosition: PositionType;
  isDraggingListeningSource: boolean;
  isSpatialAudioEnabled: boolean;

  // Connected clients
  connectedClients: ClientDataType[];
  currentUser: ClientDataType | null;
  demoUserCount: number;
  demoAudioReadyCount: number;

  // NTP
  syncMeasurements: NTPMeasurement[];
  offsetEstimate: number;
  roundTripEstimate: number;
  isSynced: boolean;
  nudgeOffsetMs: number;
  probeStats: { totalSent: number; pureCount: number; impureCount: number };

  // Audio Player
  audioPlayer: AudioPlayerState | null;
  isPlaying: boolean;
  currentTime: number;
  duration: number;
  volume: number;
  globalVolume: number; // Master volume (0-1)

  // Tracking properties
  playbackStartTime: number;
  playbackOffset: number;

  // Shuffle state
  isShuffled: boolean;
  reconnectionInfo: {
    isReconnecting: boolean;
    currentAttempt: number;
    maxAttempts: number;
  };

  // Playback controls
  playbackControlsPermissions: PlaybackControlsPermissionsType;

  // Search results
  searchResults: SearchResponseType | null;
  isSearching: boolean;
  isLoadingMoreResults: boolean;
  searchQuery: string;
  searchOffset: number;
  hasMoreResults: boolean;

  // Stream job tracking
  activeStreamJobs: number;

  // Metronome
  isMetronomeActive: boolean;

  // Low-pass filter
  lowPassFreq: number; // 20-20000 Hz (20000 = bypassed)

  // Whether nudge has been restored from server this connection (prevents re-restore on subsequent CLIENT_CHANGE)
  didRestoreNudge: boolean;
}

interface GlobalState extends GlobalStateValues {
  // Methods
  getAudioDuration: ({ url }: { url: string }) => number;
  getSelectedTrack: () => AudioSourceState | null;
  handleSetAudioSources: (data: SetAudioSourcesType) => void;

  setIsInitingSystem: (isIniting: boolean) => void;
  reorderClient: (clientId: string) => void;
  setAdminStatus: (clientId: string, isAdmin: boolean) => void;
  changeAudioSource: (url: string) => boolean;
  findAudioIndexByUrl: (url: string) => number | null;
  schedulePlay: (data: { trackTimeSeconds: number; targetServerTime: number; audioSource: string }) => void;
  schedulePause: (data: { targetServerTime: number }) => void;
  setSocket: (socket: WebSocket) => void;
  broadcastPlay: (trackTimeSeconds?: number) => void;
  broadcastPause: () => void;
  startSpatialAudio: () => void;
  sendStopSpatialAudio: () => void;
  sendChatMessage: (text: string) => void;
  setSpatialConfig: (config: SpatialConfigType) => void;
  updateListeningSource: (position: PositionType) => void;
  setListeningSourcePosition: (position: PositionType) => void;
  setIsDraggingListeningSource: (isDragging: boolean) => void;
  setIsSpatialAudioEnabled: (isEnabled: boolean) => void;
  processStopSpatialAudio: () => void;
  setConnectedClients: (clients: ClientDataType[]) => void;
  sendProbePair: () => void;
  nudge: (data: { amountMs: number }) => void;
  resetNTPConfig: () => void;
  addProbePairResult: (result: NTPMeasurement) => void;
  onConnectionReset: () => void;
  playAudio: (data: { offset: number; when: number; absoluteStartTime?: number; audioIndex?: number }) => void;
  processSpatialConfig: (config: SpatialConfigType) => void;
  pauseAudio: (data: { when: number }) => void;
  getCurrentTrackPosition: () => number;
  toggleShuffle: () => void;
  skipToNextTrack: (isAutoplay?: boolean) => void;
  skipToPreviousTrack: () => void;
  getCurrentGainValue: () => number;
  getCurrentSpatialGainValue: () => number;
  setGlobalVolume: (volume: number) => void;
  sendGlobalVolumeUpdate: (volume: number) => void;
  processGlobalVolumeConfig: (config: GlobalVolumeConfigType) => void;
  applyFinalGain: (rampTime?: number) => void;
  resetStore: () => void;
  setReconnectionInfo: (info: { isReconnecting: boolean; currentAttempt: number; maxAttempts: number }) => void;
  setPlaybackControlsPermissions: (permissions: PlaybackControlsPermissionsType) => void;

  // Search methods
  setSearchResults: (results: SearchResponseType | null, append?: boolean) => void;
  setIsSearching: (isSearching: boolean) => void;
  setIsLoadingMoreResults: (isLoading: boolean) => void;
  setSearchQuery: (query: string) => void;
  setSearchOffset: (offset: number) => void;
  setHasMoreResults: (hasMore: boolean) => void;
  clearSearchResults: () => void;
  loadMoreSearchResults: () => void;

  // Stream job methods
  setActiveStreamJobs: (count: number) => void;

  // Metronome methods
  toggleMetronome: () => void;
  processMetronomeConfig: (config: MetronomeConfigType) => void;

  // Low-pass filter methods
  sendLowPassFreqUpdate: (freq: number) => void;
  processLowPassConfig: (config: LowPassConfigType) => void;

  // Audio source methods
  handleLoadAudioSource: (sources: LoadAudioSourceType) => void;
  broadcastReorder: (urls: AudioSourceType[]) => void;
}

// Define initial state values
const initialState: GlobalStateValues = {
  // Audio Sources
  audioSources: [],
  bufferAccessQueue: [],

  // Audio playback state
  isPlaying: false,
  currentTime: 0,
  playbackStartTime: 0,
  playbackOffset: 0,
  selectedAudioUrl: "",

  // Spatial audio
  isShuffled: false,
  isSpatialAudioEnabled: false,
  isDraggingListeningSource: false,
  listeningSourcePosition: { x: GRID.SIZE / 2, y: GRID.SIZE / 2 },
  spatialConfig: undefined,

  // Network state
  socket: null,
  lastMessageReceivedTime: null,
  connectedClients: [],
  currentUser: null,
  demoUserCount: 0,
  demoAudioReadyCount: 0,

  // NTP state
  syncMeasurements: [],
  offsetEstimate: 0,
  roundTripEstimate: 0,
  isSynced: false,
  nudgeOffsetMs: 0,
  probeStats: { totalSent: 0, pureCount: 0, impureCount: 0 },

  // Loading state
  isInitingSystem: true,
  hasUserStartedSystem: false,

  // These need to be initialized to prevent type errors
  audioPlayer: null,
  duration: 0,
  volume: 0.5,
  globalVolume: 1.0, // Default 100%
  reconnectionInfo: {
    isReconnecting: false,
    currentAttempt: 0,
    maxAttempts: 0,
  },

  // Playback controls
  playbackControlsPermissions: PlaybackControlsPermissionsEnum.enum.ADMIN_ONLY,

  // Search results
  searchResults: null,
  isSearching: false,
  isLoadingMoreResults: false,
  searchQuery: "",
  searchOffset: 0,
  hasMoreResults: false,

  // Stream job tracking
  activeStreamJobs: 0,

  // Metronome
  isMetronomeActive: false,

  // Low-pass filter
  lowPassFreq: LOW_PASS_CONSTANTS.MAX_FREQ,

  didRestoreNudge: false,
};

const getAudioPlayer = (state: GlobalState) => {
  if (!state.audioPlayer) {
    throw new Error(AudioPlayerError.NotInitialized);
  }
  return state.audioPlayer;
};

const getSocket = (state: GlobalState) => {
  if (!state.socket) {
    throw new Error("Socket not initialized");
  }
  return {
    socket: state.socket,
  };
};

export const MAX_TRUSTWORTHY_OUTPUT_LATENCY_MS = 100;

/**
 * Read the browser's outputLatency, filtering out garbage values (e.g. Bluetooth reporting 648ms).
 * Wired speakers (~24ms) are trustworthy. Bluetooth users should use manual nudge.
 */
export const getFilteredOutputLatencyMs = (): number => {
  const rawMs = (audioContextManager.getContext().outputLatency ?? 0) * 1000;
  if (rawMs > MAX_TRUSTWORTHY_OUTPUT_LATENCY_MS) {
    console.warn(`[OutputLatency] ignoring ${rawMs.toFixed(0)}ms (likely Bluetooth garbage — use nudge)`);
    return 0;
  }
  return rawMs;
};

const getWaitTimeSeconds = (state: GlobalState, targetServerTime: number) => {
  const effectiveOffset = state.offsetEstimate + state.nudgeOffsetMs;
  const waitTimeMilliseconds = calculateWaitTimeMilliseconds(targetServerTime, effectiveOffset);
  const outputLatencyMs = getFilteredOutputLatencyMs();
  return Math.max(0, (waitTimeMilliseconds - outputLatencyMs) / 1000);
};

const resolveAudioUrl = (url: string): string => (url.startsWith("/") ? `${getApiUrl()}${url}` : url);

const downloadBufferFromURL = async (data: { url: string; onProgress?: (loaded: number, total: number) => void }) => {
  const response = await fetch(resolveAudioUrl(data.url));
  const contentLength = Number(response.headers.get("content-length") ?? 0);

  let arrayBuffer: ArrayBuffer;
  if (contentLength > 0 && response.body && data.onProgress) {
    // Stream with progress tracking
    const reader = response.body.getReader();
    const chunks: Uint8Array[] = [];
    let loaded = 0;
    data.onProgress(0, contentLength);

    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      chunks.push(value);
      loaded += value.byteLength;
      data.onProgress(loaded, contentLength);
    }

    // Combine chunks into single ArrayBuffer
    const combined = new Uint8Array(loaded);
    let offset = 0;
    for (const chunk of chunks) {
      combined.set(chunk, offset);
      offset += chunk.byteLength;
    }
    arrayBuffer = combined.buffer;
  } else {
    arrayBuffer = await response.arrayBuffer();
  }

  const audioBuffer = await audioContextManager.decodeAudioData(arrayBuffer);
  return { audioBuffer };
};

const initializationMutex = new Mutex();

// Selector for canMutate
export const useCanMutate = () => {
  const currentUser = useGlobalStore((state) => state.currentUser);
  const playbackControlsPermissions = useGlobalStore((state) => state.playbackControlsPermissions);

  const isAdmin = currentUser?.isAdmin || false;
  const isEveryoneMode = playbackControlsPermissions === PlaybackControlsPermissionsEnum.enum.EVERYONE;
  return isAdmin || isEveryoneMode;
};

export const useGlobalStore = create<GlobalState>((set, get) => {
  // Helper function to manage LRU cache
  const addURLToLRU = (url: string) => {
    const state = get();
    const queue = [...state.bufferAccessQueue];

    // Remove URL if it exists (to move it to front)
    const existingIndex = queue.indexOf(url);
    if (existingIndex > -1) {
      queue.splice(existingIndex, 1);
    }

    // Add URL to front
    queue.unshift(url);

    // Determine which URLs to evict (if any) — skip eviction in demo mode
    const urlsToEvict: string[] = [];
    while (!IS_DEMO_MODE && queue.length > MAX_CACHED_BUFFERS) {
      const urlToEvict = queue.pop();
      if (urlToEvict) {
        // Don't evict the currently selected/playing track
        if (urlToEvict !== state.selectedAudioUrl) {
          urlsToEvict.push(urlToEvict);
          console.log(`[LRU Cache] Evicting ${urlToEvict}`);
        } else {
          // If we tried to evict the playing track, keep it at the end
          queue.push(urlToEvict);
          break;
        }
      }
    }

    // Apply all changes in one atomic update
    set((currentState) => ({
      bufferAccessQueue: queue,
      audioSources: currentState.audioSources.map((as) =>
        urlsToEvict.includes(as.source.url) ? { ...as, status: "idle", buffer: undefined } : as
      ),
    }));
  };

  // Load audio buffer for a source
  const loadAudioSource = async (url: string) => {
    try {
      const state = get();
      const existing = state.audioSources.find((as) => as.source.url === url);

      // Skip if already loaded or in-flight
      if (existing && existing.status === "loading") {
        return;
      }
      if (existing && existing.status === "loaded") {
        // Update LRU queue when accessing an already loaded buffer
        addURLToLRU(url);

        const { socket } = getSocket(state);
        sendWSRequest({
          ws: socket,
          request: {
            type: ClientActionEnum.enum.AUDIO_SOURCE_LOADED,
            source: { url },
          },
        });
        return;
      }

      // Mark as loading
      set((currentState) => ({
        audioSources: currentState.audioSources.map((as) =>
          as.source.url === url ? { ...as, status: "loading" } : as
        ),
      }));

      let lastReportedBytes = 0;
      const PROGRESS_THRESHOLD = 100 * 1024; // Report every ~100KB
      const { audioBuffer } = await downloadBufferFromURL({
        url,
        onProgress: (loaded, total) => {
          if (loaded - lastReportedBytes < PROGRESS_THRESHOLD && loaded < total) return;
          lastReportedBytes = loaded;
          set((currentState) => ({
            audioSources: currentState.audioSources.map((as) =>
              as.source.url === url && as.status === "loading" ? { ...as, loadedBytes: loaded, totalBytes: total } : as
            ),
          }));
        },
      });

      // Update the source with loaded buffer
      set((currentState) => ({
        audioSources: currentState.audioSources.map((as) =>
          as.source.url === url ? { ...as, status: "loaded", buffer: audioBuffer } : as
        ),
      }));

      // Update LRU queue after successfully loading a new buffer
      addURLToLRU(url);

      // Send message to server that the source is loaded (re-read socket in case of reconnect during fetch)
      const { socket } = getSocket(get());
      sendWSRequest({
        ws: socket,
        request: {
          type: ClientActionEnum.enum.AUDIO_SOURCE_LOADED,
          source: { url },
        },
      });
    } catch (error) {
      console.error(`Failed to load audio source ${url}:`, error);
      // Update the source with error status
      set((currentState) => ({
        audioSources: currentState.audioSources.map((as) =>
          as.source.url === url ? { ...as, status: "error", error: String(error) } : as
        ),
      }));
    }
  };

  // Eagerly load idle audio sources (skips loading/loaded/error).
  // In demo mode: no cap (load everything). In prod: capped to MAX_CACHED_BUFFERS.
  const eagerLoadIdleSources = ({ skip }: { skip?: string } = {}) => {
    const state = get();
    let loaded = skip ? 1 : 0;
    for (const as of state.audioSources) {
      if (!IS_DEMO_MODE && loaded >= MAX_CACHED_BUFFERS) break;
      if (as.source.url === skip) continue;
      if (as.status === "idle") {
        loadAudioSource(as.source.url);
        loaded++;
      }
    }
  };

  // Function to initialize or reinitialize audio system
  // If concurrent initialization is detected, only first one will continue
  const initializeAudioExclusively = async () => {
    if (initializationMutex.isLocked()) {
      console.log("Audio initialization already in progress, skipping");
      return;
    }

    await initializationMutex.runExclusive(async () => {
      await _initializeAudio();
    });
  };

  /** Stop playback and reset to "Start System" if audio is actively playing. */
  const stopAndResetIfPlaying = (reason: string) => {
    const currentState = get();
    if (!currentState.isPlaying || !currentState.audioPlayer) return;
    try {
      currentState.audioPlayer.sourceNode.stop();
    } catch {
      // Ignore if already stopped
    }
    console.log(reason);
    set({ isInitingSystem: true, hasUserStartedSystem: false });
  };

  const _initializeAudio = async () => {
    console.log("initializeAudio()");

    // Get singleton audio context and gain node
    const audioContext = audioContextManager.getContext();
    const gainNode = audioContextManager.getMasterGain();

    // Set up state change callback for iOS suspensions
    audioContextManager.setStateChangeCallback((state) => {
      console.log(`AudioContext state changed to: ${state}`);

      if (isAudioContextPaused(state)) {
        // Only reset the init UI if audio was actively playing.
        // iOS frequently toggles AudioContext between running/suspended due to the
        // Bluetooth keepalive oscillator — this is harmless when idle and should NOT
        // force users back to the calibration screen.
        stopAndResetIfPlaying(`AudioContext ${state} by iOS during playback`);
      }
    });

    // Set initial volume
    const state = get();
    audioContextManager.setMasterGain(state.globalVolume);

    // Create initial source node (will be replaced on play)
    const sourceNode = audioContextManager.createBufferSource();

    // Initialize audio player state
    set({
      audioPlayer: {
        audioContext,
        sourceNode,
        gainNode,
      },
    });

    // Do not preload default sources; queue starts empty.
  };

  if (typeof window !== "undefined") {
    // @ts-expect-error only exists for iOS
    if (window.navigator.audioSession) {
      // @ts-expect-error only exists for iOS
      window.navigator.audioSession.type = "playback";
    }

    console.log("Detected that no audio sources were loaded, initializing");
    initializeAudioExclusively();

    // In demo mode, stop audio when the app is backgrounded (camera, swipe down, etc.)
    // iOS 26 with audioSession "playback" keeps audio alive in background, which causes
    // desync glitches. Force stop and let the user resync when they return.
    if (IS_DEMO_MODE) {
      document.addEventListener("visibilitychange", () => {
        if (document.visibilityState === "hidden") {
          stopAndResetIfPlaying("Demo mode: stopped audio on background");
        }
      });
    }
  }

  return {
    // Initialize with initialState
    ...initialState,

    // Add all required methods
    reorderClient: (clientId) => {
      const state = get();
      const { socket } = getSocket(state);

      sendWSRequest({
        ws: socket,
        request: {
          type: ClientActionEnum.enum.REORDER_CLIENT,
          clientId,
        },
      });
    },

    setAdminStatus: (clientId, isAdmin) => {
      const state = get();
      const { socket } = getSocket(state);

      sendWSRequest({
        ws: socket,
        request: {
          type: ClientActionEnum.enum.SET_ADMIN,
          clientId,
          isAdmin,
        },
      });
    },

    setSpatialConfig: (spatialConfig) => set({ spatialConfig }),

    updateListeningSource: ({ x, y }) => {
      const state = get();
      const { socket } = getSocket(state);

      // Update local state
      set({ listeningSourcePosition: { x, y } });

      sendWSRequest({
        ws: socket,
        request: { type: ClientActionEnum.enum.SET_LISTENING_SOURCE, x, y },
      });
    },

    setIsInitingSystem: async (isIniting) => {
      // When initialization is complete (isIniting = false), check if we need to resume audio
      if (!isIniting) {
        // Mark that user has started the system
        set({ hasUserStartedSystem: true });

        try {
          await audioContextManager.resume();
          console.log("AudioContext resumed via user gesture");
        } catch (err) {
          console.warn("Failed to resume AudioContext", err);
        }

        const { socket } = getSocket(get());

        // Request sync with room (catches up playback state if a track is playing)
        sendWSRequest({
          ws: socket,
          request: { type: ClientActionEnum.enum.SYNC },
        });
      }

      // Update the initialization state
      set({ isInitingSystem: isIniting });
    },

    /**
     * Switches the current audio track to the given URL.
     * - Stops any current playback immediately.
     * - Resets playback state (currentTime, playbackStartTime, playbackOffset, isPlaying).
     * - Updates selectedAudioUrl and duration (if available).
     * - Returns whether playback was active before the change (for skip logic).
     */
    changeAudioSource: (url) => {
      const state = get();
      const wasPlaying = state.isPlaying; // Store if it was playing *before* stopping

      // Stop any current playback immediately when switching tracks
      if (state.isPlaying && state.audioPlayer) {
        try {
          state.audioPlayer.sourceNode.stop();
        } catch (e) {
          // Ignore errors if already stopped or not initialized
        }
      }

      // Find the new audio source for duration
      const audioIndex = state.findAudioIndexByUrl(url);
      let newDuration = 0;
      if (audioIndex !== null) {
        const audioSourceState = state.audioSources[audioIndex];
        if (audioSourceState.status === "loaded" && audioSourceState.buffer) {
          newDuration = audioSourceState.buffer.duration;
        }
        // If not loaded, duration will be 0 (will be updated when loaded)
      }

      // Reset timing state and update selected ID
      set({
        selectedAudioUrl: url,
        isPlaying: false, // Always stop playback on track change before potentially restarting
        currentTime: 0,
        playbackStartTime: 0,
        playbackOffset: 0,
        duration: newDuration,
      });

      // Return the previous playing state for the skip functions to use
      return wasPlaying;
    },

    findAudioIndexByUrl: (url: string) => {
      const state = get();
      // Look through the audioSources for a matching URL
      const index = state.audioSources.findIndex((sourceState) => sourceState.source.url === url);
      return index >= 0 ? index : null; // Return null if not found
    },

    schedulePlay: async (data) => {
      const state = get();
      if (state.isInitingSystem) {
        console.log("Not playing audio, still loading");
        // Non-interactive state, can't play audio
        return;
      }

      // Simulate scheduling delay for testing:
      // await new Promise((resolve) => setTimeout(resolve, 500));

      let waitTimeSeconds = getWaitTimeSeconds(state, data.targetServerTime);
      const _olMs = getFilteredOutputLatencyMs();
      const _effectiveOffset = state.offsetEstimate + state.nudgeOffsetMs;
      const _rawWaitMs = calculateWaitTimeMilliseconds(data.targetServerTime, _effectiveOffset);
      console.log(
        `[Schedule] wait=${waitTimeSeconds.toFixed(3)}s = max(0, (${_rawWaitMs.toFixed(1)}ms - ${_olMs.toFixed(1)}ms OL) / 1000) | offset=${state.offsetEstimate.toFixed(1)}ms nudge=${state.nudgeOffsetMs}ms`
      );

      // Check if the scheduled time has already passed
      if (waitTimeSeconds < 0.05) {
        // Check if it would have been on time without nudge AND without output latency compensation
        // (i.e., is the network genuinely late, or did local compensation consume the buffer?)
        const rawWaitMs = calculateWaitTimeMilliseconds(data.targetServerTime, state.offsetEstimate);

        if (rawWaitMs < 50) {
          // Network is genuinely late — reschedule locally with proportional buffer.
          // No server round-trip needed; we have all the info to calculate the right position.
          const missedByMs = 50 - rawWaitMs;
          const retryDelayMs = Math.min(missedByMs + 200, 2000);
          const elapsedSinceTargetMs = epochNow() + state.offsetEstimate - data.targetServerTime;
          const trackPositionAtRetry = data.trackTimeSeconds + (elapsedSinceTargetMs + retryDelayMs) / 1000;

          console.warn(
            `[Schedule] Missed by ${missedByMs.toFixed(0)}ms, rescheduling in ${retryDelayMs.toFixed(0)}ms at track position ${trackPositionAtRetry.toFixed(2)}s`
          );

          // Update state and schedule on audio thread (sample-accurate)
          if (data.audioSource !== state.selectedAudioUrl) {
            set({ selectedAudioUrl: data.audioSource });
          }
          const audioIndex = state.findAudioIndexByUrl(data.audioSource);
          if (audioIndex === null) return;

          const absoluteStartTime = audioContextManager.getContext().currentTime + retryDelayMs / 1000;
          state.playAudio({
            offset: trackPositionAtRetry,
            when: retryDelayMs / 1000,
            absoluteStartTime,
            audioIndex,
          });
          return;
        }

        // Output latency and/or nudge consumed the buffer — play immediately
        console.log(`[Nudge] Playing immediately (local compensation consumed scheduling buffer)`);
        waitTimeSeconds = 0;
      }

      console.log(`Playing track ${data.audioSource} at ${data.trackTimeSeconds} seconds in ${waitTimeSeconds}`);

      // Update the selected audio ID
      if (data.audioSource !== state.selectedAudioUrl) {
        set({ selectedAudioUrl: data.audioSource });
      }

      // Find the index of the audio to play
      const audioIndex = state.findAudioIndexByUrl(data.audioSource);

      // Check if track doesn't exist at all
      if (audioIndex === null) {
        if (state.isPlaying) {
          state.pauseAudio({ when: 0 });
        }
        console.warn(`Cannot play audio: Track not found in audioSources: ${data.audioSource}`);
        return;
      }

      const audioSourceState = state.audioSources[audioIndex];

      // Demo mode: kick-style synchronous play — no async, no indirection
      if (IS_DEMO_MODE && audioSourceState?.status === "loaded" && audioSourceState.buffer) {
        const ctx = audioContextManager.getContext();
        const inputNode = audioContextManager.getInputNode();

        // Stop old source
        try {
          const { sourceNode } = getAudioPlayer(state);
          sourceNode.onended = null;
          sourceNode.disconnect();
          sourceNode.stop();
        } catch (_) {}

        // Create, connect, start — all synchronous, identical to kick path
        const startTime = ctx.currentTime + waitTimeSeconds;
        const newSourceNode = ctx.createBufferSource();
        newSourceNode.buffer = audioSourceState.buffer;
        newSourceNode.connect(inputNode);
        newSourceNode.start(startTime, data.trackTimeSeconds);

        console.log(
          `[DemoPlay] sync start: ctx=${ctx.currentTime.toFixed(3)} startTime=${startTime.toFixed(3)} offset=${data.trackTimeSeconds}`
        );

        // Update state after start (non-blocking)
        set({
          audioPlayer: { ...get().audioPlayer!, sourceNode: newSourceNode },
          isPlaying: true,
          selectedAudioUrl: data.audioSource,
          playbackStartTime: startTime,
          playbackOffset: data.trackTimeSeconds,
          duration: audioSourceState.buffer!.duration,
        });
        return;
      }

      // Check if track exists but is still loading
      if (audioSourceState?.status === "loading") {
        if (state.isPlaying) {
          state.pauseAudio({ when: 0 });
        }

        console.warn(`Cannot play audio: Track still loading: ${data.audioSource}`);
        toast.warning(`"${extractFileNameFromUrl(data.audioSource)}" not loaded yet...`, { id: "schedulePlay" });

        const { socket } = getSocket(state);
        setTimeout(() => {
          sendWSRequest({
            ws: socket,
            request: { type: ClientActionEnum.enum.SYNC },
          });
        }, 1000);

        return;
      }

      // Capture absolute start time NOW (synchronous) so async delays in playAudio don't shift it
      const absoluteStartTime = audioContextManager.getContext().currentTime + waitTimeSeconds;

      state.playAudio({
        offset: data.trackTimeSeconds,
        when: waitTimeSeconds,
        absoluteStartTime,
        audioIndex,
      });
    },

    schedulePause: ({ targetServerTime }: { targetServerTime: number }) => {
      const state = get();
      const waitTimeSeconds = getWaitTimeSeconds(state, targetServerTime);
      console.log(`Pausing track in ${waitTimeSeconds}`);

      state.pauseAudio({
        when: waitTimeSeconds,
      });
    },

    setSocket: (socket) => set({ socket }),

    // if trackTimeSeconds is not provided, use the current track position
    broadcastPlay: (trackTimeSeconds?: number) => {
      const state = get();
      const { socket } = getSocket(state);

      // Use selected audio or fall back to first audio source
      let audioId = state.selectedAudioUrl;
      if (!audioId && state.audioSources.length > 0) {
        audioId = state.audioSources[0].source.url;
      }

      if (!audioId) {
        console.error("Cannot broadcast play: No audio available");
        return;
      }

      sendWSRequest({
        ws: socket,
        request: {
          type: ClientActionEnum.enum.PLAY,
          trackTimeSeconds: trackTimeSeconds ?? state.getCurrentTrackPosition(),
          audioSource: audioId,
        },
      });
    },

    broadcastPause: () => {
      const state = get();
      const { socket } = getSocket(state);

      sendWSRequest({
        ws: socket,
        request: {
          type: ClientActionEnum.enum.PAUSE,
          trackTimeSeconds: state.getCurrentTrackPosition(),
          audioSource: state.selectedAudioUrl,
        },
      });
    },

    broadcastReorder: (newOrder: AudioSourceType[]) => {
      const state = get();
      const { socket } = getSocket(state);

      // Optimistically update local state immediately to prevent snap-back animation
      const newAudioSources: AudioSourceState[] = newOrder.map((source) => {
        // Preserve existing state (buffer, status) for each track
        const existing = state.audioSources.find((as) => as.source.url === source.url);
        return existing || { source, status: "idle" };
      });

      set({ audioSources: newAudioSources });

      sendWSRequest({
        ws: socket,
        request: {
          type: ClientActionEnum.enum.REORDER_AUDIO_SOURCES,
          reorderedAudioSources: newOrder,
        },
      });
    },

    startSpatialAudio: () => {
      const state = get();
      const { socket } = getSocket(state);

      sendWSRequest({
        ws: socket,
        request: {
          type: ClientActionEnum.enum.START_SPATIAL_AUDIO,
        },
      });
    },

    sendStopSpatialAudio: () => {
      const state = get();
      const { socket } = getSocket(state);

      sendWSRequest({
        ws: socket,
        request: {
          type: ClientActionEnum.enum.STOP_SPATIAL_AUDIO,
        },
      });
    },

    sendChatMessage: (text: string) => {
      const state = get();
      const { socket } = getSocket(state);

      sendWSRequest({
        ws: socket,
        request: {
          type: ClientActionEnum.enum.SEND_CHAT_MESSAGE,
          text,
        },
      });
    },

    processStopSpatialAudio: () => {
      set({ isSpatialAudioEnabled: false });
      set({ spatialConfig: undefined });

      // Apply final gain which will now just be the global volume
      get().applyFinalGain();
    },

    sendProbePair: () => {
      const state = get();
      const { socket } = getSocket(state);

      if (state.isSynced && state.roundTripEstimate > 750) {
        console.warn("Latency is very high (>750ms). Sync may be unstable.");
      }

      // Compute total local compensation (outputLatency + nudge) to report to server
      const totalCompensationMs = getFilteredOutputLatencyMs() + state.nudgeOffsetMs;

      sendProbePairWS({
        ws: socket,
        currentRTT: state.roundTripEstimate ?? undefined,
        compensationMs: totalCompensationMs > 0 ? totalCompensationMs : undefined,
        nudgeMs: state.nudgeOffsetMs,
      });
    },

    nudge: ({ amountMs }) => {
      const state = get();
      const newNudge = state.nudgeOffsetMs + amountMs;
      set({ nudgeOffsetMs: newNudge });

      // If currently playing, restart playback at the adjusted position
      if (state.isPlaying && state.audioPlayer) {
        const audioIndex = state.audioSources.findIndex((as) => as.source.url === state.selectedAudioUrl);
        if (audioIndex === -1) return;

        const currentPosition = state.getCurrentTrackPosition();
        const adjustedPosition = Math.max(0, currentPosition - amountMs / 1000);
        state.playAudio({ offset: adjustedPosition, when: 0, audioIndex });
      }
    },

    resetNTPConfig() {
      resetProbeState();
      set({
        syncMeasurements: [],
        offsetEstimate: 0,
        roundTripEstimate: 0,
        isSynced: false,
        // nudgeOffsetMs is intentionally NOT reset — it's a user preference persisted on the server
      });
    },

    addProbePairResult: (result) => {
      const prev = get();
      let results = [...prev.syncMeasurements];
      if (results.length >= MAX_NTP_MEASUREMENTS) {
        results = [...results.slice(1), result];
      } else {
        results.push(result);
      }

      const nowSynced = !prev.isSynced && results.length >= MAX_NTP_MEASUREMENTS;
      const { averageOffset, averageRoundTrip } = calculateOffsetEstimate(results);
      set({
        syncMeasurements: results,
        offsetEstimate: averageOffset,
        roundTripEstimate: averageRoundTrip,
        probeStats: getProbeStats(),
        ...(nowSynced ? { isSynced: true } : {}),
      });

      // In demo mode, NTP sync just completed — eagerly load idle audio sources
      // (they arrived from handleOpen before sync finished)
      if (nowSynced && IS_DEMO_MODE) {
        eagerLoadIdleSources();
        getKickBuffer(audioContextManager.getContext());
      }
    },
    onConnectionReset: () => {
      const state = get();

      // Stop spatial audio if enabled
      if (state.isSpatialAudioEnabled) {
        state.processStopSpatialAudio();
      }

      // Allow nudge to be restored from server on next CLIENT_CHANGE
      set({ didRestoreNudge: false });

      // Delegate NTP reset to the single source of truth
      get().resetNTPConfig();
    },

    getCurrentTrackPosition: () => {
      const state = get();
      const { audioPlayer, isPlaying, currentTime, playbackStartTime, playbackOffset } = state; // Destructure for easier access

      if (!isPlaying || !audioPlayer) {
        return currentTime; // Return the saved position when paused or not initialized
      }

      const { audioContext } = audioPlayer;
      const elapsedSinceStart = audioContext.currentTime - playbackStartTime;
      // Ensure position doesn't exceed duration due to timing glitches
      return Math.min(playbackOffset + elapsedSinceStart, state.duration);
    },

    playAudio: async (data: { offset: number; when: number; absoluteStartTime?: number; audioIndex?: number }) => {
      const state = get();
      const { sourceNode, audioContext } = getAudioPlayer(state);

      // Before any audio playback, ensure the context is running
      if (audioContext.state !== "running") {
        console.log("AudioContext still suspended, aborting play");
        toast.error("Audio context is suspended. Please try again.");
        return;
      }

      // Stop any existing source node before creating a new one
      try {
        sourceNode.onended = null;
        sourceNode.disconnect();
        sourceNode.stop();
      } catch (_) {}

      const startTime = data.absoluteStartTime ?? audioContext.currentTime + data.when;
      const audioIndex = data.audioIndex ?? 0;
      const audioSourceState = state.audioSources[audioIndex];
      if (!audioSourceState) {
        console.error(`No audio source at index ${audioIndex}`);
        return;
      }

      // Check if the audio is loaded
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

      // Validate offset is within track duration to prevent sync failures
      if (data.offset >= audioBuffer.duration) {
        console.error(
          `Sync offset ${data.offset.toFixed(2)}s is beyond track duration ${audioBuffer.duration.toFixed(
            2
          )}s. Aborting playback.`
        );
        return;
      }

      // Create a new source node using singleton
      const newSourceNode = audioContextManager.createBufferSource();
      newSourceNode.buffer = audioBuffer;
      newSourceNode.connect(audioContextManager.getInputNode());

      // Autoplay: Handle track ending naturally
      newSourceNode.onended = () => {
        const currentState = get();
        const { audioPlayer: currentPlayer, isPlaying: currentlyIsPlaying } = currentState; // Get fresh state

        // Only process if the player was 'isPlaying' right before this event fired
        // and the sourceNode that ended is the *current* sourceNode.
        // This prevents handlers from old nodes interfering after a quick skip.
        if (currentlyIsPlaying && currentPlayer?.sourceNode === newSourceNode) {
          const { audioContext } = currentPlayer;
          // Check if the buffer naturally reached its end
          // Calculate the expected end time in the AudioContext timeline
          const expectedEndTime =
            currentState.playbackStartTime + (currentState.duration - currentState.playbackOffset);
          // Use a tolerance for timing discrepancies (e.g., 0.5 seconds)
          const endedNaturally = Math.abs(audioContext.currentTime - expectedEndTime) < 0.5;

          if (endedNaturally) {
            console.log("Track ended naturally, skipping to next via autoplay.");
            // Set currentTime to duration, as playback fully completed
            // We don't set isPlaying false here, let skipToNextTrack handle state transition
            set({ currentTime: currentState.duration });
            currentState.skipToNextTrack(true); // Trigger autoplay skip
          } else {
            console.log(
              "onended fired but not deemed a natural end (likely manual stop/skip). State should be handled elsewhere."
            );
            // If stopped manually (pauseAudio) or skipped (setSelectedAudioId),
            // those functions are responsible for setting isPlaying = false and currentTime.
            // No action needed here for non-natural ends.
          }
        } else {
          console.log("onended fired but player was already stopped/paused or source node changed.");
        }
      };

      newSourceNode.start(startTime, data.offset);
      console.log("Started playback at offset:", data.offset, "with delay:", data.when, "audio index:", audioIndex);

      // Update state with the new source node and tracking info
      set((state) => ({
        ...state,
        audioPlayer: {
          ...state.audioPlayer!,
          sourceNode: newSourceNode,
        },
        isPlaying: true,
        playbackStartTime: startTime,
        playbackOffset: data.offset,
        duration: audioBuffer.duration, // Set the duration
      }));
    },

    processSpatialConfig: (config: SpatialConfigType) => {
      const state = get();
      set({ spatialConfig: config });
      const { listeningSource } = config;

      // Don't set if we were the ones dragging the listening source
      if (!state.isDraggingListeningSource) {
        set({ listeningSourcePosition: listeningSource });
      }

      // Use the shared applyFinalGain method which handles global volume multiplication
      const clientId = getClientId();
      const user = config.gains[clientId];
      if (!user) {
        console.error(`No gain config found for client ${clientId}`);
        return;
      }

      // The rampTime comes from the server-side spatial config
      state.applyFinalGain(user.rampTime);
    },

    pauseAudio: (data: { when: number }) => {
      const state = get();
      const { sourceNode, audioContext } = getAudioPlayer(state);

      const stopTime = audioContext.currentTime + data.when;
      sourceNode.stop(stopTime);

      // Calculate current position in the track at the time of pausing
      const elapsedSinceStart = stopTime - state.playbackStartTime;
      const currentTrackPosition = state.playbackOffset + elapsedSinceStart;

      console.log("Stopping at:", data.when, "Current track position:", currentTrackPosition);

      set((state) => ({
        ...state,
        isPlaying: false,
        currentTime: currentTrackPosition,
      }));
    },

    setListeningSourcePosition: (position: PositionType) => {
      set({ listeningSourcePosition: position });
    },

    setIsDraggingListeningSource: (isDragging) => {
      set({ isDraggingListeningSource: isDragging });
    },

    setConnectedClients: (clients) => {
      const clientId = getClientId();
      const currentUser = clients.find((client) => client.clientId === clientId);

      if (!currentUser) {
        throw new Error(`Current user not found in connected clients: ${clientId}`);
      }

      // Restore nudge from server once per connection (e.g. after reconnect)
      const { didRestoreNudge } = get();
      const shouldRestoreNudge = !didRestoreNudge && currentUser.nudgeMs !== 0;

      set({
        connectedClients: clients,
        currentUser,
        ...(shouldRestoreNudge ? { nudgeOffsetMs: currentUser.nudgeMs, didRestoreNudge: true } : {}),
      });
    },

    skipToNextTrack: (isAutoplay = false) => {
      // Accept optional isAutoplay flag
      const state = get();
      const { audioSources: audioSources, selectedAudioUrl: selectedAudioId, isShuffled } = state;
      if (audioSources.length <= 1) return; // Can't skip if only one track

      const currentIndex = state.findAudioIndexByUrl(selectedAudioId);
      if (currentIndex === null) return;

      let nextIndex: number;
      if (isShuffled) {
        // Shuffle logic: pick a random index DIFFERENT from the current one
        do {
          nextIndex = Math.floor(Math.random() * audioSources.length);
        } while (nextIndex === currentIndex);
      } else {
        // Normal sequential logic
        nextIndex = (currentIndex + 1) % audioSources.length;
      }

      const nextAudioId = audioSources[nextIndex].source.url;
      // setSelectedAudioId stops any current playback and sets isPlaying to false.
      // It returns true if playback was active *before* this function was called.
      const wasPlayingBeforeSkip = state.changeAudioSource(nextAudioId);

      // If the track was playing before a manual skip OR if this is an autoplay event,
      // start playing the next track from the beginning.
      if (wasPlayingBeforeSkip || isAutoplay) {
        console.log(
          `Skip to next: ${nextAudioId}. Was playing: ${wasPlayingBeforeSkip}, Is autoplay: ${isAutoplay}. Broadcasting play.`
        );
        state.broadcastPlay(0); // Play next track from start
      } else {
        console.log(
          `Skip to next: ${nextAudioId}. Was playing: ${wasPlayingBeforeSkip}, Is autoplay: ${isAutoplay}. Not broadcasting play.`
        );
      }
    },

    skipToPreviousTrack: () => {
      const state = get();
      const { audioSources, selectedAudioUrl: selectedAudioId /* isShuffled */ } = state; // Note: isShuffled is NOT used here currently
      if (audioSources.length === 0) return;

      const currentIndex = state.findAudioIndexByUrl(selectedAudioId);
      if (currentIndex === null) return;

      // Previous track always goes to the actual previous in the list, even if shuffled
      // This is a common behavior, but could be changed if needed.
      const prevIndex = (currentIndex - 1 + audioSources.length) % audioSources.length;
      const prevAudioId = audioSources[prevIndex].source.url;

      // setSelectedAudioId stops any current playback and sets isPlaying to false.
      // It returns true if playback was active *before* this function was called.
      const wasPlayingBeforeSkip = state.changeAudioSource(prevAudioId);

      // If the track was playing before the manual skip, start playing the previous track.
      if (wasPlayingBeforeSkip) {
        console.log(`Skip to previous: ${prevAudioId}. Was playing: ${wasPlayingBeforeSkip}. Broadcasting play.`);
        state.broadcastPlay(0); // Play previous track from start
      } else {
        console.log(`Skip to previous: ${prevAudioId}. Was playing: ${wasPlayingBeforeSkip}. Not broadcasting play.`);
      }
    },

    toggleShuffle: () => set((state) => ({ isShuffled: !state.isShuffled })),

    setIsSpatialAudioEnabled: (isEnabled) => set({ isSpatialAudioEnabled: isEnabled }),

    getCurrentGainValue: () => {
      const state = get();
      if (!state.audioPlayer) return 1; // Default value if no player
      return state.audioPlayer.gainNode.gain.value;
    },

    getCurrentSpatialGainValue: () => {
      const state = get();
      if (!state.spatialConfig) return 1; // Default value if no spatial config
      const clientId = getClientId();
      return state.spatialConfig.gains[clientId].gain;
    },

    setGlobalVolume: (volume) => {
      set({ globalVolume: Math.max(0, Math.min(1, volume)) });
      get().applyFinalGain();
    },

    sendGlobalVolumeUpdate: (volume) => {
      const state = get();
      const { socket } = getSocket(state);

      sendWSRequest({
        ws: socket,
        request: {
          type: ClientActionEnum.enum.SET_GLOBAL_VOLUME,
          volume,
        },
      });
    },

    processGlobalVolumeConfig: (config: GlobalVolumeConfigType) => {
      const { volume, rampTime } = config;
      set({ globalVolume: volume });
      get().applyFinalGain(rampTime);
    },

    applyFinalGain: (rampTime = 0.1) => {
      const state = get();

      // Calculate final gain
      let finalGain = state.globalVolume;

      // If spatial audio is enabled, get the spatial gain for this client
      if (state.isSpatialAudioEnabled && state.spatialConfig) {
        const clientId = getClientId();
        const spatialGain = state.spatialConfig.gains[clientId]?.gain || 1;
        finalGain = state.globalVolume * spatialGain;
      }

      // Use singleton's setMasterGain with ramping
      audioContextManager.setMasterGain(finalGain, rampTime);
    },

    getAudioDuration: ({ url }) => {
      const state = get();
      const audioSource = state.audioSources.find((as) => as.source.url === url);
      if (!audioSource || audioSource.status !== "loaded" || !audioSource.buffer) {
        // Return 0 for loading/error states or not found
        return 0;
      }
      return audioSource.buffer.duration;
    },

    getSelectedTrack: () => {
      const state = get();
      if (!state.selectedAudioUrl) return null;

      return state.audioSources.find((as) => as.source.url === state.selectedAudioUrl) || null;
    },

    async handleSetAudioSources({ sources, currentAudioSource }) {
      // Wait for audio initialization to complete if it's in progress
      if (initializationMutex.isLocked()) {
        await initializationMutex.waitForUnlock();
      }

      const state = get();

      // Clean up buffer access queue - remove URLs that are no longer in the playlist
      // const newUrls = new Set(sources.map((s) => s.url));
      // const updatedQueue = state.bufferAccessQueue.filter((url) =>
      //   newUrls.has(url)
      // );

      // Build completely new queue based on sources order
      const newQueue: string[] = [];

      // Add current/selected track first (highest priority)
      if (currentAudioSource && sources.some((s) => s.url === currentAudioSource)) {
        newQueue.push(currentAudioSource);
      } else if (state.selectedAudioUrl && sources.some((s) => s.url === state.selectedAudioUrl)) {
        newQueue.push(state.selectedAudioUrl);
      }

      // Add remaining tracks in playlist order
      sources.forEach((source) => {
        if (!newQueue.includes(source.url)) {
          newQueue.push(source.url);
        }
      });

      // Create new audioSources array (preserving loaded buffers for tracks still in playlist)
      const existingByUrl = new Map(state.audioSources.map((as) => [as.source.url, as]));
      const newAudioSources: AudioSourceState[] = sources.map((source) => {
        const existing = existingByUrl.get(source.url);
        if (existing) {
          return existing;
        }
        return {
          source,
          status: "idle",
        };
      });

      // Update state immediately to show all sources (with idle states) and cleaned queue
      set({ audioSources: newAudioSources, bufferAccessQueue: newQueue });

      // If currentAudioSource is provided from server, update selectedAudioUrl and start loading it
      if (currentAudioSource) {
        set({ selectedAudioUrl: currentAudioSource });
        loadAudioSource(currentAudioSource);
      }

      // In demo mode, eagerly load remaining sources if sync is done.
      // In prod, sources load on-demand when the user selects them.
      if (IS_DEMO_MODE && get().isSynced) {
        eagerLoadIdleSources({ skip: currentAudioSource });
      }

      // Check if the currently selected/playing track was removed
      const currentStillExists = newAudioSources.some((as) => as.source.url === state.selectedAudioUrl);

      if (!currentStillExists && state.selectedAudioUrl) {
        // Stop playback if current track was removed
        if (state.isPlaying) {
          state.pauseAudio({ when: 0 });
        }

        // Clear selected track - don't auto-select another
        set({ selectedAudioUrl: "" });
      }
    },

    // Reset function to clean up state
    resetStore: () => {
      const state = get();

      // Stop any playing audio
      if (state.isPlaying && state.audioPlayer) {
        try {
          state.audioPlayer.sourceNode.onended = null; // Remove handler
          state.audioPlayer.sourceNode.disconnect(); // Disconnect from graph
          state.audioPlayer.sourceNode.stop();
        } catch (e) {
          // Ignore errors if already stopped
        }
      }

      // Close the websocket connection if it exists
      if (state.socket && state.socket.readyState === WebSocket.OPEN) {
        state.socket.close();
      }

      // DON'T close the AudioContext - keep singleton alive!
      // The AudioContext persists for the app lifetime

      // Reset low-pass filter to bypassed
      audioContextManager.setLowPassFreq(LOW_PASS_CONSTANTS.MAX_FREQ);

      // Reset state to initial values
      set({
        ...initialState,
      });

      // Reinitialize audio player state (but reuse the same context)
      initializeAudioExclusively();
    },
    setReconnectionInfo: (info) => set({ reconnectionInfo: info }),
    setPlaybackControlsPermissions: (permissions) => set({ playbackControlsPermissions: permissions }),

    // Search methods
    setSearchResults: (results, append = false) => {
      if (append && results?.type === "success") {
        const state = get();
        if (state.searchResults?.type === "success") {
          // Append new results to existing ones
          const existingItems = state.searchResults.response.data.tracks.items;
          const newItems = results.response.data.tracks.items;
          const combinedResults = {
            ...results,
            response: {
              ...results.response,
              data: {
                ...results.response.data,
                tracks: {
                  ...results.response.data.tracks,
                  items: [...existingItems, ...newItems],
                },
              },
            },
          };
          set({ searchResults: combinedResults });
          return;
        }
      }
      set({ searchResults: results });
    },
    setIsSearching: (isSearching) => set({ isSearching }),
    setIsLoadingMoreResults: (isLoading) => set({ isLoadingMoreResults: isLoading }),
    setSearchQuery: (query) => set({ searchQuery: query }),
    setSearchOffset: (offset) => set({ searchOffset: offset }),
    setHasMoreResults: (hasMore) => set({ hasMoreResults: hasMore }),
    clearSearchResults: () =>
      set({
        searchResults: null,
        isSearching: false,
        isLoadingMoreResults: false,
        searchQuery: "",
        searchOffset: 0,
        hasMoreResults: false,
      }),
    loadMoreSearchResults: () => {
      const state = get();
      const { socket, searchQuery, searchOffset, isLoadingMoreResults } = state;

      if (!socket || !searchQuery || isLoadingMoreResults) {
        console.error("Cannot load more results: missing requirements");
        return;
      }

      // Calculate next offset based on current results
      const currentResults =
        state.searchResults?.type === "success" ? state.searchResults.response.data.tracks.items.length : 0;
      const nextOffset = searchOffset + currentResults;

      console.log("Loading more search results", { searchQuery, nextOffset });

      // Set loading state
      state.setIsLoadingMoreResults(true);
      state.setSearchOffset(nextOffset);

      sendWSRequest({
        ws: socket,
        request: {
          type: ClientActionEnum.enum.SEARCH_MUSIC,
          query: searchQuery,
          offset: nextOffset,
        },
      });
    },

    // Stream job methods
    setActiveStreamJobs: (count) => set({ activeStreamJobs: count }),

    // Metronome methods
    toggleMetronome: () => {
      const { socket, isMetronomeActive } = get();
      if (!socket) return;

      sendWSRequest({
        ws: socket,
        request: {
          type: ClientActionEnum.enum.SET_METRONOME,
          enabled: !isMetronomeActive,
        },
      });
    },

    processMetronomeConfig: (config: MetronomeConfigType) => {
      set({ isMetronomeActive: config.enabled });
    },

    sendLowPassFreqUpdate: (freq: number) => {
      const state = get();
      const { socket } = getSocket(state);

      sendWSRequest({
        ws: socket,
        request: {
          type: ClientActionEnum.enum.SET_LOW_PASS_FREQ,
          freq,
        },
      });
    },

    processLowPassConfig: (config: LowPassConfigType) => {
      const { freq, rampTime } = config;
      if (get().lowPassFreq === freq) return;
      set({ lowPassFreq: freq });
      audioContextManager.setLowPassFreq(freq, rampTime);
    },

    // Audio source methods
    handleLoadAudioSource: ({ audioSourceToPlay }: LoadAudioSourceType) => {
      set({ selectedAudioUrl: audioSourceToPlay.url });
      loadAudioSource(audioSourceToPlay.url);
    },
  };
});
