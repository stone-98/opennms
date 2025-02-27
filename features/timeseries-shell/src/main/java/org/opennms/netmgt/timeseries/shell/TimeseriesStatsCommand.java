/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2020 The OpenNMS Group, Inc.
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

package org.opennms.netmgt.timeseries.shell;

import java.io.PrintStream;

import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.lifecycle.Reference;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.opennms.integration.api.v1.timeseries.IntrinsicTagNames;
import org.opennms.integration.api.v1.timeseries.Metric;
import org.opennms.netmgt.timeseries.TimeseriesStorageManager;
import org.opennms.netmgt.timeseries.stats.StatisticsCollector;

/**
 * Shows statistics of the time series layer.
 * Install: feature:install opennms-timeseries-shell
 * Usage: type opennms:ts-stats in karaf console
 */
@Command(scope = "opennms", name = "ts-stats",
        description = "Prints statistics about the timeseries integration layer.")
@Service
public class TimeseriesStatsCommand implements Action {

    @Reference
    private TimeseriesStorageManager storageManager;

    @Reference
    private StatisticsCollector stats;


    @Override
    public Object execute() {
        PrintStream out = System.out;
        out.println("Active TimeSeriesStorage plugin:");
        out.println(storageManager.get().getClass().getName());
        out.println();
        out.println("Metrics with highest number of tags:");
        stats.getTopNMetricsWithMostTags().stream().map(this::toString).forEach(out::println);
        out.println();
        out.println("Tags with highest number of unique values (top 100):");
        stats.getTopNTags().stream().limit(100).forEach(out::println);
        return null;
    }

    private String toString(final Metric metric) {
        return metric.getFirstTagByKey(IntrinsicTagNames.resourceId).getValue() + "/"
                + metric.getFirstTagByKey(IntrinsicTagNames.name).getValue() +
                "\n    metaTags:      " + metric.getMetaTags().toString() +
                "\n    externalTags: " + metric.getExternalTags().toString();
    }
}
