#include <jni.h>

#include <android/log.h>
#include <cerrno>
#include <csignal>
#include <cstdarg>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <dlfcn.h>
#include <exception>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/utsname.h>
#include <ucontext.h>
#include <unistd.h>
#include <unwind.h>

namespace {

constexpr char kLogTag[] = "NeriNativeCrash";
constexpr char kPendingStartupCrashFlag[] = "pending_startup_crash.flag";
constexpr size_t kAltStackSize = 64 * 1024;
constexpr size_t kMaxPathLength = 1024;
constexpr size_t kMaxMetaLength = 256;
constexpr size_t kMaxInfoLength = 384;
constexpr size_t kMaxBacktraceFrames = 64;
constexpr jint kTestCrashSigSegv = 1;
constexpr jint kTestCrashSigAbrt = 2;
constexpr int kHandledSignals[] = {
    SIGABRT,
    SIGBUS,
    SIGFPE,
    SIGILL,
    SIGSEGV,
    SIGTRAP,
    SIGSYS,
};

struct SignalHandlerEntry {
    int signal_number = 0;
    struct sigaction previous_action {};
    bool installed = false;
};

struct BacktraceState {
    void** frames = nullptr;
    size_t frame_count = 0;
    size_t max_frames = 0;
};

SignalHandlerEntry g_signal_entries[sizeof(kHandledSignals) / sizeof(kHandledSignals[0])];
stack_t g_alternate_stack {};
bool g_alternate_stack_ready = false;
bool g_signal_handlers_installed = false;
bool g_terminate_handler_installed = false;
volatile sig_atomic_t g_handling_crash = 0;
std::terminate_handler g_previous_terminate = nullptr;

char g_crash_directory[kMaxPathLength] = {};
char g_app_version[kMaxMetaLength] = {};
char g_build_type[kMaxMetaLength] = {};
char g_build_uuid[kMaxMetaLength] = {};
char g_package_name[kMaxMetaLength] = {};
char g_device_info[kMaxInfoLength] = {};
char g_supported_abis[kMaxInfoLength] = {};
char g_android_version[kMaxMetaLength] = {};
jint g_sdk_int = 0;

void CopyString(const char* source, char* target, size_t capacity) {
    if (target == nullptr || capacity == 0) {
        return;
    }

    size_t index = 0;
    if (source != nullptr) {
        while (index + 1 < capacity && source[index] != '\0') {
            target[index] = source[index];
            ++index;
        }
    }
    target[index] = '\0';
}

void WriteAll(int fd, const void* data, size_t length) {
    if (fd < 0 || data == nullptr) {
        return;
    }

    const char* buffer = static_cast<const char*>(data);
    size_t written = 0;
    while (written < length) {
        const ssize_t current = write(fd, buffer + written, length - written);
        if (current < 0) {
            if (errno == EINTR) {
                continue;
            }
            return;
        }
        written += static_cast<size_t>(current);
    }
}

void WriteText(int fd, const char* text) {
    if (text == nullptr) {
        return;
    }
    WriteAll(fd, text, strlen(text));
}

void WriteFormat(int fd, const char* format, ...) {
    if (fd < 0 || format == nullptr) {
        return;
    }

    char buffer[1024] = {};
    va_list args;
    va_start(args, format);
    const int count = vsnprintf(buffer, sizeof(buffer), format, args);
    va_end(args);
    if (count <= 0) {
        return;
    }

    const size_t length = static_cast<size_t>(count) >= sizeof(buffer)
        ? sizeof(buffer) - 1
        : static_cast<size_t>(count);
    WriteAll(fd, buffer, length);
}

pid_t GetCurrentThreadId() {
    return static_cast<pid_t>(syscall(__NR_gettid));
}

const char* SignalName(int signal_number) {
    switch (signal_number) {
        case SIGABRT:
            return "SIGABRT";
        case SIGBUS:
            return "SIGBUS";
        case SIGFPE:
            return "SIGFPE";
        case SIGILL:
            return "SIGILL";
        case SIGSEGV:
            return "SIGSEGV";
        case SIGTRAP:
            return "SIGTRAP";
        case SIGSYS:
            return "SIGSYS";
        default:
            return "UNKNOWN";
    }
}

const char* SignalDescription(int signal_number) {
    switch (signal_number) {
        case SIGABRT:
            return "Abort signal";
        case SIGBUS:
            return "Bus error / invalid alignment";
        case SIGFPE:
            return "Floating point exception";
        case SIGILL:
            return "Illegal instruction";
        case SIGSEGV:
            return "Segmentation fault / invalid memory access";
        case SIGTRAP:
            return "Breakpoint / trace trap";
        case SIGSYS:
            return "Bad system call";
        default:
            return "Unknown signal";
    }
}

void BuildPath(char* buffer, size_t capacity, const char* file_name) {
    if (buffer == nullptr || capacity == 0) {
        return;
    }

    const bool has_trailing_slash =
        g_crash_directory[0] != '\0' &&
        g_crash_directory[strlen(g_crash_directory) - 1] == '/';
    snprintf(
        buffer,
        capacity,
        has_trailing_slash ? "%s%s" : "%s/%s",
        g_crash_directory,
        file_name
    );
}

void ReadSmallFile(const char* path, char* buffer, size_t capacity) {
    if (buffer == nullptr || capacity == 0) {
        return;
    }

    buffer[0] = '\0';
    const int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return;
    }

