package com.hubertstudios.coredsc.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

                                                                                    
public final class ConfigKeyInspector {
    private ConfigKeyInspector() { }

    public record UnknownKey(String path, String suggestion) { }

    public static List<UnknownKey> findUnknownKeys(
            Collection<String> currentKeys,
            Set<String> allowedKeys,
            Set<String> ignoredKeys
    ) {
        Set<String> allowed = new HashSet<>(allowedKeys);
        Set<String> ignored = new HashSet<>(ignoredKeys);
        List<String> sorted = new ArrayList<>(currentKeys);
        sorted.sort(Comparator.comparingInt(ConfigKeyInspector::pathDepth)
                .thenComparing(String::compareTo));

        List<String> unknownParents = new ArrayList<>();
        List<UnknownKey> result = new ArrayList<>();
        for (String key : sorted) {
            if (ignored.contains(key) || allowed.contains(key)
                    || hasUnknownParent(key, unknownParents)) {
                continue;
            }
            result.add(new UnknownKey(key, nearestKey(key, allowed)));
            if (hasChild(key, sorted)) {
                unknownParents.add(key);
            }
        }
        return List.copyOf(result);
    }

    private static boolean hasChild(String key, List<String> allKeys) {
        String prefix = key + ".";
        for (String candidate : allKeys) {
            if (candidate.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasUnknownParent(String key, List<String> unknownParents) {
        for (String parent : unknownParents) {
            if (key.startsWith(parent + ".")) {
                return true;
            }
        }
        return false;
    }

    private static int pathDepth(String path) {
        int depth = 1;
        for (int index = 0; index < path.length(); index++) {
            if (path.charAt(index) == '.') {
                depth++;
            }
        }
        return depth;
    }

    private static String nearestKey(String unknown, Set<String> allowed) {
        int split = unknown.lastIndexOf('.');
        String parent = split < 0 ? "" : unknown.substring(0, split);
        String leaf = split < 0 ? unknown : unknown.substring(split + 1);
        String best = "";
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : allowed) {
            int candidateSplit = candidate.lastIndexOf('.');
            String candidateParent = candidateSplit < 0 ? "" : candidate.substring(0, candidateSplit);
            if (!candidateParent.equals(parent)) {
                continue;
            }
            String candidateLeaf = candidateSplit < 0 ? candidate : candidate.substring(candidateSplit + 1);
            int distance = levenshtein(leaf, candidateLeaf);
            int maximumUsefulDistance = Math.max(2, Math.min(4,
                    Math.max(leaf.length(), candidateLeaf.length()) / 3));
            if (distance < bestDistance && distance <= maximumUsefulDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int column = 0; column <= right.length(); column++) {
            previous[column] = column;
        }
        for (int row = 1; row <= left.length(); row++) {
            current[0] = row;
            for (int column = 1; column <= right.length(); column++) {
                int substitution = previous[column - 1]
                        + (left.charAt(row - 1) == right.charAt(column - 1) ? 0 : 1);
                current[column] = Math.min(
                        Math.min(previous[column] + 1, current[column - 1] + 1), substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }
}
