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

package org.apache.shardingsphere.test.e2e.transaction.framework.container.compose;

import com.google.common.base.Strings;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.infra.database.core.type.DatabaseType;
import org.apache.shardingsphere.test.e2e.env.container.atomic.DockerITContainer;
import org.apache.shardingsphere.test.e2e.env.container.atomic.adapter.AdapterContainer;
import org.apache.shardingsphere.test.e2e.env.container.atomic.adapter.AdapterContainerFactory;
import org.apache.shardingsphere.test.e2e.env.container.atomic.adapter.config.AdaptorContainerConfiguration;
import org.apache.shardingsphere.test.e2e.env.container.atomic.adapter.impl.ShardingSphereJdbcEmbeddedContainer;
import org.apache.shardingsphere.test.e2e.env.container.atomic.adapter.impl.ShardingSphereProxyEmbeddedContainer;
import org.apache.shardingsphere.test.e2e.env.container.atomic.enums.AdapterMode;
import org.apache.shardingsphere.test.e2e.env.container.atomic.enums.AdapterType;
import org.apache.shardingsphere.test.e2e.env.container.atomic.governance.GovernanceContainer;
import org.apache.shardingsphere.test.e2e.env.container.atomic.governance.impl.ZookeeperContainer;
import org.apache.shardingsphere.test.e2e.env.container.atomic.storage.DockerStorageContainer;
import org.apache.shardingsphere.test.e2e.env.container.atomic.storage.StorageContainer;
import org.apache.shardingsphere.test.e2e.env.container.atomic.storage.StorageContainerFactory;
import org.apache.shardingsphere.test.e2e.env.container.atomic.storage.impl.NativeStorageContainer;
import org.apache.shardingsphere.test.e2e.transaction.env.TransactionE2EEnvironment;
import org.apache.shardingsphere.test.e2e.transaction.env.enums.TransactionE2EEnvTypeEnum;
import org.apache.shardingsphere.test.e2e.transaction.framework.container.config.StorageContainerConfigurationFactory;
import org.apache.shardingsphere.test.e2e.transaction.framework.container.config.proxy.ProxyClusterContainerConfigurationFactory;
import org.apache.shardingsphere.test.e2e.transaction.framework.param.TransactionTestParameter;

import java.net.URL;
import java.util.Objects;

/**
 * Composed container, include governance container and database container.
 */
@Getter
@Slf4j
public final class DockerContainerComposer extends BaseContainerComposer {
    
    private final DatabaseType databaseType;
    
    private final GovernanceContainer governanceContainer;
    
    private final AdapterContainer proxyContainer;
    
    private final ShardingSphereJdbcEmbeddedContainer jdbcContainer;
    
    private final StorageContainer storageContainer;
    
    public DockerContainerComposer(final TransactionTestParameter testParam) {
        super(testParam.getScenario());
        databaseType = testParam.getDatabaseType();
        governanceContainer = getContainers().registerContainer(new ZookeeperContainer());
        TransactionE2EEnvTypeEnum envType = TransactionE2EEnvironment.getInstance().getItEnvType();
        if (TransactionE2EEnvTypeEnum.DOCKER == envType) {
            storageContainer = getContainers().registerContainer((DockerStorageContainer) StorageContainerFactory.newInstance(databaseType, testParam.getStorageContainerImage(),
                    StorageContainerConfigurationFactory.newInstance(databaseType, testParam.getScenario())));
        } else {
            storageContainer = getContainers().registerContainer(new NativeStorageContainer(databaseType, testParam.getScenario()));
        }
        if (AdapterType.PROXY.getValue().equalsIgnoreCase(testParam.getAdapter())) {
            jdbcContainer = null;
            AdaptorContainerConfiguration containerConfig = ProxyClusterContainerConfigurationFactory.newInstance(testParam.getScenario(), databaseType, testParam.getPortBindings());
            proxyContainer =
                    AdapterContainerFactory.newInstance(AdapterMode.CLUSTER, AdapterType.PROXY, databaseType, testParam.getScenario(), containerConfig, storageContainer, envType.name());
            if (proxyContainer instanceof DockerITContainer) {
                ((DockerITContainer) proxyContainer).dependsOn(governanceContainer, storageContainer);
            }
            if (proxyContainer instanceof ShardingSphereProxyEmbeddedContainer) {
                ((ShardingSphereProxyEmbeddedContainer) proxyContainer).dependsOn(governanceContainer, storageContainer);
            }
            getContainers().registerContainer(proxyContainer);
        } else {
            proxyContainer = null;
            ShardingSphereJdbcEmbeddedContainer jdbcContainer = new ShardingSphereJdbcEmbeddedContainer(storageContainer,
                    Objects.requireNonNull(getShardingSphereConfigResource(testParam)).getFile());
            this.jdbcContainer = getContainers().registerContainer(jdbcContainer);
        }
    }
    
    private URL getShardingSphereConfigResource(final TransactionTestParameter testParam) {
        URL result = Thread.currentThread().getContextClassLoader().getResource(getScenarioResource(testParam));
        if (null != result) {
            return result;
        }
        result = Thread.currentThread().getContextClassLoader().getResource(getDefaultResource(testParam));
        log.info("Transaction IT tests use the configuration file: {}", result);
        return result;
    }
    
    private String getDefaultResource(final TransactionTestParameter testParam) {
        return String.format("env/%s/%s/database-sharding-%s%s.yaml", testParam.getAdapter().toLowerCase(),
                testParam.getDatabaseType().getType().toLowerCase(), testParam.getTransactionTypes().get(0).toString().toLowerCase(),
                getTransactionProvider(testParam.getProviders().get(0)));
    }
    
    private String getScenarioResource(final TransactionTestParameter testParam) {
        return String.format("env/scenario/%s/%s/conf/%s/database-%s-%s%s.yaml", testParam.getScenario(), testParam.getAdapter().toLowerCase(),
                testParam.getDatabaseType().getType().toLowerCase(), testParam.getScenario(), testParam.getTransactionTypes().get(0).toString().toLowerCase(),
                getTransactionProvider(testParam.getProviders().get(0)));
    }
    
    private String getTransactionProvider(final String providerType) {
        return Strings.isNullOrEmpty(providerType) ? "" : "-" + providerType.toLowerCase();
    }
    
    @Override
    public void stop() {
        super.stop();
        if (null != proxyContainer) {
            proxyContainer.stop();
        }
        if (null != jdbcContainer) {
            jdbcContainer.stop();
        }
    }
}
