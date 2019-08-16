package com.microsoft.jenkins.containeragents.aci;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.containerinstance.ContainerGroup;
import com.microsoft.azure.management.resources.Deployment;
import com.microsoft.azure.management.resources.DeploymentMode;
import com.microsoft.jenkins.containeragents.ContainerPlugin;
import com.microsoft.jenkins.containeragents.PodEnvVar;
import com.microsoft.jenkins.containeragents.aci.volumes.AzureFileVolume;
import com.microsoft.jenkins.containeragents.util.AzureContainerUtils;
import com.microsoft.jenkins.containeragents.util.Constants;
import com.microsoft.jenkins.containeragents.util.DockerRegistryUtils;
import hudson.EnvVars;
import hudson.security.ACL;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryEndpoint;


public final class AciService {

    private static final Logger LOGGER = Logger.getLogger(AciService.class.getName());

    private static final String DEPLOY_TEMPLATE_FILENAME
        = "/com/microsoft/jenkins/containeragents/aci/deployTemplate.json";

    private static final String DEPLOY_TEMPLATE_FILENAME_PRIVATE_IP
        = "/com/microsoft/jenkins/containeragents/aci/deployTemplatePrivateIp.json";

    public static void createDeployment(final AciCloud cloud,
        final AciContainerTemplate template,
        final AciAgent agent,
        final StopWatch stopWatch) throws Exception {
        String deployName = getDeploymentName(template);

        try (InputStream stream = AciService.class.getResourceAsStream(
            template.isPublicIp() ? DEPLOY_TEMPLATE_FILENAME
                : DEPLOY_TEMPLATE_FILENAME_PRIVATE_IP)) {
            final Azure azureClient = cloud.getAzureClient();

            final ObjectMapper mapper = new ObjectMapper();
            final JsonNode tmp = mapper.readTree(stream);

            ObjectNode variablesNode = ObjectNode.class.cast(tmp.get("variables"));

            variablesNode.put("containerName", agent.getNodeName());
            variablesNode.put("containerImage", template.getImage());
            variablesNode.put("osType", template.getOsType());
            variablesNode.put("cpu", template.getCpu());
            variablesNode.put("memory", template.getMemory());
            variablesNode.put("jenkinsInstance", Jenkins.getInstance().getLegacyInstanceId());
            variablesNode.put("networkName", template.getNetworkName());
            variablesNode.put("subNetName", template.getSubNetName());
            variablesNode.put("networkProfileName",
                "profile_" + template.getNetworkName() + "_" + template.getSubNetName());
            variablesNode.put("interfaceConfigName",
                "icn_" + template.getNetworkName() + "_" + template.getSubNetName());
            variablesNode.put("interfaceIpConfigName",
                "iicn_" + template.getNetworkName() + "_" + template.getSubNetName());

            JsonNode containerGroupResource = tmp.get("resources")
                .get(0);

            addCommandNode(mapper, template.getCommand(), agent, containerGroupResource);

            for (AciPort port : template.getPorts()) {
                if (StringUtils.isBlank(port.getPort())) {
                    continue;
                }
                addPortNode(mapper, port.getPort(), template.isPublicIp(), containerGroupResource);
            }
            if (template.getLaunchMethodType().equals(Constants.LAUNCH_METHOD_SSH)) {
                addPortNode(mapper, String.valueOf(template.getSshPort()), template.isPublicIp(),
                    containerGroupResource);
            }

            addEnvNode(mapper, template.getEnvVars(), containerGroupResource);

            for (DockerRegistryEndpoint registryEndpoint : template
                .getPrivateRegistryCredentials()) {
                addImageRegistryCredentialNode(mapper, registryEndpoint,
                    containerGroupResource);
            }

            for (AzureFileVolume volume : template.getVolumes()) {
                if (StringUtils.isBlank(volume.getMountPath())
                    || StringUtils.isBlank(volume.getShareName())
                    || StringUtils.isBlank(volume.getCredentialsId())) {
                    continue;
                }
                addAzureFileVolumeNode(mapper, volume, containerGroupResource);
            }

            azureClient.deployments()
                .define(deployName)
                .withExistingResourceGroup(cloud.getResourceGroup())
                .withTemplate(tmp.toString())
                .withParameters("{}")
                .withMode(DeploymentMode.INCREMENTAL)
                .beginCreate();

            //register deployName
            agent.setDeployName(deployName);

            //Wait deployment to success

            final int retryInterval = 10 * 1000;

            LOGGER.log(Level.INFO, "Waiting for deployment {0}", deployName);
            while (true) {
                if (AzureContainerUtils.isTimeout(template.getTimeout(), stopWatch.getTime())) {
                    throw new TimeoutException("Deployment timeout");
                }
                Deployment deployment
                    = azureClient.deployments()
                    .getByResourceGroup(cloud.getResourceGroup(), deployName);

                if (deployment.provisioningState().equalsIgnoreCase("succeeded")) {
                    LOGGER.log(Level.INFO, "Deployment {0} succeed", deployName);
                    break;
                } else if (deployment.provisioningState().equalsIgnoreCase("Failed")) {
                    throw new Exception(String.format("Deployment %s status: Failed", deployName));
                } else {
                    // If half of time passed, we need to inspect what happened from logs
                    if (AzureContainerUtils
                        .isHalfTimePassed(template.getTimeout(), stopWatch.getTime())) {
                        ContainerGroup containerGroup
                            = azureClient.containerGroups()
                            .getByResourceGroup(cloud.getResourceGroup(), agent.getNodeName());
                        if (containerGroup != null) {
                            LOGGER.log(Level.INFO, "Logs from container {0}: {1}",
                                new Object[]{agent.getNodeName(),
                                    containerGroup.getLogContent(agent.getNodeName())});
                        }
                    }
                    Thread.sleep(retryInterval);
                }
            }
        } catch (Exception e) {
            throw new Exception(e);
        }
    }

