package experiment;

public class ExperimentConfig {
    
    public static final int[] K_VALUES = {1300, 1500, 1700, 2000};
    public static final double TAU = 0.7;
    public static final int TIMEOUT_SECONDS = 10000000;
    public static final int NUM_RUNS = 1;
    
    public static final String[] DATASETS = {
        //"retail_uncertain.txt",
        //"mushrooms_uncertain.txt",
        //"chess_uncertain.txt",
        //"BMS1_itemset_mining_uncertain.txt",
        //"BMS2_itemset_mining_uncertain.txt",
        //"chainstoreFIM_uncertain.txt",
        //"foodmartFIM_uncertain.txt",
        //"liquor_11frequent_uncertain.txt",
        //"OnlineRetailZZ_uncertain.txt",
        //"yoochoose-buys_FIM_uncertain.txt",
        "foodmart_uncertain.txt"
    };
    
    public static final String DATASET_DIR = "data/";
    public static final String RESULTS_DIR = "results/";
    
    public enum DatasetTier {
        DENSE,
        MEDIUM,
        SPARSE
    }
    
    public static DatasetTier getDatasetTier(String dataset) {
        switch (dataset.toLowerCase()) {
            case "chess":
            case "mushroom":
            case "connect":
                return DatasetTier.DENSE;
            case "pumsb_star":
            case "accidents":
                return DatasetTier.MEDIUM;
            case "retail":
                return DatasetTier.SPARSE;
            default:
                return DatasetTier.MEDIUM;
        }
    }
    
    public static int getExpectedItems(String dataset) {
        switch (dataset.toLowerCase()) {
            case "chess": return 75;
            case "mushroom": return 119;
            case "pumsb_star": return 2088;
            case "connect": return 129;
            case "accidents": return 468;
            case "retail": return 16470;
            default: return 100;
        }
    }
    
    public static int getExpectedTransactions(String dataset) {
        switch (dataset.toLowerCase()) {
            case "chess": return 3196;
            case "mushroom": return 8124;
            case "pumsb_star": return 49046;
            case "connect": return 67557;
            case "accidents": return 340183;
            case "retail": return 88162;
            default: return 10000;
        }
    }
}
