package io.titan.graphql.validation;

import java.util.List;

public record TitanGraphqlValidationReport(List<TitanGraphqlValidationIssue> issues) {

    public TitanGraphqlValidationReport {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public static TitanGraphqlValidationReport empty() {
        return new TitanGraphqlValidationReport(List.of());
    }

    public boolean valid() {
        return errorCount() == 0;
    }

    public boolean blocksDeployment() {
        for (TitanGraphqlValidationIssue issue : issues) {
            if (issue.blocksDeployment()) {
                return true;
            }
        }
        return false;
    }

    public long errorCount() {
        return count(TitanGraphqlValidationSeverity.ERROR);
    }

    public long warningCount() {
        return count(TitanGraphqlValidationSeverity.WARNING);
    }

    public long infoCount() {
        return count(TitanGraphqlValidationSeverity.INFO);
    }

    private long count(TitanGraphqlValidationSeverity severity) {
        long count = 0;
        for (TitanGraphqlValidationIssue issue : issues) {
            if (issue.severity() == severity) {
                count++;
            }
        }
        return count;
    }
}
