/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2006-2014 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2014 The OpenNMS Group, Inc.
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

package org.opennms.netmgt.collectd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.net.InetAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.jrobin.core.RrdDb;
import org.jrobin.core.RrdDef;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opennms.core.collection.test.JUnitCollector;
import org.opennms.core.spring.BeanUtils;
import org.opennms.core.test.MockLogAppender;
import org.opennms.core.test.OpenNMSJUnit4ClassRunner;
import org.opennms.core.test.TestContextAware;
import org.opennms.core.test.db.annotations.JUnitTemporaryDatabase;
import org.opennms.core.test.snmp.annotations.JUnitSnmpAgent;
import org.opennms.core.utils.InetAddressUtils;
import org.opennms.netmgt.collection.api.CollectionException;
import org.opennms.netmgt.collection.api.CollectionInitializationException;
import org.opennms.netmgt.collection.api.CollectionSet;
import org.opennms.netmgt.collection.api.CollectionStatus;
import org.opennms.netmgt.collection.api.CollectionTimedOut;
import org.opennms.netmgt.collection.core.CollectionSpecification;
import org.opennms.netmgt.collection.test.api.CollectorTestUtils;
import org.opennms.netmgt.config.SnmpPeerFactory;
import org.opennms.netmgt.config.dao.outages.api.ReadablePollOutagesDao;
import org.opennms.netmgt.dao.api.IpInterfaceDao;
import org.opennms.netmgt.dao.api.NodeDao;
import org.opennms.netmgt.dao.support.FilesystemResourceStorageDao;
import org.opennms.netmgt.model.NetworkBuilder;
import org.opennms.netmgt.model.OnmsIpInterface;
import org.opennms.netmgt.model.OnmsNode;
import org.opennms.netmgt.model.ResourcePath;
import org.opennms.netmgt.rrd.RrdAttributeType;
import org.opennms.netmgt.rrd.RrdDataSource;
import org.opennms.netmgt.rrd.RrdStrategy;
import org.opennms.netmgt.rrd.jrobin.JRobinRrdStrategy;
import org.opennms.netmgt.snmp.SnmpAgentConfig;
import org.opennms.netmgt.snmp.SnmpObjId;
import org.opennms.netmgt.snmp.SnmpUtils;
import org.opennms.test.JUnitConfigurationEnvironment;
import org.opennms.test.mock.MockUtil;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

@RunWith(OpenNMSJUnit4ClassRunner.class)
@ContextConfiguration(locations={
        "classpath:/META-INF/opennms/applicationContext-commonConfigs.xml",
        "classpath:/META-INF/opennms/applicationContext-minimal-conf.xml",
        "classpath:/META-INF/opennms/applicationContext-soa.xml",
        "classpath:/META-INF/opennms/applicationContext-mockDao.xml",
        "classpath*:/META-INF/opennms/component-dao.xml",
        "classpath:/META-INF/opennms/applicationContext-pinger.xml",
        "classpath:/META-INF/opennms/applicationContext-daemon.xml",
        "classpath:/META-INF/opennms/mockEventIpcManager.xml",
        "classpath:/META-INF/opennms/applicationContext-proxy-snmp.xml",
        "classpath:/META-INF/opennms/applicationContext-testPollerConfigDaos.xml"
})
@JUnitConfigurationEnvironment(systemProperties="org.opennms.rrd.storeByGroup=false")
@JUnitTemporaryDatabase(reuseDatabase=false) // Relies on records created in @Before so we need a fresh database for each test
public abstract class AbstractSnmpCollectorIT implements InitializingBean, TestContextAware {

    @Autowired
    private PlatformTransactionManager m_transactionManager;

    @Autowired
    private NodeDao m_nodeDao;

    @Autowired
    private IpInterfaceDao m_ipInterfaceDao;

    @Autowired
    private SnmpPeerFactory m_snmpPeerFactory;
    
    @Autowired
    private ReadablePollOutagesDao m_pollOutagesDao;

    private TestContext m_context;

    private String m_testHostName;

    private final static String TEST_NODE_LABEL = "TestNode"; 

    private CollectionSpecification m_collectionSpecification;

    private SnmpCollectionAgent m_collectionAgent;

    private SnmpAgentConfig m_agentConfig;

    private RrdStrategy<?, ?> m_rrdStrategy;

    private FilesystemResourceStorageDao m_resourceStorageDao;

