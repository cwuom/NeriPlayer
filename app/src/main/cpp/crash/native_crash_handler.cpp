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
    [[maybe_unused]] void** frames = nullptr;
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
char g_kernel_info[kMaxInfoLength] = {};
jint g_sdk_int = 0;
long g_page_size = 0;

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

size_t StringLength(const char* text) noexcept {
    if (text == nullptr) {
        return 0;
    }

    size_t length = 0;
    while (text[length] != '\0') {
        ++length;
    }
    return length;
}

bool AppendCharacter(char* buffer, size_t capacity, size_t* length, char value) noexcept {
    if (buffer == nullptr || length == nullptr || *length + 1 >= capacity) {
        return false;
    }

    buffer[*length] = value;
    ++(*length);
    buffer[*length] = '\0';
    return true;
}

bool AppendText(char* buffer, size_t capacity, size_t* length, const char* text) noexcept {
    if (buffer == nullptr || length == nullptr || text == nullptr || capacity == 0) {
        return false;
    }

    for (size_t index = 0; text[index] != '\0'; ++index) {
        if (!AppendCharacter(buffer, capacity, length, text[index])) {
            return false;
        }
    }
    return true;
}

size_t EncodeUnsignedDecimal(uint64_t value, char* output, size_t capacity) noexcept {
    if (output == nullptr || capacity == 0) {
        return 0;
    }

    char reversed[32] = {};
    size_t digit_count = 0;
    do {
        reversed[digit_count++] = static_cast<char>('0' + (value % 10));
        value /= 10;
    } while (value != 0 && digit_count < sizeof(reversed));

    if (digit_count > capacity) {
        return 0;
    }
    for (size_t index = 0; index < digit_count; ++index) {
        output[index] = reversed[digit_count - index - 1];
    }
    return digit_count;
}

uint64_t UnsignedMagnitude(int64_t value) noexcept {
    if (value >= 0) {
        return static_cast<uint64_t>(value);
    }
    return static_cast<uint64_t>(-(value + 1)) + 1;
}

bool AppendUnsignedDecimal(
    char* buffer,
    size_t capacity,
    size_t* length,
    uint64_t value
) noexcept {
    char digits[32] = {};
    const size_t digit_count = EncodeUnsignedDecimal(value, digits, sizeof(digits));
    if (digit_count == 0 || buffer == nullptr || length == nullptr) {
        return false;
    }

    for (size_t index = 0; index < digit_count; ++index) {
        if (!AppendCharacter(buffer, capacity, length, digits[index])) {
            return false;
        }
    }
    return true;
}

bool AppendSignedDecimal(
    char* buffer,
    size_t capacity,
    size_t* length,
    int64_t value
) noexcept {
    if (value < 0 && !AppendCharacter(buffer, capacity, length, '-')) {
        return false;
    }
    return AppendUnsignedDecimal(buffer, capacity, length, UnsignedMagnitude(value));
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
        if (current == 0) {
            return;
        }
        written += static_cast<size_t>(current);
    }
}

void WriteText(int fd, const char* text) {
    if (text == nullptr) {
        return;
    }
    WriteAll(fd, text, StringLength(text));
}

void WriteUnsignedDecimal(int fd, uint64_t value, size_t minimum_width = 0) noexcept {
    char digits[32] = {};
    const size_t digit_count = EncodeUnsignedDecimal(value, digits, sizeof(digits));
    if (digit_count == 0 || minimum_width > sizeof(digits)) {
        return;
    }

    char output[32] = {};
    const size_t padding = minimum_width > digit_count ? minimum_width - digit_count : 0;
    for (size_t index = 0; index < padding; ++index) {
        output[index] = '0';
    }
    for (size_t index = 0; index < digit_count; ++index) {
        output[padding + index] = digits[index];
    }
    WriteAll(fd, output, padding + digit_count);
}

void WriteSignedDecimal(int fd, int64_t value) noexcept {
    if (value < 0) {
        WriteText(fd, "-");
    }
    WriteUnsignedDecimal(fd, UnsignedMagnitude(value));
}

void WriteHexadecimal(int fd, uint64_t value, size_t minimum_width) noexcept {
    constexpr char kHexDigits[] = "0123456789abcdef";
    char reversed[16] = {};
    size_t digit_count = 0;
    do {
        reversed[digit_count++] = kHexDigits[value & 0x0fU];
        value >>= 4U;
    } while (value != 0 && digit_count < sizeof(reversed));

    if (minimum_width > sizeof(reversed)) {
        return;
    }

    char output[16] = {};
    const size_t padding = minimum_width > digit_count ? minimum_width - digit_count : 0;
    for (size_t index = 0; index < padding; ++index) {
        output[index] = '0';
    }
    for (size_t index = 0; index < digit_count; ++index) {
        output[padding + index] = reversed[digit_count - index - 1];
    }
    WriteAll(fd, output, padding + digit_count);
}

