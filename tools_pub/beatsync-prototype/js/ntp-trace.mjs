import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFile, writeFile} from 'node:fs/promises';
import vm from 'node:vm';

const reference = new URL('./reference/', import.meta.url);
const fixtures = new URL('../../../modules/ltwlan/src/test/resources/beatsync/', import.meta.url);
const manifest = JSON.parse(await readFile(new URL('UPSTREAM.json', reference), 'utf8'));
const script = await readFile(new URL('core.js', reference));
assert.equal(manifest.commit, '94c38ab16ec861835ce0b4bc3a7db82dabbb9b88');
assert.equal(createHash('sha256').update(script).digest('hex'), manifest.generatedSha256);
const binding = vm.runInNewContext(script.toString() + '\nLanBeatSync');
const invoke = (request) => JSON.parse(binding.invoke(JSON.stringify(request)));
const rows = ['group\tindex\tt0\tt1\tt2\tt3\toffset\trtt\tsynced'];
const window = [];

function reset() {
    invoke({op: 'reset'});
    window.length = 0;
    rows.push('reset');
}

function packet(group, index, transit, rtt) {
    const t0 = 1000 + group * 60 + index * 25;
    const t1 = t0 + transit;
    const t2 = t1 + 1;
    const t3 = t0 + rtt + 1;
    return {t0, t1, t2, t3, probeGroupId: group, probeGroupIndex: index};
}

function estimate(response) {
    const sample = invoke({op: 'response', now: response.t3, response});
    if (sample === null || sample.roundTripDelay < 0) return null;
    window.push(sample);
    if (window.length > 16) window.shift();
    const result = invoke({op: 'estimate', measurements: window});
    return {...result, synced: window.length === 16};
}

function probe(group, index, transit, rtt) {
    const response = packet(group, index, transit, rtt);
    const {t0, t1, t2, t3} = response;
    const result = estimate(response);
    const row = [group, index, t0, t1, t2, t3];
    if (result) row.push(result.averageOffset, result.averageRoundTrip, result.synced);
    rows.push(row.join('\t'));
}

function pair(group, transit, drift, firstRtt, secondRtt) {
    probe(group, 0, transit, firstRtt);
    probe(group, 1, transit + drift, secondRtt);
}

reset();
probe(0, 1, 55, 12);
probe(1, 0, 55, 12);
probe(2, 1, 55, 12);
probe(1, 1, 55, 10);
probe(3, 0, -70, 12);
probe(4, 0, -60, 12);
probe(3, 1, -70, 12);
probe(4, 1, -60, 9);
probe(4, 1, -60, 9);
probe(5, 0, 40, 5);
reset();
probe(5, 1, 40, 5);
pair(6, 55, 5, 10, 10);
pair(7, -40, -5, 13, 8);
pair(8, 50, 5.001, 1, 1);
pair(9, 50, -5.001, 1, 1);
pair(10, 45, 2, -1, 9);
pair(11, 45, 2, 8, -1);
reset();
pair(12, -55, 3, 4, 4);
pair(13, 80, 1, 4, 4);
for (let group = 14; group < 45; group++) {
    pair(group, group / 8, group % 7 - 3, 10 + group % 13, 9 + group % 11);
}
reset();
pair(45, 40, 0, 0, 5);

async function verifyFixture(name, lines) {
    const file = new URL(name, fixtures);
    const contents = lines.join('\n') + '\n';
    if (process.argv.includes('--write')) await writeFile(file, contents);
    else assert.equal(await readFile(file, 'utf8'), contents);
}
await verifyFixture('ntp-trace.tsv', rows);

// 固定种子和二进制编码让两个运行时能逐段比对每个探针的可见结果
let seed = 0x4c414e34;
function draw(bound) {
    seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
    return (seed >>> 8) % bound;
}
const groups = 2048;
const batches = ['lastGroup\tsha256'];
let hash = createHash('sha256');
let accepted = 0;
for (let group = 0; group < groups; group++) {
    if (group % 257 === 0) {
        invoke({op: 'reset'});
        window.length = 0;
    }
    const transit = (draw(3201) - 1600) / 8;
    const drift = (draw(97) - 48) / 8;
    const firstRtt = draw(241) / 8;
    const secondRtt = group % 13 === 0 ? firstRtt : draw(241) / 8;
    for (const response of [packet(group, 0, transit, firstRtt), packet(group, 1, transit + drift, secondRtt)]) {
        const result = estimate(response);
        if (result) {
            const bytes = Buffer.alloc(17);
            bytes[0] = result.synced ? 2 : 1;
            bytes.writeDoubleBE(result.averageOffset, 1);
            bytes.writeDoubleBE(result.averageRoundTrip, 9);
            hash.update(bytes);
            accepted++;
        } else {
            hash.update(Buffer.from([0]));
        }
    }
    if ((group + 1) % 256 === 0) {
        batches.push([group, hash.digest('hex')].join('\t'));
        hash = createHash('sha256');
    }
}
await verifyFixture('ntp-corpus.tsv', batches);
console.log(`Verified ${rows.length - 1} boundary events and ${groups} pairs (${accepted} accepted) at ${manifest.commit}.`);
