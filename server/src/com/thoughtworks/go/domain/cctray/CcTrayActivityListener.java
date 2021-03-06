/*************************GO-LICENSE-START*********************************
 * Copyright 2015 ThoughtWorks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *************************GO-LICENSE-END***********************************/

package com.thoughtworks.go.domain.cctray;

import com.thoughtworks.go.config.CruiseConfig;
import com.thoughtworks.go.domain.JobInstance;
import com.thoughtworks.go.domain.Stage;
import com.thoughtworks.go.listener.ConfigChangedListener;
import com.thoughtworks.go.server.domain.JobStatusListener;
import com.thoughtworks.go.server.domain.StageStatusListener;
import com.thoughtworks.go.server.initializers.Initializer;
import com.thoughtworks.go.server.service.GoConfigService;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/* Listens to all activity that is needed to keep CCTray updated.
 *
 * Since this can happen from different threads, line up all of these events on to one thread,
 * for processing, and to make sure that the upstream processes are not blocked.
 */
@Component
public class CcTrayActivityListener implements Initializer, JobStatusListener, StageStatusListener, ConfigChangedListener {
    private static Logger LOGGER = Logger.getLogger(CcTrayActivityListener.class);

    private final GoConfigService goConfigService;
    private final CcTrayJobStatusChangeHandler jobStatusChangeHandler;
    private final CcTrayStageStatusChangeHandler stageStatusChangeHandler;
    private final CcTrayConfigChangeHandler configChangeHandler;

    private final BlockingQueue<Action> queue;
    private Thread queueProcessor;

    @Autowired
    public CcTrayActivityListener(GoConfigService goConfigService, CcTrayJobStatusChangeHandler jobStatusChangeHandler,
                                  CcTrayStageStatusChangeHandler stageStatusChangeHandler,
                                  CcTrayConfigChangeHandler configChangeHandler) {
        this.goConfigService = goConfigService;
        this.jobStatusChangeHandler = jobStatusChangeHandler;
        this.stageStatusChangeHandler = stageStatusChangeHandler;
        this.configChangeHandler = configChangeHandler;

        this.queue = new LinkedBlockingQueue<Action>();
    }

    @Override
    public void initialize() {
        goConfigService.register(this);
        startQueueProcessor();
    }

    @Override
    public void jobStatusChanged(final JobInstance job) {
        queue.add(new Action() {
            @Override
            public void call() {
                jobStatusChangeHandler.call(job);
            }
        });
    }

    @Override
    public void stageStatusChanged(final Stage stage) {
        queue.add(new Action() {
            @Override
            public void call() {
                stageStatusChangeHandler.call(stage);
            }
        });
    }

    @Override
    public void onConfigChange(final CruiseConfig newConfig) {
        queue.add(new Action() {
            @Override
            public void call() {
                configChangeHandler.call(newConfig);
            }
        });
    }

    private void startQueueProcessor() {
        if (queueProcessor != null) {
            throw new RuntimeException("Cannot start queue processor multiple times.");
        }

        queueProcessor = new Thread() {
            @Override
            public void run() {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        queue.take().call();
                    } catch (Exception e) {
                        LOGGER.warn("Failed to handle action in CCTray queue", e);
                    }
                }
            }
        };
        queueProcessor.setName("CCTray-Queue-Processor");
        queueProcessor.setDaemon(true);
        queueProcessor.start();
    }

    private interface Action {
        void call();
    }
}
