package org.opennms.smoketest.dcb;

import static com.jayway.awaitility.Awaitility.await;
import static io.restassured.RestAssured.given;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.junit.ClassRule;
import org.junit.Test;
import org.opennms.core.criteria.CriteriaBuilder;
import org.opennms.netmgt.dao.api.IpInterfaceDao;
import org.opennms.netmgt.dao.api.NodeDao;
import org.opennms.netmgt.dao.hibernate.IpInterfaceDaoHibernate;
import org.opennms.netmgt.dao.hibernate.NodeDaoHibernate;
import org.opennms.netmgt.model.OnmsIpInterface;
import org.opennms.netmgt.model.OnmsNode;
import org.opennms.netmgt.model.PrimaryType;
import org.opennms.netmgt.provision.persist.requisition.Requisition;
import org.opennms.netmgt.provision.persist.requisition.RequisitionInterface;
import org.opennms.netmgt.provision.persist.requisition.RequisitionMetaData;
import org.opennms.netmgt.provision.persist.requisition.RequisitionMonitoredService;
import org.opennms.netmgt.provision.persist.requisition.RequisitionNode;
import org.opennms.smoketest.stacks.OpenNMSStack;
import org.opennms.smoketest.stacks.StackModel;
import org.opennms.smoketest.utils.DaoUtils;
import org.opennms.smoketest.utils.RestClient;
import org.opennms.smoketest.utils.TestContainerUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.images.builder.ImageFromDockerfile;

import com.github.dockerjava.api.command.CreateContainerCmd;

public class DcbEndToEndIT {

    private final String FOREIGN_ID = "dcbtest";
    private final String FOREIGN_SOURCE = "SmokeTests";

    private final String DCB_SVC_NAME = "DeviceConfig";

    public static final String DCB_USERNAME = "dcbuser";
    public static final String DCB_PASSWORD = "dcbpass";
    public static final String DCB_PORT = "2222";
    public static final String DCB_SCRIPT = "await:~$&#xa;send:tftp ${tftpServer} ${tftpServerPort} -c put /test.txt test.txt-${filenameSuffix}&#xa;await:~$";

    @ClassRule
    public static final GenericContainer sshTftpContainer = new GenericContainer<>(new ImageFromDockerfile()
            .withDockerfileFromBuilder(builder ->
                    builder
                            .from("linuxserver/openssh-server")
                            .run("apk add --update tftp-hpa")
                            .run("echo Hallo > /test.txt")
                            .build()))
            .withEnv("PASSWORD_ACCESS", "true")
            .withEnv("SUDO_ACCESS", "true")
            .withEnv("USER_NAME", DCB_USERNAME)
            .withEnv("USER_PASSWORD", DCB_PASSWORD)
            .withExposedPorts(2222)
            .withNetwork(Network.SHARED)
            .withNetworkAliases("TftpClient")
            .withCreateContainerCmdModifier(cmd -> {
                final CreateContainerCmd createCmd = (CreateContainerCmd) cmd;
                TestContainerUtils.setGlobalMemAndCpuLimits(createCmd);
            });

    @ClassRule
    public static final OpenNMSStack stack = OpenNMSStack.withModel(StackModel.newBuilder()
            .withMinion()
            .build());

    @Test
    public void testSomething() throws Exception {
        final RestClient restclient = new RestClient(stack.opennms().getWebAddress());

        final List<RequisitionMonitoredService> monitoredServiceList = new ArrayList<>();
        monitoredServiceList.add(new RequisitionMonitoredService(DCB_SVC_NAME));

        final RequisitionInterface requisitionInterface = new RequisitionInterface();
        requisitionInterface.setIpAddr(sshTftpContainer.getContainerIpAddress());
        requisitionInterface.setManaged(true);
        requisitionInterface.setSnmpPrimary(PrimaryType.PRIMARY);
        requisitionInterface.setMonitoredServices(monitoredServiceList);

        final List<RequisitionMetaData> metaDataList = new ArrayList<>();
        metaDataList.add(new RequisitionMetaData("requisition", "dcb:username", DCB_USERNAME));
        metaDataList.add(new RequisitionMetaData("requisition", "dcb:password", DCB_PASSWORD));
        metaDataList.add(new RequisitionMetaData("requisition", "dcb:script", DCB_SCRIPT));
        metaDataList.add(new RequisitionMetaData("requisition", "dcb:port", DCB_PORT));

        final RequisitionNode requisitionNode = new RequisitionNode();
        requisitionNode.setForeignId(FOREIGN_ID);
        requisitionNode.setNodeLabel(FOREIGN_ID);
        requisitionNode.setLocation(stack.minion().getLocation());
        requisitionNode.setMetaData(metaDataList);
        requisitionNode.putInterface(requisitionInterface);

        final Requisition requisition = new Requisition();
        requisition.setForeignSource(FOREIGN_SOURCE);
        requisition.insertNode(requisitionNode);

        restclient.addOrReplaceRequisition(requisition);
        restclient.importRequisition(FOREIGN_SOURCE);

        final NodeDao nodeDao = stack.postgres().dao(NodeDaoHibernate.class);
        final IpInterfaceDao ipInterfaceDao = stack.postgres().dao(IpInterfaceDaoHibernate.class);

        final OnmsNode onmsNode = await()
                .atMost(3, MINUTES)
                .pollInterval(30, SECONDS)
                .until(DaoUtils.findMatchingCallable(nodeDao, new CriteriaBuilder(OnmsNode.class).eq("foreignId", FOREIGN_ID).toCriteria()), notNullValue());

        final OnmsIpInterface onmsIpInterface = ipInterfaceDao.findPrimaryInterfaceByNodeId(onmsNode.getId());

        restclient.triggerBackup("{\"ipAddress\":\"" + sshTftpContainer.getContainerIpAddress() + "\",\"location\":\"" + stack.minion().getLocation() + "\",\"serviceName\",\"" + DCB_SVC_NAME + "\"}");

        final String response = restclient.getBackups();

        System.err.println(response);

        fail(response);
    }
}
