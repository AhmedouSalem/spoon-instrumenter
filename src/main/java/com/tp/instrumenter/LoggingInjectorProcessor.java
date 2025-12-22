package com.tp.instrumenter;

import spoon.processing.AbstractProcessor;
import spoon.reflect.code.CtStatement;
import spoon.reflect.declaration.*;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;

import java.util.Locale;

public class LoggingInjectorProcessor extends AbstractProcessor<CtType<?>> {

    @Override
    public void process(CtType<?> type) {
        if (!(type instanceof CtClass<?> clazz)) return;

        String className = clazz.getSimpleName();
        if (!className.endsWith("ServiceImpl")) return;

        ensureLoggerField(clazz);

        for (CtMethod<?> m : clazz.getMethods()) {
            if (!m.isPublic()) continue;
            if (m.getBody() == null) continue;
            if (m.isAbstract()) continue;

            // évite double injection si relancé
            if (!m.getBody().getStatements().isEmpty()
                    && m.getBody().getStatement(0).toString().contains("LPS")) {
                continue;
            }

            String action = inferAction(m.getSimpleName());
            CtStatement logStmt = buildLogStatement(clazz, m, action);

            m.getBody().insertBegin(logStmt);
        }
    }

    private String inferAction(String methodName) {
        String n = methodName.toLowerCase(Locale.ROOT);

        if (n.contains("mostexpensive")) return "MOST_EXPENSIVE";

        if (n.startsWith("get") || n.startsWith("find") || n.startsWith("list")) return "READ";
        if (n.startsWith("create") || n.startsWith("add") || n.startsWith("save")
                || n.startsWith("update") || n.startsWith("delete") || n.startsWith("remove")) return "WRITE";

        // fallback : tu peux aussi mettre "OTHER"
        return "READ";
    }

    private void ensureLoggerField(CtClass<?> clazz) {
        boolean hasLog = clazz.getFields().stream().anyMatch(f -> f.getSimpleName().equals("log"));
        if (hasLog) return;

        Factory f = getFactory();

        CtField<?> field = f.createField();
        field.setSimpleName("log");
        field.addModifier(ModifierKind.PRIVATE);
        field.addModifier(ModifierKind.STATIC);
        field.addModifier(ModifierKind.FINAL);

        field.setType(f.Type().createReference("org.slf4j.Logger"));

        // Fully-qualified, no imports needed:
        String expr = "org.slf4j.LoggerFactory.getLogger(" + clazz.getSimpleName() + ".class)";
        field.setDefaultExpression(f.Code().createCodeSnippetExpression(expr));

        clazz.addFieldAtTop((CtField) field);
    }


    private CtStatement buildLogStatement(CtClass<?> clazz, CtMethod<?> m, String action) {
        Factory f = getFactory();

        // log.info("LPS", kv("event","DB_OP"), kv("action", "..."), kv("class", "..."), kv("method","..."))
        // event : on peut mettre DB_READ/DB_WRITE/EXPENSIVE directement pour faciliter Q5
        String event = switch (action) {
            case "WRITE" -> "DB_WRITE";
            case "MOST_EXPENSIVE" -> "MOST_EXPENSIVE_SEARCH";
            default -> "DB_READ";
        };

        StringBuilder sb = new StringBuilder();
        sb.append("log.info(\"LPS\"");

        sb.append(", net.logstash.logback.argument.StructuredArguments.kv(\"event\",\"").append(event).append("\")");
        sb.append(", net.logstash.logback.argument.StructuredArguments.kv(\"action\",\"").append(action).append("\")");
        sb.append(", net.logstash.logback.argument.StructuredArguments.kv(\"class\",\"").append(clazz.getSimpleName()).append("\")");
        sb.append(", net.logstash.logback.argument.StructuredArguments.kv(\"method\",\"").append(m.getSimpleName()).append("\")");

        // Si la méthode a des paramètres du type *Id (Long/long/String), on les log
        for (CtParameter<?> p : m.getParameters()) {
            String pn = p.getSimpleName();
            String pt = p.getType().getSimpleName();

            boolean isIdLike = pn.toLowerCase(Locale.ROOT).endsWith("id");
            boolean isSimple = pt.equals("Long") || pt.equals("long") || pt.equals("String") || pt.equals("Integer") || pt.equals("int");

            if (isIdLike && isSimple) {
                sb.append(", net.logstash.logback.argument.StructuredArguments.kv(\"")
                        .append(pn).append("\", ").append(pn).append(")");
            }
        }

        sb.append(");");

        return f.Code().createCodeSnippetStatement(sb.toString());
    }
}
