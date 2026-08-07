package org.graalvm.argo.dataset.generator;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.function.Predicate;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.graalvm.argo.dataset.Invocation;

import org.graalvm.argo.dataset.utils.ExternalTraceSorter;

/**
 * This class generates an invocation trace from the azure dataset. Given a set
 * of options, it will generate a file with one invocation per line.
 */
public class InvocationTraceGenerator {

    public static final String DELIMITER = ",";
    private static final String UNSORTED_FILE_SUFFIX = ".raw_unsorted_trace.csv";
    private static final String SORTED_FILE_SUFFIX = ".sorted_trace.csv";
    private static final String TEMP_BUFFER_SUFFIX = ".temp_buffer.csv";
    private static final String TEMP_WORK_SUFFIX = ".temp_work.csv";
    private static final int MINUTES_COLUMN_OFFSET = 3;
    private static final Map<String, Owner> owners = new HashMap<>(2048);
    private static final Map<String, Integer> compressedOwnerMapping = new HashMap<>(2048);
    private static boolean compress = false;
    private static int skipped = 0;
    private static String inputFilePath;

    private static Options prepareOptions() {
        Options options = new Options();
        Option input = new Option("d", "day", true, "Input dataset day (eg., d03).");
        input.setRequired(true);
        options.addOption(input);
        Option output = new Option("t", "trace", true, "Output invocation trace file path.");
        output.setRequired(true);
        options.addOption(output);
        Option firstMinute = new Option("b", "bmin", true, "First minute to include from the trace ([1,1440]).");
        firstMinute.setRequired(false);
        options.addOption(firstMinute);
        Option lastMinute = new Option("e", "emin", true, "Last minute to include from the trace ([1,1440]).");
        lastMinute.setRequired(false);
        options.addOption(lastMinute);
        Option maxMemory = new Option("m", "mem", true, "Maximum memory (in MBs) used by generated trace.");
        maxMemory.setRequired(false);
        options.addOption(maxMemory);
        Option maxUsers = new Option("u", "users", true, "Maximum number of users present in the generated trace.");
        maxUsers.setRequired(false);
        options.addOption(maxUsers);
        Option maxConcInv = new Option("i", "cinv", true, "Maximum number of concurrent invocations in the generated trace.");
        maxConcInv.setRequired(false);
        options.addOption(maxConcInv);
        Option maxFunctions = new Option("f", "functions", true, "Maximum number of functions in the generated trace.");
        maxFunctions.setRequired(false);
        options.addOption(maxFunctions);
        Option compress = new Option("c", "compress", false, "Compresses the output trace file.");
        compress.setRequired(false);
        options.addOption(compress);
        return options;
    }

    public static void main(String[] args) throws Exception {
        Options options = prepareOptions();
        try {
            CommandLine cmd = new DefaultParser().parse(options, args);
            String day = cmd.getOptionValue("day");
            String outputFilePath = cmd.getOptionValue("trace");
            int firstMinute = Integer.parseInt(cmd.getOptionValue("bmin", "701"));
            int lastMinute = Integer.parseInt(cmd.getOptionValue("emin", "710"));
            int maxMemory = Integer.parseInt(cmd.getOptionValue("mem", "0"));
            int maxUsers = Integer.parseInt(cmd.getOptionValue("users", "0"));
            int maxConcInv = Integer.parseInt(cmd.getOptionValue("cinv", "0"));
            int maxFunctions = Integer.parseInt(cmd.getOptionValue("functions", "0"));
            compress = cmd.hasOption("compress");

            FunctionInfoStorage.fillFunctionData(day);
            processDay(day, firstMinute, lastMinute);

            String currentInput = inputFilePath + SORTED_FILE_SUFFIX;

            currentInput = runDownscaleStep(currentInput, getNextOutput(currentInput), maxFunctions, "functions", (in, out, limit) -> downscaleByFunctions(in, out, maxFunctions));
            currentInput = runDownscaleStep(currentInput, getNextOutput(currentInput), maxConcInv, "concurrent invocations", (in, out, limit) -> downscaleByConcurrentInvocations(in, out, maxConcInv));
            currentInput = runDownscaleStep(currentInput, getNextOutput(currentInput), maxUsers, "users", (in, out, limit) -> downscaleByUser(in, out, maxUsers));
            currentInput = runDownscaleStep(currentInput, getNextOutput(currentInput), maxMemory, "MB of memory", (in, out, limit) -> downscaleByMemory(in, out, maxMemory));

            writeInvocationsToFile(currentInput, outputFilePath);

            /* Clear temporary files */
            new File(inputFilePath + SORTED_FILE_SUFFIX).delete();
            new File(inputFilePath + TEMP_WORK_SUFFIX).delete();
            new File(inputFilePath + TEMP_BUFFER_SUFFIX).delete();
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            new HelpFormatter().printHelp("utility-name", options);
            return;
        }
    }

