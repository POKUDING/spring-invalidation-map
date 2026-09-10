package dev.toktokhan.invalidation.core.walk;

import java.util.List;

public record WalkResult(int visitedMethods, boolean budgetExceeded, List<String> unresolved) {
}