    ssize_t bytes_read = -1;
    do {
        bytes_read = read(fd, buffer, capacity - 1);
    } while (bytes_read < 0 && errno == EINTR);
    close(fd);

    if (bytes_read <= 0) {
        buffer[0] = '\0';
        return;
    }

    size_t length = static_cast<size_t>(bytes_read);
    while (length > 0 && (buffer[length - 1] == '\n' || buffer[length - 1] == '\0')) {
        --length;
    }
    buffer[length] = '\0';
}

int OpenCrashFile(const char* prefix, pid_t tid, char* out_file_name, size_t out_capacity) {
    if (g_crash_directory[0] == '\0') {
        return -1;
    }

    timespec current_time {};
    clock_gettime(CLOCK_REALTIME, &current_time);

    char file_name[160] = {};
    snprintf(
        file_name,
        sizeof(file_name),
        "%s_%lld_%lld_%d.txt",
        prefix,
        static_cast<long long>(current_time.tv_sec),
        static_cast<long long>(current_time.tv_nsec),
        static_cast<int>(tid)
    );
    CopyString(file_name, out_file_name, out_capacity);

    char path[kMaxPathLength] = {};
    BuildPath(path, sizeof(path), file_name);
    return open(path, O_CREAT | O_WRONLY | O_TRUNC | O_CLOEXEC, 0644);
}

void WritePendingFlag(const char* origin, const char* file_name) {
    if (origin == nullptr || file_name == nullptr || file_name[0] == '\0') {
        return;
    }

    char path[kMaxPathLength] = {};
    BuildPath(path, sizeof(path), kPendingStartupCrashFlag);
    const int fd = open(path, O_CREAT | O_WRONLY | O_TRUNC | O_CLOEXEC, 0644);
    if (fd < 0) {
        return;
    }

    WriteFormat(fd, "%s\n%s", origin, file_name);
    fsync(fd);
    close(fd);
}

void FinalizeCrashFile(int fd, const char* file_name) {
    if (fd >= 0) {
        fsync(fd);
        close(fd);
    }
    WritePendingFlag("native", file_name);
}

void DumpFileSection(int out_fd, const char* title, const char* path) {
    WriteFormat(out_fd, "\n=== %s ===\n", title);

    const int in_fd = open(path, O_RDONLY | O_CLOEXEC);
    if (in_fd < 0) {
        WriteFormat(out_fd, "  <unavailable errno=%d>\n", errno);
        return;
    }

    char buffer[4096];
    while (true) {
        const ssize_t bytes_read = read(in_fd, buffer, sizeof(buffer));
        if (bytes_read < 0) {
            if (errno == EINTR) {
                continue;
            }
            break;
        }
        if (bytes_read == 0) {
            break;
        }
        WriteAll(out_fd, buffer, static_cast<size_t>(bytes_read));
    }
    close(in_fd);
    WriteText(out_fd, "\n");
}

