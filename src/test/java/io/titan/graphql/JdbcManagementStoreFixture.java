package io.titan.graphql;

import io.titan.graphql.management.TitanGraphqlDurableManagementStore;
import io.titan.graphql.management.TitanGraphqlManagementSchemaInstaller;
import io.titan.management.JdbcTransactionalMutationStore;
import io.titan.management.ManagementSchemaInstaller.Dialect;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.logging.Logger;
import javax.sql.DataSource;

final class JdbcManagementStoreFixture {

    private JdbcManagementStoreFixture() {
    }

    static TitanGraphqlDurableManagementStore create(DataSource dataSource, Dialect dialect) {
        try (Connection connection = dataSource.getConnection()) {
            TitanGraphqlManagementSchemaInstaller.install(connection, dialect);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "could not bootstrap management schema; check JDBC URL and credentials", failure);
        }
        return installedStore(dataSource, dialect);
    }

    static TitanGraphqlDurableManagementStore createInstalled(DataSource dataSource, Dialect dialect) {
        try (Connection connection = dataSource.getConnection()) {
            TitanGraphqlManagementSchemaInstaller.verifyInstalled(connection);
        } catch (SQLException failure) {
            throw new IllegalStateException("management schema is not installed; run install-management", failure);
        }
        return installedStore(dataSource, dialect);
    }

    private static TitanGraphqlDurableManagementStore installedStore(DataSource dataSource, Dialect dialect) {
        DataSource scoped = new SchemaScopedDataSource(dataSource, dialect);
        return new TitanGraphqlDurableManagementStore(new JdbcTransactionalMutationStore(scoped), scoped);
    }

    private static final class SchemaScopedDataSource implements DataSource {
        private final DataSource delegate;
        private final String initSql;

        private SchemaScopedDataSource(DataSource delegate, Dialect dialect) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.initSql = dialect == Dialect.POSTGRESQL
                    ? "SET search_path TO management" : "USE management";
        }

        @Override
        public Connection getConnection() throws SQLException {
            return prepare(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return prepare(delegate.getConnection(username, password));
        }

        private Connection prepare(Connection connection) throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.execute(initSql);
            }
            return connection;
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return iface.isInstance(this) ? iface.cast(this) : delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return iface.isInstance(this) || delegate.isWrapperFor(iface);
        }
    }
}