    @Override
    public void afterPropertiesSet() throws Exception {
        BeanUtils.assertAutowiring(this);
    }

    @Before
    public void setUp() throws Exception {
        MockServiceCollector.setDelegate(null);
        MockLogAppender.setupLogging();

        m_rrdStrategy = new JRobinRrdStrategy();

        m_resourceStorageDao = new FilesystemResourceStorageDao();
        File snmpRrdDirectory = (File)m_context.getAttribute("rrdDirectory");
        m_resourceStorageDao.setRrdDirectory(snmpRrdDirectory.getParentFile());

        m_testHostName = InetAddressUtils.str(InetAddress.getLocalHost());

        OnmsIpInterface iface = null;
        OnmsNode testNode = null;
        Collection<OnmsNode> testNodes = m_nodeDao.findByLabel(TEST_NODE_LABEL);
        if (testNodes == null || testNodes.size() < 1) {
            NetworkBuilder builder = new NetworkBuilder();
            builder.addNode(TEST_NODE_LABEL).setId(1).setSysObjectId(".1.3.6.1.4.1.1588.2.1.1.1");

            builder.addSnmpInterface(1).setIfName("lo0").setPhysAddr("00:11:22:33:44");
            builder.addSnmpInterface(2).setIfName("gif0").setPhysAddr("00:11:22:33:45").setIfType(55);
            builder.addSnmpInterface(3).setIfName("stf0").setPhysAddr("00:11:22:33:46").setIfType(57);

            builder.addSnmpInterface(6).setIfName("fw0").setPhysAddr("44:33:22:11:00").setIfType(144).setCollectionEnabled(true)
            .addIpInterface(m_testHostName).setIsSnmpPrimary("P");

            testNode = builder.getCurrentNode();
            assertNotNull(testNode);
            m_nodeDao.save(testNode);
            m_nodeDao.flush();
        } else {
            testNode = testNodes.iterator().next();
        }

        Set<OnmsIpInterface> ifaces = testNode.getIpInterfaces();
        assertEquals(1, ifaces.size());
        iface = ifaces.iterator().next();

        SnmpPeerFactory.setInstance(m_snmpPeerFactory);

        AbstractSnmpCollector collector = createSnmpCollector();
        collector.initialize();

        m_collectionSpecification = CollectorTestUtils.createCollectionSpec("SNMP", collector, "default",
                m_pollOutagesDao, collector.getClass().getCanonicalName());
        m_collectionAgent = DefaultSnmpCollectionAgent.create(iface.getId(), m_ipInterfaceDao, m_transactionManager);
        m_agentConfig = SnmpPeerFactory.getInstance().getAgentConfig(InetAddressUtils.getLocalHostAddress());
    }

    protected abstract AbstractSnmpCollector createSnmpCollector();

    @After
    public void tearDown() throws Exception {
        MockUtil.println("------------ End Test --------------------------");
        // MockLogAppender.assertNoWarningsOrGreater();
    }

    @Test
    @JUnitCollector(
                    datacollectionConfig = "/org/opennms/netmgt/config/datacollection-config.xml", 
                    datacollectionType = "snmp",
                    anticipateFiles = {
                            "1",
                            "1/fw0"
                    },
                    anticipateRrds = {
                            "1/tcpActiveOpens",
                            "1/tcpAttemptFails",
                            "1/tcpPassiveOpens",
                            "1/tcpRetransSegs",
                            "1/tcpCurrEstab",
                            "1/tcpEstabResets",
                            "1/tcpInErrors",
                            "1/tcpInSegs",
                            "1/tcpOutRsts",
                            "1/tcpOutSegs",
                            "1/fw0/ifInDiscards",
                            "1/fw0/ifInErrors",
                            "1/fw0/ifInNUcastpkts",
                            "1/fw0/ifInOctets",
                            "1/fw0/ifInUcastpkts",
                            "1/fw0/ifOutErrors",
                            "1/fw0/ifOutNUcastPkts",
                            "1/fw0/ifOutOctets",
                            "1/fw0/ifOutUcastPkts"
                    }
            )
    @JUnitSnmpAgent(resource = "/org/opennms/netmgt/snmp/snmpTestData1.properties")
    public void testCollect() throws Exception {
        System.setProperty("org.opennms.netmgt.collectd.SnmpCollector.limitCollectionToInstances", "true");

        // now do the actual collection
        CollectionSet collectionSet = m_collectionSpecification.collect(m_collectionAgent);
        assertEquals("collection status",
                     CollectionStatus.SUCCEEDED,
                     collectionSet.getStatus());
        CollectorTestUtils.persistCollectionSet(m_rrdStrategy, m_resourceStorageDao, m_collectionSpecification, collectionSet);

        System.err.println("FIRST COLLECTION FINISHED");

        //need a one second time elapse to update the RRD
        Thread.sleep(1000);

        // try collecting again
        assertEquals("collection status",
                     CollectionStatus.SUCCEEDED,
                     m_collectionSpecification.collect(m_collectionAgent).getStatus());

        System.err.println("SECOND COLLECTION FINISHED");
    }