_Unwind_Reason_Code UnwindCallback(struct _Unwind_Context* context, void* argument) {
    auto* state = static_cast<BacktraceState*>(argument);
    const uintptr_t pc = _Unwind_GetIP(context);
    if (pc != 0 && state->frame_count < state->max_frames) {
        state->frames[state->frame_count++] = reinterpret_cast<void*>(pc);
    }
    return _URC_NO_REASON;
}

size_t CaptureBacktrace(void** frames, size_t max_frames) {
    BacktraceState state {};
    state.frames = frames;
    state.max_frames = max_frames;
    _Unwind_Backtrace(UnwindCallback, &state);
    return state.frame_count;
}

void DumpBacktrace(int fd) {
    WriteText(fd, "\n=== Native Backtrace ===\n");

    void* frames[kMaxBacktraceFrames] = {};
    const size_t frame_count = CaptureBacktrace(frames, kMaxBacktraceFrames);
    if (frame_count == 0) {
        WriteText(fd, "  <empty>\n");
        return;
    }

    for (size_t index = 0; index < frame_count; ++index) {
        Dl_info info {};
        if (dladdr(frames[index], &info) != 0) {
            const uintptr_t offset = info.dli_saddr == nullptr
                ? 0
                : reinterpret_cast<uintptr_t>(frames[index]) -
                    reinterpret_cast<uintptr_t>(info.dli_saddr);
            WriteFormat(
                fd,
                "#%02zu pc %p  %s (%s+0x%zx)\n",
                index,
                frames[index],
                info.dli_fname == nullptr ? "<unknown>" : info.dli_fname,
                info.dli_sname == nullptr ? "<unknown>" : info.dli_sname,
                static_cast<size_t>(offset)
            );
        } else {
            WriteFormat(fd, "#%02zu pc %p  <unknown>\n", index, frames[index]);
        }
    }
}

void DumpRegisters(int fd, void* context) {
    WriteText(fd, "\n=== Registers ===\n");
    if (context == nullptr) {
        WriteText(fd, "  unavailable\n");
        return;
    }

#if defined(__aarch64__)
    auto* ucontext = reinterpret_cast<ucontext_t*>(context);
    for (int index = 0; index < 31; ++index) {
        WriteFormat(
            fd,
            "  x%d: 0x%016llx\n",
            index,
            static_cast<unsigned long long>(ucontext->uc_mcontext.regs[index])
        );
    }
    WriteFormat(
        fd,
        "  sp: 0x%016llx\n  pc: 0x%016llx\n  pstate: 0x%016llx\n",
        static_cast<unsigned long long>(ucontext->uc_mcontext.sp),
        static_cast<unsigned long long>(ucontext->uc_mcontext.pc),
        static_cast<unsigned long long>(ucontext->uc_mcontext.pstate)
    );
#elif defined(__arm__)
    auto* ucontext = reinterpret_cast<ucontext_t*>(context);
    WriteFormat(
        fd,
        "  r0: 0x%08lx\n  r1: 0x%08lx\n  r2: 0x%08lx\n  r3: 0x%08lx\n"
        "  r4: 0x%08lx\n  r5: 0x%08lx\n  r6: 0x%08lx\n  r7: 0x%08lx\n"
        "  r8: 0x%08lx\n  r9: 0x%08lx\n  r10: 0x%08lx\n"
        "  fp: 0x%08lx\n  ip: 0x%08lx\n  sp: 0x%08lx\n"
        "  lr: 0x%08lx\n  pc: 0x%08lx\n  cpsr: 0x%08lx\n",
        static_cast<unsigned long>(ucontext->uc_mcontext.arm_r0),
        static_cast<unsigned long>(ucontext->uc_mcontext.arm_r1),
        static_cast<unsigned long>(ucontext->uc_mcontext.arm_r2),
        static_cast<unsigned long>(ucontext->uc_mcontext.arm_r3),
        static_cast<unsigned long>(ucontext->uc_mcontext.arm_r4),
        static_cast<unsigned long>(ucontext->uc_mcontext.arm_r5),
        static_cast<unsigned long>(ucontext->uc_mcontext.arm_r6),
        static_cast<unsigned long>(ucontext->uc_mcontext.arm_r7),
        static_cast<unsigned long>(ucontext->uc_mcontext.arm_r8),
        static_cast<unsigned long>(ucontext->uc_mcontext.arm_r9),
        static_cast<unsigned long>(ucontext->uc_mcontext.arm_r10),
        static_cast<unsigned long>(ucontext->uc_mcontext.arm_fp),
        static_cast<unsigned long>(ucontext->uc_mcontext.arm_ip),
        static_cast<unsigned long>(ucontext->uc_mcontext.arm_sp),
        static_cast<unsigned long>(ucontext->uc_mcontext.arm_lr),
        static_cast<unsigned long>(ucontext->uc_mcontext.arm_pc),
        static_cast<unsigned long>(ucontext->uc_mcontext.arm_cpsr)
    );
#else
    WriteText(fd, "  unsupported architecture\n");
#endif
}