    private static void addPortNode(ObjectMapper mapper, String port,
        boolean publicIp, JsonNode containerGroupResource) {
        JsonNode propertiesNode = containerGroupResource.get("properties");
        ArrayNode containerPortsNodes = ArrayNode.class.cast(propertiesNode.get("containers")
            .get(0).get("properties").get("ports"));

        ObjectNode newContainerPortNode = mapper.createObjectNode();
        newContainerPortNode.put("port", port);
        containerPortsNodes.add(newContainerPortNode);

        if (publicIp) {
            ArrayNode ipPortsNodes = ArrayNode.class
                .cast(propertiesNode.get("ipAddress").get("ports"));

            ObjectNode newIpPortNode = mapper.createObjectNode();
            newIpPortNode.put("protocol", "tcp");
            newIpPortNode.put("port", port);
            ipPortsNodes.add(newIpPortNode);
        }
    }

    private static void addCommandNode(ObjectMapper mapper, String[] commands,
        JsonNode containerGroupResource) {
        ArrayNode commandNode = ArrayNode.class.cast(containerGroupResource
            .get("properties").get("containers").get(0)
            .get("properties").get("command"));

        for (String command : commands) {
            commandNode.add(command);
        }
    }

    private static void addCommandNode(ObjectMapper mapper, String command,
        AciAgent agent, JsonNode containerGroupResource) {
        if (StringUtils.isBlank(command)) {
            return;
        }
        String replaceCommand = commandReplace(command, agent);
        addCommandNode(mapper, StringUtils.split(replaceCommand, ' '), containerGroupResource);
    }

    private static void addImageRegistryCredentialNode(ObjectMapper mapper,
        DockerRegistryEndpoint endpoint, JsonNode containerGroupResource) throws IOException {
        if (StringUtils.isBlank(endpoint.getCredentialsId())) {
            return;
        }
        StandardUsernamePasswordCredentials credentials = CredentialsMatchers.firstOrNull(
            CredentialsProvider.lookupCredentials(
                StandardUsernamePasswordCredentials.class,
                Jenkins.getInstance(),
                ACL.SYSTEM,
                Collections.<DomainRequirement>emptyList()),
            CredentialsMatchers.withId(endpoint.getCredentialsId()));
        if (credentials == null) {
            return;
        }
        ArrayNode credentialNode = ArrayNode.class
            .cast(containerGroupResource.get("properties").get("imageRegistryCredentials"));
        ObjectNode newCredentialNode = mapper.createObjectNode();
        newCredentialNode.put("server", StringUtils.isBlank(endpoint.getUrl())
            ? "index.docker.io"
            : DockerRegistryUtils.formatUrlToWithoutProtocol(endpoint.getUrl()));
        newCredentialNode.put("username", credentials.getUsername());
        newCredentialNode.put("password", credentials.getPassword().getPlainText());

        credentialNode.add(newCredentialNode);
    }

