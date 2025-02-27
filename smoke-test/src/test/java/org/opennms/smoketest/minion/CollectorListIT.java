/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2017-2021 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2021 The OpenNMS Group, Inc.
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
package org.opennms.smoketest.minion;

import static com.jayway.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.Matchers.hasSize;

import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.opennms.smoketest.containers.OpenNMSContainer;
import org.opennms.smoketest.junit.MinionTests;
import org.opennms.smoketest.stacks.OpenNMSStack;
import org.opennms.smoketest.utils.CommandTestUtils;
import org.opennms.smoketest.utils.SshClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableSet;

/**
 * Verifies the output of "opennms:list-collectors" on both OpenNMS and Minion.
 */
@Category(MinionTests.class)
public class CollectorListIT {

    private static final Logger LOG = LoggerFactory.getLogger(CollectorListIT.class);

    @ClassRule
    public static final OpenNMSStack stack = OpenNMSStack.MINION;

    private ImmutableSet<String> commonCollectors = ImmutableSet.<String> builder().add(
            "org.opennms.netmgt.collectd.HttpCollector",
            "org.opennms.netmgt.collectd.JdbcCollector",
            "org.opennms.netmgt.collectd.Jsr160Collector",
            "org.opennms.netmgt.collectd.VmwareCimCollector",
            "org.opennms.netmgt.collectd.VmwareCollector",
            "org.opennms.netmgt.collectd.WmiCollector",
            "org.opennms.netmgt.collectd.WsManCollector",
            "org.opennms.netmgt.collectd.prometheus.PrometheusCollector",
            "org.opennms.protocols.xml.collector.XmlCollector")
            .build();

    private ImmutableSet<String> expectedMinionCollectors = ImmutableSet.<String> builder().add(
            "org.opennms.protocols.nsclient.collector.NSClientCollector")
            .addAll(commonCollectors)
            .build();

    private ImmutableSet<String> expectedOpenNMSCollectors = ImmutableSet.<String> builder().add(
            "org.opennms.netmgt.collectd.SnmpCollector")
            .addAll(commonCollectors)
            .build();

    @Test
    public void canLoadCollectorsOnMinion() throws Exception {
        final InetSocketAddress sshAddr = stack.minion().getSshAddress();
        await().atMost(3, MINUTES).pollInterval(15, SECONDS).pollDelay(0, SECONDS)
                .until(() -> listAndVerifyCollectors(sshAddr, expectedMinionCollectors), hasSize(0));
    }

    @Test
    public void canLoadCollectorsOnOpenNMS() throws Exception {
        final InetSocketAddress sshAddr = stack.opennms().getSshAddress();
        await().atMost(3, MINUTES).pollInterval(15, SECONDS).pollDelay(0, SECONDS)
                .until(() -> listAndVerifyCollectors(sshAddr, expectedOpenNMSCollectors), hasSize(0));
    }

    public List<String> listAndVerifyCollectors(InetSocketAddress sshAddr, Set<String> expectedCollectors) throws Exception {
        List<String> unmatchedCollectors = new ArrayList<>();
        try (final SshClient sshClient = new SshClient(sshAddr, OpenNMSContainer.ADMIN_USER, OpenNMSContainer.ADMIN_PASSWORD)) {
            // List the collectors
            PrintStream pipe = sshClient.openShell();
            pipe.println("opennms:list-collectors");
            pipe.println("logout");
            await().atMost(1, MINUTES).until(sshClient.isShellClosedCallable());

            // Parse the output
            String shellOutput = CommandTestUtils.stripAnsiCodes(sshClient.getStdout());

            shellOutput = StringUtils.substringAfter(shellOutput, "opennms:list-collectors");
            LOG.info("Collectors output: {}", shellOutput);
            Set<String> collectors = new HashSet<>();
            for (String collector : shellOutput.split("\\r?\\n")) {
                if (StringUtils.isNotBlank(collector)) {
                    collectors.add(collector);
                }
            }
            LOG.info("Found collectors: {}", collectors);

            // Verify
            for (String expectedCollector : expectedCollectors) {
                if (!collectors.contains(expectedCollector)) {
                    unmatchedCollectors.add(expectedCollector);
                }
            }
        }
        return unmatchedCollectors;
    }

}