    @Test
    @Transactional
    @JUnitCollector(
                    datacollectionConfig = "/org/opennms/netmgt/config/datacollection-persistTest-config.xml", 
                    datacollectionType = "snmp",
                    anticipateFiles = {
                            "1",
                            "1/fw0"
                    },
                    anticipateRrds = {
                            "1/tcpCurrEstab",
                            "1/fw0/ifInOctets"
                    }
            )
    @JUnitSnmpAgent(resource = "/org/opennms/netmgt/snmp/snmpTestData1.properties")
    public void testPersist() throws Exception {
        File snmpRrdDirectory = (File)m_context.getAttribute("rrdDirectory");

        // node level collection
        File nodeDir = new File(snmpRrdDirectory, "1");
        File rrdFile = new File(nodeDir, rrd("tcpCurrEstab"));

        // interface level collection
        File ifDir = new File(nodeDir, "fw0");
        File ifRrdFile = new File(ifDir, rrd("ifInOctets"));

        int numUpdates = 2;
        int stepSizeInSecs = 1;

        int stepSizeInMillis = stepSizeInSecs*1000;
        final int rangeSizeInMillis = stepSizeInMillis + 20000;

        CollectorTestUtils.collectNTimes(m_rrdStrategy, m_resourceStorageDao, m_collectionSpecification, m_collectionAgent, numUpdates);

        // This is the value from snmpTestData1.properties
        //.1.3.6.1.2.1.6.9.0 = Gauge32: 123
        assertEquals(Double.valueOf(123.0), m_rrdStrategy.fetchLastValueInRange(rrdFile.getAbsolutePath(), "tcpCurrEstab", stepSizeInMillis, rangeSizeInMillis));

        // This is the value from snmpTestData1.properties
        // .1.3.6.1.2.1.2.2.1.10.6 = Counter32: 1234567
        assertEquals(Double.valueOf(1234567.0), m_rrdStrategy.fetchLastValueInRange(ifRrdFile.getAbsolutePath(), "ifInOctets", stepSizeInMillis, rangeSizeInMillis));

        // now update the data in the agent
        SnmpUtils.set(m_agentConfig, SnmpObjId.get(".1.3.6.1.2.1.6.9.0"), SnmpUtils.getValueFactory().getInt32(456));
        SnmpUtils.set(m_agentConfig, SnmpObjId.get(".1.3.6.1.2.1.2.2.1.10.6"), SnmpUtils.getValueFactory().getCounter32(7654321));

        CollectorTestUtils.collectNTimes(m_rrdStrategy, m_resourceStorageDao, m_collectionSpecification, m_collectionAgent, numUpdates);

        // by now the values should be the new values
        assertEquals(Double.valueOf(456.0), m_rrdStrategy.fetchLastValueInRange(rrdFile.getAbsolutePath(), "tcpCurrEstab", stepSizeInMillis, rangeSizeInMillis));
        assertEquals(Double.valueOf(7654321.0), m_rrdStrategy.fetchLastValueInRange(ifRrdFile.getAbsolutePath(), "ifInOctets", stepSizeInMillis, rangeSizeInMillis));
    }

