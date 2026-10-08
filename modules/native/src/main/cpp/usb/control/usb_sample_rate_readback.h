#pragma once

namespace neri::usb::control {

enum class SampleRateReadback {
    Verified,
    Settle,
    AcceptUnverified
};

/**
 * SET_CUR 之后回读采样率的判定，行为对齐 Linux snd-usb-audio：
 * 时钟锁定期间不少 DAC 会先报旧值，所以先短暂等待重读；
 * 读到 0 视为设备不支持回读，仍不一致也只记日志不判失败
 */
SampleRateReadback classifySampleRateReadback(int requestedRate, int reportedRate, int attempt);

/** 第 attempt 次回读不一致后再等多久，attempt 从 0 开始 */
int sampleRateSettleDelayMs(int attempt);

} // namespace neri::usb::control
