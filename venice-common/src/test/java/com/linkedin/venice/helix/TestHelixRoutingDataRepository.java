package com.linkedin.venice.helix;

import com.linkedin.venice.meta.Instance;
import com.linkedin.venice.meta.Partition;
import com.linkedin.venice.utils.Utils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.helix.HelixAdmin;
import org.apache.helix.HelixDefinedState;
import org.apache.helix.HelixManager;
import org.apache.helix.HelixManagerFactory;
import org.apache.helix.InstanceType;
import org.apache.helix.LiveInstanceInfoProvider;
import org.apache.helix.NotificationContext;
import org.apache.helix.ZNRecord;
import org.apache.helix.controller.HelixControllerMain;
import org.apache.helix.manager.zk.ZKHelixAdmin;
import org.apache.helix.manager.zk.ZKHelixManager;
import org.apache.helix.model.HelixConfigScope;
import org.apache.helix.model.IdealState;
import org.apache.helix.model.Message;
import org.apache.helix.model.StateModelDefinition;
import org.apache.helix.model.builder.HelixConfigScopeBuilder;
import org.apache.helix.participant.statemachine.StateModel;
import org.apache.helix.participant.statemachine.StateModelFactory;
import org.apache.helix.participant.statemachine.StateModelInfo;
import org.apache.helix.participant.statemachine.Transition;
import org.testng.Assert;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;


/**
 * Test case for HelixRoutingDataRepository.
 */
public class TestHelixRoutingDataRepository {
  //This unit test need a running zookeeper. So only for debugging, disable by default.
  private boolean isEnable = false;
  private HelixManager manager;
  private HelixManager controller;
  private HelixAdmin admin;
  private String clusterName = "UnitTestCLuster";
  private String resourceName = "UnitTest";
  private String zkAddress = "localhost:2181";
  private int httpPort = 1234;
  private int adminPort = 2345;

  @BeforeTest
  public void HelixSetup()
      throws Exception {
    if (!isEnable) {
      return;
    }

    admin = new ZKHelixAdmin(zkAddress);
    admin.addCluster(clusterName);
    HelixConfigScope configScope = new HelixConfigScopeBuilder(HelixConfigScope.ConfigScopeProperty.CLUSTER).
        forCluster(clusterName).build();
    Map<String, String> helixClusterProperties = new HashMap<String, String>();
    helixClusterProperties.put(ZKHelixManager.ALLOW_PARTICIPANT_AUTO_JOIN, String.valueOf(true));
    admin.setConfig(configScope, helixClusterProperties);
    admin.addStateModelDef(clusterName, UnitTestStateModel.UNIT_TEST_STATE_MODEL, UnitTestStateModel.getDefinition());

    admin.addResource(clusterName, resourceName, 1, UnitTestStateModel.UNIT_TEST_STATE_MODEL,
        IdealState.RebalanceMode.FULL_AUTO.toString());
    admin.rebalance(clusterName, resourceName, 1);

    controller = HelixControllerMain
        .startHelixController(zkAddress, clusterName, "UnitTestController", HelixControllerMain.STANDALONE);

    String nodeId = Utils.getHostName() + "_" + httpPort;
    manager = HelixManagerFactory.getZKHelixManager(clusterName, nodeId, InstanceType.PARTICIPANT, zkAddress);
    manager.getStateMachineEngine()
        .registerStateModelFactory(UnitTestStateModel.UNIT_TEST_STATE_MODEL, new UnitTestStateModelFactory());
    Instance instance = new Instance(nodeId, Utils.getHostName(), adminPort, httpPort);
    manager.setLiveInstanceInfoProvider(new LiveInstanceInfoProvider() {
      @Override
      public ZNRecord getAdditionalLiveInstanceInfo() {
        return HelixInstanceConverter.convertInstanceToZNRecord(instance);
      }
    });

    manager.connect();
    //Waiting essential notification from ZK.
    Thread.sleep(1000l);
  }

  @AfterTest
  public void HelixCleanup() {
    if (!isEnable) {
      return;
    }
    manager.disconnect();
    controller.disconnect();
    admin.dropCluster(clusterName);
    admin.close();
  }