    @Test
    @Transactional
    @JUnitCollector(
                    datacollectionConfig = "/org/opennms/netmgt/config/datacollection-config.xml", 
                    datacollectionType = "snmp",
                    anticipateRrds = { "test" },
                    anticipateMetaFiles = false
            )
    public void testUsingFetch() throws Exception {
        System.err.println("=== testUsingFetch ===");
        File snmpDir = (File)m_context.getAttribute("rrdDirectory");

        // We initialize an empty attribute map, key=e.g OID; value=e.g. datasource name
        Map<String,String> attributeMappings = new HashMap<String, String>();

        int stepSize = 1;
        int numUpdates = 2;

        long start = System.currentTimeMillis();
        final int stepSizeInMillis = stepSize*1000;
        final int rangeSizeInMillis = stepSizeInMillis + 20000;

        File rrdFile = new File(snmpDir, rrd("test"));

        RrdStrategy<RrdDef,RrdDb> m_rrdStrategy = new JRobinRrdStrategy();

        RrdDataSource rrdDataSource = new RrdDataSource("testAttr", RrdAttributeType.GAUGE, stepSize*2, "U", "U");
        RrdDef def = m_rrdStrategy.createDefinition("test", snmpDir.getAbsolutePath(), "test", stepSize, Collections.singletonList(rrdDataSource), Collections.singletonList("RRA:AVERAGE:0.5:1:100"));
        m_rrdStrategy.createFile(def);

        RrdDb rrdFileObject = m_rrdStrategy.openFile(rrdFile.getAbsolutePath());
        for (int i = 0; i < numUpdates; i++) {
            m_rrdStrategy.updateFile(rrdFileObject, "test", ((start/1000) - (stepSize*(numUpdates-i))) + ":1");
        }
        m_rrdStrategy.closeFile(rrdFileObject);

        assertEquals(Double.valueOf(1.0), m_rrdStrategy.fetchLastValueInRange(rrdFile.getAbsolutePath(), "testAttr", stepSizeInMillis, rangeSizeInMillis));
    }

    @Test
    @Transactional
    @JUnitCollector(
                    datacollectionConfig="/org/opennms/netmgt/config/datacollection-brocade-config.xml", 
                    datacollectionType="snmp",
                    anticipateRrds={ 
                            "1/brocadeFCPortIndex/1/swFCPortTxWords",
                            "1/brocadeFCPortIndex/1/swFCPortRxWords",
                            "1/brocadeFCPortIndex/2/swFCPortTxWords",
                            "1/brocadeFCPortIndex/2/swFCPortRxWords",
                            "1/brocadeFCPortIndex/3/swFCPortTxWords",
                            "1/brocadeFCPortIndex/3/swFCPortRxWords",
                            "1/brocadeFCPortIndex/4/swFCPortTxWords",
                            "1/brocadeFCPortIndex/4/swFCPortRxWords",
                            "1/brocadeFCPortIndex/5/swFCPortTxWords",
                            "1/brocadeFCPortIndex/5/swFCPortRxWords",
                            "1/brocadeFCPortIndex/6/swFCPortTxWords",
                            "1/brocadeFCPortIndex/6/swFCPortRxWords",
                            "1/brocadeFCPortIndex/7/swFCPortTxWords",
                            "1/brocadeFCPortIndex/7/swFCPortRxWords",
                            "1/brocadeFCPortIndex/8/swFCPortTxWords",
                            "1/brocadeFCPortIndex/8/swFCPortRxWords"
                    }, 
                    anticipateFiles={ 
                            "1",
                            "1/brocadeFCPortIndex",
                            "1/brocadeFCPortIndex/1/strings.properties",
                            "1/brocadeFCPortIndex/1",
                            "1/brocadeFCPortIndex/2/strings.properties",
                            "1/brocadeFCPortIndex/2",
                            "1/brocadeFCPortIndex/3/strings.properties",
                            "1/brocadeFCPortIndex/3",
                            "1/brocadeFCPortIndex/4/strings.properties",
                            "1/brocadeFCPortIndex/4",
                            "1/brocadeFCPortIndex/5/strings.properties",
                            "1/brocadeFCPortIndex/5",
                            "1/brocadeFCPortIndex/6/strings.properties",
                            "1/brocadeFCPortIndex/6",
                            "1/brocadeFCPortIndex/7/strings.properties",
                            "1/brocadeFCPortIndex/7",
                            "1/brocadeFCPortIndex/8/strings.properties",
                            "1/brocadeFCPortIndex/8"
                    }
            )
    @JUnitSnmpAgent(resource = "/org/opennms/netmgt/snmp/brocadeTestData1.properties")
    public void testBrocadeCollect() throws Exception {
        // now do the actual collection
        CollectionSet collectionSet = m_collectionSpecification.collect(m_collectionAgent);
        assertEquals("collection status",
                     CollectionStatus.SUCCEEDED,
                     collectionSet.getStatus());

        CollectorTestUtils.persistCollectionSet(m_rrdStrategy, m_resourceStorageDao, m_collectionSpecification, collectionSet);

        System.err.println("FIRST COLLECTION FINISHED");

        //need a one second time elapse to update the RRD
        Thread.sleep(1000);

        // try collecting again
        assertEquals("collection status",
                     CollectionStatus.SUCCEEDED,
                     m_collectionSpecification.collect(m_collectionAgent).getStatus());

        System.err.println("SECOND COLLECTION FINISHED");
    }

