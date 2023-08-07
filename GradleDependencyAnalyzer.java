import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This class is capable to display gradle modules dependencies in various ways so that
 * complicated gradle modules dependencies can be optimized and optimization later verified
 * by re-running the analysis.
 * <p>
 * It can print (using just {@code System.out.print}) dependencies in various
 * format - see particular methods for more details:
 * <ul>
 *     <li>{@link #printModulesAsMindMap()}</li>
 *     <li>{@link #printModulesAsDeploymentDiagram()}</li>
 *     <li>{@link #printModulesWithDependenciesAsTable()}</li>
 *     <li>{@link #printTransientDependencyAnalysis(String, String)} ()}</li>
 * </ul>
 * By default {@link #printModulesWithDependenciesAsTable()} is running if not configured other way.
 * <p>
 * The class has no dependency on any library, so you can just put it to your favourite editor
 * and run (I tested it with java 8).
 */
public class GradleDependencyAnalyzer {

    /**
     * Name of (each) gradle module configuration file.
     */
    private static final String BUILD_GRADLE = "build.gradle";

    /**
     * I've found just one module that has a different path then the module name, which is
     * {@code Administration} vs {@code admin}. So it is manually corrected/translated.
     */

    private static final Map<String, String> MODULE_NAME_TRANSLATED = new HashMap<>();

    /**
     * Pattern for searching {@code compile project(:x)} line in {@code build.gradle}
     */
    private static final Pattern COMPILE_PROJECT_REGEXP = Pattern.compile("compile project\\(['\"]:(\\S+)['\"]\\)");

    /**
     * Pattern for searching {@code project(:x)} line in {@code build.gradle}
     */
    private static final Pattern PROJECT_REGEXP = Pattern.compile("project\\(['\"]:(\\S+)['\"]\\)");

    /**
     * Pattern for searching {@code dependencies {} in {@code build.gradle}
     */
    private static final Pattern DEPENDENCIES_REGEXP = Pattern.compile("dependencies\\W+\\{");

    /**
     * Pattern for searching {@code compile (} in {@code build.gradle}
     */
    private static final Pattern COMPILE_REGEXP = Pattern.compile("compile\\W*\\(");

    /**
     * Pattern for searching {@code )} (compile end bracket) in {@code build.gradle}
     */
    private static final Pattern COMPILE_REGEXP_END = Pattern.compile("^\\W*\\)\\W*$");

    /**
     * Simple mapping of existing module names (keys) to list of direct (explicitly defined)
     * modules dependencies - we get this during "first round of processing"
     */
    private static final Map<String, Set<String>> MODULES = new TreeMap<>(new PriorityComparator());

    /**
     * Mapping of existing module names (keys) to list of direct (explicitly defined) or transient dependencies
     * along with their paths.
     */
    private static final Map<String, Set<Dependency>> MODULES_WITH_TRANSIENT_DEPENDENCIES = new TreeMap<>(new PriorityComparator());

    /**
     * Helps to organize output so that the most common used modules are ordered first.
     */
    private static final Map<String,Integer> MODULES_PRIORITY = new HashMap<>();

    /**
     * Queue used in breadth-search algorithm explained in {@link #breathSearchWithPath(String)}
     * and {@link #doBreathSearch()}}.
     */
    private static final Queue<ModuleNode> QUEUE = new LinkedList<>();

    static {

        MODULE_NAME_TRANSLATED.put("Administration", "admin");

        MODULES_PRIORITY.put("common", 1);
        MODULES_PRIORITY.put("servercommon", 2);
        MODULES_PRIORITY.put("comms", 3);
        MODULES_PRIORITY.put("dataaccess", 4);
        MODULES_PRIORITY.put("lookup", 5);
        MODULES_PRIORITY.put("identity", 6);
        MODULES_PRIORITY.put("edna", 7);
        MODULES_PRIORITY.put("search", 8);
        MODULES_PRIORITY.put("service", 9);
        MODULES_PRIORITY.put("admin", 10);
        MODULES_PRIORITY.put("events", 11);
        MODULES_PRIORITY.put("fraudpolicy", 12);
        MODULES_PRIORITY.put("graphql", 13);
        MODULES_PRIORITY.put("pipeline", 14);
        MODULES_PRIORITY.put("reports", 15);
        MODULES_PRIORITY.put("sar", 16);
        MODULES_PRIORITY.put("thirdparty", 17);
        MODULES_PRIORITY.put("sanctionservice", 18);
        MODULES_PRIORITY.put("sanctions", 19);
        MODULES_PRIORITY.put("sanctions-client", 20);
        MODULES_PRIORITY.put("sanctions-reports", 21);
        MODULES_PRIORITY.put("sanctions-saas", 22);
        MODULES_PRIORITY.put("auditreports", 23);
        MODULES_PRIORITY.put("engine", 24);
        MODULES_PRIORITY.put("alerter", 25);
        MODULES_PRIORITY.put("ednaui/server", 26);
        MODULES_PRIORITY.put("frontend", 27);
        MODULES_PRIORITY.put("verifier", 28);
        MODULES_PRIORITY.put("status", 29);
    }

    /**
     * Path to gradle project.
     * For example: {@code /Users/pavla/myproject}
     */
    private static String pathToGradleProject;

    /**
     * Also [root]/build.gradle found when scanning for modules configuration files, but we want to
     * skip this one.
     */
    private static File rootGradleBuild;

    private static OUTPUT_FORMAT outputFormat = OUTPUT_FORMAT.MODULES_WITH_TRANSIENT_DEPEDENCIES_AS_TABLE;

    static enum OUTPUT_FORMAT {
        MODULES_AS_MIND_MAP,
        MODULES_AS_DEPLOYMENT_DIAGRAM,
        MODULES_WITH_TRANSIENT_DEPEDENCIES_AS_TABLE,
        TRANSIENT_DEPENDENCY_ANALYSIS
    }

    /**
     * Runs the analysis depending on input arguments.
     * @param args <ol>
     *             <li>{@code args[0]} = path to gradle project root</li>
     *             <li>{@code args[1]} = {@link OUTPUT_FORMAT} as enum constant name (if not supplied defaults to
     *                 {@link OUTPUT_FORMAT#MODULES_WITH_TRANSIENT_DEPEDENCIES_AS_TABLE})</li>
     *             <li>{@code args[2]} = transient dependency identifier in format
     *                 {@code [root-module-name]:[transient-dependendency-module-name]} (only needed when required output format
     *                 is {@link OUTPUT_FORMAT#TRANSIENT_DEPENDENCY_ANALYSIS})
     *             </ol>
     * @throws IOException
     */
    public static void main(String[] args) throws IOException {

       if (args.length == 0) throw new IllegalArgumentException("At least path to project root must be supplied as argument, " +
               "pls read javadoc for main method that explains the options.");
       if (args.length > 2) {
           if (OUTPUT_FORMAT.TRANSIENT_DEPENDENCY_ANALYSIS.equals(OUTPUT_FORMAT.valueOf(args[1])) && args.length != 3) {
               throw new IllegalArgumentException("To get the transient dependency analysis, you have to supply identifier of transient " +
                       "dependency in form: [root-module-name]:[transient-dependency-module-name]");
           }
           if (args[2].split(":").length != 2) {
               throw new IllegalArgumentException("Transient dependency identifier is probably in wrong format.");
           }
       }
       pathToGradleProject = args[0];
       rootGradleBuild = new File(pathToGradleProject, BUILD_GRADLE);
       if (args.length > 1) outputFormat = OUTPUT_FORMAT.valueOf(args[1]);

       try (Stream<Path> walkStream = Files.walk(new File(pathToGradleProject).toPath())) {
            walkStream.forEach(GradleDependencyAnalyzer::processCandidatePath);
        }

      if (OUTPUT_FORMAT.MODULES_AS_MIND_MAP.equals(outputFormat)) {
          System.out.println("======== Printing gradle modules with direct (explicitly defined) dependencies as MIND MAP");
          printModulesAsMindMap();
          return;
      }

      if (OUTPUT_FORMAT.MODULES_AS_DEPLOYMENT_DIAGRAM.equals(outputFormat)) {
          System.out.println("======== Printing gradle modules with direct (explicitly defined) dependencies as DEPLOYMENT DIAGRAM");
          printModulesAsDeploymentDiagram();
          return;
      }

      resolveTransientDependencies();

      if (OUTPUT_FORMAT.MODULES_WITH_TRANSIENT_DEPEDENCIES_AS_TABLE.equals(outputFormat)) {
          System.out.println("======== Printing gradle modules with direct (explicitly defined) dependencies " +
                  "and transient dependencies as TABLE");
          printModulesWithDependenciesAsTable();
          return;
      }

      if (OUTPUT_FORMAT.TRANSIENT_DEPENDENCY_ANALYSIS.equals(outputFormat)) {
          System.out.println("======== Printing paths leading to transient dependency with identifier " + args[2] + " as list.");
          String[] dependencyIdentifier = args[2].split(":");
          printTransientDependencyAnalysis(dependencyIdentifier[0], dependencyIdentifier[1]);
      }

    }


    /**
     * Prints modules with direct (explicit) dependencies only as mind map diagram. The output is source for
     * https://plantuml.com/mindmap-diagram that can be generated online here: http://www.plantuml.com/
     * It is helpful for quick check that all direct dependencies where parsed and all modules where
     * parsed.
     */
    private static void printModulesAsMindMap() {
        System.out.println("@startmindmap");
        MODULES.keySet().forEach(key -> {
            System.out.println("* " + key);
            MODULES.get(key).forEach(dependency -> System.out.println("** " + dependency));
        });
        System.out.println("@endmindmap");
    }

    /**
     * Prints modules with direct dependencies only as deployment uml graph.
     * https://plantuml.com/deployment-diagram.
     * The PlantUML is great in generating simpler graph but for more
     * complex graphs (like our) modules dependencies is very difficult to read.
     * Maybe with some other graph tool that is more interactive (highlighting current node and
     * its edges) it could be better, but I haven't tried to search for such a tool.
     */
    private static void printModulesAsDeploymentDiagram() {
        System.out.println("@startuml");
        // by default lines are curved
        /*
         -- with this directive lines in graph are polylines
         System.out.println("skinparam linetype polyline")
         */
        /* -- with this directive lines in graph are just horizontal or vertical */
        System.out.println("skinparam linetype ortho");
        Set<String> modules = new HashSet<>();
        MODULES.keySet().forEach(key -> {
            modules.add(key);
            modules.addAll(MODULES.get(key));
        });
        modules.forEach(item -> System.out.println("node \"" + item + "\""));
        MODULES.keySet().forEach(key ->
            MODULES.get(key).forEach(dependency -> System.out.println("\"" + key + "\" --> \"" + dependency + "\""))
        );
        System.out.println("@enduml");
    }

    /**
     * Prints paths that lead to given transient dependency being included as transient dependency for
     * given module.
     * To discover all transient depedencies for given module, run the default procedure first - {@link
     * #printModulesWithDependenciesAsTable()} - if you are interested WHY given transient dependency is
     * there, yo will get through this method all the possible ways how to get to it.
     */
    private static void printTransientDependencyAnalysis(String rootModule, String transientDependency) {
        Set<Dependency> dependencies = MODULES_WITH_TRANSIENT_DEPENDENCIES.get(rootModule);
        if (dependencies == null) throw new IllegalArgumentException("Root module name " + rootModule + " not found.");
        Optional<Dependency> matchingDependency = dependencies.stream().filter(dependency -> dependency.moduleName.equals(transientDependency)).findFirst();
        if (!matchingDependency.isPresent()) throw new IllegalArgumentException("Transient dependency " + transientDependency + " not found.");
        System.out.println(matchingDependency.get().pathToString("--"));
    }

    /**
     * Prints modules with direct dependencies and transient dependencies
     * as a as mind map diagram with three levels:
     * <ol>
     *    <li>root node</li>
     *    <li>direct dependencies or transient dependency</li>
     *    <li>path transient dependency (path to direct dependency is also included but
     *    it is always a root node)</li>
     * </ol>
     * It is source for https://plantuml.com/mindmap-diagram which can be online generated
     * here https://plantuml.com/.
     * <p>
     * This method is needed when you want to look how the transient dependency get to given module.
     * It is too detailed for simple overview of transient dependencies. For simple overview
     * of transient dependencies see {@link #printModulesWithDependenciesAsTable()}
     * <p>
     * I've found that plantuml.com is not capable to print the whole graph, so the logic here is ok, 
     * but prefer using more specific method {@link #printTransientDependencyAnalysis(String, String)}
     * instead.
     */
    private static void printModulesWithDependenciesIncludingPathsAsMindMap() {
        System.out.println("@startmindmap");
        MODULES_WITH_TRANSIENT_DEPENDENCIES.forEach((key, value) ->
                value.forEach(System.out::println)
        );
        System.out.println("@endmindmap");
    }

    /**
     * Prints modules with dependencies as a (.csv) table.
     * Rows say: for given root module what are its direct(x) and transient(t) dependencies.
     * Columns say: in which modules this dependency (module) is included as a direct(x) or transient(t) dependency.
     */
    private static void printModulesWithDependenciesAsTable() {
        System.out.print(",");
        Set<String> modules = new TreeSet<>(new PriorityComparator());
        MODULES_WITH_TRANSIENT_DEPENDENCIES.keySet().forEach(key -> {
            modules.add(key);
            modules.addAll(MODULES.get(key));
        });
        String[] modulesArray = modules.toArray(new String[0]);
        Arrays.stream(modulesArray).forEach(module ->
            System.out.print(module + ",")
        );
        System.out.println();
        Arrays.stream(modulesArray).forEach(module -> {
            System.out.print(module + ",");
            for (int i=0; i < modulesArray.length; i++) {
                if (MODULES_WITH_TRANSIENT_DEPENDENCIES.get(module) == null) {
                    System.out.print(",");
                    continue;
                }
                int j = i;
                Optional<Dependency> matchingDependency = MODULES_WITH_TRANSIENT_DEPENDENCIES.get(module).stream()
                        .filter(dependency -> modulesArray[j].equals(dependency.moduleName)).findFirst();
                System.out.print(matchingDependency.map(GradleDependencyAnalyzer::printMarker).orElse(","));
            }
            System.out.println();
        });
    }

    private static String printMarker(Dependency dependency) {
        if (dependency.isRoot()) {
            return (",");
        }
        if (dependency.isDirectDependency()) {
            return("x,");
        }
        return("t,");
    }

    /**
     * Looks if the {@code candidatePath} is a path to a gradle module
     * build file and if yes passes the path along with extracted gradle module name for subsequent processing
     * (extracting explicitly defined gradle module dependencies - output goes to {@link #MODULES})
     * @param candidatePath path to a file within a compliance platform gradle project
     */
    private static void processCandidatePath(Path candidatePath) {
        if (!isGradleModuleBuildFile(candidatePath)) return;
        String moduleName = extractModuleName(candidatePath);
        if (MODULE_NAME_TRANSLATED.containsKey(moduleName)) {
            moduleName = MODULE_NAME_TRANSLATED.get(moduleName);
        }
        processModule(candidatePath, moduleName);
    }

    /**
     * Is this path {@code [repo-root]/[some-module]/build.gradle}?
     * @param path path within repo
     * @return {@code true} if it is {@code build.gradle} in gradle module but NOT the
     * directly in the repo root.
     */
    private static boolean isGradleModuleBuildFile(Path path) {
        return path.toFile().isFile() && path.endsWith(BUILD_GRADLE) &&
                !rootGradleBuild.toString().equals(path.toString());
    }

    /**
     * Extract the gradle module name from file path within repo.
     * @param path path to {@code build.gradle}
     * @return gradle module name
     * @see #MODULE_NAME_TRANSLATED
     */
    private static String extractModuleName(Path path) {
        String pathAsString = path.toString();
        int modulePathEnd = pathAsString.indexOf(BUILD_GRADLE);
        return path.toString().substring(pathToGradleProject.length() + 1, modulePathEnd -1);
    }

    /**
     * Extract gradle module dependencies defined in {@code build.gradle} and puts the
     * results in {@link #MODULES}
     * @param moduleGradleBuildFilePath path to {@code build.gradle}
     * @param moduleName gradle module name
     */
    private static void processModule(Path moduleGradleBuildFilePath, String moduleName)  {
        Set<String> directDependencies = extractDirectModuleDependencies(moduleGradleBuildFilePath);
        MODULES.put(moduleName, directDependencies);
    }

    private static Set<String> extractDirectModuleDependencies(Path moduleGradleBuildFilePath)  {
        Set<String> requiredModulesToCompile = new HashSet<>();
        boolean[] dependencySectionMatch = new boolean[2];
        try (Stream<String> lines = Files.lines(moduleGradleBuildFilePath)) {
            lines.forEach(line -> {
                handleDependencyCompileBlock(dependencySectionMatch, line).ifPresent(requiredModulesToCompile::add);
                handleDependencyCompileProject(line).ifPresent(requiredModulesToCompile::add);
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return requiredModulesToCompile;
    }

    /**
     * Handles gradle dependencies defined in a block where {@code compile} and {@code project} are separate..
     * <pre>
     * dependency {
     *   compile (
     *     project(:x)
     *     project(:y)
     *     project(:z)
     *   )
     *   ...
     * }
     * </pre>
     * @return a {@code project(:x)} match within dependency section or empty result.
     */
    private static Optional<String> handleDependencyCompileBlock(boolean[] dependencySectionMatch, String line) {
        String result = null;
        Matcher matcher;
        if (!dependencySectionMatch[0]) {
            matcher = DEPENDENCIES_REGEXP.matcher(line);
            if (matcher.find()) dependencySectionMatch[0] = true;
        } else if (!dependencySectionMatch[1]) {
            matcher = COMPILE_REGEXP.matcher(line);
            if (matcher.find()) dependencySectionMatch[1] = true;
        }
        if (dependencySectionMatch[0] && dependencySectionMatch[1]) {
            matcher = PROJECT_REGEXP.matcher(line);
            if (matcher.find()) result = matcher.toMatchResult().group(1);
            matcher = COMPILE_REGEXP_END.matcher(line);
            if (matcher.find()) dependencySectionMatch[0] = dependencySectionMatch[1] = false;
        }
        return Optional.ofNullable(result);
    }

    /**
     * Handles gradle dependencies defined in a block where {@code compile project} is on one line.
     * <pre>
     * dependency {
     *   compile project(:x)
     *   compile project(:y)
     *   compile project(:z)
     *   )
     *   ...
     * }
     * </pre>
     * @return a {@code compile project(:x)} match or empty result.
     */
    private static Optional<String> handleDependencyCompileProject(String line) {
        Matcher matcher = COMPILE_PROJECT_REGEXP.matcher(line);
        return matcher.find() ? Optional.of(matcher.toMatchResult().group(1)) : Optional.empty();
    }

    /**
     * For all previously extracted {@link #MODULES} searches for their transient dependencies along
     * with paths how we got to them. This is done using graph search - my custom variant of breadth
     * graph search. Note that we are running the breadth-search for every gradle module.
     */
    private static void resolveTransientDependencies() {
        MODULES.keySet().forEach(moduleName -> {
            breathSearchWithPath(moduleName);
            processQueue(moduleName);
            QUEUE.clear();
        });
    }

    /**
     * Runs graph breadth search algorithm for single gradle module.
     * @param rootModule gradle module
     */
    private static void breathSearchWithPath(String rootModule) {
        ModuleNode moduleNode = new ModuleNode(rootModule);
        QUEUE.add(moduleNode);
        doBreathSearch();

    }

    /**
     * While not all nodes in queue are visited, search for their children and
     * put them in a queue.
     */
    private static void doBreathSearch() {
        Optional<ModuleNode> nextModuleNode;
        while ((nextModuleNode = nextModuleNode()).isPresent()) {
            ModuleNode currentModuleNode = nextModuleNode.get();
            currentModuleNode.visit();
            QUEUE.addAll(moduleChildren(currentModuleNode));
        }
    }

    /**
     * @return {@code true} if we have in {@link #QUEUE} some module that was not visited yet.
     */
    private static Optional<ModuleNode> nextModuleNode() {
        return QUEUE.stream().filter(moduleNode -> !moduleNode.visited).findFirst();
    }

    /**
     * When we are searching for node descendants ( = children = direct dependencies) we can
     * easily reuse what we already have in {@link #MODULES}.
     *
     * @param moduleNode node for which we search for direct dependencies.
     * @return list of direct dependencies unless it is a cycle, or the module was not found
     * in {@link #MODULES}
     */
    private static List<ModuleNode> moduleChildren(ModuleNode moduleNode) {
        if (moduleNode.isCycle()) {
          return Collections.emptyList();
        }
        if (MODULES.get(moduleNode.name) == null) {
            System.out.println("WARNING: dependency on not-detected module: " + moduleNode.name);
            return Collections.emptyList();
        }
        return MODULES.get(moduleNode.name).stream().map(childModuleName -> {
            ModuleNode childNode = new ModuleNode(childModuleName);
            childNode.buildPath(moduleNode.path, moduleNode.name);
            return childNode;
        }).collect(Collectors.toList());

    }

    /**
     * Now we have everything our {@link #QUEUE} and we just need to transform it to something that
     * is later easy to print and process. We store the final structure in {@link #MODULES_WITH_TRANSIENT_DEPENDENCIES}
     *
     * @param rootModule gradle module we were searching for all transient dependencies with their paths.
     */
    private static void processQueue(String rootModule) {
        Set<Dependency> existingDependencies = new TreeSet<>();
        MODULES_WITH_TRANSIENT_DEPENDENCIES.put(rootModule, existingDependencies);
        QUEUE.forEach(moduleNode -> {
            DependencyPath dependencyPath = moduleNode.isCycle() ? DependencyPath.transientDependencyFromCyclePath(moduleNode.path.toArray(new String[0])) :
                    DependencyPath.transientDependencyFromPath(moduleNode.path.toArray(new String[0]));
            Optional<Dependency> existingDependency = existingDependencies.stream().filter(dependency -> dependency.moduleName.equals(moduleNode.name)).findFirst();
            if (existingDependency.isPresent()) {
                existingDependency.get().addPath(dependencyPath);
            } else {
                existingDependencies.add(new Dependency(moduleNode.name, dependencyPath));
            }
        });
    }

    /**
     * Represents a gradle module dependency for purpose of display.
     */
    static class Dependency implements Comparable<Dependency> {

        /**
         * Name of the gradle module.
         */
        String moduleName;

        Set<DependencyPath> dependencyPathSet = new HashSet<>();

        Dependency(String moduleName, DependencyPath dependencyPath) {
            this.moduleName = moduleName;
            addPath(dependencyPath);
        }

        void addPath(DependencyPath dependencyPath) {
            dependencyPathSet.add(dependencyPath);
        }

        /**
         * @return {@code true} if the dependency is direct (explicitly defined in build.gradle) for
         * given root module.
         */
        boolean isDirectDependency() {
            return dependencyPathSet.stream().anyMatch(dependencyPath -> dependencyPath.path.length == 1);
        }

        /**
         * @return {@code true} if the this "dependency" represents the root gradle module. Root gradle modules
         * for which we search for all direct and transient modules dependencies are
         * formally considered dependency too (so called "root dependency")
         */
        boolean isRoot() {
            return dependencyPathSet.stream().anyMatch(dependencyPath -> dependencyPath.path.length == 0);
        }

        /**
         * Prints representation dependency in form of plant uml mind map source.
         * (https://plantuml.com/mindmap-diagram), see also {@link #printTransientDependencyAnalysis(String, String)}}
         */
        @Override
        public String toString() {
            StringBuilder stringBuilder = new StringBuilder(isRoot() ? "* " : "** ");
            stringBuilder.append(moduleName);
            // transient dependency
            if (!isRoot() && !isDirectDependency()) {
                stringBuilder.append(" [t]");
            }
            if (!dependencyPathSet.isEmpty() && !isDirectDependency() && !isCommonDependency(this.moduleName)) {
                pathToString("***");
            }
            return stringBuilder.toString();
        }

        public String pathToString(String prefix) {
            StringBuilder stringBuilder = new StringBuilder();
            dependencyPathSet.forEach(dependencyPath -> {
                stringBuilder.append(System.lineSeparator());
                if (dependencyPath.path.length > 0) stringBuilder.append(prefix).append(" ");
                for (int i = 0; i < dependencyPath.path.length; i++) {
                    stringBuilder.append(dependencyPath.path[i]);
                    if (i < dependencyPath.path.length - 1) stringBuilder.append(",");
                }
                if (dependencyPath.cycle) {
                    stringBuilder.append(" [cycle]");
                }
            });
            return stringBuilder.toString();
        }

        private static boolean isCommonDependency(String dependency) {
            return dependency.equals("common") || dependency.equals("servercommon");
        }

        // we don't need equals and hashcode here even when overriding compareTo

        @Override
        public int compareTo(Dependency o) {
            if (this.isRoot()) {
                return -1;
            }
            if (o.isRoot()) {
                return 1;
            }
            if (this.isDirectDependency() && !o.isDirectDependency()) {
                return -1;
            }
            if (!this.isDirectDependency() && o.isDirectDependency()) {
                return 1;
            }
            if (o.isDirectDependency() && this.isDirectDependency()) {
                if (MODULES_PRIORITY.get(this.moduleName) != null) {
                    if (MODULES_PRIORITY.get(o.moduleName) != null) {
                        return MODULES_PRIORITY.get(this.moduleName).compareTo(MODULES_PRIORITY.get(o.moduleName));
                    } // else
                    return -1;
                } // else
                if (MODULES_PRIORITY.get(o.moduleName) != null) {
                    return 1;
                }
            }
            return this.moduleName.compareTo(o.moduleName);
        }
    }

    /**
     * Representation of how we get to module from root gradle module when running
     * graph breadth-first search that is used in final results.
     */
    static class DependencyPath {
        /**
         * Every item represents a module name (on a path)
         */
        String[] path;
        /**
         * Is this circular path?
         */
        boolean cycle;

        static DependencyPath transientDependencyFromPath(String[] path) {
            DependencyPath dependencyPath = new DependencyPath();
            dependencyPath.path = path;
            return dependencyPath;
        }

        static DependencyPath transientDependencyFromCyclePath(String[] path) {
            DependencyPath dependencyPath = new DependencyPath();
            dependencyPath.path = path;
            dependencyPath.cycle = true;
            return dependencyPath;
        }
    }

    /**
     * Represents a gradle modules as a graph node, needed for
     * graph breadth-first search, it is NOT a structured used to display final results.
     */
    static class ModuleNode  {

        /**
         * Name of the gradle module
         */
        String name;

        /**
         * How we get to that module from root gradle module when running
         * graph breadth-first search.
         */
        List<String> path = new ArrayList<>();

        /**
         * Do we have already visited this node during graph walk?
         * For breadth-first search this says if we have already put referenced (child)
         * nodes to processing queue.
         */
        boolean visited;

        ModuleNode(String moduleName) {
            this.name = moduleName;
        }

        void visit() {
            this.visited = true;
        }

        void buildPath(List<String> parentPath, String parent) {
            path.addAll(parentPath);
            path.add(parent);
        }

        boolean isCycle() {
            return path.contains(name);
        }

        @Override
        public String toString() {
            return "ModuleNode{" +
                    "name='" + name + '\'' +
                    ", path=" + path +
                    ", visited=" + visited +
                    '}';
        }
    }

    /**
     * Capable to sort gradle modules names in preferred ordering.
     */
    static class PriorityComparator implements Comparator<String> {

        @Override
        public int compare(String o1, String o2) {
            if (MODULES_PRIORITY.get(o1) != null) {
                if (MODULES_PRIORITY.get(o2) != null) {
                    return MODULES_PRIORITY.get(o1).compareTo(MODULES_PRIORITY.get(o2));
                } // else
                return -1;
            } // else
            if (MODULES_PRIORITY.get(o2) != null) {
                return 1;
            }
            return o1.compareTo(o2);
        }
    }

}
