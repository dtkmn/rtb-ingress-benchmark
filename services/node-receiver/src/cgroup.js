'use strict';
const fs = require('node:fs');
const path = require('node:path');

function readCgroup(platform = process.platform, read = (filename) => {
  const value = fs.readFileSync(filename, 'utf8');
  if (Buffer.byteLength(value) > 65536) throw new Error('Kernel control file exceeds read bound');
  return value.trim();
}) {
  const result = { version: null, memory_max_bytes: null, cpu_quota_us: null, cpu_period_us: null, cpuset_cpus_effective: null, raw: {}, evidence: 'Unavailable: Linux cgroup readback is not available on this OS' };
  if (platform !== 'linux') return result;
  let groups, mounts;
  try { groups = read('/proc/self/cgroup'); mounts = read('/proc/self/mountinfo'); }
  catch { result.evidence = 'Unavailable: cannot read process cgroup membership/mounts'; return result; }
  const membership = new Map(), locations = new Map();
  for (const line of groups.split('\n')) {
    const match = /^([^:]*):([^:]*):(.*)$/.exec(line);
    if (match) for (const controller of match[2].split(',')) membership.set(controller, match[3]);
  }
  const unescape = (value) => value.replace(/\\(040|011|012|134)/g, (_, number) => ({ '040': ' ', '011': '\t', '012': '\n', '134': '\\' }[number]));
  for (const line of mounts.split('\n')) {
    const [prefix, suffix] = line.split(' - '); if (!suffix) continue;
    const left = prefix.split(' '), right = suffix.split(' '); if (left.length < 5 || right.length < 3) continue;
    const controllers = right[0] === 'cgroup2' ? [''] : right[0] === 'cgroup' ? right[2].split(',') : [];
    for (const controller of controllers) {
      const member = membership.get(controller); if (!member || member.includes('..')) continue;
      const root = unescape(left[3]), mount = unescape(left[4]);
      if (member === '/') locations.set(controller, mount);
      else if (root === '/' || member === root || member.startsWith(root.replace(/\/$/, '') + '/')) locations.set(controller, path.join(mount, root === '/' ? member.slice(1) : member.slice(root.length)));
    }
  }
  const value = (controller, filename) => {
    const directory = locations.get(controller); if (!directory) return '';
    try { const text = read(path.join(directory, filename)).trim(); result.raw[filename] = text; return text; } catch { return ''; }
  };
  const integer = (text) => /^\d+$/.test(text) && Number.isSafeInteger(Number(text)) ? Number(text) : null;
  if (locations.has('')) {
    result.version = 2;
    result.memory_max_bytes = integer(value('', 'memory.max'));
    const cpu = value('', 'cpu.max').split(/\s+/); if (cpu.length === 2) { result.cpu_quota_us = integer(cpu[0]); result.cpu_period_us = integer(cpu[1]); }
    result.cpuset_cpus_effective = value('', 'cpuset.cpus.effective') || null;
  } else {
    if (!locations.size) { result.evidence = 'Unavailable: process cgroup cannot be resolved to a visible resource mount'; return result; }
    result.version = 1;
    result.memory_max_bytes = integer(value('memory', 'memory.limit_in_bytes'));
    result.cpu_quota_us = integer(value('cpu', 'cpu.cfs_quota_us')); result.cpu_period_us = integer(value('cpu', 'cpu.cfs_period_us'));
    result.cpuset_cpus_effective = value('cpuset', 'cpuset.effective_cpus') || value('cpuset', 'cpuset.cpus') || null;
  }
  result.evidence = 'Process leaf cgroup kernel files; raw max/-1 and unrepresentable integers remain null. Ancestor CPU/memory quotas, VM limits and host contention are separate evidence.';
  return result;
}

module.exports = { readCgroup };
