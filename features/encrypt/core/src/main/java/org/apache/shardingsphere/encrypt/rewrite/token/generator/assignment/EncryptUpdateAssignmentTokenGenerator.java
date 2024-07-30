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

package org.apache.shardingsphere.encrypt.rewrite.token.generator.assignment;

import lombok.Setter;
import org.apache.shardingsphere.encrypt.rewrite.aware.DatabaseNameAware;
import org.apache.shardingsphere.encrypt.rewrite.aware.EncryptRuleAware;
import org.apache.shardingsphere.encrypt.rule.EncryptRule;
import org.apache.shardingsphere.infra.annotation.HighFrequencyInvocation;
import org.apache.shardingsphere.infra.binder.context.statement.SQLStatementContext;
import org.apache.shardingsphere.infra.binder.context.statement.dml.UpdateStatementContext;
import org.apache.shardingsphere.infra.binder.context.type.TableAvailable;
import org.apache.shardingsphere.infra.rewrite.sql.token.generator.CollectionSQLTokenGenerator;
import org.apache.shardingsphere.infra.rewrite.sql.token.pojo.SQLToken;

import java.util.Collection;

/**
 * Update assignment generator for encrypt.
 */
@HighFrequencyInvocation
@Setter
public final class EncryptUpdateAssignmentTokenGenerator implements CollectionSQLTokenGenerator<UpdateStatementContext>, EncryptRuleAware, DatabaseNameAware {
    
    private EncryptRule encryptRule;
    
    private String databaseName;
    
    @Override
    public boolean isGenerateSQLToken(final SQLStatementContext sqlStatementContext) {
        return sqlStatementContext instanceof UpdateStatementContext
                && encryptRule.findEncryptTable(((TableAvailable) sqlStatementContext).getTablesContext().getSimpleTables().iterator().next().getTableName().getIdentifier().getValue()).isPresent();
    }
    
    @Override
    public Collection<SQLToken> generateSQLTokens(final UpdateStatementContext sqlStatementContext) {
        return new EncryptAssignmentTokenGenerator(encryptRule, databaseName).generateSQLTokens(sqlStatementContext, sqlStatementContext.getSqlStatement().getSetAssignment());
    }
}
