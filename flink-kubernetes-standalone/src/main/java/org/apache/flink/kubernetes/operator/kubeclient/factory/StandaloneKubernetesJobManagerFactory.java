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

package org.apache.flink.kubernetes.operator.kubeclient.factory;

import org.apache.flink.kubernetes.kubeclient.FlinkPod;
import org.apache.flink.kubernetes.kubeclient.decorators.EnvSecretsDecorator;
import org.apache.flink.kubernetes.kubeclient.decorators.ExternalServiceDecorator;
import org.apache.flink.kubernetes.kubeclient.decorators.FlinkConfMountDecorator;
import org.apache.flink.kubernetes.kubeclient.decorators.HadoopConfMountDecorator;
import org.apache.flink.kubernetes.kubeclient.decorators.InitJobManagerDecorator;
import org.apache.flink.kubernetes.kubeclient.decorators.InternalServiceDecorator;
import org.apache.flink.kubernetes.kubeclient.decorators.KerberosMountDecorator;
import org.apache.flink.kubernetes.kubeclient.decorators.KubernetesStepDecorator;
import org.apache.flink.kubernetes.kubeclient.decorators.MountSecretsDecorator;
import org.apache.flink.kubernetes.kubeclient.parameters.KubernetesJobManagerParameters;
import org.apache.flink.kubernetes.kubeclient.resources.KubernetesOwnerReference;
import org.apache.flink.kubernetes.operator.kubeclient.StandaloneKubernetesJobManagerSpecification;
import org.apache.flink.kubernetes.operator.kubeclient.decorators.CmdStandaloneJobManagerDecorator;
import org.apache.flink.kubernetes.operator.kubeclient.decorators.UserLibMountDecorator;
import org.apache.flink.kubernetes.operator.kubeclient.parameters.StandaloneKubernetesJobManagerParameters;
import org.apache.flink.kubernetes.operator.utils.StandaloneKubernetesUtils;
import org.apache.flink.kubernetes.utils.Constants;
import org.apache.flink.util.Preconditions;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Utility class for constructing all the Kubernetes for the JobManager deploying in standalone
 * mode. This can include the StatefulSet, the ConfigMap(s), and the Service(s).
 */
public class StandaloneKubernetesJobManagerFactory {
    public static StandaloneKubernetesJobManagerSpecification
            buildKubernetesJobManagerSpecification(
                    FlinkPod podTemplate,
                    List<PersistentVolumeClaim> volumeClaims,
                    StandaloneKubernetesJobManagerParameters kubernetesJobManagerParameters)
                    throws IOException {
        FlinkPod flinkPod = Preconditions.checkNotNull(podTemplate).copy();
        List<HasMetadata> accompanyingResources = new ArrayList<>();

        final KubernetesStepDecorator[] stepDecorators =
                new KubernetesStepDecorator[] {
                    new InitJobManagerDecorator(kubernetesJobManagerParameters),
                    new EnvSecretsDecorator(kubernetesJobManagerParameters),
                    new MountSecretsDecorator(kubernetesJobManagerParameters),
                    new CmdStandaloneJobManagerDecorator(kubernetesJobManagerParameters),
                    new InternalServiceDecorator(kubernetesJobManagerParameters),
                    new ExternalServiceDecorator(kubernetesJobManagerParameters),
                    new HadoopConfMountDecorator(kubernetesJobManagerParameters),
                    new KerberosMountDecorator(kubernetesJobManagerParameters),
                    new FlinkConfMountDecorator(kubernetesJobManagerParameters),
                    new UserLibMountDecorator(kubernetesJobManagerParameters),
                };

        for (KubernetesStepDecorator stepDecorator : stepDecorators) {
            flinkPod = stepDecorator.decorateFlinkPod(flinkPod);
            accompanyingResources.addAll(stepDecorator.buildAccompanyingKubernetesResources());
        }

        final StatefulSet statefulSet =
                createJobManagerStatefulSet(flinkPod, volumeClaims, kubernetesJobManagerParameters);
        return new StandaloneKubernetesJobManagerSpecification(statefulSet, accompanyingResources);
    }

    private static StatefulSet createJobManagerStatefulSet(
            FlinkPod flinkPod,
            List<PersistentVolumeClaim> volumeClaims,
            KubernetesJobManagerParameters kubernetesJobManagerParameters) {
        final Container resolvedMainContainer = flinkPod.getMainContainer();
        final Pod resolvedPod =
                new PodBuilder(flinkPod.getPodWithoutMainContainer())
                        .editOrNewSpec()
                        .addToContainers(resolvedMainContainer)
                        .endSpec()
                        .build();

        StatefulSetBuilder statefulSetBuilder =
                new StatefulSetBuilder()
                        .withApiVersion(Constants.APPS_API_VERSION)
                        .editOrNewMetadata()
                        .withName(
                                StandaloneKubernetesUtils.getJobManagerStatefulSetName(
                                        kubernetesJobManagerParameters.getClusterId()))
                        .withAnnotations(kubernetesJobManagerParameters.getAnnotations())
                        .withLabels(kubernetesJobManagerParameters.getLabels())
                        .withOwnerReferences(
                                kubernetesJobManagerParameters.getOwnerReference().stream()
                                        .map(
                                                e ->
                                                        KubernetesOwnerReference.fromMap(e)
                                                                .getInternalResource())
                                        .collect(Collectors.toList()))
                        .endMetadata()
                        .editOrNewSpec()
                        .withServiceName(kubernetesJobManagerParameters.getClusterId())
                        .withReplicas(kubernetesJobManagerParameters.getReplicas())
                        .editOrNewTemplate()
                        .withMetadata(resolvedPod.getMetadata())
                        .withSpec(resolvedPod.getSpec())
                        .endTemplate()
                        .editOrNewSelector()
                        .addToMatchLabels(kubernetesJobManagerParameters.getSelectors())
                        .endSelector()
                        .endSpec();
        if (null != volumeClaims) {
            return statefulSetBuilder
                    .editSpec()
                    .withVolumeClaimTemplates(volumeClaims)
                    .endSpec()
                    .build();
        } else {
            return statefulSetBuilder.build();
        }
    }
}
