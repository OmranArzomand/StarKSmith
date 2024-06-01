import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class Generator {
  public static final String GENERATORS_DIR = "generators/";
  public static final int NUM_OF_THREADS = 4;
  public static final ObjectMapper mapper = new ObjectMapper();
  public static ObjectNode generatorResults;

  private static ObjectNode initialiseGeneratorResults(String resultsJsonPath) {
    File f = new File(resultsJsonPath);
    if (f.exists()) {
      try {
        return (ObjectNode) mapper.readTree(f);
      } catch (IOException e) {
        System.out.println("FAILED TO INITIALISED GENERATOR RESULTS");
        e.printStackTrace();
        return null;
      }
    } 
    try {
      f.getParentFile().mkdirs();
      f.createNewFile();
    } catch (IOException e) {
      System.out.println("FAILED TO INITIALISED GENERATOR RESULTS");
      e.printStackTrace();
      return null;
    }
    StringBuilder sb = new StringBuilder();
    sb.append( "{\"averageWallGenerationTime(s)\": 0,"
              + "\"averageCpuGenerationTime(s)\": 0,"
              + "\"filesGenerated\": 0,"
              + "\"averageLineCount\": 0,"
              + "\"numberOfCrashes\": 0," 
              + "\"numberOfTimeouts\": 0}");
    try {
      ObjectNode res = (ObjectNode) mapper.readTree(sb.toString());
      saveJsonToToFile(res, resultsJsonPath);
      return res;
    } catch (IOException e) {
      System.out.println("FAILED TO INITIALISED GENERATOR RESULTS");
      return null;
    }
  }

  public static void saveJsonToToFile(ObjectNode json, String filePath) {
    synchronized (json) {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(new File(filePath), json);
        } catch (IOException e) {
          System.out.println(e);
          System.out.println("FAILED TO SAVE GENERATOR RESULTS");
        }
    }
  }

  public static void main(String[] args) {

    if (args[0].equals("number")) {
      String config = args[1];
      int numPrograms = Integer.parseInt(args[2]);
      generateNumber(config, numPrograms);
    } else if (args[0].equals("time")) {
      String config = args[1];
      int minutes = Integer.parseInt(args[2]);
      generateTime(config, minutes);
    }
  }

  public static void generateNumber(String config, int numPrograms) {
    String resultsJsonFilePath = "./output/" + config + "/generatorResults.json";

    generatorResults = initialiseGeneratorResults(resultsJsonFilePath);

    ExecutorService executor = Executors.newFixedThreadPool(NUM_OF_THREADS);
    List<Future<GeneratorResult>> futures = new ArrayList<>();
    int currentFilesGenerated = (generatorResults.get("filesGenerated")).asInt();
    for (int i = 1; i < numPrograms + 1; i++) {
      futures.add(executor.submit(new GenerateTask(config, "prog" + (currentFilesGenerated + i) + ".kt")));
    }
    int counter = 1;
    for (Future<GeneratorResult> future : futures) {
      GeneratorResult res;
      try {
        res = future.get();
      } catch (Exception e) {
        System.out.println("Exception whilst getting program " + counter + "/" + numPrograms + " generation results");
        System.out.println("Stopping generation");
        System.out.println(e);
        return;
      }
      
      updateGeneratorResults(generatorResults, res);
      saveJsonToToFile(generatorResults, resultsJsonFilePath);
      System.out.println("Processed " + counter + "/" + numPrograms + " programs");
      counter++;
    }
    
    executor.shutdown();

    try {
      executor.awaitTermination(72, TimeUnit.HOURS);
    } catch (InterruptedException e) {
      System.out.println("======= GENERATION FAILED ======");
      e.printStackTrace();
    }
  }

  public static void generateTime(String config, int minutes) {
    String resultsJsonFilePath = "./output/" + config + "/generatorResults.json";

    generatorResults = initialiseGeneratorResults(resultsJsonFilePath);

    Instant start = Instant.now();
    Instant end = start.plus(Duration.ofMinutes(minutes));

    int currentFilesGenerated = (generatorResults.get("filesGenerated")).asInt();
    int counter = 1;
    File timeRemainingFile = new File("./output/" + config + "/timeRemaining.txt");
    try {
      timeRemainingFile.createNewFile();
    } catch (IOException e) {
      System.out.println("Failed to create timeRemaining.txt file (Stopping generation)");
      System.exit(-1);
    }
    while (Instant.now().isBefore(end)) {
      Instant now = Instant.now();
      Duration remaining = Duration.between(now, end);
      long minutesRemaining = remaining.toMinutes();
      long secondsRemaining = remaining.getSeconds() % 60;
      System.out.printf("Time remaining: %d minutes, %d seconds%n", minutesRemaining, secondsRemaining);
      try {
        FileWriter writer = new FileWriter(timeRemainingFile);
        timeRemainingFile.createNewFile();
        writer.write("Time remaining: " + minutesRemaining + " minutes, " + secondsRemaining + " seconds");
        writer.close();
      } catch (IOException e) {
        System.out.println("Failed to write to timeRemaining.txt (Stopping generation)");
        System.exit(-1);
      }
      GeneratorResult res = new GenerateTask(config, "prog" + (currentFilesGenerated + counter) + ".kt").call();
      updateGeneratorResults(generatorResults, res);
      saveJsonToToFile(generatorResults, resultsJsonFilePath);
      counter++;
    }
  }

  public static void updateGeneratorResults(ObjectNode generatorResult, GeneratorResult res) {
    int currentFilesGenerated = generatorResults.get("filesGenerated").asInt();
    generatorResults.put("numberOfTimeouts", generatorResults.get("numberOfTimeouts").asInt() + res.numTimeouts);
    generatorResults.put("numberOfCrashes", generatorResults.get("numberOfCrashes").asInt() + res.numCrashes);
    generatorResults.put("filesGenerated", currentFilesGenerated + 1);
    double avgWallDuration = (generatorResults.get("averageWallGenerationTime(s)").asDouble() * (currentFilesGenerated) + res.wallGenerationTimeS) / (currentFilesGenerated + 1);
    generatorResults.put("averageWallGenerationTime(s)", avgWallDuration);
    double avgCpuDuration = (generatorResults.get("averageCpuGenerationTime(s)").asDouble() * (currentFilesGenerated) + res.cpuGenerationTimeS) / (currentFilesGenerated + 1);
    generatorResults.put("averageCpuGenerationTime(s)", avgCpuDuration);
    int currentAvgLineCount = (generatorResults.get("averageLineCount")).asInt();
    int lineCount = res.lineCount == - 1 ? currentAvgLineCount : res.lineCount;
    int newAvgLineCount = (currentAvgLineCount * (currentFilesGenerated) + lineCount) / (currentFilesGenerated + 1);
    generatorResults.put("averageLineCount", newAvgLineCount);
  }

  public static class GenerateTask implements Callable<GeneratorResult> {
    private final String config;
    private final String progName;

    public GenerateTask(String config, String progName) {
      this.config = config;
      this.progName = progName;
    }

    private int getFileCount(String filePath) {
      try {
          int lineCount = (int) Files.lines(Paths.get(filePath)).count();
          return lineCount;
      } catch (IOException e) {
          System.out.println("ERROR CALCULATING PROGRAM LINE COUNT");
          System.out.println(e);
          return -1;
      }
    }

    private double getWallTimeFromOutput(String output) {
      Pattern pattern = Pattern.compile("real\\s+(\\d+)m([\\d\\.]+)s");
      Matcher matcher = pattern.matcher(output);

      if (matcher.find()) {
        int minutes = Integer.parseInt(matcher.group(1));
        double seconds = Double.parseDouble(matcher.group(2));        
        return  Integer.parseInt(matcher.group(1)) * 60 + Double.parseDouble(matcher.group(2));
      } else {
        System.out.println("ERROR GETTING WALL TIME FROM GENERATION SCRIPT OUTPUT");
        return -1;
      }
    }

    private double getCpuTimeFromOutput(String output) {
      Pattern pattern = Pattern.compile("user\\s+(\\d+)m([\\d\\.]+)s");
      Matcher matcher = pattern.matcher(output);

      if (matcher.find()) {
        int minutes = Integer.parseInt(matcher.group(1));
        double seconds = Double.parseDouble(matcher.group(2));        
        return  Integer.parseInt(matcher.group(1)) * 60 + Double.parseDouble(matcher.group(2));
      } else {
        System.out.println("ERROR GETTING WALL TIME FROM GENERATION SCRIPT OUTPUT");
        return -1;
      }
    }

    @Override
    public GeneratorResult call() {
      String progLocation = "output/" + config + "/programs/" + progName;
      String generatorConfigDir = "../" + GENERATORS_DIR + config;
      ProcessBuilder pb = new ProcessBuilder("./generate.sh", generatorConfigDir, "../" + progLocation);
      //ProcessBuilder pb = new ProcessBuilder("./generateBBF.sh", generatorConfigDir, "../" + progLocation);
      pb.directory(new File("./scripts"));

      double wallGenerationTime;
      double cpuGenerationTime;
      int numCrashes = 0;
      int numTimeouts = 0;
      int lineCount;
      
      while (true) {
        try {
          Process p = pb.start();
          String output = new String(p.getErrorStream().readAllBytes());
          int exitCode = p.waitFor();

          if (exitCode == 124) {
            System.out.println("Generating " + progName + " TIMED OUT (Trying again)");
            numTimeouts++;
          } else if (exitCode == 0) {
            wallGenerationTime = getWallTimeFromOutput(output);
            cpuGenerationTime = getCpuTimeFromOutput(output);
            System.out.println("Generated " + progName);
            lineCount = getFileCount(progLocation);
            break;
          } else {
            System.out.println("Generating " + progName + " CRASHED (Trying again)");
            numCrashes++;
          }
        } catch (Exception e) {
          System.err.println(e);
        }
      }
      return new GeneratorResult(numTimeouts, wallGenerationTime, cpuGenerationTime, lineCount, numCrashes); 
    }
  }
}