    private static void addEnvNode(ObjectMapper mapper, List<PodEnvVar> envVars,
        JsonNode containerGroupResource) {

        ArrayNode envVarNode = ArrayNode.class.cast(containerGroupResource
            .get("properties").get("containers").get(0).get("properties")
            .get("environmentVariables"));

        for (PodEnvVar envVar : envVars) {
            if (StringUtils.isBlank(envVar.getKey())) {
                continue;
            }
            ObjectNode newCredentialNode = mapper.createObjectNode();
            newCredentialNode.put("name", envVar.getKey());
            newCredentialNode.put("value", envVar.getValue());
            envVarNode.add(newCredentialNode);
        }
    }

    private static void addAzureFileVolumeNode(ObjectMapper mapper,
        AzureFileVolume volume, JsonNode containerGroupResource) {
        ArrayNode volumeMountsNode = ArrayNode.class.cast(containerGroupResource
            .get("properties").get("containers").get(0).get("properties").get("volumeMounts"));
        ArrayNode volumesNode = ArrayNode.class
            .cast(containerGroupResource.get("properties").get("volumes"));

        ObjectNode newVolumeMountsNode = mapper.createObjectNode();
        String volumeName = AzureContainerUtils
            .generateName("volume", Constants.ACI_VOLUME_NAME_LENGTH);
        newVolumeMountsNode.put("name", volumeName);
        newVolumeMountsNode.put("mountPath", volume.getMountPath());

        volumeMountsNode.add(newVolumeMountsNode);

        ObjectNode newAzureFileNode = mapper.createObjectNode();
        newAzureFileNode.put("shareName", volume.getShareName());
        newAzureFileNode.put("storageAccountName", volume.getStorageAccountName());
        newAzureFileNode.put("storageAccountKey", volume.getStorageAccountKey());

        ObjectNode newVolumesNode = mapper.createObjectNode();
        newVolumesNode.put("name", volumeName);
        newVolumesNode.set("azureFile", newAzureFileNode);

        volumesNode.add(newVolumesNode);
    }

    private static String commandReplace(String command, AciAgent agent) {
        String serverUrl = Jenkins.getInstance().getRootUrl();
        String nodeName = agent.getNodeName();
        String secret = agent.getComputer().getJnlpMac();
        EnvVars arguments = new EnvVars("rootUrl", serverUrl, "nodeName", nodeName, "secret",
            secret);
        return arguments.expand(command);
    }

    private static String getDeploymentName(AciContainerTemplate template) {
        return AzureContainerUtils
            .generateName(template.getName(), Constants.ACI_DEPLOYMENT_RANDOM_NAME_LENGTH);
    }

    public static void deleteAciContainerGroup(String credentialsId,
        String resourceGroup,
        String containerGroupName,
        String deployName) {
        Azure azureClient = null;
        final Map<String, String> properties = new HashMap<>();

        try {
            azureClient = AzureContainerUtils.getAzureClient(credentialsId);
            azureClient.containerGroups().deleteByResourceGroup(resourceGroup, containerGroupName);
            LOGGER.log(Level.INFO, "Delete ACI Container Group: {0} successfully",
                containerGroupName);

            properties.put(Constants.AI_ACI_NAME, containerGroupName);
            ContainerPlugin.sendEvent(Constants.AI_ACI_AGENT, "Deleted", properties);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Delete ACI Container Group: {0} failed: {1}",
                new Object[]{containerGroupName, e});

            properties.put("Message", e.getMessage());
            ContainerPlugin.sendEvent(Constants.AI_ACI_AGENT, "DeletedFailed", properties);
        }

        try {
            //To avoid to many deployments. May over deployment limits.
            properties.clear();
            if (deployName != null) {
                // Only to delete succeeded deployments for future debugging.
                if (azureClient.deployments().getByResourceGroup(resourceGroup, deployName)
                    .provisioningState()
                    .equalsIgnoreCase("succeeded")) {
                    azureClient.deployments().deleteByResourceGroup(resourceGroup, deployName);
                    LOGGER.log(Level.INFO, "Delete ACI deployment: {0} successfully", deployName);
                    properties.put(Constants.AI_ACI_NAME, containerGroupName);
                    properties.put(Constants.AI_ACI_DEPLOYMENT_NAME, deployName);
                    ContainerPlugin
                        .sendEvent(Constants.AI_ACI_AGENT, "DeploymentDeleted", properties);
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Delete ACI deployment: {0} failed: {1}",
                new Object[]{deployName, e});
            properties.put(Constants.AI_ACI_NAME, containerGroupName);
            properties.put(Constants.AI_ACI_DEPLOYMENT_NAME, deployName);
            properties.put("Message", e.getMessage());
            ContainerPlugin
                .sendEvent(Constants.AI_ACI_AGENT, "DeploymentDeletedFailed", properties);
        }
    }

    private AciService() {
        //
    }
}
