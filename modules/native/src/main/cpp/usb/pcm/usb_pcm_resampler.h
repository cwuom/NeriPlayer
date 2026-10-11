#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

namespace neri::usb {

// Kaiser 窗 sinc 多相重采样。相位用整数累加器步进，长时间播放不漂移；
// 输入帧跨写入保留滤波所需的历史，任意切块得到同一条连续输出
class PcmResampler final {
public:
    bool configure(int inputRate, int outputRate, int channels);
    void reset();

    [[nodiscard]] bool configured() const;
    [[nodiscard]] int halfTaps() const;

    // 最多接收多少输入帧，才能保证这次产出不超过 maxOutputFrames
    [[nodiscard]] int inputFramesForOutput(int availableInputFrames, size_t maxOutputFrames) const;

    // input 与 output 均为交织浮点帧，返回追加到 output 的帧数
    size_t process(const float* input, int inputFrames, std::vector<float>* output);

    // 输入结束时补 halfTaps 个零帧，算出还压在前瞻窗口里的尾部，之后回到初始状态
    size_t drain(std::vector<float>* output);

private:
    struct Kernel {
        int halfTaps = 0;
        int phases = 0;
        std::vector<float> coefficients;
        std::vector<float> deltas;
    };

    static std::shared_ptr<const Kernel> buildKernel(int inputRate, int outputRate);
    [[nodiscard]] int64_t fifoFrames() const;
    void emitFrame(std::vector<float>* output);

    std::shared_ptr<const Kernel> kernel_;
    std::vector<float> fifo_;
    std::vector<float> scratch_;
    int inputRate_ = 0;
    int outputRate_ = 0;
    int channels_ = 0;
    int64_t base_ = 0;
    int64_t fraction_ = 0;
};

} // namespace neri::usb
