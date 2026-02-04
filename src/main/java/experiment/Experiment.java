package experiment;

import domain.mining.*;
import infrastructure.persistence.UncertainDatabase;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.*;

/**
 * Experiment 1: Four-variant performance comparison (V1–V4).
 *
 * <p>Runs all four algorithm variants across every dataset and {@code k} value
 * defined in {@link ExperimentConfig}.  Three metrics are recorded per
 * (dataset, k, variant), each averaged over {@link ExperimentConfig#NUM_RUNS}
 * measured repetitions with sample standard deviation (n−1 denominator):</p>
 * <ul>
 *   <li><b>Runtime (ms)</b>        – wall-clock time around {@code mine()}.
 *       One unmeasured warmup iteration per variant is executed at the start of
 *       each dataset to allow the JIT compiler to compile hot paths before any
 *       timed measurement begins.</li>
 *   <li><b>Closure checks</b>      – total closure-check invocations.
 *       Deterministic for a given (dataset, k, variant); std dev is always 0.</li>
 *   <li><b>Memory (MB)</b>         – JVM heap delta.  Baseline and post-mining
 *       snapshots both use {@code MemoryMXBean.getHeapMemoryUsage().getUsed()}.
 *       {@code System.gc()} is called before the baseline only; the post-mining
 *       snapshot is taken without gc so that working-set footprint (including
 *       not-yet-collected objects) is captured.</li>
 * </ul>
 *
 * <h3>Output files  –  {@code results/exp1/}</h3>
 * <ul>
 *   <li>{@code all_metrics.csv}    – long format with mean and std dev columns.
 *       One row per (dataset, k, variant).  Use for plotting / analysis.</li>
 *   <li>{@code runtime.csv}        – wide format with mean and std columns per
 *       variant.  Copy-paste ready for paper tables.</li>
 *   <li>{@code closure_checks.csv} – wide format, mean only (deterministic).</li>
 *   <li>{@code memory.csv}         – wide format with mean and std columns.</li>
 * </ul>
 *
 * @author Dang Nguyen Le, Gia Huy Vo
 */
public class Experiment {

    private static final String[] VARIANTS = {"V1", "V2", "V3", "V4"};//, "V5", "V6"};

    /** k used for the unmeasured JIT-warmup run.  Small to minimise warmup cost
     *  while still exercising every code path (TopK fill, closure, extensions). */
    private static final int WARMUP_K = 0;

