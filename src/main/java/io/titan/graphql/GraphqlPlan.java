package io.titan.graphql;

import java.util.ArrayList;
import java.util.List;

public final class GraphqlPlan {

    private final List<ReadStep> readSteps = new ArrayList<>();

    public void addReadStep(String name, String sql) {
        readSteps.add(new ReadStep(name, sql));
    }

    public List<ReadStep> readSteps() {
        return List.copyOf(readSteps);
    }

    public int readStepCount() {
        return readSteps.size();
    }

    public record ReadStep(String name, String sql) {
    }
}