void WriteCommonMetadata(int fd, pid_t tid) {
    char process_name[128] = {};
    char thread_name[128] = {};
    char thread_name_path[128] = {};
    timespec current_time {};
    struct utsname system_info {};

    ReadSmallFile("/proc/self/cmdline", process_name, sizeof(process_name));
    snprintf(thread_name_path, sizeof(thread_name_path), "/proc/self/task/%d/comm", static_cast<int>(tid));
    ReadSmallFile(thread_name_path, thread_name, sizeof(thread_name));
    clock_gettime(CLOCK_REALTIME, &current_time);

    WriteText(fd, "\n=== Metadata ===\n");
    WriteFormat(fd, "Process: %s\n", process_name[0] == '\0' ? "unknown" : process_name);
    WriteFormat(fd, "Thread: %s\n", thread_name[0] == '\0' ? "unknown" : thread_name);
    WriteFormat(fd, "PID: %d\n", static_cast<int>(getpid()));
    WriteFormat(fd, "TID: %d\n", static_cast<int>(tid));
    WriteFormat(
        fd,
        "Unix Time: %lld.%09lld\n",
        static_cast<long long>(current_time.tv_sec),
        static_cast<long long>(current_time.tv_nsec)
    );
    WriteFormat(fd, "Package: %s\n", g_package_name[0] == '\0' ? "unknown" : g_package_name);
    WriteFormat(fd, "App Version: %s\n", g_app_version[0] == '\0' ? "unknown" : g_app_version);
    WriteFormat(fd, "Build Type: %s\n", g_build_type[0] == '\0' ? "unknown" : g_build_type);
    WriteFormat(fd, "Build UUID: %s\n", g_build_uuid[0] == '\0' ? "unknown" : g_build_uuid);
    WriteFormat(
        fd,
        "Android Version: %s\n",
        g_android_version[0] == '\0' ? "unknown" : g_android_version
    );
    WriteFormat(fd, "SDK Int: %d\n", static_cast<int>(g_sdk_int));
    WriteFormat(fd, "Device: %s\n", g_device_info[0] == '\0' ? "unknown" : g_device_info);
    WriteFormat(
        fd,
        "Supported ABIs: %s\n",
        g_supported_abis[0] == '\0' ? "unknown" : g_supported_abis
    );
    WriteFormat(fd, "Page Size: %ld bytes\n", sysconf(_SC_PAGESIZE));
    if (uname(&system_info) == 0) {
        WriteFormat(
            fd,
            "Kernel: %s %s %s\n",
            system_info.sysname,
            system_info.release,
            system_info.machine
        );
    }
}