    private static void writeInvocationsToFile(String inputFilePath, String outputFilePath) throws Exception {
        int firstTimestamp = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(inputFilePath))) {
            String firstLine = reader.readLine();
            if (firstLine != null) {
                String[] splitRow = firstLine.split(DELIMITER);
                firstTimestamp = Integer.parseInt(splitRow[4]);
            }
        }

        /* Stream from the final temp file to the final output file */
        try (BufferedReader reader = new BufferedReader(new FileReader(inputFilePath));
            BufferedWriter writer = new BufferedWriter(new FileWriter(outputFilePath, false))) {
            
            writer.write("HashOwner,HashFunction,AverageAllocatedMb,AverageDuration,Timestamp");
            writer.newLine();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(DELIMITER);
                String owner = parts[0];
                String function = parts[1];
                String memory = parts[2];
                String duration = parts[3];
                int timestamp = Integer.parseInt(parts[4]);
                
                int normalizedTimestamp = timestamp - firstTimestamp;

                writer.write(String.format("%s,%s,%s,%s,%d", owner, function, memory, duration, normalizedTimestamp));
                writer.newLine();
            }
        }

        if (compress) {
            writeMapping(outputFilePath, "function_mapping.csv", "HashFunction,CompressedHash", FunctionInfoStorage.COMPRESSED_MAPPING);
            writeMapping(outputFilePath, "owner_mapping.csv", "HashOwner,CompressedHash", compressedOwnerMapping);
        }
    }

    private static void writeMapping(String path, String outputMapping, String header, Map<String, ?> mapping) throws IOException {
        Path inputPath = Paths.get(path);
        Path parentDir = inputPath.getParent();
        File targetFile = (parentDir != null) ? parentDir.resolve(outputMapping).toFile() : new File(outputMapping);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(targetFile, false))) {
            writer.write(header);
            writer.newLine();
            for (Map.Entry<String, ?> entry : mapping.entrySet()) {
                writer.write(entry.getKey() + "," + entry.getValue());
                writer.newLine();
            }
        }
    }

    private static void processFunction(String line, int firstMinute, int lastMinute, BufferedWriter bw) {
        String[] splitRow = line.split(DELIMITER);
        String owner = splitRow[0];
        String app = splitRow[1];
        String function = splitRow[2];

        /* If there is no record about this function about avg duration or memory, then skip */
        if (!FunctionInfoStorage.DURATIONS.containsKey(function) || !FunctionInfoStorage.MEMORIES.containsKey(app)) {
            ++skipped;
            return;
        }

        int memory = FunctionInfoStorage.MEMORIES.get(app);
        int duration = FunctionInfoStorage.DURATIONS.get(function);

        if (compress) {
            compressedOwnerMapping.computeIfAbsent(owner, k -> compressedOwnerMapping.size());
            FunctionInfoStorage.COMPRESSED_MAPPING.computeIfAbsent(function, k -> FunctionInfoStorage.COMPRESSED_MAPPING.size());
            // use compressed mappings
            owner = compressedOwnerMapping.get(owner).toString();
            function = FunctionInfoStorage.COMPRESSED_MAPPING.get(function).toString();
        }

        int currentMinute = firstMinute;
        int invocationCount = 0;
        try {
            while (currentMinute <= lastMinute) {
                int invocationsForMinute = Integer.parseInt(splitRow[currentMinute + MINUTES_COLUMN_OFFSET]);
                invocationCount += invocationsForMinute;
                int minBeginningMs = (currentMinute - 1) * 60000;
                int minEndMs = minBeginningMs + 60000;
                for (int i = 0; i < invocationsForMinute; ++i) {
                    int timestamp = ThreadLocalRandom.current().nextInt(minBeginningMs, minEndMs);
                    String csvLine = String.join(",", 
                        owner, 
                        function, 
                        String.valueOf(memory), 
                        String.valueOf(duration), 
                        String.valueOf(timestamp)
                    );
                    bw.write(csvLine);
                    bw.newLine();
                }
                ++currentMinute;
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
            new File(inputFilePath + UNSORTED_FILE_SUFFIX).delete();
            System.exit(1);
        }

        if (invocationCount > 0) {
            if (!owners.containsKey(owner)) {
                owners.put(owner, new Owner(owner));
            }
            Owner currentOwner = owners.get(owner);
            currentOwner.addFunction(function);
            currentOwner.addInvocations(invocationCount);
        }
    }

    /*
     * Read data from the CSV file, generate timestamps for the desired time frame.
     * File expected syntax: HashOwner, HashApp, HashFunction, Trigger, 1, 2, 3...
     */
    private static void processDay(String datasetId, int firstMinute, int lastMinute) {
        try {
            inputFilePath = "input/invocations_per_function_md.anon." + datasetId + ".csv";
            File file = new File(inputFilePath);
            BufferedReader br = new BufferedReader(new FileReader(file));
            BufferedWriter bw = new BufferedWriter(new FileWriter(inputFilePath + UNSORTED_FILE_SUFFIX, false));
            String line;
            br.readLine(); // To skip the header
            int fcounter = 1;

            while ((line = br.readLine()) != null) {
                processFunction(line, firstMinute, lastMinute, bw);
                System.out.println("Processed function " + fcounter++);
            }
            System.out.println("Skipped " + skipped + " functions due to lack of information.");
            bw.close();
            br.close();
        } catch(IOException ioe) {
            ioe.printStackTrace();
            new File(inputFilePath + UNSORTED_FILE_SUFFIX).delete();
            System.exit(1);
        }

        /* At this point, we have the unordered list of all invocations */
        try {
            ExternalTraceSorter.sortTraceByTimestamp(inputFilePath + UNSORTED_FILE_SUFFIX, inputFilePath + SORTED_FILE_SUFFIX, false);
            System.out.println("Finished sorting.");
            new File(inputFilePath + UNSORTED_FILE_SUFFIX).delete();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            new File(inputFilePath + SORTED_FILE_SUFFIX).delete();
            new File(inputFilePath + UNSORTED_FILE_SUFFIX).delete();
            System.exit(1);
        }
    }

    /*
     * Generic downscaling logic
     */
    @FunctionalInterface
    private interface Downscaler {
        void perform(String input, String output, int limit) throws IOException;
    }

    private static String getNextOutput(String currentInput) {
        return currentInput.contains("buffer") ? inputFilePath + TEMP_WORK_SUFFIX : inputFilePath + TEMP_BUFFER_SUFFIX;
    }

    private static String runDownscaleStep(String input, String output, int limit, String label, Downscaler action) throws IOException {
        if (limit <= 0) return input;

        action.perform(input, output, limit);
        System.err.println("Finished downscaling to " + limit + " " + label + ".");
        
        // Swap files
        return output;
    }

    /* Filters the input file line by line, keeping only the rows that satisfy the provided predicate */
    private static void processFile(String inputPath, String outputPath, Predicate<String[]> rowFilter) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(inputPath));
            BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] splitRow = line.split(DELIMITER);
                if (rowFilter.test(splitRow)) {
                    writer.write(line);
                    writer.newLine();
                }
            }
        }
    }

    /* Remove invocations that go over the maximum number of concurrent invocations. */
    private static void downscaleByConcurrentInvocations(String inputPath, String outputPath, int maxConcInv) throws IOException {
        List<Integer> activeInvocationsEndTimes = new LinkedList<>();
        
        processFile(inputPath, outputPath, splitRow -> {
            int duration = Integer.parseInt(splitRow[3]);
            int timestamp = Integer.parseInt(splitRow[4]);
            int endTimestamp = timestamp + duration;

            /* Note: this is REALLY slow */
            activeInvocationsEndTimes.removeIf(endTime -> timestamp >= endTime);

            if (activeInvocationsEndTimes.size() < maxConcInv) {
                activeInvocationsEndTimes.add(endTimestamp);
                return true;
            }
            return false;
        });
    }

    /* Remove invocations that are not from the N more popular users. */
    private static void downscaleByUser(String inputPath, String outputPath, int maxUsers) throws IOException {
        Set<String> selectedOwners = owners.values().stream()
                .sorted(Comparator.comparingInt(Owner::getFunctions).reversed())
                .limit(maxUsers).map(Owner::getOwnerHash).collect(Collectors.toSet());

        processFile(inputPath, outputPath, splitRow -> selectedOwners.contains(splitRow[0]));
    }

    /* Remove invocations that are not from the N first functions that appear in the trace. */
    private static void downscaleByFunctions(String inputPath, String outputPath, int maxFunctions) throws IOException {
        /* Count function frequencies */
        Map<String, Long> invocationsFunction = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(inputPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] splitRow = line.split(DELIMITER);
                String functionId = splitRow[1];
                invocationsFunction.merge(functionId, 1L, Long::sum);
            }
        }

        int totalFunctions = invocationsFunction.size();
        int skipFunctions = Math.max(0, (totalFunctions - maxFunctions) / 2);

        Set<String> selectedFunctions = invocationsFunction.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .skip(skipFunctions)
                .limit(maxFunctions)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        /* Filter and write */
        processFile(inputPath, outputPath, splitRow -> selectedFunctions.contains(splitRow[1]));
    }

    /* Remove invocations that go over the maximum memory. */
    private static void downscaleByMemory(String inputPath, String outputPath, int maxMemory) throws IOException {
        List<Invocation> activeInvocations = new LinkedList<>();

        processFile(inputPath, outputPath, splitRow -> {
            int memory = Integer.parseInt(splitRow[2]);
            int duration = Integer.parseInt(splitRow[3]);
            int timestamp = Integer.parseInt(splitRow[4]);

            int currentInvocationTimestamp = timestamp;

            activeInvocations.removeIf(f -> currentInvocationTimestamp >= f.getEndTimestamp());
            int currentConsumption = activeInvocations.stream().mapToInt(Invocation::getMemory).sum();

            if (currentConsumption + memory <= maxMemory) {
                activeInvocations.add(new Invocation(splitRow[0], splitRow[1], memory, duration, timestamp));
                return true;
            }
            return false;
        });
    }
}
