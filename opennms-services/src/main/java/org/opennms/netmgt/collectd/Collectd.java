/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2002-2020 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2020 The OpenNMS Group, Inc.
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

import static org.opennms.core.utils.InetAddressUtils.str;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.apache.commons.lang.StringUtils;
import org.opennms.core.logging.Logging;
import org.opennms.core.utils.ConfigFileConstants;
import org.opennms.core.utils.InsufficientInformationException;
import org.opennms.netmgt.collection.api.CollectionInitializationException;
import org.opennms.netmgt.collection.api.CollectionInstrumentation;
import org.opennms.netmgt.collection.api.LocationAwareCollectorClient;
import org.opennms.netmgt.collection.api.PersisterFactory;
import org.opennms.netmgt.collection.api.ServiceCollector;
import org.opennms.netmgt.collection.api.ServiceCollectorRegistry;
import org.opennms.netmgt.collection.core.CollectionSpecification;
import org.opennms.netmgt.collection.core.DefaultCollectdInstrumentation;
import org.opennms.netmgt.config.CollectdConfigFactory;
import org.opennms.netmgt.config.DataCollectionConfigFactory;
import org.opennms.netmgt.config.SnmpEventInfo;
import org.opennms.netmgt.config.SnmpPeerFactory;
import org.opennms.netmgt.config.collectd.CollectdConfiguration;
import org.opennms.netmgt.config.collectd.Collector;
import org.opennms.netmgt.config.collectd.Package;
import org.opennms.netmgt.config.dao.outages.api.ReadablePollOutagesDao;
import org.opennms.netmgt.daemon.AbstractServiceDaemon;
import org.opennms.netmgt.dao.api.IpInterfaceDao;
import org.opennms.netmgt.dao.api.NodeDao;
import org.opennms.netmgt.events.api.EventConstants;
import org.opennms.netmgt.events.api.EventIpcManager;
import org.opennms.netmgt.events.api.EventListener;
import org.opennms.netmgt.events.api.model.IEvent;
import org.opennms.netmgt.events.api.model.IParm;
import org.opennms.netmgt.events.api.model.IValue;
import org.opennms.netmgt.filter.api.FilterDao;
import org.opennms.netmgt.model.AbstractEntityVisitor;
import org.opennms.netmgt.model.OnmsIpInterface;
import org.opennms.netmgt.model.OnmsMonitoredService;
import org.opennms.netmgt.model.OnmsNode;
import org.opennms.netmgt.model.events.EventBuilder;
import org.opennms.netmgt.model.events.EventUtils;
import org.opennms.netmgt.scheduler.LegacyScheduler;
import org.opennms.netmgt.scheduler.ReadyRunnable;
import org.opennms.netmgt.scheduler.Scheduler;
import org.opennms.netmgt.snmp.InetAddrUtils;
import org.opennms.netmgt.threshd.api.ThresholdingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;

import com.google.common.annotations.VisibleForTesting;

/**
 * <p>Collectd class.</p>
 *
 * @author ranger
 * @version $Id: $
 */
