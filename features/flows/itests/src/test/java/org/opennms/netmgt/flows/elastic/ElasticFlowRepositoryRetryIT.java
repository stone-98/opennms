/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2017-2017 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2017 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.netmgt.flows.elastic;

import static org.mockito.Mockito.mock;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.opennms.core.test.elastic.ElasticSearchRule;
import org.opennms.core.test.elastic.ElasticSearchServerConfig;
import org.opennms.core.test.elastic.ExecutionTime;
import org.opennms.features.jest.client.RestClientFactory;
import org.opennms.features.jest.client.executors.DefaultRequestExecutor;
import org.opennms.features.jest.client.index.IndexStrategy;
import org.opennms.features.jest.client.template.IndexSettings;
import org.opennms.netmgt.dao.mock.MockNodeDao;
import org.opennms.netmgt.dao.mock.MockSessionUtils;
import org.opennms.netmgt.dao.mock.MockSnmpInterfaceDao;
import org.opennms.netmgt.flows.api.FlowException;
import org.opennms.netmgt.flows.api.FlowRepository;
import org.opennms.netmgt.flows.api.ProcessingOptions;
import org.opennms.netmgt.flows.elastic.thresholding.FlowThresholding;

import com.codahale.metrics.MetricRegistry;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;

import io.searchbox.client.JestClient;

public class ElasticFlowRepositoryRetryIT {

    private static final long START_DELAY = 10000; // in ms
    private static final long EXECUTION_TIME_DIFF = 2500; // in ms
    private static final String HTTP_PORT = "9205";
    private static final String HTTP_TRANSPORT_PORT = "9305";
    private static final long RETRY_COOLDOWN = 500;

    // The timeout is needed because starting the elasticsearch server may fail, but we will not get notified
    // The timeout will ensure that the test will not block for ever, but fail eventually
    @Rule
    public Timeout timeout = new Timeout(START_DELAY * 5, TimeUnit.MILLISECONDS);

    @Rule
    public ExecutionTime executionTime = new ExecutionTime(START_DELAY, TimeUnit.MILLISECONDS, EXECUTION_TIME_DIFF);

    @Rule
    public ElasticSearchRule elasticServerRule = new ElasticSearchRule(
            new ElasticSearchServerConfig()
                .withStartDelay(START_DELAY)
                .withManualStartup()
    );

    @Test
    public void verifySaveSucceedsWhenServerBecomesAvailable() throws Exception {
        // try persisting data
        apply((repository) -> repository.persist(
                Lists.newArrayList(FlowDocumentTest.getMockFlow()),
                FlowDocumentTest.getMockFlowSource(), ProcessingOptions.builder().build()));
    }

    private void apply(FlowRepositoryConsumer consumer) throws Exception {
        Objects.requireNonNull(consumer);

        final RestClientFactory restClientFactory = new RestClientFactory(elasticServerRule.getUrl());
        restClientFactory.setRequestExecutorSupplier(() -> new DefaultRequestExecutor(RETRY_COOLDOWN));
        try (JestClient client = restClientFactory.createClient()) {
            executionTime.resetStartTime();
            elasticServerRule.startServer();

            final MockDocumentEnricherFactory mockDocumentEnricherFactory = new MockDocumentEnricherFactory();
            final DocumentEnricher documentEnricher = mockDocumentEnricherFactory.getEnricher();
            final FlowRepository elasticFlowRepository = new InitializingFlowRepository(
                    new ElasticFlowRepository(new MetricRegistry(), client, IndexStrategy.MONTHLY, documentEnricher,
                             new MockSessionUtils(), new MockNodeDao(), new MockSnmpInterfaceDao(),
                            new MockIdentity(), new MockTracerRegistry(), new MockDocumentForwarder(), new IndexSettings(), mock(FlowThresholding.class)), client);

            consumer.accept(elasticFlowRepository);

        } catch (FlowException e) {
            throw Throwables.propagate(e);
        }
        elasticServerRule.stopServer();

    }

    @FunctionalInterface
    interface FlowRepositoryConsumer {
        void accept(FlowRepository flowRepository) throws FlowException;
    }
}
