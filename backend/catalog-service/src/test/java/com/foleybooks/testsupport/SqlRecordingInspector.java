package com.foleybooks.testsupport;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Test-only Hibernate {@link StatementInspector} that records every SQL
 * statement the session executes, so a test can assert on the statement's
 * <em>shape</em> — e.g. that a query reaches its target column directly instead
 * of reaching it through a join — not just its result. Registered through the
 * {@code hibernate.session_factory.statement_inspector} property on the test
 * context that needs it; the recorder is deliberately process-static (and the
 * list thread-safe) because Hibernate instantiates the inspector reflectively,
 * so the test never receives the instance it can call back into — it reads and
 * clears the shared list instead.
 */
public class SqlRecordingInspector implements StatementInspector {

    private static final List<String> EXECUTED = new CopyOnWriteArrayList<>();

    public static List<String> executed() {
        return EXECUTED;
    }

    public static void clear() {
        EXECUTED.clear();
    }

    @Override
    public String inspect(String sql) {
        EXECUTED.add(sql);
        return sql;
    }
}