public class Collectd extends AbstractServiceDaemon implements
        EventListener {
    
    private static final Logger LOG = LoggerFactory.getLogger(Collectd.class);
    
    private static CollectionInstrumentation s_instrumentation = null;
    
    /**
     * <p>instrumentation</p>
     *
     * @return a {@link org.opennms.netmgt.collection.api.CollectionInstrumentation} object.
     */
    public static CollectionInstrumentation instrumentation() {
        if (s_instrumentation == null) {
            String className = System.getProperty("org.opennms.collectd.instrumentationClass", DefaultCollectdInstrumentation.class.getName());
            try { 
                s_instrumentation = (CollectionInstrumentation) ClassUtils.forName(className, Thread.currentThread().getContextClassLoader()).newInstance();
            } catch (Throwable e) {
                s_instrumentation = new DefaultCollectdInstrumentation();
            }
        }

        return s_instrumentation;
    }
    
    /**
     * Log4j category
     */
    static final String LOG4J_CATEGORY = "collectd";
    
    /**
     * Instantiated service collectors specified in config file
     */
    private final Map<String,ServiceCollector> m_collectors = new HashMap<>(4);

    /**
     * List of all CollectableService objects.
     */
    private final List<CollectableService> m_collectableServices;

    /**
     * Reference to the collection scheduler
     */
    private volatile Scheduler m_scheduler;

    /**
     * Indicates if scheduling of existing interfaces has been completed
     */
    @Autowired
    private volatile CollectdConfigFactory m_collectdConfigFactory;

    @Autowired
    private volatile IpInterfaceDao m_ifaceDao;

    @Autowired
    private volatile FilterDao m_filterDao;

    @Autowired
    private volatile ServiceCollectorRegistry m_serviceCollectorRegistry;

    @Autowired
    private volatile LocationAwareCollectorClient m_locationAwareCollectorClient;

    static class SchedulingCompletedFlag {
        volatile boolean m_schedulingCompleted = false;

        public synchronized void setSchedulingCompleted(
                boolean schedulingCompleted) {
            m_schedulingCompleted = schedulingCompleted;
        }

        public synchronized boolean isSchedulingCompleted() {
            return m_schedulingCompleted;
        }

    }

    private final SchedulingCompletedFlag m_schedulingCompletedFlag = new SchedulingCompletedFlag();

    private volatile EventIpcManager m_eventIpcManager;

    @Autowired
    private volatile TransactionTemplate m_transTemplate;

    @Autowired
    private volatile NodeDao m_nodeDao;

    @Autowired
    private PersisterFactory m_persisterFactory;

    @Autowired
    private ThresholdingService m_thresholdingService;
    
    @Autowired
    private ReadablePollOutagesDao pollOutagesDao;

    private AtomicInteger sessionID = new AtomicInteger();

    /**
     * Constructor.
     */
    public Collectd() {
        super(LOG4J_CATEGORY);

        m_collectableServices = Collections.synchronizedList(new LinkedList<>());
    }

    /**
     * <p>onInit</p>
     */
    @Override
    protected void onInit() {
        Assert.notNull(m_collectdConfigFactory, "collectdConfigFactory must not be null");
        Assert.notNull(m_eventIpcManager, "eventIpcManager must not be null");
        Assert.notNull(m_transTemplate, "transTemplate must not be null");
        Assert.notNull(m_ifaceDao, "ifaceDao must not be null");
        Assert.notNull(m_nodeDao, "nodeDao must not be null");
        Assert.notNull(m_filterDao, "filterDao must not be null");

        LOG.debug("init: Initializing collection daemon");
        
        // make sure the instrumentation gets initialized
        instrumentation();
        //initialize and schedule collectors
        instantiateCollectors();
        //listen to the events
        installMessageSelectors();
    }

    private void installMessageSelectors() {
        // Add the EventListeners for the UEIs in which this service is
        // interested
        List<String> ueiList = new ArrayList<>();

        // nodeGainedService
        ueiList.add(EventConstants.NODE_GAINED_SERVICE_EVENT_UEI);

        // primarySnmpInterfaceChanged
        ueiList.add(EventConstants.PRIMARY_SNMP_INTERFACE_CHANGED_EVENT_UEI);

        // reinitializePrimarySnmpInterface
        ueiList.add(EventConstants.REINITIALIZE_PRIMARY_SNMP_INTERFACE_EVENT_UEI);
        
        // interfaceReparented
        ueiList.add(EventConstants.INTERFACE_REPARENTED_EVENT_UEI);

        // nodeDeleted
        ueiList.add(EventConstants.NODE_DELETED_EVENT_UEI);

        // duplicateNodeDeleted
        ueiList.add(EventConstants.DUP_NODE_DELETED_EVENT_UEI);

        // interfaceDeleted
        ueiList.add(EventConstants.INTERFACE_DELETED_EVENT_UEI);

        // serviceDeleted
        ueiList.add(EventConstants.SERVICE_DELETED_EVENT_UEI);

        // outageConfigurationChanged
        ueiList.add(EventConstants.SCHEDOUTAGES_CHANGED_EVENT_UEI);

        // configureSNMP
        ueiList.add(EventConstants.CONFIGURE_SNMP_EVENT_UEI);
        
        // thresholds configuration change
        ueiList.add(EventConstants.THRESHOLDCONFIG_CHANGED_EVENT_UEI);

        // daemon configuration change
        ueiList.add(EventConstants.RELOAD_DAEMON_CONFIG_UEI);
        
        // node category membership changes
        ueiList.add(EventConstants.NODE_CATEGORY_MEMBERSHIP_CHANGED_EVENT_UEI);

        // node location changed event
        ueiList.add(EventConstants.NODE_LOCATION_CHANGED_EVENT_UEI);
        
        getEventIpcManager().addEventListener(this, ueiList);
    }

    /**
     * <p>setEventIpcManager</p>
     *
     * @param eventIpcManager a {@link org.opennms.netmgt.events.api.EventIpcManager} object.
     */
    public void setEventIpcManager(EventIpcManager eventIpcManager) {
        m_eventIpcManager = eventIpcManager;
    }

    /**
     * <p>getEventIpcManager</p>
     *
     * @return a {@link org.opennms.netmgt.events.api.EventIpcManager} object.
     */
    public EventIpcManager getEventIpcManager() {
        return m_eventIpcManager;
    }

    public ThresholdingService getThresholdingService() {
        return m_thresholdingService;
    }

    public void setThresholdingService(ThresholdingService thresholdingService) {
        m_thresholdingService = thresholdingService;
    }

    private void createScheduler() {
        Logging.withPrefix(LOG4J_CATEGORY, () -> {
            // Create a scheduler
            try {
                LOG.debug("init: Creating collectd scheduler");
                setScheduler(new LegacyScheduler("Collectd", m_collectdConfigFactory.getCollectdConfig().getThreads()));
            } catch (final RuntimeException e) {
                LOG.error("init: Failed to create collectd scheduler", e);
                throw e;
            }
        });
    }

    /** {@inheritDoc} */
    @Override
    protected void onStart() {
        // start the scheduler
        try {
            LOG.debug("start: Starting collectd scheduler");

            getScheduler().start();
        } catch (RuntimeException e) {
            LOG.error("start: Failed to start scheduler", e);
            throw e;
        }
    }

    /** {@inheritDoc} */
    @Override
    protected void onStop() {
        getScheduler().stop();
        deinstallMessageSelectors();

        setScheduler(null);
    }

    /** {@inheritDoc} */
    @Override
    protected void onPause() {
        getScheduler().pause();
    }

    /** {@inheritDoc} */
    @Override
    protected void onResume() {
        getScheduler().resume();
    }

    private void scheduleInterfacesWithService(String svcName) {
        instrumentation().beginScheduleInterfacesWithService(svcName);
        try {
        LOG.info("scheduleInterfacesWithService: svcName = {}", svcName);

        Collection<OnmsIpInterface> ifsWithServices = findInterfacesWithService(svcName);
        for (OnmsIpInterface iface : ifsWithServices) {
            scheduleInterface(iface, svcName, true);
        }
        } finally {
            instrumentation().endScheduleInterfacesWithService(svcName);
        }
    }

    private Collection<OnmsIpInterface> findInterfacesWithService(String svcName) {
        instrumentation().beginFindInterfacesWithService(svcName);
        int count = -1;
        try {
           Collection<OnmsIpInterface> ifaces = m_ifaceDao.findByServiceType(svcName);
           count = ifaces.size();
           return ifaces;
        } finally {
            instrumentation().endFindInterfacesWithService(svcName, count);
        }
        	
    }

    /**
     * This method is responsible for scheduling the specified
     * node/address/svcname tuple for data collection.
     * 
     * @param nodeId
     *            Node id
     * @param ipAddress
     *            IP address
     * @param svcName
     *            Service name
     * @param existing
     *            True if called by scheduleExistingInterfaces(), false
     *            otheriwse
     */
    private void scheduleInterface(int nodeId, String ipAddress,
            String svcName, boolean existing) {
        
        OnmsIpInterface iface = getIpInterface(nodeId, ipAddress);
        if (iface == null) {
            LOG.error("Unable to find interface with address {} on node {}", ipAddress, nodeId);
            return;
        }
        
        OnmsMonitoredService svc = iface.getMonitoredServiceByServiceType(svcName);
        if (svc == null) {
            LOG.error("Unable to find service {} on interface with address {} on node {}", svcName, ipAddress, nodeId);
            return;
        }
        
        scheduleInterface(iface, svc.getServiceType().getName(),
                          existing);
    }
    
	private void scheduleNode(final int nodeId, final boolean existing) {
		OnmsNode node = m_nodeDao.getHierarchy(nodeId);
		node.visit(new AbstractEntityVisitor() {

			@Override
			public void visitMonitoredService(OnmsMonitoredService monSvc) {
				scheduleInterface(monSvc.getIpInterface(), monSvc.getServiceName(), existing);
			}
			
		});
	}

	private OnmsIpInterface getIpInterface(int nodeId, String ipAddress) {
		OnmsNode node = m_nodeDao.load(nodeId);
		return node.getIpInterfaceByIpAddress(ipAddress);
	}

    private void scheduleInterface(OnmsIpInterface iface, String svcName, boolean existing) {
        
        final String ipAddress = str(iface.getIpAddress());
        if (ipAddress == null) {
        	LOG.warn("Unable to schedule interface {}, could not determine IP address.", iface);
        	return;
        }

		instrumentation().beginScheduleInterface(iface.getNode().getId(), ipAddress, svcName);
        try {
        
        Collection<CollectionSpecification> matchingSpecs = getSpecificationsForInterface(iface, svcName);
        StringBuilder sb;
        
        LOG.debug("scheduleInterface: found {} matching specs for interface: {}", matchingSpecs.size(), iface);

        for (CollectionSpecification spec : matchingSpecs) {

            if (existing == false) {
                /*
                 * It is possible that both a nodeGainedService and a
                 * primarySnmpInterfaceChanged event are generated for an
                 * interface during a rescan. To handle this scenario we must
                 * verify that the ipAddress/pkg pair identified by this event
                 * does not already exist in the collectable services list.
                 */
                if (alreadyScheduled(iface, spec)) {
                    LOG.debug("scheduleInterface: svc/pkgName {}/{} already in collectable service list, skipping.", iface, spec);
                    continue;
                }
            }

            try {
                /*
                 * Criteria checks have all passed. The interface/service pair
                 * can be scheduled.
                 */
                LOG.debug("scheduleInterface: now scheduling interface: {}/{}", iface, svcName);
                CollectableService cSvc = null;

                /*
                 * Create a new SnmpCollector object representing this node,
                 * interface, service and package pairing
                 */

                cSvc = new CollectableService(
                    iface, 
                    m_ifaceDao, 
                    spec, 
                    getScheduler(),
                    m_schedulingCompletedFlag,
                    m_transTemplate.getTransactionManager(),
                    m_persisterFactory,
                    m_thresholdingService
                );

                // Add new collectable service to the collectable service list.
                m_collectableServices.add(cSvc);

                // Schedule the collectable service for immediate collection
                getScheduler().schedule(0, cSvc.getReadyRunnable());

                LOG.debug("scheduleInterface: {}/{} collection, scheduled", iface, svcName);
            } catch (CollectionInitializationException e) {
                sb = new StringBuilder();
                sb.append("scheduleInterface: Unable to schedule ");
                sb.append(iface);
                sb.append('/');
                sb.append(svcName);
                sb.append(", reason: ");
                sb.append(e.getMessage());

                // Only log the stack trace if TRACE level logging is enabled.
                // Fixes bug NMS-3324.
                // http://issues.opennms.org/browse/NMS-3324
                if (LOG.isTraceEnabled()) {
                    LOG.trace(sb.toString(), e);
                } else {
                    LOG.info(sb.toString());
                }
            } catch (Throwable t) {
                LOG.error("scheduleInterface: Uncaught exception, failed to schedule interface {}/{}.", iface, svcName, t);
            }
        } // end while more specifications exist
        
        } finally {
            instrumentation().endScheduleInterface(iface.getNode().getId(), ipAddress, svcName);
        }
    }

    /**
     * <p>getSpecificationsForInterface</p>
     *
     * @param iface a {@link org.opennms.netmgt.model.OnmsIpInterface} object.
     * @param svcName a {@link java.lang.String} object.
     * @return a {@link java.util.Collection} object.
     */
    public Collection<CollectionSpecification> getSpecificationsForInterface(OnmsIpInterface iface, String svcName) {
        Collection<CollectionSpecification> matchingPkgs = new LinkedList<>();

        CollectdConfiguration collectdConfig = m_collectdConfigFactory.getCollectdConfig();

        /*
         * Compare interface/service pair against each collectd package
         * For each match, create new SnmpCollector object and
         * schedule it for collection
         */
        for(Package wpkg : collectdConfig.getPackages()) {
            /*
             * Make certain the the current service is in the package
             * and enabled!
             */
            if (!wpkg.serviceInPackageAndEnabled(svcName)) {
                LOG.debug("getSpecificationsForInterface: address/service: {}/{} not scheduled, service is not enabled or does not exist in package: {}", iface, svcName, wpkg.getName());
                continue;
            }

            // Ensure that the package is not a remote package
            if (wpkg.isRemote()) {
                LOG.debug("getSpecificationsForInterface: address/service: {}/{} not scheduled, package {} is a remote package.", iface, svcName, wpkg.getName());
                continue;
            }

            // Is the interface in the package?
            if (!m_collectdConfigFactory.interfaceInPackage(iface, wpkg)) {
                LOG.debug("getSpecificationsForInterface: address/service: {}/{} not scheduled, interface does not belong to package: {}", iface, svcName, wpkg.getName());
                continue;
            }

            LOG.debug("getSpecificationsForInterface: address/service: {}/{} scheduled, interface does belong to package: {}", iface, svcName, wpkg.getName());
            String className = m_collectdConfigFactory.getCollectdConfig().getCollectors().stream().filter(c->c.getService().equals(svcName)).findFirst().orElse(null).getClassName();
            if(className != null) {
                matchingPkgs.add(new CollectionSpecification(wpkg, svcName, getServiceCollector(svcName), instrumentation(), m_locationAwareCollectorClient, pollOutagesDao, className));
            } else {
                LOG.warn("The class for collector {} is not available yet.", svcName);
            }
        }
        return matchingPkgs;
    }

    /**
     * Returns true if specified address/pkg pair is already represented in
     * the collectable services list. False otherwise.
     * 
     * @param iface
     *            TODO
     * @param spec
     *            TODO
     */
    private boolean alreadyScheduled(OnmsIpInterface iface, CollectionSpecification spec) {
        String ipAddress = str(iface.getIpAddress());
        
        if (ipAddress == null) {
            LOG.warn("Cannot determine if interface {} is already scheduled.  Unable to look up IP address.", iface);
            return false;
        }

        String svcName = spec.getServiceName();
        String pkgName = spec.getPackageName();
        StringBuilder sb;
        boolean isScheduled = false;
        
        if (LOG.isDebugEnabled()) {
            sb = new StringBuilder();
            sb.append("alreadyScheduled: determining if interface: ");
            sb.append(iface);
            sb.append(" is already scheduled.");
        }
        
        synchronized (m_collectableServices) {
        	for (CollectableService cSvc : m_collectableServices) {
                InetAddress addr = (InetAddress) cSvc.getAddress();
                if (cSvc.getNodeId() == iface.getNode().getId()
                        && str(addr).equals(ipAddress)
                        && cSvc.getPackageName().equals(pkgName)
                        && cSvc.getServiceName().equals(svcName)) {
                    isScheduled = true;
                    break;
                }
            }
        }

        if (LOG.isDebugEnabled()) {
            sb = new StringBuilder();
            sb.append("alreadyScheduled: interface ");
            sb.append(iface);
            sb.append("already scheduled check: ");
            sb.append(isScheduled);
        }
        return isScheduled;
    }

    /**
     * @param schedulingCompleted
     *            The schedulingCompleted to set.
     */
    private void setSchedulingCompleted(boolean schedulingCompleted) {
        m_schedulingCompletedFlag.setSchedulingCompleted(schedulingCompleted);
    }

    private void refreshServicePackages() throws CollectionInitializationException {
    	for (CollectableService thisService : m_collectableServices) {
            thisService.refreshPackage(m_collectdConfigFactory);
        }
    }

    protected List<CollectableService> getCollectableServices() {
        return m_collectableServices;
    }

    /**
     * {@inheritDoc}
     *
     * This method is invoked by the JMS topic session when a new event is
     * available for processing. Currently only text based messages are
     * processed by this callback. Each message is examined for its Universal
     * Event Identifier and the appropriate action is taking based on each
     * UEI.
     */
    @Override
    public void onEvent(final IEvent event) {
        Logging.withPrefix(getName(), () -> {
            m_transTemplate.execute(new TransactionCallbackWithoutResult() {
                @Override
                public void doInTransactionWithoutResult(TransactionStatus status) {
                    onEventInTransaction(event);
                }
            });
        });
    }

    private void onEventInTransaction(IEvent event) {
        // print out the uei
        //
        LOG.debug("received event, uei = {}", event.getUei());

        try {
            if (event.getUei().equals(EventConstants.SCHEDOUTAGES_CHANGED_EVENT_UEI)) {
                handleScheduledOutagesChanged(event);
            } else if (event.getUei().equals(EventConstants.CONFIGURE_SNMP_EVENT_UEI)) {
                handleConfigureSNMP(event);
            } else if (event.getUei().equals(EventConstants.NODE_GAINED_SERVICE_EVENT_UEI)) {
                handleNodeGainedService(event);
            } else if (event.getUei().equals(EventConstants.PRIMARY_SNMP_INTERFACE_CHANGED_EVENT_UEI)) {
                handlePrimarySnmpInterfaceChanged(event);
            } else if (event.getUei().equals(EventConstants.REINITIALIZE_PRIMARY_SNMP_INTERFACE_EVENT_UEI)) {
                handleReinitializePrimarySnmpInterface(event);
            } else if (event.getUei().equals(EventConstants.INTERFACE_REPARENTED_EVENT_UEI)) {
                handleInterfaceReparented(event);
            } else if (event.getUei().equals(EventConstants.NODE_DELETED_EVENT_UEI)) {
                handleNodeDeleted(event);
            } else if (event.getUei().equals(EventConstants.DUP_NODE_DELETED_EVENT_UEI)) {
                handleDupNodeDeleted(event);
            } else if (event.getUei().equals(EventConstants.INTERFACE_DELETED_EVENT_UEI)) {
                handleInterfaceDeleted(event);
            } else if (event.getUei().equals(EventConstants.SERVICE_DELETED_EVENT_UEI)) {
                handleServiceDeleted(event);
            } else if (event.getUei().equals(EventConstants.RELOAD_DAEMON_CONFIG_UEI)) {
                handleReloadDaemonConfig(event);
            } else if (event.getUei().equals(EventConstants.NODE_CATEGORY_MEMBERSHIP_CHANGED_EVENT_UEI)) {
                handleNodeCategoryMembershipChanged(event);
            } else if (event.getUei().equals(EventConstants.NODE_LOCATION_CHANGED_EVENT_UEI)) {
                handleNodeLocationChanged(event);
            }
        } catch (InsufficientInformationException e) {
            handleInsufficientInfo(e);
        }
    }

    /**
     * <p>handleInsufficientInfo</p>
     *
     * @param e a {@link org.opennms.core.utils.InsufficientInformationException} object.
     */
    protected void handleInsufficientInfo(InsufficientInformationException e) {
        LOG.info(e.getMessage());
    }

    private void handleDupNodeDeleted(IEvent event)
            throws InsufficientInformationException {
        handleNodeDeleted(event);
    }

    private void handleScheduledOutagesChanged(IEvent event) {
        try {
            LOG.info("Reloading Collectd config factory");
            m_collectdConfigFactory.reload();
            refreshServicePackages();
        } catch (Throwable e) {
            LOG.error("Failed to reload CollectdConfigFactory", e);
        }
    }

    /**
     * </p>
     * Closes the current connections to the Java Message Queue if they are
     * still active. This call may be invoked more than once safely and may be
     * invoked during object finalization.
     * </p>
     */
    private void deinstallMessageSelectors() {
        getEventIpcManager().removeEventListener(this);
    }

    /**
     * This method is responsible for handling configureSNMP events.
     * 
     * @param event
     *            The event to process.
     */
    private void handleConfigureSNMP(final IEvent event) {
        LOG.debug("configureSNMPHandler: processing configure SNMP event...", event);
        
        SnmpEventInfo info = null;
        try {
            info = new SnmpEventInfo(event);
            
            if (StringUtils.isBlank(info.getFirstIPAddress())) {				
                LOG.error("configureSNMPHandler: event contained invalid firstIpAddress. {}", event);
                return;
            }
            
            LOG.debug("configureSNMPHandler: processing configure SNMP event: {}", info);
            SnmpPeerFactory.getInstance().define(info);
            SnmpPeerFactory.getInstance().saveCurrent();
            LOG.debug("configureSNMPHandler: process complete. {}", info);
            
        } catch (Throwable e) {
            LOG.error("configureSNMPHandler: ",e);
        }
    }

    /**
     * This method is responsible for handling interfaceDeleted events.
     * 
     * @param event
     *            The event to process.
     * @throws InsufficientInformationException
     */
    private void handleInterfaceDeleted(IEvent event)
            throws InsufficientInformationException {
        EventUtils.checkNodeId(event);

        String ipAddr = event.getInterface();
        if(EventUtils.isNonIpInterface(ipAddr) ) {
            LOG.debug("handleInterfaceDeleted: the deleted interface was a non-ip interface. Nothing to do here.");
            return;
        }

        Long nodeId = event.getNodeid();

        // Iterate over the collectable services list and mark any entries
        // which match the deleted nodeId/IP address pair for deletion
        synchronized (getCollectableServices()) {
            CollectableService cSvc = null;
            ListIterator<CollectableService> liter = getCollectableServices().listIterator();
            while (liter.hasNext()) {
                cSvc = liter.next();

                // Only interested in entries with matching nodeId and IP
                // address
                InetAddress addr = (InetAddress) cSvc.getAddress();
                if (!(cSvc.getNodeId() == nodeId && InetAddrUtils.str(addr).equals(ipAddr)))
                    continue;

                synchronized (cSvc) {
                    // Retrieve the CollectorUpdates object associated with
                    // this CollectableService if one exists.
                    CollectorUpdates updates = cSvc.getCollectorUpdates();

                    // Now set the update's deletion flag so the next
                    // time it is selected for execution by the scheduler
                    // the collection will be skipped and the service will not
                    // be rescheduled.
                    LOG.debug("Marking CollectableService for deletion because an interface was deleted:  Service nodeid={}, deleted node:{}service address:{}deleted interface:{}", cSvc.getNodeId(), nodeId, InetAddrUtils.str(addr), ipAddr);

                    updates.markForDeletion();
                }

                // Now safe to remove the collectable service from
                // the collectable services list
                liter.remove();
            }
        }

            LOG.debug("interfaceDeletedHandler: processing of interfaceDeleted event for {}/{} completed", nodeId, ipAddr);
    }

    /**
     * This method is responsible for processing 'interfacReparented' events.
     * An 'interfaceReparented' event will have old and new nodeId parms
     * associated with it. All CollectableService objects in the service
     * updates map which match the event's interface address and the SNMP
     * service have a reparenting update associated with them. When the
     * scheduler next pops one of these services from an interval queue for
     * collection all of the RRDs associated with the old nodeId are moved
     * under the new nodeId and the nodeId of the collectable service is
     * updated to reflect the interface's new parent nodeId.
     * 
     * @param event
     *            The event to process.
     * @throws InsufficientInformationException
     */
    private void handleInterfaceReparented(IEvent event)
            throws InsufficientInformationException {
        EventUtils.checkNodeId(event);
        EventUtils.checkInterface(event);

        LOG.debug("interfaceReparentedHandler:  processing interfaceReparented event for {}", event.getInterface());

        // Verify that the event has an interface associated with it
        if (event.getInterface() == null)
            return;

        // Extract the old and new nodeId's from the event parms
        String oldNodeIdStr = null;
        String newNodeIdStr = null;
        String parmName = null;
        IValue parmValue = null;
        String parmContent = null;

        for (IParm parm : event.getParmCollection()) {
            parmName = parm.getParmName();
            parmValue = parm.getValue();
            if (parmValue == null)
                continue;
            else
                parmContent = parmValue.getContent();

            // old nodeid
            if (parmName.equals(EventConstants.PARM_OLD_NODEID)) {
                oldNodeIdStr = parmContent;
            }

            // new nodeid
            else if (parmName.equals(EventConstants.PARM_NEW_NODEID)) {
                newNodeIdStr = parmContent;
            }
        }

        // Only proceed provided we have both an old and a new nodeId
        //
        if (oldNodeIdStr == null || newNodeIdStr == null) {
            LOG.warn("interfaceReparentedHandler: old and new nodeId parms are required, unable to process.");
            return;
        }

        // Iterate over the CollectableService objects in the services
        // list looking for entries which share the same interface
        // address as the reparented interface. Mark any matching objects
        // for reparenting.
        //
        // The next time the service is scheduled for execution it
        // will move all of the RRDs associated
        // with the old nodeId under the new nodeId and update the service's
        // SnmpMonitor.NodeInfo attribute to reflect the new nodeId. All
        // subsequent collections will then be updating the appropriate RRDs.
        //
        OnmsIpInterface iface = null;
        synchronized (getCollectableServices()) {
            CollectableService cSvc = null;
            Iterator<CollectableService> iter = getCollectableServices().iterator();
            while (iter.hasNext()) {
                cSvc = iter.next();

                InetAddress addr = (InetAddress) cSvc.getAddress();
				if (addr.equals(event.getInterfaceAddress())) {
                    synchronized (cSvc) {
                        // Got a match!
                        LOG.debug("interfaceReparentedHandler: got a CollectableService match for {}", event.getInterface());

                        // Retrieve the CollectorUpdates object associated
                        // with
                        // this CollectableService.
                        CollectorUpdates updates = cSvc.getCollectorUpdates();
                        if (iface == null) {
                        	iface = getIpInterface(event.getNodeid().intValue(), event.getInterface());
                        }

                        // Now set the reparenting flag
                        updates.markForReparenting(oldNodeIdStr, newNodeIdStr, iface);
                        LOG.debug("interfaceReparentedHandler: marking {} for reparenting for service SNMP.", event.getInterface());
                    }
                }
            }
        }

        LOG.debug("interfaceReparentedHandler: processing of interfaceReparented event for interface {} completed.", event.getInterface());
    }

    /**
     * This method is responsible for handling nodeDeleted events.
     * 
     * @param event
     *            The event to process.
     * @throws InsufficientInformationException
     */
    private void handleNodeDeleted(IEvent event)
            throws InsufficientInformationException {
        EventUtils.checkNodeId(event);

        Long nodeId = event.getNodeid();

        unscheduleNodeAndMarkForDeletion(nodeId);

        LOG.debug("nodeDeletedHandler: processing of nodeDeleted event for nodeid {} completed.", nodeId);
    }

    /**
     * This method is responsible for handling NodeCategoryMembershipChanged events.
     * 
     * @param event
     *            The event to process.
     * @throws InsufficientInformationException if the event does not have a nodeId
     */
    private void handleNodeCategoryMembershipChanged(IEvent event) throws InsufficientInformationException {
        EventUtils.checkNodeId(event);

        Long nodeId = event.getNodeid();

        unscheduleNodeAndMarkForDeletion(nodeId);

        LOG.debug("nodeCategoryMembershipChanged: unscheduling nodeid {} completed.", nodeId);

        scheduleNode(nodeId.intValue(), true);
    }

    /**
     * This method is responsible for handling NodeLocationChanged events.
     *
     * @param event
     *            The event to process.
     * @throws InsufficientInformationException if the event does not have a nodeId
     */
    private void handleNodeLocationChanged(IEvent event) throws InsufficientInformationException {
        EventUtils.checkNodeId(event);

        Long nodeId = event.getNodeid();

        unscheduleNodeAndMarkForDeletion(nodeId);

        LOG.debug("nodeLocationChanged: unscheduling nodeid {} completed.", nodeId);

        scheduleNode(nodeId.intValue(), true);
    }

    private void rebuildScheduler() {
        //Remove all collectable services
        Collection<Integer> nodeIds = m_nodeDao.getNodeIds();
        m_filterDao.flushActiveIpAddressListCache();
        for (Integer nodeId : nodeIds) {
            unscheduleNodeAndMarkForDeletion(Long.valueOf(nodeId));
        }
       //Remove unused collectors if necessary
        Set<String> newConfigured = m_collectdConfigFactory.getCollectdConfig().getCollectors().stream().map(c->c.getService()).collect(Collectors.toSet());
        List<String> removedList = getCollectorNames().stream().filter(name-> !newConfigured.contains(name)).collect(Collectors.toList());
        removedList.forEach(name -> m_collectors.remove(name));
        //Re-instantiate collectors
        instantiateCollectors();
    }

    private void unscheduleNodeAndMarkForDeletion(Long nodeId) {
		// Iterate over the collectable service list and mark any entries
        // which match the deleted nodeId for deletion.
        synchronized (getCollectableServices()) {
            CollectableService cSvc = null;
            final ListIterator<CollectableService> liter = getCollectableServices().listIterator();
            while (liter.hasNext()) {
                cSvc = liter.next();

                // Only interested in entries with matching nodeId
                if (!(cSvc.getNodeId() == nodeId))
                    continue;

                synchronized (cSvc) {
                    // Retrieve the CollectorUpdates object associated
                    // with this CollectableService.
                    CollectorUpdates updates = cSvc.getCollectorUpdates();

                    // Now set the update's deletion flag so the next
                    // time it is selected for execution by the scheduler
                    // the collection will be skipped and the service will not
                    // be rescheduled.
                    LOG.debug("Marking CollectableService for deletion because a node was deleted:  Service nodeid={}, deleted node:{}", cSvc.getNodeId(), nodeId);
                    updates.markForDeletion();
                }

                // Now safe to remove the collectable service from
                // the collectable services list
                liter.remove();
            }
        }
	}

    /**
     * Process the event, construct a new CollectableService object
     * representing the node/interface combination, and schedule the interface
     * for collection. If any errors occur scheduling the interface no error
     * is returned.
     * 
     * @param event
     *            The event to process.
     * @throws InsufficientInformationException
     */
    private void handleNodeGainedService(IEvent event)
            throws InsufficientInformationException {
        EventUtils.checkNodeId(event);
        EventUtils.checkInterface(event);
        EventUtils.checkService(event);

        // Schedule the interface
        scheduleForCollection(event);
    }
    
    private void handleReloadDaemonConfig(IEvent event) {
        final String collectionDaemonName = "Collectd";
        boolean isCollection = false;
        for (IParm parm : event.getParmCollection()) {
            if (EventConstants.PARM_DAEMON_NAME.equals(parm.getParmName()) && collectionDaemonName.equalsIgnoreCase(parm.getValue().getContent())) {
                isCollection = true;
                break;
            }
        }
        if (isCollection) {
            final String targetFile = ConfigFileConstants.getFileName(ConfigFileConstants.DATA_COLLECTION_CONF_FILE_NAME);
            boolean isDataCollectionConfig = false;
            for (IParm parm : event.getParmCollection()) {
                if (EventConstants.PARM_CONFIG_FILE_NAME.equals(parm.getParmName()) && targetFile.equalsIgnoreCase(parm.getValue().getContent())) {
                    isDataCollectionConfig = true;
                    break;
                }
            }
            EventBuilder ebldr = null;
            if (isDataCollectionConfig) {
                try {
                    DataCollectionConfigFactory.reload();
                    // Preparing successful event
                    ebldr = new EventBuilder(EventConstants.RELOAD_DAEMON_CONFIG_SUCCESSFUL_UEI, "Collectd");
                    ebldr.addParam(EventConstants.PARM_DAEMON_NAME, collectionDaemonName);
                    ebldr.addParam(EventConstants.PARM_CONFIG_FILE_NAME, targetFile);
                } catch (Throwable e) {
                    // Preparing failed event
                    LOG.error("handleReloadDaemonConfig: Error reloading/processing datacollection configuration: {}", e.getMessage(), e);
                    ebldr = new EventBuilder(EventConstants.RELOAD_DAEMON_CONFIG_FAILED_UEI, "Collectd");
                    ebldr.addParam(EventConstants.PARM_DAEMON_NAME, collectionDaemonName);
                    ebldr.addParam(EventConstants.PARM_CONFIG_FILE_NAME, targetFile);
                    ebldr.addParam(EventConstants.PARM_REASON, e.getMessage());
                }
                finally {
                    if (ebldr != null) {
                        getEventIpcManager().sendNow(ebldr.getEvent());
                    }
                }
            } else {
                final String cfgFile = ConfigFileConstants.getFileName(ConfigFileConstants.COLLECTD_CONFIG_FILE_NAME);
                try {
                    m_collectdConfigFactory.reload();
                    rebuildScheduler();
                    ebldr = new EventBuilder(EventConstants.RELOAD_DAEMON_CONFIG_SUCCESSFUL_UEI, "Collectd");
                    ebldr.addParam(EventConstants.PARM_DAEMON_NAME, collectionDaemonName);
                    ebldr.addParam(EventConstants.PARM_CONFIG_FILE_NAME, cfgFile);
                } catch (Throwable e) {
                    LOG.error("handleReloadDaemonConfig: Error reloading/processing collectd configuration: {}", e.getMessage(), e);
                    ebldr = new EventBuilder(EventConstants.RELOAD_DAEMON_CONFIG_FAILED_UEI, "Collectd");
                    ebldr.addParam(EventConstants.PARM_DAEMON_NAME, collectionDaemonName);
                    ebldr.addParam(EventConstants.PARM_CONFIG_FILE_NAME, cfgFile);
                    ebldr.addParam(EventConstants.PARM_REASON, e.getMessage());
                }
                finally {
                    if (ebldr != null) {
                        getEventIpcManager().sendNow(ebldr.getEvent());
                    }
                }
            }
        }
    }
    
    private void scheduleForCollection(IEvent event) {
        // This moved to here from the scheduleInterface() for better behavior
        // during initialization
        
        m_filterDao.flushActiveIpAddressListCache();

        scheduleInterface(event.getNodeid().intValue(), event.getInterface(),
                          event.getService(), false);
    }

    /**
     * Process the 'primarySnmpInterfaceChanged' event. Extract the old and
     * new primary SNMP interface addresses from the event parms. Any
     * CollectableService objects located in the collectable services list
     * which match the IP address of the old primary interface and have a
     * service name of "SNMP" are flagged for deletion. This will ensure that
     * the old primary interface is no longer collected against. Finally the
     * new primary SNMP interface is scheduled. The packages are examined and
     * new CollectableService objects are created, initialized and scheduled
     * for collection.
     * 
     * @param event
     *            The event to process.
     * @throws InsufficientInformationException
     */
    private void handlePrimarySnmpInterfaceChanged(IEvent event)
            throws InsufficientInformationException {
        EventUtils.checkNodeId(event);
        EventUtils.checkInterface(event);

        LOG.debug("primarySnmpInterfaceChangedHandler:  processing primary SNMP interface changed event...");

        // Currently only support SNMP data collection.
        //
        if (!event.getService().equals("SNMP"))
            return;

        // Extract the old and new primary SNMP interface addresses from the
        // event parms.
        //
        String oldPrimaryIfAddr = null;
        String parmName = null;
        IValue parmValue = null;
        String parmContent = null;

        for (IParm parm : event.getParmCollection()) {
            parmName = parm.getParmName();
            parmValue = parm.getValue();
            if (parmValue == null)
                continue;
            else
                parmContent = parmValue.getContent();

            // old primary SNMP interface (optional parameter)
            if (parmName.equals(EventConstants.PARM_OLD_PRIMARY_SNMP_ADDRESS)) {
                oldPrimaryIfAddr = parmContent;
            }
        }

        if (oldPrimaryIfAddr != null) {
            // Mark the service for deletion so that it will not be
            // rescheduled
            // for
            // collection.
            //
            // Iterate over the CollectableService objects in the service
            // updates map
            // and mark any which have the same interface address as the old
            // primary SNMP interface and a service name of "SNMP" for
            // deletion.
            //
            synchronized (getCollectableServices()) {
                CollectableService cSvc = null;
                ListIterator<CollectableService> liter = getCollectableServices().listIterator();
                while (liter.hasNext()) {
                    cSvc = liter.next();

                    final InetAddress addr = (InetAddress) cSvc.getAddress();
                    final String addrString = str(addr);
					if (addrString != null && addrString.equals(oldPrimaryIfAddr)) {
                        synchronized (cSvc) {
                            // Got a match! Retrieve the CollectorUpdates
                            // object
                            // associated
                            // with this CollectableService.
                            CollectorUpdates updates = cSvc.getCollectorUpdates();

                            // Now set the deleted flag
                            updates.markForDeletion();
                            LOG.debug("primarySnmpInterfaceChangedHandler: marking {} as deleted for service SNMP.", oldPrimaryIfAddr);
                        }

                        // Now safe to remove the collectable service from
                        // the collectable services list
                        liter.remove();
                    }
                }
            }
        }

        // Now we can schedule the new service...
        //
        scheduleForCollection(event);

        LOG.debug("primarySnmpInterfaceChangedHandler: processing of primarySnmpInterfaceChanged event for nodeid {} completed.", event.getNodeid());
    }

    /**
     * Process the event. This event is generated when a managed node which
     * supports SNMP gains a new interface. In this situation the
     * CollectableService object representing the primary SNMP interface of
     * the node must be reinitialized. The CollectableService object
     * associated with the primary SNMP interface for the node will be marked
     * for reinitialization. Reinitializing the CollectableService object
     * consists of calling the ServiceCollector.release() method followed by
     * the ServiceCollector.initialize() method which will refresh attributes
     * such as the interface key list and number of interfaces (both of which
     * most likely have changed). Reinitialization will take place the next
     * time the CollectableService is popped from an interval queue for
     * collection. If any errors occur scheduling the service no error is
     * returned.
     * 
     * @param event
     *            The event to process.
     * @throws InsufficientInformationException
     */
    private void handleReinitializePrimarySnmpInterface(IEvent event)
            throws InsufficientInformationException {
        EventUtils.checkNodeId(event);
        EventUtils.checkInterface(event);

        Long nodeid = event.getNodeid();
        String ipAddress = event.getInterface();

        // Mark the primary SNMP interface for reinitialization in
        // order to update any modified attributes associated with
        // the collectable service..
        //
        // Iterate over the CollectableService objects in the
        // updates map and mark any which have the same interface
        // address for reinitialization
        //
        OnmsIpInterface iface = null;
        synchronized (getCollectableServices()) {
            Iterator<CollectableService> iter = getCollectableServices().iterator();
            while (iter.hasNext()) {
                CollectableService cSvc = iter.next();
        
                final InetAddress addr = (InetAddress) cSvc.getAddress();
                final String addrString = str(addr);
                LOG.debug("Comparing CollectableService ip address = {} and event ip interface = {}", addrString, ipAddress);
                if (addrString != null && addrString.equals(ipAddress) && cSvc.getNodeId() == nodeid.intValue()) {
                    synchronized (cSvc) {
                    	if (iface == null) {
                            iface = getIpInterface(nodeid.intValue(), ipAddress);
                    	}
                        // Got a match! Retrieve the CollectorUpdates object
                        // associated
                        // with this CollectableService.
                        CollectorUpdates updates = cSvc.getCollectorUpdates();
        
                        // Now set the reinitialization flag
                        updates.markForReinitialization(iface);
                        LOG.debug("reinitializePrimarySnmpInterfaceHandler: marking {} for reinitialization for service SNMP.", ipAddress);
                    }
                }
            }
        }
    }
    
    /**
     * This method is responsible for handling serviceDeleted events.
     * 
     * @param event
     *            The event to process.
     * @throws InsufficientInformationException 
     * 
     */
    private void handleServiceDeleted(IEvent event)
            throws InsufficientInformationException {
        EventUtils.checkNodeId(event);
        EventUtils.checkInterface(event);
        EventUtils.checkService(event);

        

        //INCORRECT; we now support all *sorts* of data collection.  This is *way* out of date
        // Currently only support SNMP data collection.
        //
        //if (!event.getService().equals("SNMP"))
        //    return;

        Long nodeId = event.getNodeid();
        String ipAddr = event.getInterface();
        String svcName = event.getService();

        // Iterate over the collectable services list and mark any entries
        // which match the nodeId/ipAddr of the deleted service
        // for deletion.
        synchronized (getCollectableServices()) {
            CollectableService cSvc = null;
            ListIterator<CollectableService> liter = getCollectableServices().listIterator();
            while (liter.hasNext()) {
                cSvc = liter.next();

                // Only interested in entries with matching nodeId, IP address
                // and service
                InetAddress addr = (InetAddress) cSvc.getAddress();
                
                //WATCH the brackets; there used to be an extra close bracket after the ipAddr comparison which borked this whole expression
                if (!(cSvc.getNodeId() == nodeId && 
                        InetAddrUtils.str(addr).equals(ipAddr) &&
                        cSvc.getServiceName().equals(svcName))) 
                    continue;

                synchronized (cSvc) {
                    // Retrieve the CollectorUpdates object associated with
                    // this CollectableService if one exists.
                    CollectorUpdates updates = cSvc.getCollectorUpdates();

                    // Now set the update's deletion flag so the next
                    // time it is selected for execution by the scheduler
                    // the collection will be skipped and the service will not
                    // be rescheduled.
                    LOG.debug("Marking CollectableService for deletion because a service was deleted:  Service nodeid={}, deleted node:{}, service address:{}, deleted interface:{}, service servicename:{}, deleted service name:{}, event source {}", cSvc.getNodeId(), nodeId, InetAddrUtils.str(addr), ipAddr, cSvc.getServiceName(), svcName, event.getSource());
                    updates.markForDeletion();
                }

                // Now safe to remove the collectable service from
                // the collectable services list
                liter.remove();
            }
        }

        LOG.debug("serviceDeletedHandler: processing of serviceDeleted event for {}/{}/{} completed.", nodeId, ipAddr, svcName);
    }

    /**
     * <p>setScheduler</p>
     *
     * @param scheduler a {@link org.opennms.netmgt.scheduler.Scheduler} object.
     */
    public void setScheduler(Scheduler scheduler) {
        m_scheduler = scheduler;
    }

    /**
     * <p>getScheduler</p>
     *
     * @return a {@link org.opennms.netmgt.scheduler.Scheduler} object.
     */
    public Scheduler getScheduler() {
        if (m_scheduler == null) {
            createScheduler();
        }
        return m_scheduler;
    }

    /**
     * <p>setCollectorConfigDao</p>
     *
     * @param collectdConfigFactory a {@link org.opennms.netmgt.config.CollectdConfigFactory} object.
     */
    void setCollectdConfigFactory(CollectdConfigFactory collectdConfigFactory) {
        m_collectdConfigFactory = collectdConfigFactory;
    }

    /**
     * <p>setIpInterfaceDao</p>
     *
     * @param ifSvcDao a {@link org.opennms.netmgt.dao.api.IpInterfaceDao} object.
     */
    void setIpInterfaceDao(IpInterfaceDao ifSvcDao) {
        m_ifaceDao = ifSvcDao;
    }

    /**
     * <p>setFilterDao</p>
     *
     * @param dao a {@link org.opennms.netmgt.filter.api.FilterDao} object.
     */
    void setFilterDao(FilterDao dao) {
        m_filterDao = dao;
    }

    public void setServiceCollectorRegistry(ServiceCollectorRegistry serviceCollectorRegistry) {
        m_serviceCollectorRegistry = serviceCollectorRegistry;
    }

    public void setLocationAwareCollectorClient(LocationAwareCollectorClient locationAwareCollectorClient) {
        m_locationAwareCollectorClient = locationAwareCollectorClient;
    }

    /**
     * <p>setTransactionTemplate</p>
     *
     * @param transTemplate a {@link org.springframework.transaction.support.TransactionTemplate} object.
     */
    void setTransactionTemplate(TransactionTemplate transTemplate) {
        m_transTemplate = transTemplate;
    }

    /**
     * <p>setNodeDao</p>
     *
     * @param nodeDao a {@link org.opennms.netmgt.dao.api.NodeDao} object.
     */
    void setNodeDao(NodeDao nodeDao) {
        m_nodeDao = nodeDao;
    }

    /**
     * <p>setServiceCollector</p>
     *
     * @param svcName a {@link java.lang.String} object.
     * @param collector a {@link org.opennms.netmgt.collection.api.ServiceCollector} object.
     */
    public void setServiceCollector(String svcName, ServiceCollector collector) {
        m_collectors.put(svcName, collector);
    }

    /**
     * <p>getServiceCollector</p>
     *
     * @param svcName a {@link java.lang.String} object.
     * @return a {@link org.opennms.netmgt.collection.api.ServiceCollector} object.
     */
    public ServiceCollector getServiceCollector(String svcName) {
        return m_collectors.get(svcName);
    }

    public PersisterFactory getPersisterFactory() {
        return m_persisterFactory;
    }

    public void setPersisterFactory(PersisterFactory persisterFactory) {
        m_persisterFactory = persisterFactory;
    }

    /**
     * <p>getCollectorNames</p>
     *
     * @return a {@link java.util.Set} object.
     */
    public Set<String> getCollectorNames() {
        return m_collectors.keySet();
    }

    private void instantiateCollectors() {
        LOG.debug("instantiateCollectors: Loading collectors");
        AtomicInteger scheduledCounter = new AtomicInteger(0);
        /*
         * Load up an instance of each collector from the config
         * so that the event processor will have them for
         * new incoming events to create collectable service objects.
         */
        Collection<Collector> collectors = m_collectdConfigFactory.getCollectdConfig().getCollectors();

        final int currentSessionID = sessionID.incrementAndGet();

        for(Collector collector: collectors) {
            String svcName = collector.getService();
            LOG.debug("instantiateCollectors: Loading collector {}, classname {}", svcName, collector.getClassName());
            CompletableFuture<ServiceCollector> collectorFuture = m_serviceCollectorRegistry.getCollectorFutureByClassName(collector.getClassName());
            collectorFuture.whenComplete((sc, ex) -> {
                try {
                    sc.initialize();
                    setServiceCollector(svcName, sc);
                    LOG.debug("instantiateCollectors: Loading collector {} was initialized", svcName);
                } catch (CollectionInitializationException e) {
                    LOG.warn("instantiateCollectors: Failed to load collector {} for service {}", collector.getClassName(), svcName, e);
                }
            }).whenComplete((Void, ex) -> {
                if(currentSessionID != sessionID.get()) { //prevent schedule the same collector twice
                    return;
                }
                getScheduler().schedule(0, scheduleCollector(svcName));
                if(scheduledCounter.incrementAndGet() == collectors.size()) {
                    setSchedulingCompleted(true);
                }
            });
            if(!collectorFuture.isDone()) {
                LOG.warn("The collector {} with class {} is not available yet, if the feature is installed correctly it will be available later.", svcName, collector.getClassName());
            }
        }
    }

    private ReadyRunnable scheduleCollector(String svcName) {
        ReadyRunnable runnable= new ReadyRunnable() {

            @Override
            public void run() {
                Logging.withPrefix(LOG4J_CATEGORY, () ->{
                    m_transTemplate.execute(new TransactionCallbackWithoutResult() {
                        @Override
                        protected void doInTransactionWithoutResult(TransactionStatus transactionStatus) {
                            scheduleInterfacesWithService(svcName);
                        }
                    });
                });
            }

            @Override
            public boolean isReady() {
                return true;
            }
        };
        return runnable;
    }

    public static String getLoggingCategory() {
    	return LOG4J_CATEGORY;
    }

    public long getCollectableServiceCount() {
        return m_collectableServices.size();
    }

    @VisibleForTesting
    public void setPollOutagesDao(ReadablePollOutagesDao pollOutagesDao) {
        this.pollOutagesDao = Objects.requireNonNull(pollOutagesDao);
    }
}