  @Test
  public void testGetPartitionIds()
      throws Exception {
    if (!isEnable) {
      return;
    }
    HelixManager readManager =
        HelixManagerFactory.getZKHelixManager(clusterName, "reader", InstanceType.SPECTATOR, zkAddress);
    readManager.connect();
    HelixRoutingDataRepository repository = new HelixRoutingDataRepository(readManager);
    repository.init();

    List<Integer> ids = repository.getPartitionIds(resourceName);
    Assert.assertEquals(1, ids.size());
    Assert.assertEquals(0, ids.get(0).intValue());

    readManager.disconnect();
  }

  @Test
  public void testGetInstances()
      throws Exception {
    if (!isEnable) {
      return;
    }
    HelixManager readManager =
        HelixManagerFactory.getZKHelixManager(clusterName, "reader", InstanceType.SPECTATOR, zkAddress);
    readManager.connect();
    HelixRoutingDataRepository repository = new HelixRoutingDataRepository(readManager);
    repository.init();

    List<Instance> instances = repository.getInstances(resourceName, 0);
    Assert.assertEquals(1, instances.size());
    Instance instance = instances.get(0);
    Assert.assertEquals(Utils.getHostName(), instance.getHost());
    Assert.assertEquals(httpPort, instance.getHttpPort());
    Assert.assertEquals(adminPort, instance.getAdminPort());

    readManager.disconnect();
  }

  @Test
  public void testGetNumberOfPartitions()
      throws Exception {
    if (!isEnable) {
      return;
    }
    HelixManager readManager =
        HelixManagerFactory.getZKHelixManager(clusterName, "reader", InstanceType.SPECTATOR, zkAddress);
    readManager.connect();
    HelixRoutingDataRepository repository = new HelixRoutingDataRepository(readManager);
    repository.init();
    Assert.assertEquals(1, repository.getNumberOfPartitions(resourceName));
    readManager.disconnect();
  }

  @Test
  public void testGetPartitions()
      throws Exception {
    if (!isEnable) {
      return;
    }
    HelixManager readManager =
        HelixManagerFactory.getZKHelixManager(clusterName, "reader", InstanceType.SPECTATOR, zkAddress);
    readManager.connect();
    HelixRoutingDataRepository repository = new HelixRoutingDataRepository(readManager);
    repository.init();
    List<Partition> partitions = repository.getPartitions(resourceName);
    Assert.assertEquals(1, partitions.size());
    Assert.assertEquals(1, partitions.get(0).getInstances().size());

    Instance instance = partitions.get(0).getInstances().get(0);
    Assert.assertEquals(Utils.getHostName(), instance.getHost());
    Assert.assertEquals(httpPort, instance.getHttpPort());
    Assert.assertEquals(adminPort, instance.getAdminPort());

    readManager.disconnect();
  }

  private static class UnitTestStateModel {

    public static final String UNIT_TEST_STATE_MODEL = "UnitTestStateModel";

    public static StateModelDefinition getDefinition() {

      StateModelDefinition.Builder builder = new StateModelDefinition.Builder(UNIT_TEST_STATE_MODEL);

      builder.addState(HelixState.ONLINE.toString(), 1);
      builder.addState(HelixState.OFFLINE.toString());
      builder.addState(HelixState.DROPPED.toString());
      builder.initialState(HelixState.OFFLINE.toString());
      builder.addTransition(HelixState.OFFLINE.toString(), HelixState.ONLINE.toString());
      builder.addTransition(HelixState.ONLINE.toString(), HelixState.OFFLINE.toString());
      builder.addTransition(HelixState.OFFLINE.toString(), HelixDefinedState.DROPPED.toString());
      builder.dynamicUpperBound(HelixState.ONLINE.toString(), "R");

      return builder.build();
    }
  }

  private static class UnitTestStateModelFactory extends StateModelFactory<StateModel> {
    @Override
    public StateModel createNewStateModel(String resourceName, String partitionName) {
      OnlineOfflineStateModel stateModel = new OnlineOfflineStateModel();
      return stateModel;
    }

    @StateModelInfo(states = "{'OFFLINE','ONLINE'}", initialState = "OFFLINE")
    public static class OnlineOfflineStateModel extends StateModel {
      @Transition(from = "OFFLINE", to = "ONLINE")
      public void onBecomeOnlineFromOffline(Message message, NotificationContext context) {
      }

      @Transition(from = "ONLINE", to = "OFFLINE")
      public void onBecomeOfflineFromOnline(Message message, NotificationContext context) {
      }
    }
  }
}
