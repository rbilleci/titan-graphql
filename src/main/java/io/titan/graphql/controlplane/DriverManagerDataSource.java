package io.titan.graphql.controlplane;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;
import javax.sql.DataSource;

final class DriverManagerDataSource implements DataSource {
    private final String url;
    private final String user;
    private final String password;
    private final String initSql;

    DriverManagerDataSource(
            String url,
            String user,
            String password,
            TitanGraphqlControlJobQueue.Dialect dialect
    ) {
        this(url, user, password, dialect, true);
    }

    DriverManagerDataSource(
            String url,
            String user,
            String password,
            TitanGraphqlControlJobQueue.Dialect dialect,
            boolean selectManagementSchema
    ) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.initSql = selectManagementSchema
                ? (dialect == TitanGraphqlControlJobQueue.Dialect.POSTGRESQL
                        ? "SET search_path TO management" : "USE management")
                : null;
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection connection = user == null || user.isBlank()
                ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, user, password == null ? "" : password);
        return prepare(connection);
    }

    @Override
    public Connection getConnection(String username, String credential) throws SQLException {
        return prepare(DriverManager.getConnection(url, username, credential));
    }

    private Connection prepare(Connection connection) throws SQLException {
        if (initSql == null) {
            return connection;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute(initSql);
            return connection;
        } catch (SQLException failure) {
            connection.close();
            throw failure;
        }
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        throw new SQLException("log writer is not configurable");
    }

    @Override
    public void setLogWriter(PrintWriter writer) throws SQLException {
        throw new SQLException("log writer is not configurable");
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        throw new SQLException("login timeout is not configurable");
    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getGlobal();
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        if (type.isInstance(this)) {
            return type.cast(this);
        }
        throw new SQLException("not a wrapper for " + type.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> type) {
        return type.isInstance(this);
    }
}
