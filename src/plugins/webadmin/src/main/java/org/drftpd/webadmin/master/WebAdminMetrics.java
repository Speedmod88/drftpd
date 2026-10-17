package org.drftpd.webadmin.master;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

final class WebAdminMetrics {
    private WebAdminMetrics() { }

    static Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        var os = ManagementFactory.getOperatingSystemMXBean();
        double[] load = { os.getSystemLoadAverage(), -1, -1 };
        try { load = parseLoad(Files.readString(Path.of("/proc/loadavg"))); }
        catch (Exception ignored) { }
        result.put("loadAverages", load);
        result.put("systemCpu", -1D);
        result.put("processCpu", -1D);
        result.put("memoryTotalBytes", -1L);
        result.put("memoryFreeBytes", -1L);
        if (os instanceof com.sun.management.OperatingSystemMXBean extended) {
            result.put("systemCpu", extended.getCpuLoad());
            result.put("processCpu", extended.getProcessCpuLoad());
            result.put("memoryTotalBytes", extended.getTotalMemorySize());
            result.put("memoryFreeBytes", extended.getFreeMemorySize());
        }
        result.put("sampledAt", System.currentTimeMillis());
        result.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        return result;
    }

    static double[] parseLoad(String text) {
        String[] fields = text.trim().split("\\s+");
        if (fields.length < 3) throw new IllegalArgumentException("Missing load averages");
        double[] values = new double[3];
        for (int i = 0; i < 3; i++) {
            values[i] = Double.parseDouble(fields[i]);
            if (!Double.isFinite(values[i]) || values[i] < 0) throw new IllegalArgumentException("Invalid load average");
        }
        return values;
    }
}
