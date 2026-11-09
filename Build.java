import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The build, written in Java. No Maven, no Gradle.
 *
 * <pre>
 *   java Build.java          compile, then start the app
 *   java Build.java build    compile only
 *   java Build.java test     compile, then run every test
 *   java Build.java run      start the app without recompiling
 *   java Build.java clean    delete the build folder
 * </pre>
 *
 * Needs a JDK (not just a JRE), version 17 or later. The only third-party code is the
 * PostgreSQL JDBC driver in lib/.
 */
public class Build {

    static final Path ROOT = Path.of("").toAbsolutePath();
    static final Path MAIN_SRC = ROOT.resolve("src/main/java");
    static final Path MAIN_RES = ROOT.resolve("src/main/resources");
    static final Path TEST_SRC = ROOT.resolve("src/test/java");
    static final Path OUT = ROOT.resolve("build/classes");
    static final Path TEST_OUT = ROOT.resolve("build/test-classes");
    static final Path LIB = ROOT.resolve("lib");

    public static void main(String[] args) throws Exception {
        String command = args.length == 0 ? "start" : args[0].toLowerCase(Locale.ROOT);

        switch (command) {
            case "build" -> buildMain();
            case "test" -> { buildMain(); buildTests(); runTests(); }
            case "run" -> { buildMain(); runApp(); }
            case "clean" -> { delete(ROOT.resolve("build")); System.out.println("Removed build/"); }
            case "start" -> { buildMain(); runApp(); }
            default -> {
                System.out.println("Unknown command '" + command + "'. Use build, test, run or clean.");
                System.exit(2);
            }
        }
    }

    /* ------------------------------------------------------------------------- */

    static void buildMain() throws IOException {
        long started = System.nanoTime();
        compile(MAIN_SRC, OUT, libraryPath());
        copyResources(MAIN_RES, OUT);
        System.out.printf("Built %d classes in %.1fs%n", count(OUT, ".class"),
                (System.nanoTime() - started) / 1e9);
    }

    static void buildTests() throws IOException {
        compile(TEST_SRC, TEST_OUT, OUT + File.pathSeparator + libraryPath());
    }

    static void compile(Path sourceRoot, Path output, String classpath) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            fail("No Java compiler found. You are running a JRE; install a JDK 17 or later.");
        }

        List<File> sources;
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            sources = files.filter(p -> p.toString().endsWith(".java")).map(Path::toFile).toList();
        }
        if (sources.isEmpty()) {
            fail("No Java sources under " + ROOT.relativize(sourceRoot));
        }

        delete(output);
        Files.createDirectories(output);

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(
                diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {

            List<String> options = List.of(
                    "--release", "17",
                    "-encoding", "UTF-8",
                    "-Xlint:all,-serial,-processing",
                    "-d", output.toString(),
                    "-cp", classpath);

            boolean ok = compiler.getTask(null, files, diagnostics, options, null,
                    files.getJavaFileObjectsFromFiles(sources)).call();

            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR || d.getKind() == Diagnostic.Kind.WARNING
                        || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING) {
                    String where = d.getSource() == null ? ""
                            : ROOT.relativize(Path.of(d.getSource().toUri())) + ":" + d.getLineNumber() + ": ";
                    System.out.println(d.getKind().toString().toLowerCase(Locale.ROOT) + ": "
                            + where + d.getMessage(Locale.ROOT));
                }
            }
            if (!ok) {
                fail("Compilation failed.");
            }
        }
    }

    static void copyResources(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) {
            return;
        }
        try (Stream<Path> files = Files.walk(from)) {
            for (Path source : files.filter(Files::isRegularFile).toList()) {
                Path target = to.resolve(from.relativize(source).toString());
                Files.createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    static void runTests() throws Exception {
        int exit = java("planner.TestRunner", OUT + File.pathSeparator + TEST_OUT
                + File.pathSeparator + libraryPath());
        if (exit != 0) {
            System.exit(exit);
        }
    }

    static void runApp() throws Exception {
        if (!Files.exists(OUT.resolve("planner/App.class"))) {
            buildMain();
        }
        System.exit(java("planner.App", OUT + File.pathSeparator + libraryPath()));
    }

    /** Starts a child JVM with the same java binary, sharing this console. */
    static int java(String mainClass, String classpath) throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(javaBin, "-Dfile.encoding=UTF-8",
                "-cp", classpath, mainClass)
                .inheritIO()
                .start();
        Runtime.getRuntime().addShutdownHook(new Thread(process::destroy));
        return process.waitFor();
    }

    static String libraryPath() throws IOException {
        if (!Files.isDirectory(LIB)) {
            return "";
        }
        try (Stream<Path> jars = Files.list(LIB)) {
            return jars.filter(p -> p.toString().endsWith(".jar"))
                    .map(Path::toString)
                    .collect(Collectors.joining(File.pathSeparator));
        }
    }

    static long count(Path dir, String suffix) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(p -> p.toString().endsWith(suffix)).count();
        }
    }

    static void delete(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    static void fail(String message) {
        System.out.println(message);
        System.exit(1);
    }
}
