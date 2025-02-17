/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.proxy.backend.mysql.handler.admin;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.shardingsphere.infra.metadata.database.schema.manager.SystemSchemaManager;
import org.apache.shardingsphere.proxy.backend.handler.admin.executor.AbstractDatabaseMetaDataExecutor.DefaultDatabaseMetaDataExecutor;
import org.apache.shardingsphere.proxy.backend.handler.admin.executor.DatabaseAdminExecutor;
import org.apache.shardingsphere.proxy.backend.mysql.handler.admin.executor.information.SelectInformationSchemataExecutor;
import org.apache.shardingsphere.sql.parser.statement.core.segment.generic.table.SimpleTableSegment;
import org.apache.shardingsphere.sql.parser.statement.core.statement.dml.SelectStatement;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

/**
 * Construct the information schema executor's factory.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MySQLInformationSchemaExecutorFactory {
    
    private static final String SCHEMATA_TABLE = "SCHEMATA";
    
    /**
     * Create executor.
     *
     * @param sqlStatement SQL statement
     * @param sql SQL being executed
     * @param parameters parameters
     * @return executor
     */
    public static Optional<DatabaseAdminExecutor> newInstance(final SelectStatement sqlStatement, final String sql, final List<Object> parameters) {
        if (!sqlStatement.getFrom().isPresent() || !(sqlStatement.getFrom().get() instanceof SimpleTableSegment)) {
            return Optional.empty();
        }
        String tableName = ((SimpleTableSegment) sqlStatement.getFrom().get()).getTableName().getIdentifier().getValue();
        if (SCHEMATA_TABLE.equalsIgnoreCase(tableName)) {
            return Optional.of(new SelectInformationSchemataExecutor(sqlStatement, sql, parameters));
        }
        Map<String, Collection<String>> selectedSchemaTables = Collections.singletonMap("information_schema", Collections.singletonList(tableName));
        if (isSelectSystemTable(selectedSchemaTables)) {
            return Optional.of(new DefaultDatabaseMetaDataExecutor(sql, parameters));
        }
        return Optional.empty();
    }
    
    private static boolean isSelectSystemTable(final Map<String, Collection<String>> selectedSchemaTableNames) {
        for (Entry<String, Collection<String>> each : selectedSchemaTableNames.entrySet()) {
            if (!SystemSchemaManager.isSystemTable("mysql", each.getKey(), each.getValue())) {
                return false;
            }
        }
        return true;
    }
}
