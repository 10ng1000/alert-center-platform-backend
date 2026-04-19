package org.example.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 制冷设备数据处理工具集
 * 流程: 查询(MySQL mock) -> 分析 -> 生成PDF(mock)
 */
@Component
public class RefrigerationDataTools {

    private static final Logger logger = LoggerFactory.getLogger(RefrigerationDataTools.class);

    public static final String TOOL_QUERY = "queryRefrigerationDataFromMysql";
    public static final String TOOL_ANALYZE = "analyzeRefrigerationData";
    public static final String TOOL_PDF = "generateRefrigerationPdfReport";

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${refrigeration.mock-enabled:true}")
    private boolean mockEnabled;

    @Value("${refrigeration.report.output-dir:./uploads/reports}")
    private String reportOutputDir;

    @jakarta.annotation.PostConstruct
    public void init() {
        logger.info("✅ RefrigerationDataTools 初始化成功, Mock模式: {}, 报表目录: {}", mockEnabled, reportOutputDir);
    }

    @Tool(description = "Query refrigeration equipment telemetry data from MySQL. " +
            "IMPORTANT: in current version this tool runs in mock mode and returns simulated rows. " +
            "Call this tool first when user requests refrigeration data analysis for a specific time range.")
    public String queryRefrigerationDataFromMysql(
            @ToolParam(description = "Start time in ISO-8601 format, e.g. 2026-04-02T08:00:00Z") String startTime,
            @ToolParam(description = "End time in ISO-8601 format, e.g. 2026-04-02T12:00:00Z") String endTime,
            @ToolParam(description = "Comma-separated device IDs. Empty means all devices, e.g. chiller-01,chiller-02") String deviceIds,
            @ToolParam(description = "Comma-separated metrics. Supported: inletTemp,outletTemp,powerKw,cop,flowRate") String metrics,
            @ToolParam(description = "Sampling interval minutes. Default 15") Integer intervalMinutes) {
        logger.info("调用制冷数据查询工具: startTime={}, endTime={}, deviceIds={}, metrics={}",
                startTime, endTime, deviceIds, metrics);

        try {
            if (!mockEnabled) {
                return error("当前仅支持 mock 模式，请开启 refrigeration.mock-enabled=true");
            }

            Instant start = parseInstant(startTime);
            Instant end = parseInstant(endTime);
            if (end.isBefore(start)) {
                return error("endTime 不能早于 startTime");
            }

            List<String> resolvedDevices = parseCsv(deviceIds, List.of("chiller-01", "chiller-02", "cooling-pump-01"));
            List<String> resolvedMetrics = parseCsv(metrics, List.of("inletTemp", "outletTemp", "powerKw", "cop", "flowRate"));
            int minutes = (intervalMinutes == null || intervalMinutes <= 0) ? 15 : intervalMinutes;

            List<RefrigerationDataRow> rows = new ArrayList<>();
            for (String device : resolvedDevices) {
                Instant cursor = start;
                int idx = 0;
                while (!cursor.isAfter(end)) {
                    rows.add(mockRow(device, cursor, idx, resolvedMetrics));
                    cursor = cursor.plus(Duration.ofMinutes(minutes));
                    idx++;
                }
            }

            QueryOutput output = new QueryOutput();
            output.setSuccess(true);
            output.setSource("mysql-mock");
            output.setMessage(String.format(Locale.ROOT, "mock 查询成功，共 %d 条数据", rows.size()));
            output.setStartTime(start.toString());
            output.setEndTime(end.toString());
            output.setDeviceIds(resolvedDevices);
            output.setMetrics(resolvedMetrics);
            output.setIntervalMinutes(minutes);
            output.setRows(rows);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
        } catch (Exception e) {
            logger.error("查询制冷设备数据失败", e);
            return error("查询失败: " + e.getMessage());
        }
    }

