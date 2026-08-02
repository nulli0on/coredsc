package com.hubertstudios.coredsc.voice;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;


public final class ProximityTopology {
    private ProximityTopology() { }

    public record Node(
            UUID id,
            UUID worldId,
            double x,
            double y,
            double z
    ) {
        public Node {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(worldId, "worldId");
        }

        private double horizontalDistance(Node other) {
            double dx = x - other.x;
            double dz = z - other.z;
            return Math.sqrt(dx * dx + dz * dz);
        }

        private double verticalDistance(Node other) {
            return Math.abs(y - other.y);
        }
    }

    public static List<Set<UUID>> connectedComponents(
            Collection<Node> nodes,
            Map<UUID, UUID> previousRooms,
            double horizontalDistance,
            double verticalDistance,
            double falloff
    ) {
        Objects.requireNonNull(nodes, "nodes");
        Objects.requireNonNull(previousRooms, "previousRooms");
        if (!Double.isFinite(horizontalDistance) || horizontalDistance <= 0.0) {
            throw new IllegalArgumentException("horizontalDistance must be greater than zero");
        }
        if (!Double.isFinite(verticalDistance) || verticalDistance <= 0.0) {
            throw new IllegalArgumentException("verticalDistance must be greater than zero");
        }
        if (!Double.isFinite(falloff) || falloff < 0.0) {
            throw new IllegalArgumentException("falloff must not be negative");
        }

        List<Node> ordered = new ArrayList<>(nodes);
        Map<UUID, Set<UUID>> edges = new HashMap<>();
        for (Node node : ordered) {
            edges.put(node.id(), new LinkedHashSet<>());
        }

        for (int i = 0; i < ordered.size(); i++) {
            Node left = ordered.get(i);
            for (int j = i + 1; j < ordered.size(); j++) {
                Node right = ordered.get(j);
                if (!left.worldId().equals(right.worldId())) {
                    continue;
                }
                UUID previousRoom = previousRooms.get(left.id());
                boolean sameRoom = previousRoom != null
                        && previousRoom.equals(previousRooms.get(right.id()));
                double horizontalLimit = horizontalDistance + (sameRoom ? falloff : 0.0);
                double verticalLimit = verticalDistance + (sameRoom ? falloff : 0.0);
                if (left.horizontalDistance(right) <= horizontalLimit
                        && left.verticalDistance(right) <= verticalLimit) {
                    edges.get(left.id()).add(right.id());
                    edges.get(right.id()).add(left.id());
                }
            }
        }

        List<Set<UUID>> components = new ArrayList<>();
        Set<UUID> visited = new HashSet<>();
        for (Node start : ordered) {
            if (!visited.add(start.id())) {
                continue;
            }
            Set<UUID> component = new LinkedHashSet<>();
            ArrayDeque<UUID> queue = new ArrayDeque<>();
            queue.add(start.id());
            while (!queue.isEmpty()) {
                UUID current = queue.removeFirst();
                component.add(current);
                for (UUID neighbor : edges.getOrDefault(current, Set.of())) {
                    if (visited.add(neighbor)) {
                        queue.addLast(neighbor);
                    }
                }
            }
            components.add(Set.copyOf(component));
        }
        return List.copyOf(components);
    }
}
