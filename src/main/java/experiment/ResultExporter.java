package experiment;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class ResultExporter {
    
    public static void exportToCSV(String filepath, List<String> headers, 
                                   List<List<Object>> rows) {
        try (PrintWriter pw = new PrintWriter(filepath)) {
            pw.println(String.join(",", headers));
            
            for (List<Object> row : rows) {
                StringJoiner joiner = new StringJoiner(",");
                for (Object value : row) {
                    joiner.add(formatValue(value));
                }
                pw.println(joiner.toString());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    public static void appendToCSV(String filepath, List<Object> row) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(filepath, true))) {
            StringJoiner joiner = new StringJoiner(",");
            for (Object value : row) {
                joiner.add(formatValue(value));
            }
            pw.println(joiner.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    public static void createResultsDirectory(String path) {
        try {
            Files.createDirectories(Paths.get(path));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    private static String formatValue(Object value) {
        if (value == null) {
            return "";
        } else if (value instanceof Double) {
            double d = (Double) value;
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return "";
            }
            return String.format("%.6f", d);
        } else if (value instanceof Float) {
            float f = (Float) value;
            if (Float.isNaN(f) || Float.isInfinite(f)) {
                return "";
            }
            return String.format("%.6f", f);
        } else if (value instanceof String) {
            String s = (String) value;
            if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
                return "\"" + s.replace("\"", "\"\"") + "\"";
            }
            return s;
        } else {
            return value.toString();
        }
    }
    
    public static void exportRuntimeComparison(String filepath, String dataset,
                                               Map<Integer, Map<String, Double>> results,
                                               int[] kValues) {
        try (PrintWriter pw = new PrintWriter(filepath)) {
            pw.println("k,V1_ms,V2_ms,V3_ms,V4_ms,speedup_V1_V2,speedup_V1_V4");
            
            for (int k : kValues) {
                Map<String, Double> variantResults = results.get(k);
                if (variantResults == null) continue;
                
                double v1 = variantResults.getOrDefault("V1", -1.0);
                double v2 = variantResults.getOrDefault("V2", -1.0);
                double v3 = variantResults.getOrDefault("V3", -1.0);
                double v4 = variantResults.getOrDefault("V4", -1.0);
                
                double speedupV2 = (v1 > 0 && v2 > 0) ? v2 / v1 : 0;
                double speedupV4 = (v1 > 0 && v4 > 0) ? v4 / v1 : 0;
                
                pw.printf("%d,%.3f,%.3f,%.3f,%.3f,%.2f,%.2f\n",
                    k, v1, v2, v3, v4, speedupV2, speedupV4);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    public static void exportThresholdEvolution(String filepath, 
                                                List<ThresholdTracker.ThresholdSnapshot> snapshots) {
        try (PrintWriter pw = new PrintWriter(filepath)) {
            pw.println("candidates_processed,threshold,progress_percent");
            
            for (ThresholdTracker.ThresholdSnapshot s : snapshots) {
                pw.printf("%d,%d,%.4f\n", s.candidatesProcessed, s.threshold, s.progressPercent);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    public static void exportClosureDistribution(String filepath,
                                                 Map<Integer, Integer> distribution) {
        try (PrintWriter pw = new PrintWriter(filepath)) {
            pw.println("position,count,cumulative_percent");
            
            int total = distribution.values().stream().mapToInt(Integer::intValue).sum();
            int cumulative = 0;
            
            List<Integer> sortedPositions = new ArrayList<>(distribution.keySet());
            Collections.sort(sortedPositions);
            
            for (int pos : sortedPositions) {
                int count = distribution.get(pos);
                cumulative += count;
                double percent = total > 0 ? (cumulative * 100.0 / total) : 0;
                pw.printf("%d,%d,%.2f\n", pos, count, percent);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