    @Tool(description = "Analyze refrigeration telemetry query result. " +
            "Input should be JSON returned by queryRefrigerationDataFromMysql. " +
            "This tool calculates min/max/avg statistics and basic risk hints based on user goal.")
    public String analyzeRefrigerationData(
            @ToolParam(description = "JSON string returned by queryRefrigerationDataFromMysql") String queryResultJson,
            @ToolParam(description = "Analysis goal from user, e.g. energy optimization or fault diagnosis") String analysisGoal) {
        logger.info("调用制冷数据分析工具, analysisGoal={}", analysisGoal);

        try {
            JsonNode root = objectMapper.readTree(queryResultJson);
            ArrayNode rows = (ArrayNode) root.path("rows");
            if (rows.isEmpty()) {
                return error("无可分析数据，请先调用 queryRefrigerationDataFromMysql 并确认返回 rows");
            }

            List<Double> inletTemps = new ArrayList<>();
            List<Double> outletTemps = new ArrayList<>();
            List<Double> powerKws = new ArrayList<>();
            List<Double> cops = new ArrayList<>();
            List<Double> flowRates = new ArrayList<>();

            for (JsonNode row : rows) {
                addIfNumber(inletTemps, row, "inletTemp");
                addIfNumber(outletTemps, row, "outletTemp");
                addIfNumber(powerKws, row, "powerKw");
                addIfNumber(cops, row, "cop");
                addIfNumber(flowRates, row, "flowRate");
            }

            AnalysisOutput output = new AnalysisOutput();
            output.setSuccess(true);
            output.setAnalysisGoal(analysisGoal == null ? "" : analysisGoal);
            output.setSampleCount(rows.size());
            output.setInletTemp(statsOf(inletTemps));
            output.setOutletTemp(statsOf(outletTemps));
            output.setPowerKw(statsOf(powerKws));
            output.setCop(statsOf(cops));
            output.setFlowRate(statsOf(flowRates));
            output.setRiskHints(buildRiskHints(output));
            output.setMessage("分析完成，可将本结果传给 generateRefrigerationPdfReport 生成报表");

            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
        } catch (Exception e) {
            logger.error("分析制冷设备数据失败", e);
            return error("分析失败: " + e.getMessage());
        }
    }

