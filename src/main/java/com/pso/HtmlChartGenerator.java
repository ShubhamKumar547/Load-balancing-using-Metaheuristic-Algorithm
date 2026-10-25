
package com.pso;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class HtmlChartGenerator {

    // FIX: Keep metric indexes consistent with result.csv columns.
    private static final int MAKESPAN = 0;
    private static final int RESPONSE_TIME = 1;
    private static final int IMBALANCE = 2;
    private static final int THROUGHPUT = 3;
    private static final int METRIC_COUNT = 4;

    private static final List<String> METRIC_NAMES = Arrays.asList(
        "Makespan",
        "Average Response Time",
        "Load Imbalance",
        "Throughput"
    );

    private static final List<String> ALGORITHM_ORDER = Arrays.asList(
        "PSO", "RoundRobin", "Random", "LeastLoaded"
    );

    private static final Map<String, String> COLORS = Map.of(
        "PSO", "#3984e8",
        "RoundRobin", "#ef6262",
        "Random", "#e6ad38",
        "LeastLoaded", "#36a58b"
    );

    // FIX: Store sums and counts separately to avoid incorrect averages
    // when an algorithm has no results for a scenario.
    private static class Stats {
        private final double[] sums = new double[METRIC_COUNT];
        private int count = 0;

        void add(double makespan, double responseTime,
                 double imbalance, double throughput) {
            sums[MAKESPAN] += makespan;
            sums[RESPONSE_TIME] += responseTime;
            sums[IMBALANCE] += imbalance;
            sums[THROUGHPUT] += throughput;
            count++;
        }

        double average(int metric) {
            return count == 0 ? Double.NaN : sums[metric] / count;
        }
    }

    public static void generate(String csvPath, String htmlPath)
            throws IOException {

        Path input = Path.of(csvPath);
        Path output = Path.of(htmlPath);

        if (!Files.exists(input)) {
            throw new IOException("CSV file not found: " + input.toAbsolutePath());
        }

        List<String> lines = Files.readAllLines(input, StandardCharsets.UTF_8);

        if (lines.size() < 2) {
            throw new IOException("CSV file has no result rows: " + csvPath);
        }

        // Scenario -> Algorithm -> Statistics.
        Map<String, Map<String, Stats>> data = new LinkedHashMap<>();

        int validRows = 0;
        int skippedRows = 0;

        // FIX: Parse and validate every result row before aggregating it.
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();

            if (line.isEmpty()) {
                continue;
            }

            String[] parts = line.split(",", -1);

            if (parts.length < 7) {
                System.err.println("Skipping malformed CSV row " + (i + 1));
                skippedRows++;
                continue;
            }

            try {
                String scenario = parts[0].trim();
                String algorithm = parts[1].trim();

                if (scenario.isEmpty() || algorithm.isEmpty()) {
                    throw new IllegalArgumentException("Missing scenario or algorithm");
                }

                double makespan = parseMetric(parts[3]);
                double responseTime = parseMetric(parts[4]);
                double imbalance = parseMetric(parts[5]);
                double throughput = parseMetric(parts[6]);

                // FIX: Invalid metrics must not silently enter the report.
                if (makespan < 0 || responseTime < 0 ||
                    imbalance < 0 || throughput < 0) {
                    throw new IllegalArgumentException("Negative metric");
                }

                data.computeIfAbsent(scenario, k -> new LinkedHashMap<>())
                    .computeIfAbsent(algorithm, k -> new Stats())
                    .add(makespan, responseTime, imbalance, throughput);

                validRows++;

            } catch (RuntimeException ex) {
                System.err.println(
                    "Skipping invalid CSV row " + (i + 1) + ": " + ex.getMessage()
                );
                skippedRows++;
            }
        }

        if (validRows == 0) {
            throw new IOException("No valid result rows were found in " + csvPath);
        }

        // FIX: Preserve the standard algorithm order and include other algorithms
        // found in the CSV instead of silently discarding them.
        List<String> algorithms = new ArrayList<>(ALGORITHM_ORDER);

        for (Map<String, Stats> scenarioData : data.values()) {
            for (String algorithm : scenarioData.keySet()) {
                if (!algorithms.contains(algorithm)) {
                    algorithms.add(algorithm);
                }
            }
        }

        String labelsJson = jsonArray(new ArrayList<>(data.keySet()));

        String makespanDatasets = buildDatasets(data, algorithms, MAKESPAN);
        String responseDatasets = buildDatasets(data, algorithms, RESPONSE_TIME);
        String imbalanceDatasets = buildDatasets(data, algorithms, IMBALANCE);
        String throughputDatasets = buildDatasets(data, algorithms, THROUGHPUT);

        String summaryRows = buildSummaryRows(data, algorithms);
        String scenarioCards = buildScenarioCards(data, algorithms);

        String html = buildHtml()
            .replace("__SCENARIOS__", labelsJson)
            .replace("__MAKESPAN_DATASETS__", makespanDatasets)
            .replace("__RESPONSE_DATASETS__", responseDatasets)
            .replace("__IMBALANCE_DATASETS__", imbalanceDatasets)
            .replace("__THROUGHPUT_DATASETS__", throughputDatasets)
            .replace("__SUMMARY_ROWS__", summaryRows)
            .replace("__SCENARIO_CARDS__", scenarioCards)
            .replace("__VALID_ROWS__", String.valueOf(validRows))
            .replace("__SKIPPED_ROWS__", String.valueOf(skippedRows))
            .replace("__SCENARIO_COUNT__", String.valueOf(data.size()));

        // FIX: Write using UTF-8 and create the output directory if necessary.
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Files.writeString(output, html, StandardCharsets.UTF_8);

        System.out.println("HTML report generated successfully.");
        System.out.println("Input CSV : " + input.toAbsolutePath());
        System.out.println("HTML report: " + output.toAbsolutePath());
        System.out.println("Valid rows : " + validRows);
        System.out.println("Skipped rows: " + skippedRows);
    }

    private static double parseMetric(String value) {
        double number = Double.parseDouble(value.trim());

        if (!Double.isFinite(number)) {
            throw new IllegalArgumentException("Metric must be finite: " + value);
        }

        return number;
    }

    private static String buildDatasets(
            Map<String, Map<String, Stats>> data,
            List<String> algorithms,
            int metric) {

        StringBuilder result = new StringBuilder();

        for (int a = 0; a < algorithms.size(); a++) {
            String algorithm = algorithms.get(a);

            if (a > 0) {
                result.append(",");
            }

            String color = COLORS.getOrDefault(algorithm, "#8b7cc8");

            result.append("{")
                .append("label: ").append(json(algorithm)).append(",")
                .append("data: [");

            int s = 0;

            for (Map<String, Stats> scenarioData : data.values()) {
                if (s++ > 0) {
                    result.append(",");
                }

                Stats stats = scenarioData.get(algorithm);

                // FIX: Use null for missing results rather than falsely reporting zero.
                if (stats == null || stats.count == 0) {
                    result.append("null");
                } else {
                    result.append(number(stats.average(metric)));
                }
            }

            result.append("],")
                .append("backgroundColor: ").append(json(color)).append(",")
                .append("borderColor: ").append(json(color)).append(",")
                .append("borderWidth: 1")
                .append("}");
        }

        return result.toString();
    }

    private static String buildSummaryRows(
            Map<String, Map<String, Stats>> data,
            List<String> algorithms) {

        StringBuilder rows = new StringBuilder();

        for (Map.Entry<String, Map<String, Stats>> scenario : data.entrySet()) {
            for (String algorithm : algorithms) {
                Stats stats = scenario.getValue().get(algorithm);

                if (stats == null) {
                    continue;
                }

                rows.append("<tr>")
                    .append("<td>").append(escapeHtml(scenario.getKey())).append("</td>")
                    .append("<td>").append(escapeHtml(algorithm)).append("</td>")
                    .append("<td>").append(stats.count).append("</td>");

                for (int metric = 0; metric < METRIC_COUNT; metric++) {
                    rows.append("<td>")
                        .append(format(stats.average(metric)))
                        .append("</td>");
                }

                // FIX: Compare PSO with the best baseline in the same scenario.
                double bestBaselineMakespan = Double.POSITIVE_INFINITY;

                if ("PSO".equals(algorithm)) {
                    for (String other : algorithms) {
                        if ("PSO".equals(other)) {
                            continue;
                        }

                        Stats baseline = scenario.getValue().get(other);

                        if (baseline != null && baseline.count > 0) {
                            bestBaselineMakespan = Math.min(
                                bestBaselineMakespan,
                                baseline.average(MAKESPAN)
                            );
                        }
                    }
                }

                rows.append("<td>");

                if ("PSO".equals(algorithm) &&
                    Double.isFinite(bestBaselineMakespan) &&
                    bestBaselineMakespan > 0) {

                    double psoMakespan = stats.average(MAKESPAN);
                    double improvement =
                        (bestBaselineMakespan - psoMakespan)
                        / bestBaselineMakespan * 100.0;

                    rows.append(String.format(
                        Locale.US, "%+.2f%%", improvement
                    ));

                } else if ("PSO".equals(algorithm)) {
                    rows.append("N/A");
                } else {
                    rows.append("—");
                }

                rows.append("</td></tr>");
            }
        }

        return rows.toString();
    }

    private static String buildScenarioCards(
            Map<String, Map<String, Stats>> data,
            List<String> algorithms) {

        StringBuilder cards = new StringBuilder();

        for (Map.Entry<String, Map<String, Stats>> scenario : data.entrySet()) {
            String fastestAlgorithm = null;
            double fastestMakespan = Double.POSITIVE_INFINITY;

            for (String algorithm : algorithms) {
                Stats stats = scenario.getValue().get(algorithm);

                if (stats != null &&
                    stats.average(MAKESPAN) < fastestMakespan) {
                    fastestMakespan = stats.average(MAKESPAN);
                    fastestAlgorithm = algorithm;
                }
            }

            cards.append("<div class=\"scenario-card\">")
                .append("<h3>").append(escapeHtml(scenario.getKey())).append("</h3>")
                .append("<p><strong>Lowest average makespan:</strong> ")
                .append(fastestAlgorithm == null
                    ? "N/A" : escapeHtml(fastestAlgorithm))
                .append("</p>");

            if (fastestAlgorithm != null) {
                cards.append("<p><strong>Makespan:</strong> ")
                    .append(format(fastestMakespan))
                    .append("</p>");
            }

            cards.append("<p><strong>Algorithms with results:</strong> ")
                .append(scenario.getValue().size())
                .append("</p></div>");
        }

        return cards.toString();
    }

    private static String jsonArray(List<String> values) {
        StringBuilder result = new StringBuilder("[");

        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                result.append(",");
            }

            result.append(json(values.get(i)));
        }

        return result.append("]").toString();
    }

    // FIX: Escape strings so scenario and algorithm names remain valid JavaScript.
    private static String json(String value) {
        StringBuilder result = new StringBuilder("\"");

        for (char c : value.toCharArray()) {
            switch (c) {
                case '"'  -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 32) {
                        result.append(String.format("\\u%04x", (int) c));
                    } else {
                        result.append(c);
                    }
                }
            }
        }

        return result.append("\"").toString();
    }

    private static String number(double value) {
        return Double.isFinite(value)
            ? String.format(Locale.US, "%.6f", value)
            : "null";
    }

    private static String format(double value) {
        return Double.isFinite(value)
            ? String.format(Locale.US, "%.4f", value)
            : "N/A";
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    private static String buildHtml() {
        // FIX: Use replaceable placeholders instead of String.formatted(),
        // avoiding accidental formatting of CSS percentage signs.
        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>PSO Load Balancer Comparative Analysis</title>
            <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>

            <style>
                * { box-sizing: border-box; }

                body {
                    margin: 0;
                    background: #f4f6fa;
                    color: #202938;
                    font-family: Arial, Helvetica, sans-serif;
                }

                header {
                    padding: 30px 20px;
                    text-align: center;
                    background: #182338;
                    color: white;
                }

                header h1 { margin: 0 0 10px; }
                header p { margin: 0; color: #d3dceb; }

                main {
                    max-width: 1400px;
                    margin: 24px auto;
                    padding: 0 20px 40px;
                }

                .stats-grid, .charts-grid, .scenario-grid {
                    display: grid;
                    grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
                    gap: 20px;
                    margin-bottom: 24px;
                }

                .panel, .stat-card, .scenario-card {
                    background: white;
                    border-radius: 12px;
                    padding: 20px;
                    box-shadow: 0 2px 10px rgba(20, 30, 50, 0.06);
                }

                .stat-card h3 {
                    margin: 0 0 10px;
                    color: #687386;
                    font-size: 14px;
                }

                .stat-card .value {
                    font-size: 27px;
                    font-weight: bold;
                }

                .panel h2 { margin-top: 0; font-size: 19px; }

                .chart-area {
                    position: relative;
                    height: 340px;
                }

                .table-wrap { overflow-x: auto; }

                table {
                    width: 100%;
                    border-collapse: collapse;
                    white-space: nowrap;
                    font-size: 14px;
                }

                th, td {
                    padding: 12px;
                    text-align: left;
                    border-bottom: 1px solid #e8edf3;
                }

                th { background: #f1f4f8; }

                tr:hover td { background: #f8faff; }

                .note {
                    color: #596579;
                    line-height: 1.6;
                    font-size: 14px;
                }

                .scenario-card h3 { margin-top: 0; }

                footer {
                    text-align: center;
                    color: #687386;
                    padding: 15px;
                    font-size: 13px;
                }

                @media (max-width: 600px) {
                    main { padding: 0 10px 25px; }
                    .chart-area { height: 280px; }
                }
            </style>
        </head>

        <body>
            <header>
                <h1>Load Balancing Algorithm Comparison</h1>
                <p>PSO vs Round Robin vs Random vs Least Loaded</p>
            </header>

            <main>
                <section class="stats-grid">
                    <div class="stat-card">
                        <h3>Scenarios analysed</h3>
                        <div class="value">__SCENARIO_COUNT__</div>
                    </div>

                    <div class="stat-card">
                        <h3>Valid experiment runs</h3>
                        <div class="value">__VALID_ROWS__</div>
                    </div>

                    <div class="stat-card">
                        <h3>Skipped CSV rows</h3>
                        <div class="value">__SKIPPED_ROWS__</div>
                    </div>
                </section>

                <section class="panel">
                    <h2>Scenario overview</h2>
                    <div class="scenario-grid">__SCENARIO_CARDS__</div>
                </section>

                <section class="charts-grid">
                    <div class="panel">
                        <h2>Average Makespan (lower is better)</h2>
                        <div class="chart-area">
                            <canvas id="makespanChart"></canvas>
                        </div>
                    </div>

                    <div class="panel">
                        <h2>Average Response Time (lower is better)</h2>
                        <div class="chart-area">
                            <canvas id="responseChart"></canvas>
                        </div>
                    </div>

                    <div class="panel">
                        <h2>Average Load Imbalance (lower is better)</h2>
                        <div class="chart-area">
                            <canvas id="imbalanceChart"></canvas>
                        </div>
                    </div>

                    <div class="panel">
                        <h2>Average Throughput (higher is better)</h2>
                        <div class="chart-area">
                            <canvas id="throughputChart"></canvas>
                        </div>
                    </div>
                </section>

                <section class="panel">
                    <h2>Detailed comparative results</h2>
                    <p class="note">
                        Each row reports averages across the available runs for that
                        scenario and algorithm. A positive PSO improvement percentage
                        means PSO has a lower average makespan than the best available
                        baseline in the same scenario; a negative value means it is slower.
                    </p>

                    <div class="table-wrap">
                        <table>
                            <thead>
                                <tr>
                                    <th>Scenario</th>
                                    <th>Algorithm</th>
                                    <th>Runs</th>
                                    <th>Avg Makespan</th>
                                    <th>Avg Response Time</th>
                                    <th>Avg Imbalance</th>
                                    <th>Avg Throughput</th>
                                    <th>PSO Makespan Improvement</th>
                                </tr>
                            </thead>
                            <tbody>__SUMMARY_ROWS__</tbody>
                        </table>
                    </div>
                </section>

                <section class="panel">
                    <h2>How to interpret the results</h2>
                    <ul class="note">
                        <li><strong>Makespan:</strong> time when the last cloudlet finishes.</li>
                        <li><strong>Response time:</strong> interpret according to the
                            calculation implemented in your simulation.</li>
                        <li><strong>Load imbalance:</strong> lower values indicate a more
                            balanced distribution under the metric used by your code.</li>
                        <li><strong>Throughput:</strong> completed cloudlets per unit of
                            simulation time.</li>
                        <li>Compare algorithms only within the same scenario and workload.</li>
                        <li>These are descriptive averages; multiple runs and variability
                            statistics are needed for stronger research conclusions.</li>
                    </ul>
                </section>
            </main>

            <footer>
                Generated from result.csv | PSO Load Balancer Research
            </footer>

            <script>
                const labels = __SCENARIOS__;

                // FIX: Keep metric datasets separated for accurate comparisons.
                const chartConfigs = [
                    {
                        id: "makespanChart",
                        datasets: [__MAKESPAN_DATASETS__],
                        title: "Makespan"
                    },
                    {
                        id: "responseChart",
                        datasets: [__RESPONSE_DATASETS__],
                        title: "Average Response Time"
                    },
                    {
                        id: "imbalanceChart",
                        datasets: [__IMBALANCE_DATASETS__],
                        title: "Load Imbalance"
                    },
                    {
                        id: "throughputChart",
                        datasets: [__THROUGHPUT_DATASETS__],
                        title: "Throughput"
                    }
                ];

                chartConfigs.forEach(config => {
                    new Chart(document.getElementById(config.id), {
                        type: "bar",
                        data: {
                            labels: labels,
                            datasets: config.datasets
                        },
                        options: {
                            responsive: true,
                            maintainAspectRatio: false,
                            interaction: {
                                mode: "index",
                                intersect: false
                            },
                            plugins: {
                                legend: {
                                    position: "bottom"
                                },
                                tooltip: {
                                    callbacks: {
                                        label: context => {
                                            const value = context.parsed.y;
                                            return context.dataset.label + ": " +
                                                (value === null ? "No data" :
                                                    value.toFixed(4));
                                        }
                                    }
                                }
                            },
                            scales: {
                                x: {
                                    title: {
                                        display: true,
                                        text: "Scenario"
                                    }
                                },
                                y: {
                                    beginAtZero: true,
                                    title: {
                                        display: true,
                                        text: config.title
                                    }
                                }
                            }
                        }
                    });
                });
            </script>
        </body>
        </html>
        """;
    }

    // FIX: Add a main method so the report can be generated directly from Maven.
    public static void main(String[] args) throws Exception {
        String csvPath = args.length > 0 ? args[0] : "result.csv";
        String htmlPath = args.length > 1
            ? args[1]
            : "results/comparative-analysis.html";

        generate(csvPath, htmlPath);
    }
}
