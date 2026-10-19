package com.scanner.exception.complexity;

import com.scanner.exception.complexity.AnalysisReport.ClassMetric;
import com.scanner.exception.complexity.AnalysisReport.DuplicateGroup;
import com.scanner.exception.complexity.AnalysisReport.DuplicateMember;
import com.scanner.exception.complexity.AnalysisReport.FileMetric;

import net.sourceforge.pmd.cpd.CPDConfiguration;
import net.sourceforge.pmd.cpd.CpdAnalysis;
import net.sourceforge.pmd.cpd.Mark;
import net.sourceforge.pmd.cpd.Match;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Proje geneli duplicate / near-duplicate tespiti:
 * Type-1 {@link #detectExactTextDuplicates} (gövde metni + literal/isim),
 * Type-2 {@link #detectExactStructureDuplicates} (yapısal hash + aynı imza),
 * Type-3 {@link #detectNearMissDuplicates} (PMD CPD, literal eşleşmeli).
 */
final class DuplicateDetectionEngine {

    private static final int MIN_CC_FOR_DUPLICATE_CHECK = 3;
    private static final int MIN_LOC_FOR_DUPLICATE_CHECK = 8;

    List<DuplicateGroup> detectExactTextDuplicates(List<FileMetric> files) {
        return groupByHash(files, "EXACT_TEXT", method -> method.bodyHash());
    }

    List<DuplicateGroup> detectExactStructureDuplicates(List<FileMetric> files) {
        Map<String, List<DuplicateMember>> byHash = new LinkedHashMap<>();
        for (FileMetric file : files) {
            for (ClassMetric type : file.classes()) {
                for (MethodMetric method : type.methods()) {
                    if (!qualifiesForDuplicateCheck(method)) {
                        continue;
                    }
                    String structural = method.structuralHash();
                    if (structural == null || structural.isEmpty()) {
                        continue;
                    }
                    String key = structural + "\0" + method.structureDuplicateSignature();
                    byHash.computeIfAbsent(key, k -> new ArrayList<>())
                            .add(new DuplicateMember(file.path(), type.name(), method.signature(),
                                    method.startLine(), method.endLine()));
                }
            }
        }
        List<DuplicateGroup> groups = buildGroups(byHash, "EXACT_STRUCTURE", "EXACT-STRUCT-");
        return dropStructureGroupsAlreadyExactText(files, groups);
    }

    /** Aynı gövde metni EXACT_TEXT'te raporlanır; yapısal grupta tekrar etme. */
    private static List<DuplicateGroup> dropStructureGroupsAlreadyExactText(List<FileMetric> files,
                                                                          List<DuplicateGroup> structureGroups) {
        Map<String, MethodMetric> byFileSig = indexMethods(files);
        List<DuplicateGroup> out = new ArrayList<>();
        for (DuplicateGroup group : structureGroups) {
            String sharedBody = null;
            boolean allSameBody = true;
            for (DuplicateMember member : group.members()) {
                MethodMetric m = byFileSig.get(member.file() + "\0" + member.method());
                if (m == null || m.bodyHash().isEmpty()) {
                    allSameBody = false;
                    break;
                }
                if (sharedBody == null) {
                    sharedBody = m.bodyHash();
                } else if (!sharedBody.equals(m.bodyHash())) {
                    allSameBody = false;
                    break;
                }
            }
            if (allSameBody && group.members().size() >= 2) {
                continue;
            }
            out.add(group);
        }
        return out;
    }

    private static Map<String, MethodMetric> indexMethods(List<FileMetric> files) {
        Map<String, MethodMetric> map = new LinkedHashMap<>();
        for (FileMetric file : files) {
            for (ClassMetric type : file.classes()) {
                for (MethodMetric method : type.methods()) {
                    map.put(file.path() + "\0" + method.signature(), method);
                }
            }
        }
        return map;
    }

    /** @deprecated use {@link #detectExactTextDuplicates} + {@link #detectExactStructureDuplicates} */
    List<DuplicateGroup> detectExactDuplicates(List<FileMetric> files) {
        List<DuplicateGroup> groups = new ArrayList<>();
        groups.addAll(detectExactTextDuplicates(files));
        groups.addAll(detectExactStructureDuplicates(files));
        return groups;
    }

    List<DuplicateGroup> detectNearMissDuplicates(Path root, int minimumTokens, List<FileMetric> files) {
        Map<String, FileMetric> filesByPath = files.stream()
                .collect(Collectors.toMap(FileMetric::path, f -> f, (a, b) -> a));

        CPDConfiguration config = new CPDConfiguration();
        config.setMinimumTileSize(minimumTokens);
        config.setOnlyRecognizeLanguage(config.getLanguageRegistry().getLanguageById("java"));
        config.setIgnoreIdentifiers(true);
        config.setIgnoreLiterals(false);
        config.setSourceEncoding(StandardCharsets.UTF_8);
        config.addInputPath(root);

        List<DuplicateGroup> groups = new ArrayList<>();
        try (CpdAnalysis cpd = CpdAnalysis.create(config)) {
            cpd.performAnalysis(report -> {
                int groupId = 1;
                for (Match match : report.getMatches()) {
                    List<DuplicateMember> members = new ArrayList<>();
                    for (Mark mark : match.getMarkSet()) {
                        var location = mark.getLocation();
                        Path absoluteFile = Path.of(location.getFileId().getAbsolutePath());
                        int beginLine = location.getStartLine();
                        int endLine = location.getEndLine();
                        String relative = relativize(root, absoluteFile);
                        FileMetric file = filesByPath.get(relative);
                        ResolvedMethod resolved = resolveMethod(file, beginLine, endLine);
                        if (resolved != null && !qualifiesForDuplicateCheck(resolved.method())) {
                            continue;
                        }
                        String className = resolved != null ? resolved.className() : "?";
                        String signature = resolved != null ? resolved.method().signature()
                                : "(satır " + beginLine + "-" + endLine + ")";
                        members.add(new DuplicateMember(relative, className, signature,
                                beginLine, endLine));
                    }
                    List<DuplicateMember> consolidated = consolidateMembers(members);
                    if (consolidated.size() >= 2) {
                        groups.add(new DuplicateGroup("NEARMISS-" + groupId++, "NEAR_MISS",
                                match.getTokenCount(), match.getLineCount(), consolidated));
                    }
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("PMD CPD analysis failed", e);
        }
        return groups;
    }

    private static List<DuplicateGroup> groupByHash(List<FileMetric> files, String similarityType,
                                                    java.util.function.Function<MethodMetric, String> hashFn) {
        Map<String, List<DuplicateMember>> byHash = new LinkedHashMap<>();
        for (FileMetric file : files) {
            for (ClassMetric type : file.classes()) {
                for (MethodMetric method : type.methods()) {
                    if (!qualifiesForDuplicateCheck(method)) {
                        continue;
                    }
                    String hash = hashFn.apply(method);
                    if (hash == null || hash.isEmpty()) {
                        continue;
                    }
                    byHash.computeIfAbsent(hash, k -> new ArrayList<>())
                            .add(new DuplicateMember(file.path(), type.name(), method.signature(),
                                    method.startLine(), method.endLine()));
                }
            }
        }
        String prefix = "EXACT_TEXT".equals(similarityType) ? "EXACT-TEXT-" : "EXACT-";
        return buildGroups(byHash, similarityType, prefix);
    }

    private static List<DuplicateGroup> buildGroups(Map<String, List<DuplicateMember>> byHash,
                                                    String similarityType, String idPrefix) {
        List<DuplicateGroup> groups = new ArrayList<>();
        int groupId = 1;
        for (List<DuplicateMember> members : byHash.values()) {
            if (members.size() < 2) {
                continue;
            }
            int duplicatedLines = members.stream()
                    .mapToInt(m -> Math.max(0, m.endLine() - m.startLine() + 1))
                    .max().orElse(0);
            groups.add(new DuplicateGroup(idPrefix + groupId++, similarityType, 0, duplicatedLines, members));
        }
        return groups;
    }

    private static boolean qualifiesForDuplicateCheck(MethodMetric method) {
        return method.cyclomaticComplexity() >= MIN_CC_FOR_DUPLICATE_CHECK
                || method.codeLines() >= MIN_LOC_FOR_DUPLICATE_CHECK;
    }

    private static ResolvedMethod resolveMethod(FileMetric file, int beginLine, int endLine) {
        if (file == null) {
            return null;
        }
        ResolvedMethod best = null;
        int bestOverlap = 0;
        for (ClassMetric type : file.classes()) {
            for (MethodMetric method : type.methods()) {
                int overlap = lineOverlap(method.startLine(), method.endLine(), beginLine, endLine);
                if (overlap > bestOverlap) {
                    bestOverlap = overlap;
                    best = new ResolvedMethod(type.name(), method);
                }
            }
        }
        return best;
    }

    private static int lineOverlap(int aStart, int aEnd, int bStart, int bEnd) {
        return Math.max(0, Math.min(aEnd, bEnd) - Math.max(aStart, bStart) + 1);
    }

    private static String relativize(Path root, Path file) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedFile = file.toAbsolutePath().normalize();
        Path relative = normalizedFile.startsWith(normalizedRoot)
                ? normalizedRoot.relativize(normalizedFile) : normalizedFile;
        return relative.toString().replace('\\', '/');
    }

    private record ResolvedMethod(String className, MethodMetric method) {
    }

    /** PMD CPD bazen aynı metod için kaydırmalı satır pencereleri üretir; raporda metod başına tek satır. */
    static List<DuplicateMember> consolidateMembers(List<DuplicateMember> members) {
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        Map<String, DuplicateMember> merged = new LinkedHashMap<>();
        for (DuplicateMember member : members) {
            String key = member.file() + "\0" + member.className() + "\0" + member.method();
            merged.merge(key, member, DuplicateDetectionEngine::unionMemberLines);
        }
        List<DuplicateMember> out = new ArrayList<>(merged.values());
        out.sort(Comparator
                .comparing(DuplicateMember::file)
                .thenComparing(DuplicateMember::className)
                .thenComparing(DuplicateMember::method));
        return out;
    }

    static DuplicateGroup consolidateGroup(DuplicateGroup group) {
        if (group == null) {
            return null;
        }
        return new DuplicateGroup(group.groupId(), group.similarityType(), group.matchedTokenCount(),
                group.duplicatedLines(), consolidateMembers(group.members()));
    }

    private static DuplicateMember unionMemberLines(DuplicateMember a, DuplicateMember b) {
        return new DuplicateMember(a.file(), a.className(), a.method(),
                Math.min(a.startLine(), b.startLine()), Math.max(a.endLine(), b.endLine()));
    }
}
