package com.microsoft.jenkins.containeragents.aci;

import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.containerinstance.ContainerGroup;
import com.microsoft.azure.util.AzureCredentials;
import com.microsoft.jenkins.azurecommons.telemetry.AppInsightsConstants;
import com.microsoft.jenkins.containeragents.ContainerPlugin;
import com.microsoft.jenkins.containeragents.PodEnvVar;
import com.microsoft.jenkins.containeragents.aci.volumes.AzureFileVolume;
import com.microsoft.jenkins.containeragents.strategy.ProvisionRetryStrategy;
import com.microsoft.jenkins.containeragents.util.AzureContainerUtils;
import com.microsoft.jenkins.containeragents.util.Constants;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.Cloud;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.NodeProvisioner;
import hudson.util.ListBoxModel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.apache.commons.lang3.time.StopWatch;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryEndpoint;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;


public class AciCloud extends Cloud {

    private static final Logger LOGGER = Logger.getLogger(AciCloud.class.getName());

    private String credentialsId;

    private String resourceGroup;

    private List<AciContainerTemplate> templates;

    private final List<DockerRegistryEndpoint> defaultPrivateRegistryCredentials;

    private final List<PodEnvVar> defaultEnvVars;

    private final List<AzureFileVolume> defaultVolumes;


    private static ExecutorService threadPool;

    private transient ProvisionRetryStrategy provisionRetryStrategy = new ProvisionRetryStrategy();

    @DataBoundConstructor
    public AciCloud(String name,
        String credentialsId,
        String resourceGroup,
        List<AciContainerTemplate> templates,
        List<DockerRegistryEndpoint> defaultPrivateRegistryCredentials,
        List<PodEnvVar> defaultEnvVars,
        List<AzureFileVolume> defaultVolumes) {
        super(name);
        this.credentialsId = credentialsId;
        this.resourceGroup = resourceGroup;
        this.templates = templates;
        this.defaultPrivateRegistryCredentials = defaultPrivateRegistryCredentials;
        this.defaultEnvVars = defaultEnvVars;
        this.defaultVolumes = defaultVolumes;
    }