    @Test
    @Transactional
    @JUnitCollector(
                    datacollectionConfig = "/org/opennms/netmgt/config/datacollection-brocade-no-ifaces-config.xml", 
                    datacollectionType = "snmp",
                    anticipateRrds={ 
                            "1/brocadeFCPortIndex/1/swFCPortTxWords",
                            "1/brocadeFCPortIndex/1/swFCPortRxWords",
                            "1/brocadeFCPortIndex/2/swFCPortTxWords",
                            "1/brocadeFCPortIndex/2/swFCPortRxWords",
                            "1/brocadeFCPortIndex/3/swFCPortTxWords",
                            "1/brocadeFCPortIndex/3/swFCPortRxWords",
                            "1/brocadeFCPortIndex/4/swFCPortTxWords",
                            "1/brocadeFCPortIndex/4/swFCPortRxWords",
                            "1/brocadeFCPortIndex/5/swFCPortTxWords",
                            "1/brocadeFCPortIndex/5/swFCPortRxWords",
                            "1/brocadeFCPortIndex/6/swFCPortTxWords",
                            "1/brocadeFCPortIndex/6/swFCPortRxWords",
                            "1/brocadeFCPortIndex/7/swFCPortTxWords",
                            "1/brocadeFCPortIndex/7/swFCPortRxWords",
                            "1/brocadeFCPortIndex/8/swFCPortTxWords",
                            "1/brocadeFCPortIndex/8/swFCPortRxWords"
                    }, 
                    anticipateFiles={ 
                            "1",
                            "1/brocadeFCPortIndex",
                            "1/brocadeFCPortIndex/1/strings.properties",
                            "1/brocadeFCPortIndex/1",
                            "1/brocadeFCPortIndex/2/strings.properties",
                            "1/brocadeFCPortIndex/2",
                            "1/brocadeFCPortIndex/3/strings.properties",
                            "1/brocadeFCPortIndex/3",
                            "1/brocadeFCPortIndex/4/strings.properties",
                            "1/brocadeFCPortIndex/4",
                            "1/brocadeFCPortIndex/5/strings.properties",
                            "1/brocadeFCPortIndex/5",
                            "1/brocadeFCPortIndex/6/strings.properties",
                            "1/brocadeFCPortIndex/6",
                            "1/brocadeFCPortIndex/7/strings.properties",
                            "1/brocadeFCPortIndex/7",
                            "1/brocadeFCPortIndex/8/strings.properties",
                            "1/brocadeFCPortIndex/8"
                    }
            )
    @JUnitSnmpAgent(resource = "/org/opennms/netmgt/snmp/brocadeTestData1.properties")
    public void testBug2447_GenericIndexedOnlyCollect() throws Exception {
        // now do the actual collection
        CollectionSet collectionSet = m_collectionSpecification.collect(m_collectionAgent);
        assertEquals("collection status",
                     CollectionStatus.SUCCEEDED,
                     collectionSet.getStatus());

        CollectorTestUtils.persistCollectionSet(m_rrdStrategy, m_resourceStorageDao, m_collectionSpecification, collectionSet);

        System.err.println("FIRST COLLECTION FINISHED");

        //need a one second time elapse to update the RRD
        Thread.sleep(1000);

        // try collecting again
        assertEquals("collection status",
                     CollectionStatus.SUCCEEDED,
                     m_collectionSpecification.collect(m_collectionAgent).getStatus());

        System.err.println("SECOND COLLECTION FINISHED");
    }

