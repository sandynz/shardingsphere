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

package org.apache.shardingsphere.infra.instance;

import lombok.Getter;
import lombok.Setter;
import org.apache.shardingsphere.infra.instance.metadata.InstanceMetaData;
import org.apache.shardingsphere.infra.state.instance.InstanceStateContext;
import org.apache.shardingsphere.infra.state.instance.InstanceState;

import java.util.Collection;
import java.util.LinkedList;

/**
 * Instance of compute node.
 */
@Getter
public final class ComputeNodeInstance {
    
    private final InstanceMetaData metaData;
    
    private final InstanceStateContext state = new InstanceStateContext();
    
    private Collection<String> labels = new LinkedList<>();
    
    @Setter
    private volatile int workerId;
    
    public ComputeNodeInstance(final InstanceMetaData metaData) {
        this.metaData = metaData;
        workerId = -1;
    }
    
    /**
     * Set labels.
     *
     * @param labels labels
     */
    public void setLabels(final Collection<String> labels) {
        if (null != labels) {
            this.labels = labels;
        }
    }
    
    /**
     * Switch state.
     *
     * @param status status
     */
    public void switchState(final String status) {
        if (InstanceState.CIRCUIT_BREAK.name().equals(status)) {
            state.switchToValidState(InstanceState.CIRCUIT_BREAK);
        } else {
            state.switchToInvalidState(InstanceState.CIRCUIT_BREAK);
        }
    }
}