void WritePointer(int fd, const void* pointer) noexcept {
    WriteText(fd, "0x");
    WriteHexadecimal(
        fd,
        static_cast<uint64_t>(reinterpret_cast<uintptr_t>(pointer)),
        sizeof(uintptr_t) * 2
    );
}

void WriteKeyText(int fd, const char* key, const char* value) noexcept {
    WriteText(fd, key);
    WriteText(fd, value == nullptr || value[0] == '\0' ? "unknown" : value);
    WriteText(fd, "\n");
}

void WriteKeySignedDecimal(int fd, const char* key, int64_t value) noexcept {
    WriteText(fd, key);
    WriteSignedDecimal(fd, value);
    WriteText(fd, "\n");
}

[[maybe_unused]] void WriteKeyHexadecimal(
    int fd,
    const char* key,
    uint64_t value,
    size_t minimum_width
) noexcept {
    WriteText(fd, key);
    WriteText(fd, "0x");
    WriteHexadecimal(fd, value, minimum_width);
    WriteText(fd, "\n");
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

bool BuildPath(char* buffer, size_t capacity, const char* file_name) noexcept {
    if (buffer == nullptr || capacity == 0 || file_name == nullptr) {
        return false;
    }

    buffer[0] = '\0';
    size_t length = 0;
    if (!AppendText(buffer, capacity, &length, g_crash_directory)) {
        return false;
    }
    if (length == 0) {
        return false;
    }
    if (buffer[length - 1] != '/' && !AppendCharacter(buffer, capacity, &length, '/')) {
        return false;
    }
    return AppendText(buffer, capacity, &length, file_name);
}

bool BuildCrashFileName(
    char* buffer,
    size_t capacity,
    const char* prefix,
    const timespec& current_time,
    pid_t tid
) noexcept {
    if (buffer == nullptr || capacity == 0 || prefix == nullptr) {
        return false;
    }

    buffer[0] = '\0';
    size_t length = 0;
    return AppendText(buffer, capacity, &length, prefix) &&
        AppendCharacter(buffer, capacity, &length, '_') &&
        AppendSignedDecimal(
            buffer,
            capacity,
            &length,
            static_cast<int64_t>(current_time.tv_sec)
        ) &&
        AppendCharacter(buffer, capacity, &length, '_') &&
        AppendSignedDecimal(
            buffer,
            capacity,
            &length,
            static_cast<int64_t>(current_time.tv_nsec)
        ) &&
        AppendCharacter(buffer, capacity, &length, '_') &&
        AppendSignedDecimal(buffer, capacity, &length, static_cast<int64_t>(tid)) &&
        AppendText(buffer, capacity, &length, ".txt");
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

    auto length = static_cast<size_t>(bytes_read);
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
    if (!BuildCrashFileName(file_name, sizeof(file_name), prefix, current_time, tid)) {
        return -1;
    }
    CopyString(file_name, out_file_name, out_capacity);

    char path[kMaxPathLength] = {};
    if (!BuildPath(path, sizeof(path), file_name)) {
        return -1;
    }
    return open(path, O_CREAT | O_WRONLY | O_TRUNC | O_CLOEXEC, 0644);
}

void WritePendingFlag(const char* origin, const char* file_name) {
    if (origin == nullptr || file_name == nullptr || file_name[0] == '\0') {
        return;
    }

    char path[kMaxPathLength] = {};
    if (!BuildPath(path, sizeof(path), kPendingStartupCrashFlag)) {
        return;
    }
    const int fd = open(path, O_CREAT | O_WRONLY | O_TRUNC | O_CLOEXEC, 0644);
    if (fd < 0) {
        return;
    }

    WriteText(fd, origin);
    WriteText(fd, "\n");
    WriteText(fd, file_name);
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
    WriteText(out_fd, "\n=== ");
    WriteText(out_fd, title);
    WriteText(out_fd, " ===\n");

    const int in_fd = open(path, O_RDONLY | O_CLOEXEC);
    if (in_fd < 0) {
        const int error_number = errno;
        WriteText(out_fd, "  <unavailable errno=");
        WriteSignedDecimal(out_fd, static_cast<int64_t>(error_number));
        WriteText(out_fd, ">\n");
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

void DumpBacktrace(int fd, bool symbolize_frames) {
    WriteText(fd, "\n=== Native Backtrace ===\n");

    void* frames[kMaxBacktraceFrames] = {};
    const size_t frame_count = CaptureBacktrace(frames, kMaxBacktraceFrames);
    if (frame_count == 0) {
        WriteText(fd, "  <empty>\n");
        return;
    }

    for (size_t index = 0; index < frame_count; ++index) {
        if (!symbolize_frames) {
            WriteText(fd, "#");
            WriteUnsignedDecimal(fd, static_cast<uint64_t>(index), 2);
            WriteText(fd, " pc ");
            WritePointer(fd, frames[index]);
            WriteText(fd, "  <symbolication deferred>\n");
            continue;
        }
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
        WriteText(fd, "  x");
        WriteUnsignedDecimal(fd, static_cast<uint64_t>(index));
        WriteText(fd, ": 0x");
        WriteHexadecimal(
            fd,
            static_cast<uint64_t>(ucontext->uc_mcontext.regs[index]),
            16
        );
        WriteText(fd, "\n");
    }
    WriteKeyHexadecimal(
        fd,
        "  sp: ",
        static_cast<uint64_t>(ucontext->uc_mcontext.sp),
        16
    );
    WriteKeyHexadecimal(
        fd,
        "  pc: ",
        static_cast<uint64_t>(ucontext->uc_mcontext.pc),
        16
    );
    WriteKeyHexadecimal(
        fd,
        "  pstate: ",
        static_cast<uint64_t>(ucontext->uc_mcontext.pstate),
        16
    );
#elif defined(__arm__)
    auto* ucontext = reinterpret_cast<ucontext_t*>(context);
    WriteKeyHexadecimal(fd, "  r0: ", ucontext->uc_mcontext.arm_r0, 8);
    WriteKeyHexadecimal(fd, "  r1: ", ucontext->uc_mcontext.arm_r1, 8);
    WriteKeyHexadecimal(fd, "  r2: ", ucontext->uc_mcontext.arm_r2, 8);
    WriteKeyHexadecimal(fd, "  r3: ", ucontext->uc_mcontext.arm_r3, 8);
    WriteKeyHexadecimal(fd, "  r4: ", ucontext->uc_mcontext.arm_r4, 8);
    WriteKeyHexadecimal(fd, "  r5: ", ucontext->uc_mcontext.arm_r5, 8);
    WriteKeyHexadecimal(fd, "  r6: ", ucontext->uc_mcontext.arm_r6, 8);
    WriteKeyHexadecimal(fd, "  r7: ", ucontext->uc_mcontext.arm_r7, 8);
    WriteKeyHexadecimal(fd, "  r8: ", ucontext->uc_mcontext.arm_r8, 8);
    WriteKeyHexadecimal(fd, "  r9: ", ucontext->uc_mcontext.arm_r9, 8);
    WriteKeyHexadecimal(fd, "  r10: ", ucontext->uc_mcontext.arm_r10, 8);
    WriteKeyHexadecimal(fd, "  fp: ", ucontext->uc_mcontext.arm_fp, 8);
    WriteKeyHexadecimal(fd, "  ip: ", ucontext->uc_mcontext.arm_ip, 8);
    WriteKeyHexadecimal(fd, "  sp: ", ucontext->uc_mcontext.arm_sp, 8);
    WriteKeyHexadecimal(fd, "  lr: ", ucontext->uc_mcontext.arm_lr, 8);
    WriteKeyHexadecimal(fd, "  pc: ", ucontext->uc_mcontext.arm_pc, 8);
    WriteKeyHexadecimal(fd, "  cpsr: ", ucontext->uc_mcontext.arm_cpsr, 8);
#else
    WriteText(fd, "  unsupported architecture\n");
#endif
}

void CacheRuntimeMetadata() {
    g_page_size = sysconf(_SC_PAGESIZE);
    g_kernel_info[0] = '\0';

    struct utsname system_info {};
    if (uname(&system_info) != 0) {
        return;
    }

    size_t length = 0;
    if (!AppendText(g_kernel_info, sizeof(g_kernel_info), &length, system_info.sysname) ||
        !AppendCharacter(g_kernel_info, sizeof(g_kernel_info), &length, ' ') ||
        !AppendText(g_kernel_info, sizeof(g_kernel_info), &length, system_info.release) ||
        !AppendCharacter(g_kernel_info, sizeof(g_kernel_info), &length, ' ') ||
        !AppendText(g_kernel_info, sizeof(g_kernel_info), &length, system_info.machine)) {
        g_kernel_info[0] = '\0';
    }
}

void WriteCommonMetadata(int fd, pid_t tid) {
    char process_name[128] = {};
    char thread_name[128] = {};
    char thread_name_path[128] = {};
    timespec current_time {};

    ReadSmallFile("/proc/self/cmdline", process_name, sizeof(process_name));
    size_t thread_path_length = 0;
    if (AppendText(
            thread_name_path,
            sizeof(thread_name_path),
            &thread_path_length,
            "/proc/self/task/"
        ) &&
        AppendSignedDecimal(
            thread_name_path,
            sizeof(thread_name_path),
            &thread_path_length,
            static_cast<int64_t>(tid)
        ) &&
        AppendText(
            thread_name_path,
            sizeof(thread_name_path),
            &thread_path_length,
            "/comm"
        )) {
        ReadSmallFile(thread_name_path, thread_name, sizeof(thread_name));
    }
    clock_gettime(CLOCK_REALTIME, &current_time);

    WriteText(fd, "\n=== Metadata ===\n");
    WriteKeyText(fd, "Process: ", process_name);
    WriteKeyText(fd, "Thread: ", thread_name);
    WriteKeySignedDecimal(fd, "PID: ", static_cast<int64_t>(getpid()));
    WriteKeySignedDecimal(fd, "TID: ", static_cast<int64_t>(tid));
    WriteText(fd, "Unix Time: ");
    WriteSignedDecimal(fd, static_cast<int64_t>(current_time.tv_sec));
    WriteText(fd, ".");
    WriteUnsignedDecimal(fd, static_cast<uint64_t>(current_time.tv_nsec), 9);
    WriteText(fd, "\n");
    WriteKeyText(fd, "Package: ", g_package_name);
    WriteKeyText(fd, "App Version: ", g_app_version);
    WriteKeyText(fd, "Build Type: ", g_build_type);
    WriteKeyText(fd, "Build UUID: ", g_build_uuid);
    WriteKeyText(fd, "Android Version: ", g_android_version);
    WriteKeySignedDecimal(fd, "SDK Int: ", static_cast<int64_t>(g_sdk_int));
    WriteKeyText(fd, "Device: ", g_device_info);
    WriteKeyText(fd, "Supported ABIs: ", g_supported_abis);
    if (g_page_size > 0) {
        WriteText(fd, "Page Size: ");
        WriteSignedDecimal(fd, static_cast<int64_t>(g_page_size));
        WriteText(fd, " bytes\n");
    } else {
        WriteText(fd, "Page Size: unknown\n");
    }
    WriteKeyText(fd, "Kernel: ", g_kernel_info);
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
    WriteText(fd, "Signal: ");
    WriteText(fd, SignalName(signal_number));
    WriteText(fd, " (");
    WriteSignedDecimal(fd, static_cast<int64_t>(signal_number));
    WriteText(fd, ")\n");
    WriteKeyText(fd, "Description: ", SignalDescription(signal_number));
    if (info != nullptr) {
        WriteKeySignedDecimal(fd, "Code: ", static_cast<int64_t>(info->si_code));
        WriteKeySignedDecimal(fd, "Errno: ", static_cast<int64_t>(info->si_errno));
        WriteText(fd, "Fault Addr: ");
        WritePointer(fd, info->si_addr);
        WriteText(fd, "\n");
    }

    WriteCommonMetadata(fd, tid);
    DumpRegisters(fd, context);
    DumpBacktrace(fd, false);
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
    DumpBacktrace(fd, true);
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

[[noreturn]] void ForwardSignalToPreviousHandler(int signal_number) {
    RestorePreviousHandler(signal_number);

    sigset_t unblocked_signals;
    sigemptyset(&unblocked_signals);
    sigaddset(&unblocked_signals, signal_number);
    sigprocmask(SIG_UNBLOCK, &unblocked_signals, nullptr);

    syscall(__NR_tgkill, getpid(), GetCurrentThreadId(), signal_number);
    _exit(128 + signal_number);
}

void HandleSignal(int signal_number, siginfo_t* info, void* context) {
    if (g_handling_crash != 0) {
        ForwardSignalToPreviousHandler(signal_number);
    }

    g_handling_crash = 1;
    WriteSignalCrashLog(signal_number, info, context);
    ForwardSignalToPreviousHandler(signal_number);
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

static jboolean NativeCrashHandlerNativeInstall(
    JNIEnv* env,
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
    CacheRuntimeMetadata();

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

static void NativeCrashHandlerTriggerTestCrash(jint crash_type) {
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

extern "C" JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_util_crash_NativeCrashHandler_nativeInstall(
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
    return NativeCrashHandlerNativeInstall(
        env,
        crash_directory,
        app_version,
        build_type,
        build_uuid,
        package_name,
        device_info,
        supported_abis,
        sdk_int,
        android_version
    );
}

extern "C" JNIEXPORT void JNICALL
Java_moe_ouom_neriplayer_util_crash_NativeCrashHandler_nativeTriggerTestCrash(
    JNIEnv*,
    jclass,
    jint crash_type
) {
    NativeCrashHandlerTriggerTestCrash(crash_type);
}
