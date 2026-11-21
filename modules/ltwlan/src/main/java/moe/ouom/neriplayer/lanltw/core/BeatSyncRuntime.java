package moe.ouom.neriplayer.lanltw.core;

import app.cash.zipline.QuickJs;

/** Typed Java bridge to the bytecode-public QuickJs host API; never evaluates remote scripts. */
@kotlin.OptIn(markerClass = app.cash.zipline.EngineApi.class)
public final class BeatSyncRuntime implements AutoCloseable {
    private final QuickJs engine;
    private final Thread owner = Thread.currentThread();
    public BeatSyncRuntime(String source) {
        engine = QuickJs.create();
        engine.setMemoryLimit(8L * 1024 * 1024);
        engine.setMaxStackSize(512 * 1024);
        try { engine.evaluate(source, "beatsync/core.js"); }
        catch (RuntimeException | Error error) { engine.close(); throw error; }
    }
    public String invoke(String quotedJson) {
        checkThread();
        return (String) engine.evaluate("LanBeatSync.invoke(" + quotedJson + ")", "host-call.js");
    }
    private void checkThread() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Wrong JS owner thread");
    }
    @Override public void close() { checkThread(); engine.close(); }
}