void WriteSignalCrashLog(int signal_number, const siginfo_t* info, void* context) {
    const pid_t tid = GetCurrentThreadId();
    char file_name[160] = {};
    const int fd = OpenCrashFile("native_crash", tid, file_name, sizeof(file_name));
    if (fd < 0) {
        return;
    }

    WriteText(fd, "=== Native Crash Report ===\n");
    WriteText(fd, "Category: Native Signal\n");
    WriteText(fd, "\n=== Signal ===\n");
    WriteFormat(fd, "Signal: %s (%d)\n", SignalName(signal_number), signal_number);
    WriteFormat(fd, "Description: %s\n", SignalDescription(signal_number));
    if (info != nullptr) {
        WriteFormat(fd, "Code: %d\n", info->si_code);
        WriteFormat(fd, "Errno: %d\n", info->si_errno);
        WriteFormat(fd, "Fault Addr: %p\n", info->si_addr);
    }

    WriteCommonMetadata(fd, tid);
    DumpRegisters(fd, context);
    DumpBacktrace(fd);
    DumpFileSection(fd, "Process Status (/proc/self/status)", "/proc/self/status");
    DumpFileSection(fd, "Memory Maps (/proc/self/maps)", "/proc/self/maps");
    FinalizeCrashFile(fd, file_name);
}

void WriteTerminateLog(const char* reason) {
    const pid_t tid = GetCurrentThreadId();
    char file_name[160] = {};
    const int fd = OpenCrashFile("native_terminate", tid, file_name, sizeof(file_name));
    if (fd < 0) {
        return;
    }

    WriteText(fd, "=== Native Crash Report ===\n");
    WriteText(fd, "Category: C++ Terminate\n");
    WriteText(fd, "\n=== Terminate Reason ===\n");
    WriteFormat(fd, "Reason: %s\n", reason == nullptr ? "unknown" : reason);

    WriteCommonMetadata(fd, tid);
    DumpBacktrace(fd);
    DumpFileSection(fd, "Process Status (/proc/self/status)", "/proc/self/status");
    DumpFileSection(fd, "Memory Maps (/proc/self/maps)", "/proc/self/maps");
    FinalizeCrashFile(fd, file_name);
}

SignalHandlerEntry* FindSignalEntry(int signal_number) {
    for (auto& entry : g_signal_entries) {
        if (entry.signal_number == signal_number) {
            return &entry;
        }
    }
    return nullptr;
}

void RestorePreviousHandler(int signal_number) {
    SignalHandlerEntry* entry = FindSignalEntry(signal_number);
    if (entry != nullptr && entry->installed) {
        sigaction(signal_number, &entry->previous_action, nullptr);
        return;
    }

    struct sigaction default_action {};
    default_action.sa_handler = SIG_DFL;
    sigemptyset(&default_action.sa_mask);
    sigaction(signal_number, &default_action, nullptr);
}

void HandleSignal(int signal_number, siginfo_t* info, void* context) {
    if (g_handling_crash != 0) {
        _exit(128 + signal_number);
    }

    g_handling_crash = 1;
    WriteSignalCrashLog(signal_number, info, context);
    RestorePreviousHandler(signal_number);
}

void HandleTerminate() {
    if (g_handling_crash != 0) {
        abort();
    }
    g_handling_crash = 1;

    const char* reason = "uncaught native exception";
    try {
        const std::exception_ptr current = std::current_exception();
        if (current) {
            try {
                std::rethrow_exception(current);
            } catch (const std::exception& error) {
                reason = error.what();
            } catch (...) {
                reason = "non-std native exception";
            }
        } else {
            reason = "std::terminate without current exception";
        }
    } catch (...) {
        reason = "failed to inspect terminate reason";
    }

    WriteTerminateLog(reason);

    if (g_previous_terminate != nullptr && g_previous_terminate != HandleTerminate) {
        std::set_terminate(g_previous_terminate);
        g_previous_terminate();
    }
    abort();
}

void InstallAlternateStack() {
    if (g_alternate_stack_ready) {
        return;
    }

    void* stack_memory = mmap(
        nullptr,
        kAltStackSize,
        PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANONYMOUS,
        -1,
        0
    );
    if (stack_memory == MAP_FAILED) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "sigaltstack mmap failed: %d", errno);
        return;
    }

    g_alternate_stack.ss_sp = stack_memory;
    g_alternate_stack.ss_size = kAltStackSize;
    g_alternate_stack.ss_flags = 0;
    if (sigaltstack(&g_alternate_stack, nullptr) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "sigaltstack install failed: %d", errno);
        munmap(stack_memory, kAltStackSize);
        memset(&g_alternate_stack, 0, sizeof(g_alternate_stack));
        return;
    }

    g_alternate_stack_ready = true;
}