    public Azure getAzureClient() throws Exception {
        return AzureContainerUtils.getAzureClient(credentialsId);
    }

    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(Label label, int excessWorkload) {
        try {
            LOGGER.log(Level.INFO, "Start ACI container for label {0} workLoad {1}",
                new Object[]{label, excessWorkload});
            List<NodeProvisioner.PlannedNode> r = new ArrayList<>();
            final AciContainerTemplate template = getFirstTemplate(label);
            LOGGER.log(Level.INFO, "Using ACI Container template: {0}", template.getName());
            for (int i = 1; i <= excessWorkload; i++) {
                r.add(new NodeProvisioner.PlannedNode(template.getName(),
                    Computer.threadPoolForRemoting.submit(
                        new Callable<Node>() {

                            @Override
                            public Node call() throws Exception {
                                AciAgent agent = null;
                                final Map<String, String> properties = new HashMap<>();

                                try {
                                    agent = new AciAgent(AciCloud.this, template);

                                    LOGGER
                                        .log(Level.INFO, "Add ACI node: {0}", agent.getNodeName());
                                    Jenkins.getInstance().addNode(agent);

                                    //start a timeWatcher
                                    StopWatch stopWatch = new StopWatch();
                                    stopWatch.start();

                                    //BI properties
                                    properties.put(AppInsightsConstants.AZURE_SUBSCRIPTION_ID,
                                        AzureCredentials.getServicePrincipal(credentialsId)
                                            .getSubscriptionId());
                                    properties.put(Constants.AI_ACI_NAME, agent.getNodeName());
                                    properties.put(Constants.AI_ACI_CPU_CORE, template.getCpu());

                                    //Deploy ACI and wait
                                    template.provisionAgents(AciCloud.this, agent, stopWatch);

                                    if (template.getLaunchMethodType()
                                        .equals(Constants.LAUNCH_METHOD_JNLP)) {
                                        //wait JNLP to online
                                        waitToOnline(agent, template.getTimeout(), stopWatch);
                                    } else {
                                        addHost(agent);
                                        Computer computer = agent.toComputer();
                                        if (computer == null) {
                                            throw new IllegalStateException(
                                                "Agent node has been deleted");
                                        }
                                        computer.connect(false).get();
                                    }

                                    addIpEnv(agent);

                                    provisionRetryStrategy.success(template.getName());

                                    //Send BI
                                    ContainerPlugin
                                        .sendEvent(Constants.AI_ACI_AGENT, "Provision", properties);

                                    return agent;
                                } catch (Exception e) {
                                    LOGGER.log(Level.WARNING,
                                        "AciCloud: Provision agent {0} failed: {1}",
                                        new Object[]{agent == null ? "Known agent node"
                                            : agent.getNodeName(),
                                            e.getMessage()});

                                    properties.put("Message", e.getMessage());
                                    ContainerPlugin
                                        .sendEvent(Constants.AI_ACI_AGENT, "ProvisionFailed",
                                            properties);

                                    if (agent != null) {
                                        agent.terminate();
                                    }

                                    provisionRetryStrategy.failure(template.getName());

                                    throw new Exception(e);
                                }
                            }
                        }
                    ), 1));
            }

            return r;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, e.toString());

        }
        return Collections.emptyList();
    }

    @Override
    public boolean canProvision(Label label) {
        AciContainerTemplate template = getFirstTemplate(label);
        if (template == null) {
            return false;
        }
        if (!provisionRetryStrategy.isEnabled(template.getName())) {
            LOGGER.log(Level.WARNING,
                "Cannot provision: template for label {0} is not available now, "
                    + "because it failed to provision last time. ", label);
            return false;
        }
        return true;
    }

    public AciContainerTemplate getFirstTemplate(Label label) {
        for (AciContainerTemplate template : templates) {
            if (label == null || label.matches(template.getLabelSet())) {
                return template;
            }
        }
        return null;
    }

    public void addIpEnv(AciAgent agent) throws Exception {
        Azure azureClient = getAzureClient();

        String ip = azureClient.containerGroups()
            .getByResourceGroup(resourceGroup, agent.getNodeName()).ipAddress();

        EnvironmentVariablesNodeProperty ipEnv = new EnvironmentVariablesNodeProperty(
            new EnvironmentVariablesNodeProperty.Entry("IP", ip)
        );

        agent.getNodeProperties().add(ipEnv);
        agent.save();
    }

    public void addHost(AciAgent agent) throws Exception {
        Azure azureClient = getAzureClient();

        String ip = azureClient.containerGroups()
            .getByResourceGroup(resourceGroup, agent.getNodeName()).ipAddress();

        agent.setHost(ip);
        agent.save();
    }

    private void waitToOnline(AciAgent agent, int startupTimeout, StopWatch stopWatch)
        throws Exception {
        LOGGER.log(Level.INFO, "Waiting agent {0} to online", agent.getNodeName());
        Azure azureClient = getAzureClient();

        while (true) {
            if (AzureContainerUtils.isTimeout(startupTimeout, stopWatch.getTime())) {
                throw new TimeoutException("ACI container connection timeout");
            }

            Computer computer = agent.toComputer();
            if (computer == null) {
                throw new IllegalStateException("Agent node has been deleted");
            }
            ContainerGroup containerGroup =
                azureClient.containerGroups()
                    .getByResourceGroup(resourceGroup, agent.getNodeName());

            if (containerGroup.containers().containsKey(agent.getNodeName())
                && containerGroup.containers().get(agent.getNodeName()).instanceView()
                .currentState().state()
                .equals("Terminated")) {
                LOGGER.log(Level.WARNING, "Logs from container {0}: {1}",
                    new Object[]{agent.getNodeName(),
                        containerGroup.getLogContent(agent.getNodeName())});
                throw new IllegalStateException("ACI container terminated");
            }

            if (computer.isOnline()) {
                break;
            }
            final int retryInterval = 5 * 1000;
            Thread.sleep(retryInterval);
        }
    }

    public String getName() {
        return name;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public String getResourceGroup() {
        return resourceGroup;
    }

    public List<AciContainerTemplate> getTemplates() {
        return templates;
    }

    public List<DockerRegistryEndpoint> getDefaultPrivateRegistryCredentials() {
        return defaultPrivateRegistryCredentials;
    }

    public List<PodEnvVar> getDefaultEnvVars() {
        return defaultEnvVars;
    }

    public List<AzureFileVolume> getDefaultVolumes() {
        return defaultVolumes;
    }

    public static synchronized ExecutorService getThreadPool() {
        if (AciCloud.threadPool == null) {
            AciCloud.threadPool = Executors.newCachedThreadPool();
        }
        return AciCloud.threadPool;
    }

    private Object readResolve() {
        this.provisionRetryStrategy = new ProvisionRetryStrategy();
        return this;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {

        @Override
        public String getDisplayName() {
            return "Azure Container Instance";
        }

        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item owner) {
            return AzureContainerUtils.listCredentialsIdItems(owner);
        }

        public ListBoxModel doFillResourceGroupItems(@QueryParameter String credentialsId)
            throws IOException {
            return AzureContainerUtils.listResourceGroupItems(credentialsId);
        }
    }
}