    @Tool(description = "Generate refrigeration analysis PDF report. " +
            "IMPORTANT: this is a mock implementation that writes text content into a .pdf file path.")
    public String generateRefrigerationPdfReport(
            @ToolParam(description = "Report title") String reportTitle,
            @ToolParam(description = "Structured analysis JSON from analyzeRefrigerationData") String analysisJson,
            @ToolParam(description = "Optional output file name, e.g. report-20260402.pdf") String outputFileName) {
        logger.info("调用制冷 PDF 报表工具, title={}", reportTitle);

        try {
            if (!mockEnabled) {
                return error("当前仅支持 mock 模式，请开启 refrigeration.mock-enabled=true");
            }

            String safeName = (outputFileName == null || outputFileName.isBlank())
                    ? "refrigeration-report-" + System.currentTimeMillis() + ".pdf"
                    : outputFileName;
            if (!safeName.endsWith(".pdf")) {
                safeName = safeName + ".pdf";
            }

            Path dir = Paths.get(reportOutputDir);
            Files.createDirectories(dir);

            Path file = dir.resolve(safeName);
            String content = "# Refrigeration Report (MOCK PDF)\n\n"
                    + "title: " + (reportTitle == null ? "Untitled" : reportTitle) + "\n"
                    + "generatedAt: " + FORMATTER.format(Instant.now()) + "\n\n"
                    + "analysis:\n"
                    + analysisJson + "\n";
            Files.writeString(file, content, StandardCharsets.UTF_8);

            Map<String, Object> result = Map.of(
                    "success", true,
                    "mock", true,
                    "message", "PDF 报表生成成功（mock）",
                    "filePath", file.toString(),
                    "fileName", safeName
            );
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (Exception e) {
            logger.error("生成制冷 PDF 报表失败", e);
            return error("生成报表失败: " + e.getMessage());
        }
    }

    private RefrigerationDataRow mockRow(String deviceId, Instant ts, int idx, List<String> metrics) {
        double inletTempBase = "chiller-02".equals(deviceId) ? 15.8 : 15.0;
        double outletTempBase = "chiller-02".equals(deviceId) ? 7.3 : 7.0;
        double powerBase = "cooling-pump-01".equals(deviceId) ? 95.0 : 210.0;

        RefrigerationDataRow row = new RefrigerationDataRow();
        row.setDeviceId(deviceId);
        row.setTimestamp(ts.toString());

        if (metrics.contains("inletTemp")) {
            row.setInletTemp(round(inletTempBase + (idx % 5) * 0.3));
        }
        if (metrics.contains("outletTemp")) {
            row.setOutletTemp(round(outletTempBase + (idx % 4) * 0.2));
        }
        if (metrics.contains("powerKw")) {
            row.setPowerKw(round(powerBase + (idx % 7) * 3.8));
        }
        if (metrics.contains("cop")) {
            row.setCop(round(4.6 - (idx % 6) * 0.08));
        }
        if (metrics.contains("flowRate")) {
            row.setFlowRate(round(128.0 + (idx % 5) * 2.5));
        }
        return row;
    }

    private Stats statsOf(List<Double> values) {
        if (values.isEmpty()) {
            return new Stats(0.0, 0.0, 0.0);
        }
        double min = values.stream().mapToDouble(v -> v).min().orElse(0.0);
        double max = values.stream().mapToDouble(v -> v).max().orElse(0.0);
        double avg = values.stream().mapToDouble(v -> v).average().orElse(0.0);
        return new Stats(round(min), round(max), round(avg));
    }

    private List<String> buildRiskHints(AnalysisOutput output) {
        List<String> hints = new ArrayList<>();
        if (output.getCop() != null && output.getCop().getAvg() < 4.2) {
            hints.add("COP 均值偏低，建议检查换热效率或冷凝器结垢情况");
        }
        if (output.getPowerKw() != null && output.getPowerKw().getMax() > 240) {
            hints.add("功率峰值较高，建议核查是否存在高负载尖峰或控制策略不稳定");
        }
        if (output.getInletTemp() != null && output.getOutletTemp() != null) {
            double delta = output.getInletTemp().getAvg() - output.getOutletTemp().getAvg();
            if (delta < 6.5) {
                hints.add("进出水温差偏小，建议检查流量设定与阀门开度");
            }
        }
        if (hints.isEmpty()) {
            hints.add("未发现显著风险，可继续监控负载波动与能效趋势");
        }
        return hints;
    }

    private List<String> parseCsv(String csv, List<String> defaultValues) {
        if (csv == null || csv.isBlank()) {
            return defaultValues;
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("时间参数不能为空");
        }
        return Instant.parse(value);
    }

    private void addIfNumber(List<Double> container, JsonNode row, String field) {
        JsonNode node = row.get(field);
        if (node != null && node.isNumber()) {
            container.add(node.asDouble());
        }
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String error(String msg) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "success", false,
                    "message", msg
            ));
        } catch (Exception e) {
            return "{\"success\":false,\"message\":\"" + msg + "\"}";
        }
    }

    @Data
    public static class RefrigerationDataRow {
        private String deviceId;
        private String timestamp;
        private Double inletTemp;
        private Double outletTemp;
        private Double powerKw;
        private Double cop;
        private Double flowRate;
    }

    @Data
    public static class QueryOutput {
        private boolean success;
        private String source;
        private String message;
        private String startTime;
        private String endTime;
        private List<String> deviceIds;
        private List<String> metrics;
        private int intervalMinutes;
        private List<RefrigerationDataRow> rows;
    }

    @Data
    public static class AnalysisOutput {
        private boolean success;
        private String analysisGoal;
        private int sampleCount;
        private Stats inletTemp;
        private Stats outletTemp;
        private Stats powerKw;
        private Stats cop;
        private Stats flowRate;
        private List<String> riskHints;
        private String message;
    }

    @Data
    public static class Stats {
        private double min;
        private double max;
        private double avg;

        public Stats(double min, double max, double avg) {
            this.min = min;
            this.max = max;
            this.avg = avg;
        }
    }
}