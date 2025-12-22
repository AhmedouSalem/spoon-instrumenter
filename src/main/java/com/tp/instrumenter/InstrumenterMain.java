package com.tp.instrumenter;

import spoon.Launcher;
import spoon.support.sniper.SniperJavaPrettyPrinter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

public class InstrumenterMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: InstrumenterMain <originalProjectDir> <targetRunnableInstrumentedDir>");
            System.exit(1);
        }

        Path original = Path.of(args[0]).toAbsolutePath().normalize();
        Path target = Path.of(args[1]).toAbsolutePath().normalize();

        if (!Files.exists(original.resolve("pom.xml"))) {
            throw new IllegalArgumentException("pom.xml not found in original project: " + original);
        }
        if (!Files.exists(original.resolve("src/main/java"))) {
            throw new IllegalArgumentException("src/main/java not found in original project: " + original);
        }

        System.out.println("==> ORIGINAL: " + original);
        System.out.println("==> TARGET  : " + target);

        // 1) Copy whole project (runnable) WITHOUT .git and WITHOUT target/
        deleteIfExists(target);
        copyWholeProject(original, target);

        // 2) Run Spoon into a TEMP folder (never directly into target)
        Path tmp = target.resolve(".spoon-tmp");
        deleteIfExists(tmp);
        Files.createDirectories(tmp.resolve("src/main/java"));

        System.out.println("==> Running Spoon to temp output...");
        Launcher launcher = new Launcher();
        launcher.getEnvironment().setPrettyPrinterCreator(() ->
                new SniperJavaPrettyPrinter(launcher.getEnvironment())
        );


        // IMPORTANT: autoImports can break jakarta.persistence imports in noClasspath mode
        launcher.getEnvironment().setAutoImports(false);
        launcher.getEnvironment().setNoClasspath(true);
        launcher.addInputResource(
                original.resolve("src/main/java/com/obs/productmanagement/service").toString()
        );
        launcher.setSourceOutputDirectory(tmp.resolve("src/main/java").toString());

        launcher.addProcessor(new LoggingInjectorProcessor());
        launcher.run();

        // 3) Patch ONLY instrumented *ServiceImpl.java into the runnable project
        System.out.println("==> Copying ONLY *ServiceImpl.java into target...");
        Path tmpJava = tmp.resolve("src/main/java");
        Path targetJava = target.resolve("src/main/java");

        patchOnlyServiceImplFiles(tmpJava, targetJava);

        // 4) Ensure JSON logging dependencies and config
        ensureLogstashEncoderDependency(target.resolve("pom.xml"));
        ensureLogbackSpringXml(target.resolve("src/main/resources/logback-spring.xml"));
        Files.createDirectories(target.resolve("logs"));

        // 5) Cleanup
        deleteIfExists(tmp);

        System.out.println("✅ DONE. Runnable instrumented project created at: " + target);
        System.out.println("Try:");
        System.out.println("  cd \"" + target + "\"");
        System.out.println("  ./mvnw -DskipTests package");
        System.out.println("  ./mvnw spring-boot:run");
    }

    /** Copies the full project but excludes: .git/, target/, and the generated logs folder if you want */
    private static void copyWholeProject(Path original, Path target) throws IOException {
        System.out.println("==> Copying project to target (excluding .git/, target/) ...");

        Files.walkFileTree(original, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path rel = original.relativize(dir);
                String relStr = rel.toString().replace("\\", "/");

                // Exclusions
                if (relStr.equals("target") || relStr.startsWith("target/")) return FileVisitResult.SKIP_SUBTREE;
                if (relStr.equals(".git") || relStr.startsWith(".git/")) return FileVisitResult.SKIP_SUBTREE;

                Files.createDirectories(target.resolve(rel));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path rel = original.relativize(file);
                String relStr = rel.toString().replace("\\", "/");

                if (relStr.startsWith("target/")) return FileVisitResult.CONTINUE;
                if (relStr.startsWith(".git/")) return FileVisitResult.CONTINUE;

                Path dest = target.resolve(rel);
                Files.createDirectories(dest.getParent());
                Files.copy(file, dest, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Copy only *ServiceImpl.java from tmp to target (preserves DTO/model/controller/repo intact) */
    private static void patchOnlyServiceImplFiles(Path tmpJavaRoot, Path targetJavaRoot) throws IOException {
        Files.walkFileTree(tmpJavaRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String path = file.toString().replace("\\", "/");
                if (!path.endsWith("ServiceImpl.java")) return FileVisitResult.CONTINUE;

                Path rel = tmpJavaRoot.relativize(file);
                Path dest = targetJavaRoot.resolve(rel);

                Files.createDirectories(dest.getParent());
                Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);

                System.out.println("  patched: " + rel);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void ensureLogstashEncoderDependency(Path pomPath) throws IOException {
        String pom = Files.readString(pomPath, StandardCharsets.UTF_8);

        if (pom.contains("logstash-logback-encoder")) {
            System.out.println("==> pom.xml already contains logstash-logback-encoder ✅");
            return;
        }

        System.out.println("==> Adding logstash-logback-encoder dependency to pom.xml ...");

        String dep = """
                <dependency>
                  <groupId>net.logstash.logback</groupId>
                  <artifactId>logstash-logback-encoder</artifactId>
                  <version>7.4</version>
                </dependency>
                """;

        if (pom.contains("</dependencies>")) {
            pom = pom.replace("</dependencies>", dep + "\n</dependencies>");
        } else {
            pom = pom.replace("</project>", "<dependencies>\n" + dep + "\n</dependencies>\n</project>");
        }

        Files.writeString(pomPath, pom, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        System.out.println("==> pom.xml patched ✅");
    }

    private static void ensureLogbackSpringXml(Path logbackPath) throws IOException {
        if (Files.exists(logbackPath)) {
            System.out.println("==> logback-spring.xml already exists ✅");
            return;
        }

        System.out.println("==> Creating logback-spring.xml for JSON logs (logs/app.jsonl) ...");
        Files.createDirectories(logbackPath.getParent());

        String content = """
                <configuration>
                  <appender name="JSON_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
                    <file>logs/app.jsonl</file>
                    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
                      <fileNamePattern>logs/app.%d{yyyy-MM-dd}.jsonl</fileNamePattern>
                      <maxHistory>7</maxHistory>
                    </rollingPolicy>
                    <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
                  </appender>

                  <root level="INFO">
                    <appender-ref ref="JSON_FILE"/>
                  </root>
                </configuration>
                """;

        Files.writeString(logbackPath, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println("==> logback-spring.xml created ✅");
    }

    private static void deleteIfExists(Path path) throws IOException {
        if (!Files.exists(path)) return;

        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}

