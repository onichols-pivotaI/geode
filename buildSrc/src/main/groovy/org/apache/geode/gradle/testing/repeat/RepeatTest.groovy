/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.gradle.testing.repeat

import org.gradle.StartParameter
import org.gradle.api.file.FileTree
import org.gradle.api.internal.DocumentationRegistry
import org.gradle.api.internal.tasks.testing.JvmTestExecutionSpec
import org.gradle.api.internal.tasks.testing.TestExecuter
import org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter
import org.gradle.api.tasks.testing.Test
import org.gradle.internal.time.Clock
import org.gradle.internal.work.WorkerLeaseRegistry

class RepeatTest extends Test {
    int times = 1

    /**
     * Submit each test class for processing multiple times.
     */
    @Override
    FileTree getCandidateClassFiles() {
        FileTree candidates = super.getCandidateClassFiles()
        int additionalRuns = times - 1
        for (int i = 0; i < additionalRuns; i++) {
            candidates = candidates.plus(super.getCandidateClassFiles())
        }

        return candidates
    }

    /**
     * Use a custom {@link TestExecuter} that processes each test class as many times as submitted.
     */
    @Override
    protected TestExecuter<JvmTestExecutionSpec> createTestExecuter() {
        return new RepeatTestExecuter(
                super.createTestExecuter().workerFactory,
                getActorFactory(),
                getModuleRegistry(),
                getServices().get(WorkerLeaseRegistry.class),
                getServices().get(StartParameter.class).getMaxWorkerCount(),
                getServices().get(Clock.class),
                getServices().get(DocumentationRegistry.class),
                (DefaultTestFilter) getFilter(),
                times)
    }
}