    // ═══════════════════════════════════════════════════════════════════════════
    //  Public entry point
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Run experiment 1.  Called by {@link ExperimentRunner}.
     */
    public static void runExp1() {
        String outputDir = ExperimentConfig.RESULTS_DIR + "exp1/";
        ResultExporter.createResultsDirectory(outputDir);

        // Ordered collection preserving iteration order:
        //   dataset → k → variant → metrics[5]
        //     [0] runtime_mean (ms)   [1] runtime_std (ms)
        //     [2] closure_checks
        //     [3] memory_mean (MB)    [4] memory_std  (MB)
        Map<String, LinkedHashMap<Integer, LinkedHashMap<String, double[]>>> collected
                = new LinkedHashMap<>();

        printHeader();

        for (String dataset : ExperimentConfig.DATASETS) {
            String filepath  = ExperimentConfig.DATASET_DIR + dataset;
            String shortName = dataset.replace("_uncertain.txt", "");

            UncertainDatabase db;
            try {
                db = UncertainDatabase.loadFromFile(filepath);
            } catch (IOException e) {
                System.err.println("[SKIP] " + dataset + ": " + e.getMessage());
                continue;
            }

            System.out.println("─".repeat(65));
            System.out.printf("Dataset: %s  (%d transactions, %d items)%n",
                    shortName, db.size(), db.getVocabulary().size());

            // ── JIT warmup ──────────────────────────────────────────────────
            // One unmeasured run per variant.  All shared (AbstractMiner) and
            // variant-specific hot methods are compiled before any timed run.
            // Uses a small k to keep warmup fast.
            System.out.println("  (JIT warmup — unmeasured)");
            for (String variant : VARIANTS) {
                try {
                    createMiner(variant, db, ExperimentConfig.TAU, WARMUP_K).mine();
                } catch (Exception e) {
                    // warmup failure is non-fatal; real errors surface in measured runs
                }
            }
            // ────────────────────────────────────────────────────────────────

            LinkedHashMap<Integer, LinkedHashMap<String, double[]>> datasetMap
                    = new LinkedHashMap<>();

            for (int k : ExperimentConfig.K_VALUES) {
                System.out.printf("  k = %d%n", k);

                LinkedHashMap<String, double[]> kMap = new LinkedHashMap<>();

                for (String variant : VARIANTS) {
                    double[] m = runVariant(variant, db, k);
                    kMap.put(variant, m);

                    if (m[0] < 0) {
                        System.out.printf("    %-2s: FAILED%n", variant);
                    } else {
                        System.out.printf("    %-2s: %8.1f ± %5.1f ms | %10.0f closure checks | %5.2f ± %.2f MB%n",
                                variant, m[0], m[1], m[2], m[3], m[4]);
                    }
                }

                datasetMap.put(k, kMap);
            }

            collected.put(shortName, datasetMap);
        }

        // ── Export ──
        exportLongFormat(outputDir  + "all_metrics.csv",    collected);
        exportWideFormat(outputDir  + "runtime.csv",        collected, 0, 1,  "runtime_ms");
        exportWideFormat(outputDir  + "closure_checks.csv", collected, 2, -1, "closure_checks");
        exportWideFormat(outputDir  + "memory.csv",         collected, 3, 4,  "memory_mb");

        printFooter(outputDir);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Core measurement
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Execute one variant {@link ExperimentConfig#NUM_RUNS} times (all measured —
     * the JIT warmup lives at the dataset level in {@link #runExp1}).
     *
     * <p>Per-run values are stored in arrays; mean and sample std dev (n−1) are
     * computed at the end.  This avoids the numerical instability of online
     * mean/variance updates at the small n we use.</p>
     *
     * @return {@code [runtime_mean, runtime_std, closure_checks,
     *                  memory_mean, memory_std]}
     *         in ms / count / MB, or {@code [-1, 0, -1, -1, 0]} on total failure
     */
    private static double[] runVariant(String variant, UncertainDatabase db, int k) {
        int numRuns = ExperimentConfig.NUM_RUNS;

        long[] runtimeValues      = new long[numRuns];   // ms
        long[] closureCheckValues = new long[numRuns];
        long[] memoryValues       = new long[numRuns];   // bytes
        int    successfulRuns     = 0;

        for (int r = 0; r < numRuns; r++) {
            try {
                AbstractMiner miner = createMiner(variant, db, ExperimentConfig.TAU, k);

                // ── baseline  (MemoryMXBean + gc) ──
                System.gc();
                long memBefore = ManagementFactory.getMemoryMXBean()
                        .getHeapMemoryUsage().getUsed();

                // ── mine ──
                long startNanos = System.nanoTime();
                miner.mine();
                long endNanos   = System.nanoTime();

                // ── post-mining snapshot (no gc — captures working-set footprint) ──
                long memAfter = ManagementFactory.getMemoryMXBean()
                        .getHeapMemoryUsage().getUsed();

                runtimeValues[successfulRuns]      = (endNanos - startNanos) / 1_000_000;
                closureCheckValues[successfulRuns] = extractClosureChecks(miner);
                memoryValues[successfulRuns]       = Math.max(0, memAfter - memBefore);
                successfulRuns++;

            } catch (Exception e) {
                System.err.printf("    [ERROR] %s k=%d run=%d: %s%n",
                        variant, k, r + 1, e.getMessage());
            }
        }

        if (successfulRuns == 0) {
            return new double[]{-1, 0, -1, -1, 0};
        }

        // ── statistics ──
        double runtimeMean  = mean(runtimeValues,      successfulRuns);
        double runtimeStd   = sampleStd(runtimeValues, successfulRuns, runtimeMean);
        double closureMean  = mean(closureCheckValues,  successfulRuns);

        double memMeanBytes = mean(memoryValues,        successfulRuns);
        double memStdBytes  = sampleStd(memoryValues,   successfulRuns, memMeanBytes);

        return new double[]{
                runtimeMean,
                runtimeStd,
                closureMean,
                memMeanBytes / (1024.0 * 1024.0),   // MB
                memStdBytes  / (1024.0 * 1024.0)    // MB  (std scales linearly)
        };
    }

    /** Arithmetic mean of the first {@code n} elements. */
    private static double mean(long[] values, int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) sum += values[i];
        return (double) sum / n;
    }

    /**
     * Sample standard deviation (divides by n−1).
     * Returns 0 when n ≤ 1 to avoid division by zero.
     */
    private static double sampleStd(long[] values, int n, double mean) {
        if (n <= 1) return 0.0;
        double sumSq = 0;
        for (int i = 0; i < n; i++) {
            double diff = values[i] - mean;
            sumSq += diff * diff;
        }
        return Math.sqrt(sumSq / (n - 1));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  CSV export
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Long-format CSV: one row per (dataset, k, variant).
     * Columns include std dev for runtime and memory; closure checks is a single
     * column (deterministic metric — std is always 0).
     */
    private static void exportLongFormat(String filepath,
            Map<String, LinkedHashMap<Integer, LinkedHashMap<String, double[]>>> collected) {

        List<String> headers = Arrays.asList(
                "dataset", "k", "variant",
                "runtime_ms", "runtime_std",
                "closure_checks",
                "memory_mb", "memory_std");
        List<List<Object>> rows = new ArrayList<>();

        for (Map.Entry<String, LinkedHashMap<Integer, LinkedHashMap<String, double[]>>> dEntry
                : collected.entrySet()) {
            for (Map.Entry<Integer, LinkedHashMap<String, double[]>> kEntry
                    : dEntry.getValue().entrySet()) {
                for (Map.Entry<String, double[]> vEntry
                        : kEntry.getValue().entrySet()) {
                    double[] m = vEntry.getValue();
                    if (m[0] < 0) {
                        rows.add(Arrays.asList(dEntry.getKey(), kEntry.getKey(),
                                vEntry.getKey(), "FAILED", "", "FAILED", "FAILED", ""));
                    } else {
                        rows.add(Arrays.asList(
                                dEntry.getKey(),
                                kEntry.getKey(),
                                vEntry.getKey(),
                                String.format("%.1f", m[0]),   // runtime mean
                                String.format("%.1f", m[1]),   // runtime std
                                String.format("%.0f", m[2]),   // closure checks
                                String.format("%.2f", m[3]),   // memory mean
                                String.format("%.2f", m[4])    // memory std
                        ));
                    }
                }
            }
        }

        ResultExporter.exportToCSV(filepath, headers, rows);
    }

    /**
     * Wide-format CSV: one row per (dataset, k), one or two columns per variant.
     *
     * @param meanIdx  index of the mean value in the metrics array
     * @param stdIdx   index of the std dev value, or {@code -1} to emit mean only
     * @param metricName column-header base name
     */
    private static void exportWideFormat(String filepath,
            Map<String, LinkedHashMap<Integer, LinkedHashMap<String, double[]>>> collected,
            int meanIdx, int stdIdx, String metricName) {

        List<String> headers = new ArrayList<>(Arrays.asList("dataset", "k"));
        for (String v : VARIANTS) {
            headers.add(v + "_" + metricName);
            if (stdIdx >= 0) {
                headers.add(v + "_" + metricName + "_std");
            }
        }

        List<List<Object>> rows = new ArrayList<>();

        for (Map.Entry<String, LinkedHashMap<Integer, LinkedHashMap<String, double[]>>> dEntry
                : collected.entrySet()) {
            for (Map.Entry<Integer, LinkedHashMap<String, double[]>> kEntry
                    : dEntry.getValue().entrySet()) {

                List<Object> row = new ArrayList<>();
                row.add(dEntry.getKey());
                row.add(kEntry.getKey());

                for (String variant : VARIANTS) {
                    double[] m = kEntry.getValue().get(variant);
                    if (m == null || m[0] < 0) {
                        row.add("FAILED");
                        if (stdIdx >= 0) row.add("");
                    } else {
                        row.add(formatMetric(m[meanIdx], meanIdx));
                        if (stdIdx >= 0) {
                            row.add(formatMetric(m[stdIdx], stdIdx));
                        }
                    }
                }

                rows.add(row);
            }
        }

        ResultExporter.exportToCSV(filepath, headers, rows);
    }

    /**
     * Format a metric value by column semantics.
     * Indices match the metrics array layout documented in {@link #runExp1}.
     */
    private static String formatMetric(double value, int idx) {
        switch (idx) {
            case 0: case 1: return String.format("%.1f", value);   // runtime mean / std (ms)
            case 2:         return String.format("%.0f", value);   // closure checks
            case 3: case 4: return String.format("%.2f", value);   // memory mean / std (MB)
            default:        return String.valueOf(value);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Miner factory & stats extraction
    // ═══════════════════════════════════════════════════════════════════════════

    private static AbstractMiner createMiner(String variant, UncertainDatabase db,
                                             double tau, int k) {
        switch (variant) {
            case "V1": return new TUFCI_V1(db, tau, k);
            case "V2": return new TUFCI_V2(db, tau, k);
            case "V3": return new TUFCI_V3(db, tau, k);
            case "V4": return new TUFCI_V4(db, tau, k);
            case "V5": return new TUFCI_V5(db, tau, k);
            case "V6": return new TUFCI_V6(db, tau, k);
            default:   throw new IllegalArgumentException("Unknown variant: " + variant);
        }
    }

    /**
     * All four variants expose {@code getClosureChecks()} but it is not declared
     * in {@link AbstractMiner}.  Dispatch by concrete type.
     */
    private static long extractClosureChecks(AbstractMiner miner) {
        if (miner instanceof TUFCI_V1) return ((TUFCI_V1) miner).getClosureChecks();
        if (miner instanceof TUFCI_V2) return ((TUFCI_V2) miner).getClosureChecks();
        if (miner instanceof TUFCI_V3) return ((TUFCI_V3) miner).getClosureChecks();
        if (miner instanceof TUFCI_V4) return ((TUFCI_V4) miner).getClosureChecks();
        if (miner instanceof TUFCI_V5) return ((TUFCI_V5) miner).getClosureChecks();
        if (miner instanceof TUFCI_V6) return ((TUFCI_V6) miner).getClosureChecks();
        return 0;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Console output
    // ═══════════════════════════════════════════════════════════════════════════

    private static void printHeader() {
        System.out.println("╔═══════════════════════════════════════════════════════════════╗");
        System.out.println("║  Experiment 1: Runtime / Closure Checks / Memory — V1–V4     ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════╝");
        System.out.printf("  datasets : %s%n", Arrays.toString(ExperimentConfig.DATASETS));
        System.out.printf("  k values : %s%n", Arrays.toString(ExperimentConfig.K_VALUES));
        System.out.printf("  tau      : %.1f%n", ExperimentConfig.TAU);
        System.out.printf("  runs     : %d measured  +  1 JIT warmup%n", ExperimentConfig.NUM_RUNS);
        System.out.println();
    }

    private static void printFooter(String outputDir) {
        System.out.println();
        System.out.println("─".repeat(65));
        System.out.println("Output: " + outputDir);
        System.out.println("  all_metrics.csv     — long format with mean + std dev");
        System.out.println("  runtime.csv         — wide format with mean + std");
        System.out.println("  closure_checks.csv  — wide format, mean only (deterministic)");
        System.out.println("  memory.csv          — wide format with mean + std");
    }
}