bool InstallSignalHandlers() {
    bool installed_any = false;
    for (size_t index = 0; index < sizeof(kHandledSignals) / sizeof(kHandledSignals[0]); ++index) {
        struct sigaction action {};
        sigemptyset(&action.sa_mask);
        for (int handled_signal : kHandledSignals) {
            sigaddset(&action.sa_mask, handled_signal);
        }

        action.sa_flags = SA_SIGINFO | SA_ONSTACK;
        action.sa_sigaction = HandleSignal;

        g_signal_entries[index].signal_number = kHandledSignals[index];
        if (sigaction(
                kHandledSignals[index],
                &action,
                &g_signal_entries[index].previous_action
            ) == 0) {
            g_signal_entries[index].installed = true;
            installed_any = true;
        } else {
            __android_log_print(
                ANDROID_LOG_ERROR,
                kLogTag,
                "sigaction install failed for %d: %d",
                kHandledSignals[index],
                errno
            );
        }
    }
    return installed_any;
}

void CacheJavaString(JNIEnv* env, jstring value, char* target, size_t capacity) {
    if (env == nullptr || target == nullptr || capacity == 0) {
        return;
    }

    if (value == nullptr) {
        target[0] = '\0';
        return;
    }

    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        target[0] = '\0';
        return;
    }

    CopyString(chars, target, capacity);
    env->ReleaseStringUTFChars(value, chars);
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_util_NativeCrashHandler_nativeInstall(
    JNIEnv* env,
    jclass,
    jstring crash_directory,
    jstring app_version,
    jstring build_type,
    jstring build_uuid,
    jstring package_name,
    jstring device_info,
    jstring supported_abis,
    jint sdk_int,
    jstring android_version
) {
    if (env == nullptr || crash_directory == nullptr) {
        return JNI_FALSE;
    }

    CacheJavaString(env, crash_directory, g_crash_directory, sizeof(g_crash_directory));
    CacheJavaString(env, app_version, g_app_version, sizeof(g_app_version));
    CacheJavaString(env, build_type, g_build_type, sizeof(g_build_type));
    CacheJavaString(env, build_uuid, g_build_uuid, sizeof(g_build_uuid));
    CacheJavaString(env, package_name, g_package_name, sizeof(g_package_name));
    CacheJavaString(env, device_info, g_device_info, sizeof(g_device_info));
    CacheJavaString(env, supported_abis, g_supported_abis, sizeof(g_supported_abis));
    CacheJavaString(env, android_version, g_android_version, sizeof(g_android_version));
    g_sdk_int = sdk_int;

    if (g_crash_directory[0] == '\0') {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "empty crash directory");
        return JNI_FALSE;
    }

    if (!g_terminate_handler_installed) {
        g_previous_terminate = std::set_terminate(HandleTerminate);
        g_terminate_handler_installed = true;
    }

    if (g_signal_handlers_installed) {
        return JNI_TRUE;
    }

    InstallAlternateStack();
    g_signal_handlers_installed = InstallSignalHandlers();

    __android_log_print(
        g_signal_handlers_installed ? ANDROID_LOG_INFO : ANDROID_LOG_WARN,
        kLogTag,
        "native crash interceptor install result=%d, altStack=%d, output=lib_neri.so, dir=%s",
        g_signal_handlers_installed ? 1 : 0,
        g_alternate_stack_ready ? 1 : 0,
        g_crash_directory
    );
    return g_signal_handlers_installed ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_moe_ouom_neriplayer_util_NativeCrashHandler_nativeTriggerTestCrash(
    JNIEnv*,
    jclass,
    jint crash_type
) {
    switch (crash_type) {
        case kTestCrashSigSegv: {
            volatile int* invalid_address = nullptr;
            *invalid_address = 0x4E50;
            break;
        }
        case kTestCrashSigAbrt:
        default:
            abort();
    }
}
