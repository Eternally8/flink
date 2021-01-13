/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.highavailability.nonha.embedded;

import org.apache.flink.api.common.JobID;
import org.apache.flink.runtime.checkpoint.CheckpointRecoveryFactory;
import org.apache.flink.runtime.checkpoint.EmbeddedCompletedCheckpointStore;
import org.apache.flink.runtime.checkpoint.PerJobCheckpointRecoveryFactory;
import org.apache.flink.runtime.checkpoint.StandaloneCheckpointIDCounter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** {@link EmbeddedHaServices} extension to expose leadership granting and revoking. */
public class EmbeddedHaServicesWithLeadershipControl extends EmbeddedHaServices
        implements HaLeadershipControl {
    private final CheckpointRecoveryFactory testingCheckpointRecoveryFactory;

    public EmbeddedHaServicesWithLeadershipControl(Executor executor) {
        super(executor);
        this.testingCheckpointRecoveryFactory =
                new PerJobCheckpointRecoveryFactory(
                        n -> new EmbeddedCompletedCheckpointStore(),
                        StandaloneCheckpointIDCounter::new);
    }

    @Override
    public CompletableFuture<Void> revokeDispatcherLeadership() {
        final EmbeddedLeaderService dispatcherLeaderService = getDispatcherLeaderService();
        return dispatcherLeaderService.revokeLeadership();
    }

    @Override
    public CompletableFuture<Void> grantDispatcherLeadership() {
        final EmbeddedLeaderService dispatcherLeaderService = getDispatcherLeaderService();
        return dispatcherLeaderService.grantLeadership();
    }

    @Override
    public CompletableFuture<Void> revokeJobMasterLeadership(JobID jobId) {
        final EmbeddedLeaderService jobMasterLeaderService = getJobManagerLeaderService(jobId);
        return jobMasterLeaderService.revokeLeadership();
    }

    @Override
    public CompletableFuture<Void> grantJobMasterLeadership(JobID jobId) {
        final EmbeddedLeaderService jobMasterLeaderService = getJobManagerLeaderService(jobId);
        return jobMasterLeaderService.grantLeadership();
    }

    @Override
    public CompletableFuture<Void> revokeResourceManagerLeadership() {
        final EmbeddedLeaderService resourceManagerLeaderService =
                getResourceManagerLeaderService();
        return resourceManagerLeaderService.revokeLeadership();
    }

    @Override
    public CompletableFuture<Void> grantResourceManagerLeadership() {
        final EmbeddedLeaderService resourceManagerLeaderService =
                getResourceManagerLeaderService();
        return resourceManagerLeaderService.grantLeadership();
    }

    @Override
    public CheckpointRecoveryFactory getCheckpointRecoveryFactory() {
        synchronized (lock) {
            checkNotShutdown();
            return testingCheckpointRecoveryFactory;
        }
    }
}
