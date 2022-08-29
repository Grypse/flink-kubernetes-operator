/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.kubernetes.operator.service;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.JobManagerOptions;
import org.apache.flink.configuration.SchedulerExecutionMode;
import org.apache.flink.kubernetes.configuration.KubernetesConfigOptions;
import org.apache.flink.kubernetes.operator.TestUtils;
import org.apache.flink.kubernetes.operator.config.FlinkConfigBuilder;
import org.apache.flink.kubernetes.operator.config.FlinkConfigManager;
import org.apache.flink.kubernetes.operator.crd.AbstractFlinkResource;
import org.apache.flink.kubernetes.operator.crd.FlinkDeployment;
import org.apache.flink.kubernetes.operator.crd.spec.KubernetesDeploymentMode;
import org.apache.flink.kubernetes.operator.utils.StandaloneKubernetesUtils;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetSpecBuilder;
import io.fabric8.kubernetes.client.NamespacedKubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** @link StandaloneFlinkService unit tests */
@EnableKubernetesMockClient(crud = true)
public class StandaloneFlinkServiceTest {
    KubernetesMockServer mockServer;

    private NamespacedKubernetesClient kubernetesClient;
    StandaloneFlinkService flinkStandaloneService;
    Configuration configuration = new Configuration();

    @BeforeEach
    public void setup() {
        configuration.set(KubernetesConfigOptions.CLUSTER_ID, TestUtils.TEST_DEPLOYMENT_NAME);
        configuration.set(KubernetesConfigOptions.NAMESPACE, TestUtils.TEST_NAMESPACE);

        kubernetesClient = mockServer.createClient().inAnyNamespace();
        flinkStandaloneService =
                new StandaloneFlinkService(kubernetesClient, new FlinkConfigManager(configuration));
    }

    @Test
    public void testDeleteClusterDeployment() throws Exception {
        FlinkDeployment flinkDeployment = TestUtils.buildStandaloneSessionCluster();
        configuration = buildConfig(flinkDeployment, configuration);

        createStatefulSets(flinkDeployment);

        List<StatefulSet> statefulSets = kubernetesClient.apps().statefulSets().list().getItems();

        assertEquals(2, statefulSets.size());

        flinkStandaloneService.deleteClusterDeployment(
                flinkDeployment.getMetadata(), flinkDeployment.getStatus(), false);

        statefulSets = kubernetesClient.apps().statefulSets().list().getItems();

        assertEquals(0, statefulSets.size());
    }

    @Test
    public void testDeleteClusterDeploymentWithHADelete() throws Exception {
        FlinkDeployment flinkDeployment = TestUtils.buildStandaloneSessionCluster();
        configuration = buildConfig(flinkDeployment, configuration);

        createStatefulSets(flinkDeployment);

        List<StatefulSet> statefulSets = kubernetesClient.apps().statefulSets().list().getItems();
        assertEquals(2, statefulSets.size());

        flinkStandaloneService.deleteClusterDeployment(
                flinkDeployment.getMetadata(), flinkDeployment.getStatus(), true);

        statefulSets = kubernetesClient.apps().statefulSets().list().getItems();

        assertEquals(0, statefulSets.size());
    }

    @Test
    public void testReactiveScale() throws Exception {
        var flinkDeployment = TestUtils.buildStandaloneApplicationCluster();
        var clusterId = flinkDeployment.getMetadata().getName();
        var namespace = flinkDeployment.getMetadata().getNamespace();
        flinkDeployment.getSpec().setMode(KubernetesDeploymentMode.STANDALONE);
        flinkDeployment
                .getSpec()
                .getFlinkConfiguration()
                .put(
                        JobManagerOptions.SCHEDULER_MODE.key(),
                        SchedulerExecutionMode.REACTIVE.name());
        createStatefulSets(flinkDeployment);
        assertTrue(
                flinkStandaloneService.scale(
                        flinkDeployment.getMetadata(),
                        flinkDeployment.getSpec().getJob(),
                        buildConfig(flinkDeployment, configuration)));

        assertEquals(
                1,
                kubernetesClient
                        .apps()
                        .statefulSets()
                        .inNamespace(namespace)
                        .withName(
                                StandaloneKubernetesUtils.getTaskManagerStatefulSetName(clusterId))
                        .get()
                        .getSpec()
                        .getReplicas());

        flinkDeployment.getSpec().getJob().setParallelism(4);
        assertTrue(
                flinkStandaloneService.scale(
                        flinkDeployment.getMetadata(),
                        flinkDeployment.getSpec().getJob(),
                        buildConfig(flinkDeployment, configuration)));
        assertEquals(
                2,
                kubernetesClient
                        .apps()
                        .statefulSets()
                        .inNamespace(namespace)
                        .withName(
                                StandaloneKubernetesUtils.getTaskManagerStatefulSetName(clusterId))
                        .get()
                        .getSpec()
                        .getReplicas());

        kubernetesClient
                .apps()
                .statefulSets()
                .inNamespace(namespace)
                .withName(StandaloneKubernetesUtils.getTaskManagerStatefulSetName(clusterId))
                .delete();
        assertFalse(
                flinkStandaloneService.scale(
                        flinkDeployment.getMetadata(),
                        flinkDeployment.getSpec().getJob(),
                        buildConfig(flinkDeployment, configuration)));

        createStatefulSets(flinkDeployment);
        assertTrue(
                flinkStandaloneService.scale(
                        flinkDeployment.getMetadata(),
                        flinkDeployment.getSpec().getJob(),
                        buildConfig(flinkDeployment, configuration)));

        flinkDeployment
                .getSpec()
                .getFlinkConfiguration()
                .remove(JobManagerOptions.SCHEDULER_MODE.key());
        assertFalse(
                flinkStandaloneService.scale(
                        flinkDeployment.getMetadata(),
                        flinkDeployment.getSpec().getJob(),
                        buildConfig(flinkDeployment, configuration)));
    }

    private Configuration buildConfig(FlinkDeployment flinkDeployment, Configuration configuration)
            throws Exception {
        return FlinkConfigBuilder.buildFrom(
                flinkDeployment.getMetadata().getNamespace(),
                flinkDeployment.getMetadata().getName(),
                flinkDeployment.getSpec(),
                configuration);
    }

    private void createStatefulSets(AbstractFlinkResource cr) {
        StatefulSet jmStatefulSet = new StatefulSet();
        ObjectMeta jmMetadata = new ObjectMeta();
        jmMetadata.setName(
                StandaloneKubernetesUtils.getJobManagerStatefulSetName(cr.getMetadata().getName()));
        jmStatefulSet.setMetadata(jmMetadata);
        kubernetesClient
                .apps()
                .statefulSets()
                .inNamespace(cr.getMetadata().getNamespace())
                .createOrReplace(jmStatefulSet);

        StatefulSet tmStatefulSet = new StatefulSet();
        ObjectMeta tmMetadata = new ObjectMeta();
        tmMetadata.setName(
                StandaloneKubernetesUtils.getTaskManagerStatefulSetName(
                        cr.getMetadata().getName()));
        tmStatefulSet.setMetadata(tmMetadata);
        tmStatefulSet.setSpec(new StatefulSetSpecBuilder().withReplicas(1).build());
        kubernetesClient
                .apps()
                .statefulSets()
                .inNamespace(cr.getMetadata().getNamespace())
                .createOrReplace(tmStatefulSet);
    }
}
