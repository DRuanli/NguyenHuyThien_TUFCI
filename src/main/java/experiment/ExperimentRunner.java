package experiment;

public class ExperimentRunner {
    
    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }
        
        String command = args[0].toLowerCase();
        
        switch (command) {
            case "exp1":
            case "1":
                Experiment.runExp1();
                break;

            case "exp2": case "2":
            case "exp4": case "4":
            case "exp5": case "5":
            case "exp7": case "7":
                System.out.println(command + " — not yet implemented");
                break;

            case "all":
                Experiment.runExp1();
                break;

            case "help":
            case "-h":
            case "--help":
                printUsage();
                break;

            default:
                System.out.println("Unknown command: " + command);
                printUsage();
        }
    }
    
    private static void printUsage() {
    }
}