    @Test
    @Transactional
    @JUnitCollector(
                    datacollectionConfig = "/org/opennms/netmgt/config/datacollection-brocade-no-ifaces-config.xml",
                    datacollectionType = "snmp",
                    anticipateRrds={
                            "1/brocadeFCPortIndex/1/swFCPortTxWords",
                            "1/brocadeFCPortIndex/1/swFCPortRxWords",
                            "1/brocadeFCPortIndex/2/swFCPortTxWords",
                            "1/brocadeFCPortIndex/2/swFCPortRxWords",
                            "1/brocadeFCPortIndex/3/swFCPortTxWords",
                            "1/brocadeFCPortIndex/3/swFCPortRxWords",
                            "1/brocadeFCPortIndex/4/swFCPortTxWords",
                            "1/brocadeFCPortIndex/4/swFCPortRxWords",
                            "1/brocadeFCPortIndex/5/swFCPortTxWords",
                            "1/brocadeFCPortIndex/5/swFCPortRxWords",
                            "1/brocadeFCPortIndex/6/swFCPortTxWords",
                            "1/brocadeFCPortIndex/6/swFCPortRxWords",
                            "1/brocadeFCPortIndex/7/swFCPortTxWords",
                            "1/brocadeFCPortIndex/7/swFCPortRxWords",
                            "1/brocadeFCPortIndex/8/swFCPortTxWords",
                            "1/brocadeFCPortIndex/8/swFCPortRxWords"
                    },
                    anticipateFiles={
                            "1",
                            "1/brocadeFCPortIndex",
                            "1/brocadeFCPortIndex/1/strings.properties",
                            "1/brocadeFCPortIndex/1",
                            "1/brocadeFCPortIndex/2/strings.properties",
                            "1/brocadeFCPortIndex/2",
                            "1/brocadeFCPortIndex/3/strings.properties",
                            "1/brocadeFCPortIndex/3",
                            "1/brocadeFCPortIndex/4/strings.properties",
                            "1/brocadeFCPortIndex/4",
                            "1/brocadeFCPortIndex/5/strings.properties",
                            "1/brocadeFCPortIndex/5",
                            "1/brocadeFCPortIndex/6/strings.properties",
                            "1/brocadeFCPortIndex/6",
                            "1/brocadeFCPortIndex/7/strings.properties",
                            "1/brocadeFCPortIndex/7",
                            "1/brocadeFCPortIndex/8/strings.properties",
                            "1/brocadeFCPortIndex/8"
                    }
            )
    @JUnitSnmpAgent(resource = "/org/opennms/netmgt/snmp/brocadeTestData1.properties")
    public void verifyPersistedStringProperties() throws Exception {
        // Perform the collection
        CollectionSet collectionSet = m_collectionSpecification.collect(m_collectionAgent);
        assertEquals("collection status",
                CollectionStatus.SUCCEEDED,
                collectionSet.getStatus());

        // Persist
        CollectorTestUtils.persistCollectionSet(m_rrdStrategy, m_resourceStorageDao, m_collectionSpecification, collectionSet);

        // Verify results on disk
        Map<String, String> properties = m_resourceStorageDao.getStringAttributes(ResourcePath.get("snmp", "1", "brocadeFCPortIndex", "1"));

        // "string" attributes should convert the octets directly to a string
        String value = properties.get("swFCPortName");
        assertTrue(value.startsWith("..3DUfw"));

        // "hexstring" attributes should convert the octets to a hex string
        // see http://issues.opennms.org/browse/NMS-7367
        value = properties.get("swFCPortWwn");
        assertEquals("1100334455667788", value);
    }

    @Transactional
    @JUnitCollector(
                    datacollectionConfig = "/org/opennms/netmgt/config/datacollection-persistTest-config.xml",
                    datacollectionType = "snmp"
            )
    public void collectionTimedOutExceptionOnAgentTimeout() throws CollectionInitializationException, CollectionException {
        // There is no @JUnitSnmpAgent annotation on this method, so
        // we don't actually start the SNMP agent, which should
        // generate a CollectionTimedOut exception

        CollectionException caught = null;
        try {
            m_collectionSpecification.collect(m_collectionAgent);
        } catch (final CollectionException e) {
            caught = e;
        }

        assertNotNull(caught);
        assertEquals(CollectionTimedOut.class, caught.getCause().getClass());
    }

    private String rrd(String file) {
        return file + m_rrdStrategy.getDefaultFileExtension();
    }

    @Override
    public void setTestContext(TestContext context) {
        m_context = context;
    }
}
