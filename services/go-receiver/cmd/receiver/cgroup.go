package main

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
)

func cgroupSnapshot() map[string]any {
	return readCgroup(runtime.GOOS, func(path string) (string, error) {
		file, err := os.Open(path)
		if err != nil {
			return "", err
		}
		defer file.Close()
		data, err := io.ReadAll(io.LimitReader(file, 65537))
		if len(data) > 65536 {
			return "", fmt.Errorf("kernel control file exceeds read bound")
		}
		return strings.TrimSpace(string(data)), err
	})
}

// Only fixed process membership/mount files and known resource filenames are
// read. No route parameter can select a path. Resolve membership so a native
// Linux process is not mistakenly reported using the host's root cgroup.
func readCgroup(platform string, read func(string) (string, error)) map[string]any {
	result := map[string]any{"version": nil, "memory_max_bytes": nil, "cpu_quota_us": nil, "cpu_period_us": nil, "cpuset_cpus_effective": nil, "raw": map[string]string{}, "evidence": "Unavailable: Linux cgroup readback is not available on this OS"}
	if platform != "linux" {
		return result
	}
	groups, err := read("/proc/self/cgroup")
	if err != nil {
		result["evidence"] = "Unavailable: cannot read process cgroup membership"
		return result
	}
	mounts, err := read("/proc/self/mountinfo")
	if err != nil {
		result["evidence"] = "Unavailable: cannot read cgroup mounts"
		return result
	}
	membership := map[string]string{}
	for _, line := range strings.Split(groups, "\n") {
		parts := strings.SplitN(line, ":", 3)
		if len(parts) == 3 {
			for _, controller := range strings.Split(parts[1], ",") {
				membership[controller] = parts[2]
			}
		}
	}
	locations := map[string]string{}
	unescape := strings.NewReplacer(`\040`, " ", `\011`, "\t", `\012`, "\n", `\134`, `\`)
	for _, line := range strings.Split(mounts, "\n") {
		sections := strings.SplitN(line, " - ", 2)
		if len(sections) != 2 {
			continue
		}
		left, right := strings.Fields(sections[0]), strings.Fields(sections[1])
		if len(left) < 5 || len(right) < 3 {
			continue
		}
		controllers := strings.Split(right[2], ",")
		if right[0] == "cgroup2" {
			controllers = []string{""}
		} else if right[0] != "cgroup" {
			continue
		}
		for _, controller := range controllers {
			member, ok := membership[controller]
			if !ok {
				continue
			}
			root, mount := unescape.Replace(left[3]), unescape.Replace(left[4])
			if strings.Contains(member, "..") {
				continue
			}
			if member == "/" {
				locations[controller] = mount
				continue
			}
			if root == "/" || member == root || strings.HasPrefix(member, strings.TrimRight(root, "/")+"/") {
				locations[controller] = filepath.Join(mount, strings.TrimPrefix(member, root))
			}
		}
	}
	raw := result["raw"].(map[string]string)
	value := func(directory, name string) string {
		if directory == "" {
			return ""
		}
		text, err := read(filepath.Join(directory, name))
		if err != nil {
			return ""
		}
		text = strings.TrimSpace(text)
		raw[name] = text
		return text
	}
	integer := func(text string) any {
		number, err := strconv.ParseInt(text, 10, 64)
		if err != nil || number < 0 {
			return nil
		}
		return number
	}
	if directory := locations[""]; directory != "" {
		result["version"] = 2
		result["memory_max_bytes"] = integer(value(directory, "memory.max"))
		cpu := strings.Fields(value(directory, "cpu.max"))
		if len(cpu) == 2 {
			result["cpu_quota_us"] = integer(cpu[0])
			result["cpu_period_us"] = integer(cpu[1])
		}
		if cpuset := value(directory, "cpuset.cpus.effective"); cpuset != "" {
			result["cpuset_cpus_effective"] = cpuset
		}
	} else {
		if len(locations) == 0 {
			result["evidence"] = "Unavailable: process cgroup cannot be resolved to a visible resource mount"
			return result
		}
		result["version"] = 1
		memory := value(locations["memory"], "memory.limit_in_bytes")
		if parsed, err := strconv.ParseInt(memory, 10, 64); err == nil && parsed >= 0 && parsed != (int64(^uint64(0)>>1) & ^int64(os.Getpagesize()-1)) {
			result["memory_max_bytes"] = parsed
		}
		result["cpu_quota_us"] = integer(value(locations["cpu"], "cpu.cfs_quota_us"))
		result["cpu_period_us"] = integer(value(locations["cpu"], "cpu.cfs_period_us"))
		cpuset := value(locations["cpuset"], "cpuset.effective_cpus")
		if cpuset == "" {
			cpuset = value(locations["cpuset"], "cpuset.cpus")
		}
		if cpuset != "" {
			result["cpuset_cpus_effective"] = cpuset
		}
	}
	result["evidence"] = "Read process leaf cgroup kernel files; raw max/-1 denotes unlimited, missing values remain null. CPU/memory ancestor quotas, VM limits and host contention are separate evidence."
	return result
}
