package com.scanner.complexity;

import net.sourceforge.pmd.lang.ast.Node;
import net.sourceforge.pmd.lang.java.ast.ASTCatchClause;
import net.sourceforge.pmd.lang.java.ast.ASTExecutableDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTInfixExpression;
import net.sourceforge.pmd.lang.java.ast.ASTLambdaExpression;
import net.sourceforge.pmd.lang.java.ast.ASTLocalVariableDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTSwitchLabel;

/** PMD AST uzerinden metod sayaclari (JavaParser yok). */
final class PmdAstMethodStats {

    record Stats(
            int maxNestingDepth,
            int logicalStatements,
            int exitPoints,
            int catchClauses,
            int emptyCatchBlocks,
            int catchExceptionOrThrowable,
            int lambdaCount,
            int localVariableCount,
            int maxTryNestingDepth,
            int switchCases,
            int maxBooleanOperatorsInCondition) {
    }

    private PmdAstMethodStats() {
    }

    static Stats analyze(ASTExecutableDeclaration exec) {
        Counter counter = new Counter();
        counter.walk(exec, 0, 0);
        return counter.toStats();
    }

    private static final class Counter {
        int maxNest;
        int statements;
        int exits;
        int catches;
        int emptyCatch;
        int genericCatch;
        int lambdas;
        int locals;
        int maxTryNest;
        int switchCases;
        int maxBoolOps;

        void walk(Node node, int nestDepth, int tryDepth) {
            String name = node.getXPathNodeName();
            int nextNest = nestDepth;
            int nextTry = tryDepth;
            if (isControlFlow(name)) {
                nextNest = nestDepth + 1;
                maxNest = Math.max(maxNest, nextNest);
            }
            if ("TryStatement".equals(name)) {
                nextTry = tryDepth + 1;
                maxTryNest = Math.max(maxTryNest, nextTry);
            }
            switch (name) {
                case "Statement", "ExpressionStatement", "IfStatement", "WhileStatement", "ForStatement",
                        "ForeachStatement", "DoStatement", "SynchronizedStatement", "AssertStatement" ->
                        statements++;
                case "ReturnStatement", "ThrowStatement", "YieldStatement" -> exits++;
                case "CatchClause" -> {
                    catches++;
                    if (node instanceof ASTCatchClause catchClause) {
                        if (catchClause.getBody().size() == 0) {
                            emptyCatch++;
                        }
                        String type = catchClause.getParameter().getTypeNode().toString();
                        if (type.contains("Exception") || type.contains("Throwable") || type.contains("Error")) {
                            genericCatch++;
                        }
                    }
                }
                case "LambdaExpression" -> lambdas++;
                case "LocalVariableDeclaration" -> locals++;
                case "SwitchLabel" -> {
                    if (node instanceof ASTSwitchLabel label && !label.isDefault()) {
                        switchCases++;
                    }
                }
                case "InfixExpression" -> {
                    if (node instanceof ASTInfixExpression infix) {
                        String op = infix.getOperator().toString();
                        if ("&&".equals(op) || "||".equals(op)) {
                            maxBoolOps = Math.max(maxBoolOps, countBooleanOps(infix));
                        }
                    }
                }
                default -> { }
            }
            for (Node child : node.children()) {
                walk(child, nextNest, nextTry);
            }
        }

        Stats toStats() {
            return new Stats(maxNest, statements, exits, catches, emptyCatch, genericCatch, lambdas, locals,
                    maxTryNest, switchCases, maxBoolOps);
        }
    }

    private static boolean isControlFlow(String xpathName) {
        return switch (xpathName) {
            case "IfStatement", "WhileStatement", "ForStatement", "ForeachStatement", "DoStatement",
                    "TryStatement", "SwitchStatement", "SynchronizedStatement" -> true;
            default -> false;
        };
    }

    private static int countBooleanOps(ASTInfixExpression infix) {
        int count = 0;
        if (infix.getOperator().toString().equals("&&") || infix.getOperator().toString().equals("||")) {
            count = 1;
        }
        for (Node child : infix.children()) {
            if (child instanceof ASTInfixExpression nested) {
                count += countBooleanOps(nested);
            }
        }
        return count;
    }
}
