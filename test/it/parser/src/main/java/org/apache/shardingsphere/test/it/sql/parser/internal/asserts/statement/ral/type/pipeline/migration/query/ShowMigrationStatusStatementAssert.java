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

package org.apache.shardingsphere.test.it.sql.parser.internal.asserts.statement.ral.type.pipeline.migration.query;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.shardingsphere.data.pipeline.migration.distsql.statement.queryable.ShowMigrationStatusStatement;
import org.apache.shardingsphere.test.it.sql.parser.internal.asserts.SQLCaseAssertContext;
import org.apache.shardingsphere.test.it.sql.parser.internal.asserts.statement.ExistingAssert;
import org.apache.shardingsphere.test.it.sql.parser.internal.asserts.statement.ral.type.pipeline.JobIdAssert;
import org.apache.shardingsphere.test.it.sql.parser.internal.cases.parser.jaxb.statement.ral.migration.ShowMigrationStatusStatementTestCase;

/**
 * Show migration status statement assert.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ShowMigrationStatusStatementAssert {
    
    /**
     * Assert show migration status statement is correct with expected parser result.
     *
     * @param assertContext assert context
     * @param actual actual show migration status statement
     * @param expected expected show migration status statement test case
     */
    public static void assertIs(final SQLCaseAssertContext assertContext, final ShowMigrationStatusStatement actual, final ShowMigrationStatusStatementTestCase expected) {
        if (ExistingAssert.assertIs(assertContext, actual, expected)) {
            JobIdAssert.assertJobId(assertContext, actual.getJobId(), expected.getJobId());
        }
    }
}
