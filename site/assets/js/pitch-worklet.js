/* NeriPlayer site · pitch-worklet —— 不改速度的移调：两路交错的变速延迟线，sin² 窗交叉淡化 */
class NeriPitchShifter extends AudioWorkletProcessor {
  static get parameterDescriptors() {
    return [{ name: 'ratio', defaultValue: 1, minValue: 0.25, maxValue: 4, automationRate: 'k-rate' }];
  }

  constructor() {
    super();
    this.size = 8192;
    this.mask = this.size - 1;
    this.win = 2048;
    this.buf = [new Float32Array(this.size), new Float32Array(this.size)];
    this.w = 0;
    this.phase = 0;
  }

  read(b, pos) {
    const i = Math.floor(pos);
    const f = pos - i;
    const a = b[i & this.mask];
    return a + (b[(i + 1) & this.mask] - a) * f;
  }

  process(inputs, outputs, parameters) {
    const input = inputs[0];
    const output = outputs[0];
    if (!input || !input.length) return true;
    const ratio = parameters.ratio[0];
    const n = output[0].length;
    const chans = output.length;
    if (Math.abs(ratio - 1) < 1e-3) {
      for (let c = 0; c < chans; c++) output[c].set(input[Math.min(c, input.length - 1)]);
      for (let i = 0; i < n; i++) {
        for (let c = 0; c < 2; c++) this.buf[c][this.w] = input[Math.min(c, input.length - 1)][i];
        this.w = (this.w + 1) & this.mask;
      }
      return true;
    }
    const W = this.win;
    const step = (1 - ratio) / W;
    for (let i = 0; i < n; i++) {
      for (let c = 0; c < 2; c++) this.buf[c][this.w] = input[Math.min(c, input.length - 1)][i];
      const p1 = this.phase;
      const p2 = (p1 + 0.5) % 1;
      const g1 = Math.sin(Math.PI * p1) ** 2;
      const g2 = 1 - g1;
      const r1 = this.w - p1 * W - 1;
      const r2 = this.w - p2 * W - 1;
      for (let c = 0; c < chans; c++) {
        const b = this.buf[Math.min(c, 1)];
        output[c][i] = this.read(b, r1 + this.size) * g1 + this.read(b, r2 + this.size) * g2;
      }
      this.w = (this.w + 1) & this.mask;
      this.phase += step;
      if (this.phase >= 1) this.phase -= 1;
      else if (this.phase < 0) this.phase += 1;
    }
    return true;
  }
}

registerProcessor('neri-pitch', NeriPitchShifter);
