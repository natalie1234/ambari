/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ambari.server.controller;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.Role;
import org.apache.ambari.server.RoleCommand;
import org.apache.ambari.server.actionmanager.ActionManager;
import org.apache.ambari.server.actionmanager.RequestFactory;
import org.apache.ambari.server.actionmanager.Stage;
import org.apache.ambari.server.actionmanager.StageFactory;
import org.apache.ambari.server.api.services.AmbariMetaInfo;
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.controller.internal.ArtifactResourceProvider;
import org.apache.ambari.server.controller.internal.RequestImpl;
import org.apache.ambari.server.controller.internal.RequestResourceFilter;
import org.apache.ambari.server.controller.internal.RequestStageContainer;
import org.apache.ambari.server.controller.spi.ClusterController;
import org.apache.ambari.server.controller.spi.NoSuchParentResourceException;
import org.apache.ambari.server.controller.spi.NoSuchResourceException;
import org.apache.ambari.server.controller.spi.Predicate;
import org.apache.ambari.server.controller.spi.Request;
import org.apache.ambari.server.controller.spi.Resource;
import org.apache.ambari.server.controller.spi.ResourceProvider;
import org.apache.ambari.server.controller.spi.SystemException;
import org.apache.ambari.server.controller.spi.UnsupportedPropertyException;
import org.apache.ambari.server.controller.utilities.ClusterControllerHelper;
import org.apache.ambari.server.controller.utilities.PredicateBuilder;
import org.apache.ambari.server.metadata.RoleCommandOrder;
import org.apache.ambari.server.serveraction.ServerAction;
import org.apache.ambari.server.serveraction.kerberos.CreateKeytabFilesServerAction;
import org.apache.ambari.server.serveraction.kerberos.CreatePrincipalsServerAction;
import org.apache.ambari.server.serveraction.kerberos.DestroyPrincipalsServerAction;
import org.apache.ambari.server.serveraction.kerberos.FinalizeKerberosServerAction;
import org.apache.ambari.server.serveraction.kerberos.KDCType;
import org.apache.ambari.server.serveraction.kerberos.KerberosIdentityDataFileWriter;
import org.apache.ambari.server.serveraction.kerberos.KerberosAdminAuthenticationException;
import org.apache.ambari.server.serveraction.kerberos.KerberosCredential;
import org.apache.ambari.server.serveraction.kerberos.KerberosIdentityDataFileWriterFactory;
import org.apache.ambari.server.serveraction.kerberos.KerberosInvalidConfigurationException;
import org.apache.ambari.server.serveraction.kerberos.KerberosKDCConnectionException;
import org.apache.ambari.server.serveraction.kerberos.KerberosLDAPContainerException;
import org.apache.ambari.server.serveraction.kerberos.KerberosMissingAdminCredentialsException;
import org.apache.ambari.server.serveraction.kerberos.KerberosOperationException;
import org.apache.ambari.server.serveraction.kerberos.KerberosOperationHandler;
import org.apache.ambari.server.serveraction.kerberos.KerberosOperationHandlerFactory;
import org.apache.ambari.server.serveraction.kerberos.KerberosRealmException;
import org.apache.ambari.server.serveraction.kerberos.KerberosServerAction;
import org.apache.ambari.server.serveraction.kerberos.PrepareDisableKerberosServerAction;
import org.apache.ambari.server.serveraction.kerberos.PrepareEnableKerberosServerAction;
import org.apache.ambari.server.serveraction.kerberos.PrepareKerberosIdentitiesServerAction;
import org.apache.ambari.server.serveraction.kerberos.UpdateKerberosConfigsServerAction;
import org.apache.ambari.server.serveraction.kerberos.CleanupServerAction;
import org.apache.ambari.server.stageplanner.RoleGraph;
import org.apache.ambari.server.stageplanner.RoleGraphFactory;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.Config;
import org.apache.ambari.server.state.ConfigHelper;
import org.apache.ambari.server.state.Host;
import org.apache.ambari.server.state.HostState;
import org.apache.ambari.server.state.SecurityState;
import org.apache.ambari.server.state.SecurityType;
import org.apache.ambari.server.state.Service;
import org.apache.ambari.server.state.ServiceComponent;
import org.apache.ambari.server.state.ServiceComponentHost;
import org.apache.ambari.server.state.StackId;
import org.apache.ambari.server.state.State;
import org.apache.ambari.server.state.kerberos.KerberosComponentDescriptor;
import org.apache.ambari.server.state.kerberos.KerberosConfigurationDescriptor;
import org.apache.ambari.server.state.kerberos.KerberosDescriptor;
import org.apache.ambari.server.state.kerberos.KerberosDescriptorFactory;
import org.apache.ambari.server.state.kerberos.KerberosIdentityDescriptor;
import org.apache.ambari.server.state.kerberos.KerberosKeytabDescriptor;
import org.apache.ambari.server.state.kerberos.KerberosPrincipalDescriptor;
import org.apache.ambari.server.state.kerberos.KerberosServiceDescriptor;
import org.apache.ambari.server.state.kerberos.VariableReplacementHelper;
import org.apache.ambari.server.state.svccomphost.ServiceComponentHostServerActionEvent;
import org.apache.ambari.server.utils.StageUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

@Singleton
public class KerberosHelperImpl implements KerberosHelper {

  private static final String BASE_LOG_DIR = "/tmp/ambari";

  private static final Logger LOG = LoggerFactory.getLogger(KerberosHelperImpl.class);

  /**
   * name of the property used to hold the service check identifier value, used when creating and
   * destroying the (unique) service check identity.
   */
  private static final String SERVICE_CHECK_IDENTIFIER = "_kerberos_internal_service_check_identifier";

  @Inject
  private AmbariCustomCommandExecutionHelper customCommandExecutionHelper;

  @Inject
  private AmbariManagementController ambariManagementController;

  @Inject
  private AmbariMetaInfo ambariMetaInfo;

  @Inject
  private ActionManager actionManager;

  @Inject
  private RequestFactory requestFactory;

  @Inject
  private StageFactory stageFactory;

  @Inject
  private RoleGraphFactory roleGraphFactory;

  @Inject
  private Clusters clusters;

  @Inject
  private ConfigHelper configHelper;

  @Inject
  private VariableReplacementHelper variableReplacementHelper;

  @Inject
  private Configuration configuration;

  @Inject
  private KerberosOperationHandlerFactory kerberosOperationHandlerFactory;

  @Inject
  private KerberosDescriptorFactory kerberosDescriptorFactory;

  @Inject
  private KerberosIdentityDataFileWriterFactory kerberosIdentityDataFileWriterFactory;

  /**
   * Used to get kerberos descriptors associated with the cluster or stack.
   * Currently not available via injection.
   */
  private static ClusterController clusterController = null;

  @Override
  public RequestStageContainer toggleKerberos(Cluster cluster, SecurityType securityType,
                                              RequestStageContainer requestStageContainer,
                                              Boolean manageIdentities)
      throws AmbariException, KerberosOperationException {

    KerberosDetails kerberosDetails = getKerberosDetails(cluster, manageIdentities);

    // Update KerberosDetails with the new security type - the current one in the cluster is the "old" value
    kerberosDetails.setSecurityType(securityType);

    if (securityType == SecurityType.KERBEROS) {
      LOG.info("Configuring Kerberos for realm {} on cluster, {}", kerberosDetails.getDefaultRealm(), cluster.getClusterName());
      requestStageContainer = handle(cluster, kerberosDetails, null, null, null, requestStageContainer, new EnableKerberosHandler());
    } else if (securityType == SecurityType.NONE) {
      LOG.info("Disabling Kerberos from cluster, {}", cluster.getClusterName());
      requestStageContainer = handle(cluster, kerberosDetails, null, null, null, requestStageContainer, new DisableKerberosHandler());
    } else {
      throw new AmbariException(String.format("Unexpected security type value: %s", securityType.name()));
    }

    return requestStageContainer;
  }

  @Override
  public RequestStageContainer executeCustomOperations(Cluster cluster, Map<String, String> requestProperties,
                                                       RequestStageContainer requestStageContainer,
                                                       Boolean manageIdentities)
      throws AmbariException, KerberosOperationException {

    if (requestProperties != null) {

      for (SupportedCustomOperation operation : SupportedCustomOperation.values()) {
        if (requestProperties.containsKey(operation.name().toLowerCase())) {
          String value = requestProperties.get(operation.name().toLowerCase());

          // The operation specific logic is kept in one place and described here
          switch (operation) {
            case REGENERATE_KEYTABS:
              if (cluster.getSecurityType() != SecurityType.KERBEROS) {
                throw new AmbariException(String.format("Custom operation %s can only be requested with the security type cluster property: %s", operation.name(), SecurityType.KERBEROS.name()));
              }

              if ("true".equalsIgnoreCase(value) || "all".equalsIgnoreCase(value)) {
                requestStageContainer = handle(cluster, getKerberosDetails(cluster, manageIdentities), null, null, null,
                    requestStageContainer, new CreatePrincipalsAndKeytabsHandler(true));
              } else if ("missing".equalsIgnoreCase(value)) {
                requestStageContainer = handle(cluster, getKerberosDetails(cluster, manageIdentities), null, null, null,
                    requestStageContainer, new CreatePrincipalsAndKeytabsHandler(false));
              } else {
                throw new AmbariException(String.format("Unexpected directive value: %s", value));
              }

              break;

            default: // No other operations are currently supported
              throw new AmbariException(String.format("Custom operation not supported: %s", operation.name()));
          }
        }
      }
    }

    return requestStageContainer;
  }


  @Override
  public RequestStageContainer ensureIdentities(Cluster cluster, Map<String, ? extends Collection<String>> serviceComponentFilter,
                                                Collection<String> identityFilter, Set<String> hostsToForceKerberosOperations,
                                                RequestStageContainer requestStageContainer, Boolean manageIdentities)
      throws AmbariException, KerberosOperationException {
    return handle(cluster, getKerberosDetails(cluster, manageIdentities), serviceComponentFilter, identityFilter,
        hostsToForceKerberosOperations, requestStageContainer, new CreatePrincipalsAndKeytabsHandler(false));
  }

  @Override
  public RequestStageContainer deleteIdentities(Cluster cluster, Map<String, ? extends Collection<String>> serviceComponentFilter,
                                                Collection<String> identityFilter, RequestStageContainer requestStageContainer,
                                                Boolean manageIdentities)
      throws AmbariException, KerberosOperationException {
    return handle(cluster, getKerberosDetails(cluster, manageIdentities), serviceComponentFilter, identityFilter, null,
        requestStageContainer, new DeletePrincipalsAndKeytabsHandler());
  }

  @Override
  public void configureService(Cluster cluster, ServiceComponentHost serviceComponentHost)
      throws AmbariException, KerberosInvalidConfigurationException {

    KerberosDetails kerberosDetails = getKerberosDetails(cluster, null);

    // Set properties...
    String serviceName = serviceComponentHost.getServiceName();
    KerberosDescriptor kerberosDescriptor = getKerberosDescriptor(cluster);
    KerberosServiceDescriptor serviceDescriptor = kerberosDescriptor.getService(serviceName);

    if (serviceDescriptor != null) {
      Map<String, String> kerberosDescriptorProperties = kerberosDescriptor.getProperties();
      Map<String, Map<String, String>> kerberosConfigurations = new HashMap<String, Map<String, String>>();
      Map<String, Map<String, String>> configurations = calculateConfigurations(cluster,
          serviceComponentHost.getHostName(), kerberosDescriptorProperties);

      Map<String, KerberosComponentDescriptor> componentDescriptors = serviceDescriptor.getComponents();
      for (KerberosComponentDescriptor componentDescriptor : componentDescriptors.values()) {
        if (componentDescriptor != null) {
          Map<String, Map<String, String>> identityConfigurations;
          List<KerberosIdentityDescriptor> identities;

          identities = serviceDescriptor.getIdentities(true);
          identityConfigurations = getConfigurations(identities);
          if (identityConfigurations != null) {
            for (Map.Entry<String, Map<String, String>> entry : identityConfigurations.entrySet()) {
              mergeConfigurations(kerberosConfigurations, entry.getKey(), entry.getValue(), configurations);
            }
          }

          identities = componentDescriptor.getIdentities(true);
          identityConfigurations = getConfigurations(identities);
          if (identityConfigurations != null) {
            for (Map.Entry<String, Map<String, String>> entry : identityConfigurations.entrySet()) {
              mergeConfigurations(kerberosConfigurations, entry.getKey(), entry.getValue(), configurations);
            }
          }

          mergeConfigurations(kerberosConfigurations,
              componentDescriptor.getConfigurations(true), configurations);
        }
      }

      setAuthToLocalRules(kerberosDescriptor, cluster, kerberosDetails.getDefaultRealm(), configurations, kerberosConfigurations);

      for (Map.Entry<String, Map<String, String>> entry : kerberosConfigurations.entrySet()) {
        configHelper.updateConfigType(cluster, ambariManagementController, entry.getKey(), entry.getValue(), null,
            ambariManagementController.getAuthName(), String.format("Enabling Kerberos for %s", serviceName));
      }
    }
  }

  @Override
  public RequestStageContainer createTestIdentity(Cluster cluster, Map<String, String> commandParamsStage,
                                                  RequestStageContainer requestStageContainer)
      throws KerberosOperationException, AmbariException {
    return handleTestIdentity(cluster, getKerberosDetails(cluster, null), commandParamsStage, requestStageContainer, new CreatePrincipalsAndKeytabsHandler(false));
  }

  @Override
  public RequestStageContainer deleteTestIdentity(Cluster cluster, Map<String, String> commandParamsStage,
                                                  RequestStageContainer requestStageContainer)
      throws KerberosOperationException, AmbariException {
    requestStageContainer = handleTestIdentity(cluster, getKerberosDetails(cluster, null), commandParamsStage, requestStageContainer, new DeletePrincipalsAndKeytabsHandler());

    // Clear the Kerberos service check identifier
    setKerberosServiceCheckIdentifier(cluster, null);

    return requestStageContainer;
  }

  @Override
  public void validateKDCCredentials(Cluster cluster) throws KerberosMissingAdminCredentialsException,
      KerberosAdminAuthenticationException,
      KerberosInvalidConfigurationException,
      AmbariException {
    validateKDCCredentials(null, cluster);
  }

  @Override
  public void setAuthToLocalRules(KerberosDescriptor kerberosDescriptor, Cluster cluster, String realm,
                                  Map<String, Map<String, String>> existingConfigurations,
                                  Map<String, Map<String, String>> kerberosConfigurations)
      throws AmbariException {

    if (kerberosDescriptor != null) {

      Set<String> authToLocalProperties;
      Set<String> authToLocalPropertiesToSet = new HashSet<String>();

      // Determine which properties need to be set
      AuthToLocalBuilder authToLocalBuilder = new AuthToLocalBuilder();

      addIdentities(authToLocalBuilder, kerberosDescriptor.getIdentities(), null, existingConfigurations);

      authToLocalProperties = kerberosDescriptor.getAuthToLocalProperties();
      if (authToLocalProperties != null) {
        authToLocalPropertiesToSet.addAll(authToLocalProperties);
      }

      Map<String, KerberosServiceDescriptor> services = kerberosDescriptor.getServices();
      if (services != null) {
        Map<String, Service> installedServices = cluster.getServices();

        for (KerberosServiceDescriptor service : services.values()) {
          if (installedServices.containsKey(service.getName())) {

            addIdentities(authToLocalBuilder, service.getIdentities(true), null, existingConfigurations);

            authToLocalProperties = service.getAuthToLocalProperties();
            if (authToLocalProperties != null) {
              authToLocalPropertiesToSet.addAll(authToLocalProperties);
            }

            Map<String, KerberosComponentDescriptor> components = service.getComponents();
            if (components != null) {
              for (KerberosComponentDescriptor component : components.values()) {
                addIdentities(authToLocalBuilder, component.getIdentities(true), null, existingConfigurations);

                authToLocalProperties = component.getAuthToLocalProperties();
                if (authToLocalProperties != null) {
                  authToLocalPropertiesToSet.addAll(authToLocalProperties);
                }
              }
            }
          }
        }
      }

      if (!authToLocalPropertiesToSet.isEmpty()) {
        for (String authToLocalProperty : authToLocalPropertiesToSet) {
          String[] parts = authToLocalProperty.split("/");

          if (parts.length == 2) {
            AuthToLocalBuilder builder = authToLocalBuilder.copy();
            String configType = parts[0];
            String propertyName = parts[1];

            // Add existing auth_to_local configuration, if set
            Map<String, String> existingConfiguration = existingConfigurations.get(configType);
            if (existingConfiguration != null) {
              builder.addRules(existingConfiguration.get(propertyName));
            }

            // Add/update descriptor auth_to_local configuration, if set
            Map<String, String> kerberosConfiguration = kerberosConfigurations.get(configType);
            if (kerberosConfiguration != null) {
              builder.addRules(kerberosConfiguration.get(propertyName));
            } else {
              kerberosConfiguration = new HashMap<String, String>();
              kerberosConfigurations.put(configType, kerberosConfiguration);
            }

            kerberosConfiguration.put(propertyName, builder.generate(realm));
          }
        }
      }
    }
  }


  @Override
  public List<ServiceComponentHost> getServiceComponentHostsToProcess(Cluster cluster,
                                                                      KerberosDescriptor kerberosDescriptor,
                                                                      Map<String, ? extends Collection<String>> serviceComponentFilter,
                                                                      Collection<String> identityFilter,
                                                                      Command<Boolean, ServiceComponentHost> shouldProcessCommand)
      throws AmbariException {
    List<ServiceComponentHost> serviceComponentHostsToProcess = new ArrayList<ServiceComponentHost>();
    Map<String, Service> services = cluster.getServices();

    if ((services != null) && !services.isEmpty()) {
      Collection<Host> hosts = cluster.getHosts();

      if ((hosts != null) && !hosts.isEmpty()) {
        // Iterate over the hosts in the cluster to find the components installed in each.  For each
        // component (aka service component host - sch) determine the configuration updates and
        // and the principals an keytabs to create.
        for (Host host : hosts) {
          String hostname = host.getHostName();

          // Get a list of components on the current host
          List<ServiceComponentHost> serviceComponentHosts = cluster.getServiceComponentHosts(hostname);

          if ((serviceComponentHosts != null) && !serviceComponentHosts.isEmpty()) {

            // Iterate over the components installed on the current host to get the service and
            // component-level Kerberos descriptors in order to determine which principals,
            // keytab files, and configurations need to be created or updated.
            for (ServiceComponentHost sch : serviceComponentHosts) {
              String serviceName = sch.getServiceName();
              String componentName = sch.getServiceComponentName();

              // If there is no filter or the filter contains the current service name...
              if ((serviceComponentFilter == null) || serviceComponentFilter.containsKey(serviceName)) {
                Collection<String> componentFilter = (serviceComponentFilter == null) ? null : serviceComponentFilter.get(serviceName);
                KerberosServiceDescriptor serviceDescriptor = kerberosDescriptor.getService(serviceName);

                if (serviceDescriptor != null) {
                  // If there is no filter or the filter contains the current component name,
                  // test to see if this component should be process by querying the handler...
                  if (((componentFilter == null) || componentFilter.contains(componentName)) && shouldProcessCommand.invoke(sch)) {
                    KerberosComponentDescriptor componentDescriptor = serviceDescriptor.getComponent(componentName);
                    int identities = 0;

                    // Add service-level principals (and keytabs)
                    identities += filteredIdentitiesCount(serviceDescriptor.getIdentities(false), identityFilter);

                    if (componentDescriptor != null) {
                      // Add component-level principals (and keytabs)
                      identities += filteredIdentitiesCount(componentDescriptor.getIdentities(false), identityFilter);
                    }

                    if (identities > 0) {
                      serviceComponentHostsToProcess.add(sch);
                    }
                  }
                }
              }
            }
          }
        }
      }
    }

    return serviceComponentHostsToProcess;
  }


  @Override
  public Set<String> getHostsWithValidKerberosClient(Cluster cluster)
      throws AmbariException {
    Set<String> hostsWithValidKerberosClient = new HashSet<String>();
    List<ServiceComponentHost> schKerberosClients = cluster.getServiceComponentHosts(Service.Type.KERBEROS.name(), Role.KERBEROS_CLIENT.name());

    if (schKerberosClients != null) {
      for (ServiceComponentHost sch : schKerberosClients) {
        if (sch.getState() == State.INSTALLED) {
          hostsWithValidKerberosClient.add(sch.getHostName());
        }
      }
    }

    return hostsWithValidKerberosClient;
  }

  @Override
  public KerberosDescriptor getKerberosDescriptor(Cluster cluster) throws AmbariException {
    StackId stackId = cluster.getCurrentStackVersion();

    // -------------------------------
    // Get the default Kerberos descriptor from the stack, which is the same as the value from
    // stacks/:stackName/versions/:version/artifacts/kerberos_descriptor
    KerberosDescriptor defaultDescriptor = ambariMetaInfo.getKerberosDescriptor(stackId.getStackName(), stackId.getStackVersion());
    // -------------------------------

    // Get the user-supplied Kerberos descriptor from cluster/:clusterName/artifacts/kerberos_descriptor
    KerberosDescriptor descriptor = null;

    PredicateBuilder pb = new PredicateBuilder();
    Predicate predicate = pb.begin().property("Artifacts/cluster_name").equals(cluster.getClusterName()).and().
        property(ArtifactResourceProvider.ARTIFACT_NAME_PROPERTY).equals("kerberos_descriptor").
        end().toPredicate();

    synchronized (KerberosHelperImpl.class) {
      if (clusterController == null) {
        clusterController = ClusterControllerHelper.getClusterController();
      }
    }

    ResourceProvider artifactProvider =
        clusterController.ensureResourceProvider(Resource.Type.Artifact);

    Request request = new RequestImpl(Collections.<String>emptySet(),
        Collections.<Map<String, Object>>emptySet(), Collections.<String, String>emptyMap(), null);

    Set<Resource> response = null;
    try {
      response = artifactProvider.getResources(request, predicate);
    } catch (SystemException e) {
      e.printStackTrace();
      throw new AmbariException("An unknown error occurred while trying to obtain the cluster kerberos descriptor", e);
    } catch (UnsupportedPropertyException e) {
      e.printStackTrace();
      throw new AmbariException("An unknown error occurred while trying to obtain the cluster kerberos descriptor", e);
    } catch (NoSuchParentResourceException e) {
      // parent cluster doesn't exist.  shouldn't happen since we have the cluster instance
      e.printStackTrace();
      throw new AmbariException("An unknown error occurred while trying to obtain the cluster kerberos descriptor", e);
    } catch (NoSuchResourceException e) {
      // no descriptor registered, use the default from the stack
    }

    if (response != null && !response.isEmpty()) {
      Resource descriptorResource = response.iterator().next();
      Map<String, Map<String, Object>> propertyMap = descriptorResource.getPropertiesMap();
      if (propertyMap != null) {
        Map<String, Object> artifactData = propertyMap.get(ArtifactResourceProvider.ARTIFACT_DATA_PROPERTY);
        Map<String, Object> artifactDataProperties = propertyMap.get(ArtifactResourceProvider.ARTIFACT_DATA_PROPERTY + "/properties");
        HashMap<String, Object> data = new HashMap<String, Object>();

        if (artifactData != null) {
          data.putAll(artifactData);
        }

        if (artifactDataProperties != null) {
          data.put("properties", artifactDataProperties);
        }

        descriptor = kerberosDescriptorFactory.createInstance(data);
      }
    }
    // -------------------------------

    // -------------------------------
    // Attempt to build and return a composite of the default Kerberos descriptor and the user-supplied
    // Kerberos descriptor. If the default descriptor exists, overlay the user-supplied Kerberos
    // descriptor on top of it (if it exists) and return the composite; else return the user-supplied
    // Kerberos descriptor. If both values are null, null may be returned.
    if (defaultDescriptor == null) {
      return descriptor;
    } else {
      if (descriptor != null) {
        defaultDescriptor.update(descriptor);
      }
      return defaultDescriptor;
    }
    // -------------------------------
  }

  @Override
  public Map<String, Map<String, String>> mergeConfigurations(Map<String, Map<String, String>> configurations,
                                                              Map<String, KerberosConfigurationDescriptor> updates,
                                                              Map<String, Map<String, String>> replacements)
      throws AmbariException {

    if ((updates != null) && !updates.isEmpty()) {
      if (configurations == null) {
        configurations = new HashMap<String, Map<String, String>>();
      }

      for (Map.Entry<String, KerberosConfigurationDescriptor> entry : updates.entrySet()) {
        String type = entry.getKey();
        KerberosConfigurationDescriptor configurationDescriptor = entry.getValue();

        if (configurationDescriptor != null) {
          Map<String, String> updatedProperties = configurationDescriptor.getProperties();
          mergeConfigurations(configurations, type, updatedProperties, replacements);
        }
      }
    }

    return configurations;
  }

  @Override
  public int addIdentities(KerberosIdentityDataFileWriter kerberosIdentityDataFileWriter,
                           Collection<KerberosIdentityDescriptor> identities,
                           Collection<String> identityFilter, String hostname, String serviceName,
                           String componentName, Map<String, Map<String, String>> kerberosConfigurations,
                           Map<String, Map<String, String>> configurations)
      throws IOException {
    int identitiesAdded = 0;

    if (identities != null) {
      for (KerberosIdentityDescriptor identity : identities) {
        // If there is no filter or the filter contains the current identity's name...
        if ((identityFilter == null) || identityFilter.contains(identity.getName())) {
          KerberosPrincipalDescriptor principalDescriptor = identity.getPrincipalDescriptor();
          String principal = null;
          String principalType = null;
          String principalConfiguration = null;

          if (principalDescriptor != null) {
            principal = variableReplacementHelper.replaceVariables(principalDescriptor.getValue(), configurations);
            principalType = principalDescriptor.getType().name().toLowerCase();
            principalConfiguration = variableReplacementHelper.replaceVariables(principalDescriptor.getConfiguration(), configurations);
          }

          if (principal != null) {
            KerberosKeytabDescriptor keytabDescriptor = identity.getKeytabDescriptor();
            String keytabFilePath = null;
            String keytabFileOwnerName = null;
            String keytabFileOwnerAccess = null;
            String keytabFileGroupName = null;
            String keytabFileGroupAccess = null;
            String keytabFileConfiguration = null;
            boolean keytabIsCachable = false;

            if (keytabDescriptor != null) {
              keytabFilePath = variableReplacementHelper.replaceVariables(keytabDescriptor.getFile(), configurations);
              keytabFileOwnerName = variableReplacementHelper.replaceVariables(keytabDescriptor.getOwnerName(), configurations);
              keytabFileOwnerAccess = variableReplacementHelper.replaceVariables(keytabDescriptor.getOwnerAccess(), configurations);
              keytabFileGroupName = variableReplacementHelper.replaceVariables(keytabDescriptor.getGroupName(), configurations);
              keytabFileGroupAccess = variableReplacementHelper.replaceVariables(keytabDescriptor.getGroupAccess(), configurations);
              keytabFileConfiguration = variableReplacementHelper.replaceVariables(keytabDescriptor.getConfiguration(), configurations);
              keytabIsCachable = keytabDescriptor.isCachable();
            }

            // Append an entry to the action data file builder...
            kerberosIdentityDataFileWriter.writeRecord(
                hostname,
                serviceName,
                componentName,
                principal,
                principalType,
                keytabFilePath,
                keytabFileOwnerName,
                keytabFileOwnerAccess,
                keytabFileGroupName,
                keytabFileGroupAccess,
                (keytabIsCachable) ? "true" : "false");

            // Add the principal-related configuration to the map of configurations
            mergeConfiguration(kerberosConfigurations, principalConfiguration, principal, null);

            // Add the keytab-related configuration to the map of configurations
            mergeConfiguration(kerberosConfigurations, keytabFileConfiguration, keytabFilePath, null);

            identitiesAdded++;
          }
        }
      }
    }

    return identitiesAdded;
  }

  @Override
  public Map<String, Map<String, String>> calculateConfigurations(Cluster cluster, String hostname,
                                                                  Map<String, String> kerberosDescriptorProperties)
      throws AmbariException {
    // For a configuration type, both tag and an actual configuration can be stored
    // Configurations from the tag is always expanded and then over-written by the actual
    // global:version1:{a1:A1,b1:B1,d1:D1} + global:{a1:A2,c1:C1,DELETED_d1:x} ==>
    // global:{a1:A2,b1:B1,c1:C1}
    Map<String, Map<String, String>> configurations = new HashMap<String, Map<String, String>>();
    Map<String, Map<String, String>> configurationTags = ambariManagementController.findConfigurationTagsWithOverrides(cluster, hostname);

    if (configurationTags.get(Configuration.GLOBAL_CONFIG_TAG) != null) {
      configHelper.applyCustomConfig(
          configurations, Configuration.GLOBAL_CONFIG_TAG,
          Configuration.RCA_ENABLED_PROPERTY, "false", false);
    }

    Map<String, Map<String, String>> configProperties = configHelper.getEffectiveConfigProperties(cluster, configurationTags);

    // Apply the configurations saved with the Execution Cmd on top of
    // derived configs - This will take care of all the hacks
    for (Map.Entry<String, Map<String, String>> entry : configProperties.entrySet()) {
      String type = entry.getKey();
      Map<String, String> allLevelMergedConfig = entry.getValue();
      Map<String, String> configuration = configurations.get(type);

      if (configuration == null) {
        configuration = new HashMap<String, String>(allLevelMergedConfig);
      } else {
        Map<String, String> mergedConfig = configHelper.getMergedConfig(allLevelMergedConfig, configuration);
        configuration.clear();
        configuration.putAll(mergedConfig);
      }

      configurations.put(type, configuration);
    }

    // A map to hold un-categorized properties.  This may come from the KerberosDescriptor
    // and will also contain a value for the current host
    Map<String, String> generalProperties = configurations.get("");
    if (generalProperties == null) {
      generalProperties = new HashMap<String, String>();
      configurations.put("", generalProperties);
    }

    // If any properties are set in the calculated KerberosDescriptor, add them into the
    // Map of configurations as an un-categorized type (using an empty string)
    if (kerberosDescriptorProperties != null) {
      generalProperties.putAll(kerberosDescriptorProperties);
    }

    // Add the current hostname under "host" and "hostname"
    generalProperties.put("host", hostname);
    generalProperties.put("hostname", hostname);

    // Add the current cluster's name
    generalProperties.put("cluster_name", cluster.getClusterName());

    // add clusterHostInfo config
    Map<String, String> componentHosts = new HashMap<String, String>();
    for (Map.Entry<String, Service> service : cluster.getServices().entrySet()) {
      for (Map.Entry<String, ServiceComponent> serviceComponent : service.getValue().getServiceComponents().entrySet()) {
        if (StageUtils.getComponentToClusterInfoKeyMap().keySet().contains(serviceComponent.getValue().getName())) {
          componentHosts.put(StageUtils.getComponentToClusterInfoKeyMap().get(serviceComponent.getValue().getName()),
              StringUtils.join(serviceComponent.getValue().getServiceComponentHosts().keySet(), ","));
        }
      }
    }
    configurations.put("clusterHostInfo", componentHosts);

    return configurations;
  }

  @Override
  public Map<String, Collection<KerberosIdentityDescriptor>> getActiveIdentities(String clusterName,
                                                                                 String hostName,
                                                                                 String serviceName,
                                                                                 String componentName,
                                                                                 boolean replaceHostNames)
      throws AmbariException {

    if ((clusterName == null) || clusterName.isEmpty()) {
      throw new IllegalArgumentException("Invalid argument, cluster name is required");
    }

    Cluster cluster = clusters.getCluster(clusterName);

    if (cluster == null) {
      throw new AmbariException(String.format("The cluster object for the cluster name %s is not available", clusterName));
    }

    Map<String, Collection<KerberosIdentityDescriptor>> activeIdentities = new HashMap<String, Collection<KerberosIdentityDescriptor>>();

    Collection<String> hosts;

    if (hostName == null) {
      Map<String, Host> hostMap = clusters.getHostsForCluster(clusterName);
      if (hostMap == null) {
        hosts = null;
      } else {
        hosts = hostMap.keySet();
      }
    } else {
      hosts = Collections.singleton(hostName);
    }

    if ((hosts != null) && !hosts.isEmpty()) {
      KerberosDescriptor kerberosDescriptor = getKerberosDescriptor(cluster);

      if (kerberosDescriptor != null) {
        Map<String, String> kerberosDescriptorProperties = kerberosDescriptor.getProperties();

        for (String hostname : hosts) {
          Map<String, KerberosIdentityDescriptor> hostActiveIdentities = new HashMap<String, KerberosIdentityDescriptor>();
          List<KerberosIdentityDescriptor> identities = getActiveIdentities(cluster, hostname, serviceName, componentName, kerberosDescriptor);

          if (!identities.isEmpty()) {
            // Calculate the current host-specific configurations. These will be used to replace
            // variables within the Kerberos descriptor data
            Map<String, Map<String, String>> configurations = calculateConfigurations(cluster, hostname, kerberosDescriptorProperties);

            for (KerberosIdentityDescriptor identity : identities) {
              KerberosPrincipalDescriptor principalDescriptor = identity.getPrincipalDescriptor();
              String principal = null;

              if (principalDescriptor != null) {
                principal = variableReplacementHelper.replaceVariables(principalDescriptor.getValue(), configurations);
              }

              if (principal != null) {
                KerberosKeytabDescriptor keytabDescriptor = identity.getKeytabDescriptor();
                String keytabFile = null;

                if (keytabDescriptor != null) {
                  keytabFile = variableReplacementHelper.replaceVariables(keytabDescriptor.getFile(), configurations);
                }

                if (replaceHostNames) {
                  principal = principal.replace("_HOST", hostname);
                }

                String uniqueKey = String.format("%s|%s", principal, (keytabFile == null) ? "" : keytabFile);

                if (!hostActiveIdentities.containsKey(uniqueKey)) {
                  KerberosPrincipalDescriptor resolvedPrincipalDescriptor =
                      new KerberosPrincipalDescriptor(principal,
                          principalDescriptor.getType(),
                          variableReplacementHelper.replaceVariables(principalDescriptor.getConfiguration(), configurations),
                          variableReplacementHelper.replaceVariables(principalDescriptor.getLocalUsername(), configurations));

                  KerberosKeytabDescriptor resolvedKeytabDescriptor;

                  if (keytabFile == null) {
                    resolvedKeytabDescriptor = null;
                  } else {
                    resolvedKeytabDescriptor =
                        new KerberosKeytabDescriptor(
                            keytabFile,
                            variableReplacementHelper.replaceVariables(keytabDescriptor.getOwnerName(), configurations),
                            variableReplacementHelper.replaceVariables(keytabDescriptor.getOwnerAccess(), configurations),
                            variableReplacementHelper.replaceVariables(keytabDescriptor.getGroupName(), configurations),
                            variableReplacementHelper.replaceVariables(keytabDescriptor.getGroupAccess(), configurations),
                            variableReplacementHelper.replaceVariables(keytabDescriptor.getConfiguration(), configurations),
                            keytabDescriptor.isCachable());
                  }

                  hostActiveIdentities.put(uniqueKey, new KerberosIdentityDescriptor(
                      identity.getName(),
                      resolvedPrincipalDescriptor,
                      resolvedKeytabDescriptor));
                }
              }
            }
          }

          activeIdentities.put(hostname, hostActiveIdentities.values());
        }
      }
    }

    return activeIdentities;
  }

  /**
   * Validate the KDC admin credentials.
   *
   * @param kerberosDetails the KerberosDetails containing information about the Kerberos configuration
   *                        for the cluster, if null, a new KerberosDetails will be created based on
   *                        information found in the associated cluster
   * @param cluster         associated cluster
   * @throws AmbariException if any other error occurs while trying to validate the credentials
   */
  private void validateKDCCredentials(KerberosDetails kerberosDetails, Cluster cluster) throws KerberosMissingAdminCredentialsException,
      KerberosAdminAuthenticationException,
      KerberosInvalidConfigurationException,
      AmbariException {

    if (kerberosDetails == null) {
      kerberosDetails = getKerberosDetails(cluster, null);
    }

    if (kerberosDetails.manageIdentities()) {
      String credentials = getEncryptedAdministratorCredentials(cluster);
      if (credentials == null) {
        throw new KerberosMissingAdminCredentialsException(
            "Missing KDC administrator credentials.\n" +
                "The KDC administrator credentials must be set in session by updating the relevant Cluster resource." +
                "This may be done by issuing a PUT to the api/v1/clusters/(cluster name) API entry point with the following payload:\n" +
                "{\n" +
                "  \"session_attributes\" : {\n" +
                "    \"kerberos_admin\" : {\"principal\" : \"(PRINCIPAL)\", \"password\" : \"(PASSWORD)\"}\n" +
                "  }\n" +
                "}"
        );
      } else {
        KerberosOperationHandler operationHandler = kerberosOperationHandlerFactory.getKerberosOperationHandler(kerberosDetails.getKdcType());

        if (operationHandler == null) {
          throw new AmbariException("Failed to get an appropriate Kerberos operation handler.");
        } else {
          byte[] key = Integer.toHexString(cluster.hashCode()).getBytes();
          KerberosCredential kerberosCredentials = KerberosCredential.decrypt(credentials, key);

          boolean missingCredentials = false;
          try {
            operationHandler.open(kerberosCredentials, kerberosDetails.getDefaultRealm(), kerberosDetails.getKerberosEnvProperties());
            // todo: this is really odd that open doesn't throw an exception if the credentials are missing
            missingCredentials = !operationHandler.testAdministratorCredentials();
          } catch (KerberosAdminAuthenticationException e) {
            throw new KerberosAdminAuthenticationException(
                "Invalid KDC administrator credentials.\n" +
                    "The KDC administrator credentials must be set in session by updating the relevant Cluster resource." +
                    "This may be done by issuing a PUT to the api/v1/clusters/(cluster name) API entry point with the following payload:\n" +
                    "{\n" +
                    "  \"session_attributes\" : {\n" +
                    "    \"kerberos_admin\" : {\"principal\" : \"(PRINCIPAL)\", \"password\" : \"(PASSWORD)\"}\n" +
                    "  }\n" +
                    "}", e);
          } catch (KerberosKDCConnectionException e) {
            throw new KerberosInvalidConfigurationException(
                "Failed to connect to KDC - " + e.getMessage() + "\n" +
                    "Update the KDC settings in krb5-conf and kerberos-env configurations to correct this issue.",
                e);
          } catch (KerberosRealmException e) {
            throw new KerberosInvalidConfigurationException(
                "Failed to find a KDC for the specified realm - " + e.getMessage() + "\n" +
                    "Update the KDC settings in krb5-conf and kerberos-env configurations to correct this issue.",
                e);
          } catch (KerberosLDAPContainerException e) {
            throw new KerberosInvalidConfigurationException(
                "The principal container was not specified\n" +
                    "Set the 'container_dn' value in the kerberos-env configuration to correct this issue.",
                e);
          } catch (KerberosOperationException e) {
            throw new AmbariException(e.getMessage(), e);
          } finally {
            try {
              operationHandler.close();
            } catch (KerberosOperationException e) {
              // Ignore this...
            }
          }

          // need to throw this outside of the try/catch so it isn't caught
          if (missingCredentials) {
            throw new KerberosMissingAdminCredentialsException(
                "Invalid KDC administrator credentials.\n" +
                    "The KDC administrator credentials must be set in session by updating the relevant Cluster resource." +
                    "This may be done by issuing a PUT to the api/v1/clusters/(cluster name) API entry point with the following payload:\n" +
                    "{\n" +
                    "  \"session_attributes\" : {\n" +
                    "    \"kerberos_admin\" : {\"principal\" : \"(PRINCIPAL)\", \"password\" : \"(PASSWORD)\"}\n" +
                    "  }\n" +
                    "}"
            );
          }
        }
      }
    }
  }

  /**
   * Performs operations needed to process Kerberos related tasks on the relevant cluster.
   * <p/>
   * Iterates through the components installed on the relevant cluster to determine if work
   * need to be done.  Calls into the Handler implementation to provide guidance and set up stages
   * to perform the work needed to complete the relative action.
   *
   * @param cluster                        the relevant Cluster
   * @param kerberosDetails                a KerberosDetails containing information about relevant Kerberos configuration
   * @param serviceComponentFilter         a Map of service names to component names indicating the relevant
   *                                       set of services and components - if null, no filter is relevant;
   *                                       if empty, the filter indicates no relevant services or components
   * @param identityFilter                 a Collection of identity names indicating the relevant identities -
   *                                       if null, no filter is relevant; if empty, the filter indicates no
   *                                       relevant identities
   * @param hostsToForceKerberosOperations a set of host names on which it is expected that the
   *                                       Kerberos client is or will be in the INSTALLED state by
   *                                       the time the operations targeted for them are to be
   *                                       executed - if empty or null, this no hosts will be
   *                                       "forced"
   * @param requestStageContainer          a RequestStageContainer to place generated stages, if needed -
   *                                       if null a new RequestStageContainer will be created.
   * @param handler                        a Handler to use to provide guidance and set up stages
   *                                       to perform the work needed to complete the relative action
   * @return the updated or a new RequestStageContainer containing the stages that need to be
   * executed to complete this task; or null if no stages need to be executed.
   * @throws AmbariException
   * @throws KerberosInvalidConfigurationException if an issue occurs trying to get the
   *                                               Kerberos-specific configuration details
   */
  @Transactional
  private RequestStageContainer handle(Cluster cluster,
                                       KerberosDetails kerberosDetails,
                                       Map<String, ? extends Collection<String>> serviceComponentFilter,
                                       Collection<String> identityFilter,
                                       Set<String> hostsToForceKerberosOperations,
                                       RequestStageContainer requestStageContainer,
                                       final Handler handler)
      throws AmbariException, KerberosOperationException {

    final KerberosDescriptor kerberosDescriptor = getKerberosDescriptor(cluster);
    final SecurityState desiredSecurityState = handler.getNewServiceSecurityState();

    List<ServiceComponentHost> schToProcess = getServiceComponentHostsToProcess(
        cluster,
        kerberosDescriptor,
        serviceComponentFilter,
        identityFilter,
        new Command<Boolean, ServiceComponentHost>() {
          @Override
          public Boolean invoke(ServiceComponentHost arg) throws AmbariException {
            return handler.shouldProcess(desiredSecurityState, arg);
          }
        });


    // While iterating over all the ServiceComponentHosts find hosts that have KERBEROS_CLIENT
    // components in the INSTALLED state and add them to the hostsWithValidKerberosClient Set.
    // This is needed to help determine which hosts to perform actions for and create tasks for.
    Set<String> hostsWithValidKerberosClient = null;

    // Create a temporary directory to store metadata needed to complete this task.  Information
    // such as which principals and keytabs files to create as well as what configurations need
    // to be update are stored in data files in this directory. Any keytab files are stored in
    // this directory until they are distributed to their appropriate hosts.
    File dataDirectory = null;

    // If there are ServiceComponentHosts to process...
    if (!schToProcess.isEmpty()) {

      validateKDCCredentials(kerberosDetails, cluster);

      // Create a temporary directory to store metadata needed to complete this task.  Information
      // such as which principals and keytabs files to create as well as what configurations need
      // to be update are stored in data files in this directory. Any keytab files are stored in
      // this directory until they are distributed to their appropriate hosts.
      dataDirectory = createTemporaryDirectory();

      hostsWithValidKerberosClient = getHostsWithValidKerberosClient(cluster);

      // Ensure that that hosts that should be assumed to be in the correct state when needed are
      // in the hostsWithValidKerberosClient collection.
      if (hostsToForceKerberosOperations != null) {
        hostsWithValidKerberosClient.addAll(hostsToForceKerberosOperations);
      }
    }

    // Always set up the necessary stages to perform the tasks needed to complete the operation.
    // Some stages may be no-ops, this is expected.
    // Gather data needed to create stages and tasks...
    Map<String, Set<String>> clusterHostInfo = StageUtils.getClusterHostInfo(cluster);
    String clusterHostInfoJson = StageUtils.getGson().toJson(clusterHostInfo);
    Map<String, String> hostParams = customCommandExecutionHelper.createDefaultHostParams(cluster);
    String hostParamsJson = StageUtils.getGson().toJson(hostParams);
    String ambariServerHostname = StageUtils.getHostName();
    ServiceComponentHostServerActionEvent event = new ServiceComponentHostServerActionEvent(
        "AMBARI_SERVER",
        ambariServerHostname, // TODO: Choose a random hostname from the cluster. All tasks for the AMBARI_SERVER service will be executed on this Ambari server
        System.currentTimeMillis());
    RoleCommandOrder roleCommandOrder = ambariManagementController.getRoleCommandOrder(cluster);

    // If a RequestStageContainer does not already exist, create a new one...
    if (requestStageContainer == null) {
      requestStageContainer = new RequestStageContainer(
          actionManager.getNextRequestId(),
          null,
          requestFactory,
          actionManager);
    }

    // Use the handler implementation to setup the relevant stages.
    handler.createStages(cluster, clusterHostInfoJson,
        hostParamsJson, event, roleCommandOrder, kerberosDetails, dataDirectory,
        requestStageContainer, schToProcess, serviceComponentFilter, identityFilter,
        hostsWithValidKerberosClient);

    // Add the finalize stage...
    handler.addFinalizeOperationStage(cluster, clusterHostInfoJson, hostParamsJson, event,
        dataDirectory, roleCommandOrder, requestStageContainer);

    // If all goes well, set the appropriate states on the relevant ServiceComponentHosts
    for (ServiceComponentHost sch : schToProcess) {
      // Update the desired and current states for the ServiceComponentHost
      // using new state information from the the handler implementation
      SecurityState newSecurityState;

      newSecurityState = handler.getNewDesiredSCHSecurityState();
      if (newSecurityState != null) {
        sch.setDesiredSecurityState(newSecurityState);
      }

      newSecurityState = handler.getNewSCHSecurityState();
      if (newSecurityState != null) {
        sch.setSecurityState(newSecurityState);
      }
    }

    // If all goes well, set all services to _desire_ to be secured or unsecured, depending on handler
    if (desiredSecurityState != null) {
      Map<String, Service> services = cluster.getServices();

      for (Service service : services.values()) {
        if ((serviceComponentFilter == null) || serviceComponentFilter.containsKey(service.getName())) {
          service.setSecurityState(desiredSecurityState);
        }
      }
    }

    return requestStageContainer;
  }

  private int filteredIdentitiesCount(List<KerberosIdentityDescriptor> identities, Collection<String> identityFilter) {

    if ((identities == null) || identities.isEmpty()) {
      return 0;
    } else if (identityFilter == null) {
      return identities.size();
    } else {
      int count = 0;

      for (KerberosIdentityDescriptor identity : identities) {
        if (identityFilter.contains(identity.getName())) {
          count++;
        }
      }

      return count;
    }
  }


  /**
   * Performs operations needed to process Kerberos related tasks to manage a (unique) test identity
   * on the relevant cluster.
   * <p/>
   * If Ambari is not managing Kerberos identities, than this method does nothing.
   *
   * @param cluster               the relevant Cluster
   * @param kerberosDetails       a KerberosDetails containing information about relevant Kerberos
   *                              configuration
   * @param commandParameters     the command parameters map used to read and/or write attributes
   *                              related to this operation
   * @param requestStageContainer a RequestStageContainer to place generated stages, if needed -
   *                              if null a new RequestStageContainer will be created.
   * @param handler               a Handler to use to provide guidance and set up stages
   *                              to perform the work needed to complete the relative action
   * @return the updated or a new RequestStageContainer containing the stages that need to be
   * executed to complete this task; or null if no stages need to be executed.
   * @throws AmbariException
   * @throws KerberosOperationException
   */
  private RequestStageContainer handleTestIdentity(Cluster cluster,
                                                   KerberosDetails kerberosDetails,
                                                   Map<String, String> commandParameters, RequestStageContainer requestStageContainer,
                                                   Handler handler) throws AmbariException, KerberosOperationException {

    if (kerberosDetails.manageIdentities()) {
      if (commandParameters == null) {
        throw new AmbariException("The properties map must not be null.  It is needed to store data related to the service check identity");
      }

      Map<String, Service> services = cluster.getServices();

      if ((services != null) && !services.isEmpty()) {
        String clusterName = cluster.getClusterName();
        Map<String, Host> hosts = clusters.getHostsForCluster(clusterName);

        if ((hosts != null) && !hosts.isEmpty()) {
          List<ServiceComponentHost> serviceComponentHostsToProcess = new ArrayList<ServiceComponentHost>();
          KerberosDescriptor kerberosDescriptor = getKerberosDescriptor(cluster);
          KerberosIdentityDataFileWriter kerberosIdentityDataFileWriter = null;
          Map<String, String> kerberosDescriptorProperties = kerberosDescriptor.getProperties();

          // This is needed to help determine which hosts to perform actions for and create tasks for.
          Set<String> hostsWithValidKerberosClient = getHostsWithValidKerberosClient(cluster);

          // Create a temporary directory to store metadata needed to complete this task.  Information
          // such as which principals and keytabs files to create as well as what configurations need
          // to be update are stored in data files in this directory. Any keytab files are stored in
          // this directory until they are distributed to their appropriate hosts.
          File dataDirectory = createTemporaryDirectory();

          // Create the file used to store details about principals and keytabs to create
          File identityDataFile = new File(dataDirectory, KerberosIdentityDataFileWriter.DATA_FILE_NAME);

          // Create a special identity for the test user
          KerberosIdentityDescriptor identity = new KerberosIdentityDescriptor(new HashMap<String, Object>() {
            {
              put("principal",
                  new HashMap<String, Object>() {
                    {
                      put("value", "${kerberos-env/service_check_principal_name}@${realm}");
                      put("type", "user");
                    }
                  });
              put("keytab",
                  new HashMap<String, Object>() {
                    {
                      put("file", "${keytab_dir}/kerberos.service_check.${short_date}.keytab");

                      put("owner", new HashMap<String, Object>() {{
                        put("name", "${cluster-env/smokeuser}");
                        put("access", "rw");
                      }});

                      put("group", new HashMap<String, Object>() {{
                        put("name", "${cluster-env/user_group}");
                        put("access", "r");
                      }});

                      put("cachable", "false");
                    }
                  });
            }
          });

          // Get or create the unique service check identifier
          String serviceCheckId = getKerberosServiceCheckIdentifier(cluster, true);

          try {
            // Iterate over the hosts in the cluster to find the components installed in each.  For each
            // component (aka service component host - sch) determine the configuration updates and
            // and the principals an keytabs to create.
            for (Host host : hosts.values()) {
              String hostname = host.getHostName();

              // Get a list of components on the current host
              List<ServiceComponentHost> serviceComponentHosts = cluster.getServiceComponentHosts(hostname);

              if ((serviceComponentHosts != null) && !serviceComponentHosts.isEmpty()) {
                // Calculate the current host-specific configurations. These will be used to replace
                // variables within the Kerberos descriptor data
                Map<String, Map<String, String>> configurations = calculateConfigurations(cluster, hostname, kerberosDescriptorProperties);

                // Set the unique service check identifier
                configurations.get("").put("service_check_id", serviceCheckId);

                // Add a short date value
                configurations.get("").put("short_date", new SimpleDateFormat("MMddyy").format(new Date()));

                // Iterate over the components installed on the current host to get the service and
                // component-level Kerberos descriptors in order to determine which principals,
                // keytab files, and configurations need to be created or updated.
                for (ServiceComponentHost sch : serviceComponentHosts) {
                  String serviceName = sch.getServiceName();
                  String componentName = sch.getServiceComponentName();

                  // If the current ServiceComponentHost represents the KERBEROS/KERBEROS_CLIENT and
                  // indicates that the KERBEROS_CLIENT component is in the INSTALLED state, add the
                  // current host to the set of hosts that should be handled...
                  if (Service.Type.KERBEROS.name().equals(serviceName) &&
                      Role.KERBEROS_CLIENT.name().equals(componentName) &&
                      (sch.getState() == State.INSTALLED)) {
                    hostsWithValidKerberosClient.add(hostname);

                    int identitiesAdded = 0;

                    // Lazily create the KerberosIdentityDataFileWriter instance...
                    if (kerberosIdentityDataFileWriter == null) {
                      kerberosIdentityDataFileWriter = kerberosIdentityDataFileWriterFactory.createKerberosIdentityDataFileWriter(identityDataFile);
                    }

                    // Add service-level principals (and keytabs)
                    identitiesAdded += addIdentities(kerberosIdentityDataFileWriter, Collections.singleton(identity),
                        null, hostname, serviceName, componentName, null, configurations);

                    if (identitiesAdded > 0) {
                      // Add the relevant principal name and keytab file data to the command params state
                      if (!commandParameters.containsKey("principal_name") || !commandParameters.containsKey("keytab_file")) {
                        commandParameters.put("principal_name",
                            variableReplacementHelper.replaceVariables(identity.getPrincipalDescriptor().getValue(), configurations));
                        commandParameters.put("keytab_file",
                            variableReplacementHelper.replaceVariables(identity.getKeytabDescriptor().getFile(), configurations));
                      }

                      serviceComponentHostsToProcess.add(sch);
                    }
                  }
                }
              }
            }
          } catch (IOException e) {
            String message = String.format("Failed to write index file - %s", identityDataFile.getAbsolutePath());
            LOG.error(message);
            throw new AmbariException(message, e);
          } finally {
            if (kerberosIdentityDataFileWriter != null) {
              // Make sure the data file is closed
              try {
                kerberosIdentityDataFileWriter.close();
              } catch (IOException e) {
                LOG.warn("Failed to close the index file writer", e);
              }
            }
          }

          // If there are ServiceComponentHosts to process, make sure the administrator credentials
          // are available
          if (!serviceComponentHostsToProcess.isEmpty()) {
            try {
              validateKDCCredentials(kerberosDetails, cluster);
            } catch (KerberosOperationException e) {
              try {
                FileUtils.deleteDirectory(dataDirectory);
              } catch (Throwable t) {
                LOG.warn(String.format("The data directory (%s) was not deleted due to an error condition - {%s}",
                    dataDirectory.getAbsolutePath(), t.getMessage()), t);
              }

              throw e;
            }
          }

          // Always set up the necessary stages to perform the tasks needed to complete the operation.
          // Some stages may be no-ops, this is expected.
          // Gather data needed to create stages and tasks...
          Map<String, Set<String>> clusterHostInfo = StageUtils.getClusterHostInfo(cluster);
          String clusterHostInfoJson = StageUtils.getGson().toJson(clusterHostInfo);
          Map<String, String> hostParams = customCommandExecutionHelper.createDefaultHostParams(cluster);
          String hostParamsJson = StageUtils.getGson().toJson(hostParams);
          String ambariServerHostname = StageUtils.getHostName();
          ServiceComponentHostServerActionEvent event = new ServiceComponentHostServerActionEvent(
              "AMBARI_SERVER",
              ambariServerHostname, // TODO: Choose a random hostname from the cluster. All tasks for the AMBARI_SERVER service will be executed on this Ambari server
              System.currentTimeMillis());
          RoleCommandOrder roleCommandOrder = ambariManagementController.getRoleCommandOrder(cluster);

          // If a RequestStageContainer does not already exist, create a new one...
          if (requestStageContainer == null) {
            requestStageContainer = new RequestStageContainer(
                actionManager.getNextRequestId(),
                null,
                requestFactory,
                actionManager);
          }

          // Use the handler implementation to setup the relevant stages.
          // Set the service/component filter to an empty map since the service/component processing
          // was done above.
          handler.createStages(cluster,
              clusterHostInfoJson, hostParamsJson, event, roleCommandOrder, kerberosDetails,
              dataDirectory, requestStageContainer, serviceComponentHostsToProcess,
              Collections.<String, Collection<String>>emptyMap(), null, hostsWithValidKerberosClient);


          handler.addFinalizeOperationStage(cluster, clusterHostInfoJson, hostParamsJson, event,
              dataDirectory, roleCommandOrder, requestStageContainer);
        }
      }
    }

    return requestStageContainer;
  }


  /**
   * Gathers the Kerberos-related data from configurations and stores it in a new KerberosDetails
   * instance.
   *
   * @param cluster          the relevant Cluster
   * @param manageIdentities a Boolean value indicating how to override the configured behavior
   *                         of managing Kerberos identities; if null the configured behavior
   *                         will not be overridden
   * @return a new KerberosDetails with the collected configuration data
   * @throws AmbariException
   */
  private KerberosDetails getKerberosDetails(Cluster cluster, Boolean manageIdentities)
      throws KerberosInvalidConfigurationException, AmbariException {

    KerberosDetails kerberosDetails = new KerberosDetails();

    if (cluster == null) {
      String message = "The cluster object is not available";
      LOG.error(message);
      throw new AmbariException(message);
    }

    Config configKrb5Conf = cluster.getDesiredConfigByType("krb5-conf");
    if (configKrb5Conf == null) {
      String message = "The 'krb5-conf' configuration is not available";
      LOG.error(message);
      throw new AmbariException(message);
    }

    Map<String, String> krb5ConfProperties = configKrb5Conf.getProperties();
    if (krb5ConfProperties == null) {
      String message = "The 'krb5-conf' configuration properties are not available";
      LOG.error(message);
      throw new AmbariException(message);
    }

    Config configKerberosEnv = cluster.getDesiredConfigByType("kerberos-env");
    if (configKerberosEnv == null) {
      String message = "The 'kerberos-env' configuration is not available";
      LOG.error(message);
      throw new AmbariException(message);
    }

    Map<String, String> kerberosEnvProperties = configKerberosEnv.getProperties();
    if (kerberosEnvProperties == null) {
      String message = "The 'kerberos-env' configuration properties are not available";
      LOG.error(message);
      throw new AmbariException(message);
    }

    kerberosDetails.setSecurityType(cluster.getSecurityType());
    kerberosDetails.setDefaultRealm(kerberosEnvProperties.get("realm"));

    kerberosDetails.setKerberosEnvProperties(kerberosEnvProperties);

    // If set, override the manage identities behavior
    kerberosDetails.setManageIdentities(manageIdentities);

    String kdcTypeProperty = kerberosEnvProperties.get("kdc_type");
    if ((kdcTypeProperty == null) && kerberosDetails.manageIdentities()) {
      String message = "The 'kerberos-env/kdc_type' value must be set to a valid KDC type";
      LOG.error(message);
      throw new KerberosInvalidConfigurationException(message);
    }

    KDCType kdcType;
    try {
      kdcType = KDCType.translate(kdcTypeProperty);
    } catch (IllegalArgumentException e) {
      String message = String.format("Invalid 'kdc_type' value: %s", kdcTypeProperty);
      LOG.error(message);
      throw new AmbariException(message);
    }

    // Set the KDCType to the the MIT_KDC as a fallback.
    kerberosDetails.setKdcType((kdcType == null) ? KDCType.MIT_KDC : kdcType);

    return kerberosDetails;
  }

  /**
   * Creates a temporary directory within the system temporary directory
   * <p/>
   * The resulting directory is to be removed by the caller when desired.
   *
   * @return a File pointing to the new temporary directory, or null if one was not created
   * @throws AmbariException if a new temporary directory cannot be created
   */
  private File createTemporaryDirectory() throws AmbariException {
    String tempDirectoryPath = configuration.getProperty(Configuration.SERVER_TMP_DIR_KEY);

    if ((tempDirectoryPath == null) || tempDirectoryPath.isEmpty()) {
      tempDirectoryPath = System.getProperty("java.io.tmpdir");
    }

    try {
      if (tempDirectoryPath == null) {
        throw new IOException("The System property 'java.io.tmpdir' does not specify a temporary directory");
      }

      File directory;
      int tries = 0;
      long now = System.currentTimeMillis();

      do {
        directory = new File(tempDirectoryPath, String.format("%s%d-%d.d",
            KerberosServerAction.DATA_DIRECTORY_PREFIX, now, tries));

        if ((directory.exists()) || !directory.mkdirs()) {
          directory = null; // Rest and try again...
        } else {
          LOG.debug("Created temporary directory: {}", directory.getAbsolutePath());
        }
      } while ((directory == null) && (++tries < 100));

      if (directory == null) {
        throw new IOException(String.format("Failed to create a temporary directory in %s", tempDirectoryPath));
      }

      return directory;
    } catch (IOException e) {
      String message = "Failed to create the temporary data directory.";
      LOG.error(message, e);
      throw new AmbariException(message, e);
    }
  }


  /**
   * Merges the specified configuration property in a map of configuration types.
   * The supplied property is processed to replace variables using the replacement Map.
   * <p/>
   * See {@link VariableReplacementHelper#replaceVariables(String, java.util.Map)}
   * for information on variable replacement.
   *
   * @param configurations             the Map of configuration types to update
   * @param configurationSpecification the config-type/property_name value specifying the property to set
   * @param value                      the value of the property to set
   * @param replacements               a Map of (grouped) replacement values
   * @throws AmbariException
   */
  private void mergeConfiguration(Map<String, Map<String, String>> configurations,
                                  String configurationSpecification,
                                  String value,
                                  Map<String, Map<String, String>> replacements) throws AmbariException {

    if (configurationSpecification != null) {
      String[] parts = configurationSpecification.split("/");
      if (parts.length == 2) {
        String type = parts[0];
        String property = parts[1];

        mergeConfigurations(configurations, type, Collections.singletonMap(property, value), replacements);
      }
    }
  }

  /**
   * Merges configuration from a Map of configuration updates into a main configurations Map.  Each
   * property in the updates Map is processed to replace variables using the replacement Map.
   * <p/>
   * See {@link VariableReplacementHelper#replaceVariables(String, java.util.Map)}
   * for information on variable replacement.
   *
   * @param configurations a Map of configurations
   * @param type           the configuration type
   * @param updates        a Map of property updates
   * @param replacements   a Map of (grouped) replacement values
   * @throws AmbariException
   */
  private void mergeConfigurations(Map<String, Map<String, String>> configurations, String type,
                                   Map<String, String> updates,
                                   Map<String, Map<String, String>> replacements) throws AmbariException {
    if (updates != null) {
      Map<String, String> existingProperties = configurations.get(type);
      if (existingProperties == null) {
        existingProperties = new HashMap<String, String>();
        configurations.put(type, existingProperties);
      }

      for (Map.Entry<String, String> property : updates.entrySet()) {
        existingProperties.put(
            variableReplacementHelper.replaceVariables(property.getKey(), replacements),
            variableReplacementHelper.replaceVariables(property.getValue(), replacements)
        );
      }
    }
  }

  /**
   * Adds identities to the AuthToLocalBuilder.
   *
   * @param authToLocalBuilder the AuthToLocalBuilder to use to build the auth_to_local mapping
   * @param identities         a List of KerberosIdentityDescriptors to process
   * @param identityFilter     a Collection of identity names indicating the relevant identities -
   *                           if null, no filter is relevant; if empty, the filter indicates no
   *                           relevant identities
   * @param configurations     a Map of configurations to use a replacements for variables
   *                           in identity fields
   * @throws org.apache.ambari.server.AmbariException
   */
  private void addIdentities(AuthToLocalBuilder authToLocalBuilder,
                             List<KerberosIdentityDescriptor> identities, Collection<String> identityFilter,
                             Map<String, Map<String, String>> configurations) throws AmbariException {
    if (identities != null) {
      for (KerberosIdentityDescriptor identity : identities) {
        // If there is no filter or the filter contains the current identity's name...
        if ((identityFilter == null) || identityFilter.contains(identity.getName())) {
          KerberosPrincipalDescriptor principalDescriptor = identity.getPrincipalDescriptor();
          if (principalDescriptor != null) {
            authToLocalBuilder.addRule(
                variableReplacementHelper.replaceVariables(principalDescriptor.getValue(), configurations),
                variableReplacementHelper.replaceVariables(principalDescriptor.getLocalUsername(), configurations));
          }
        }
      }
    }
  }


  /**
   * Creates a new stage
   *
   * @param id              the new stage's id
   * @param cluster         the relevant Cluster
   * @param requestId       the relevant request Id
   * @param requestContext  a String describing the stage
   * @param clusterHostInfo JSON-encoded clusterHostInfo structure
   * @param commandParams   JSON-encoded command parameters
   * @param hostParams      JSON-encoded host parameters
   * @return a newly created Stage
   */
  private Stage createNewStage(long id, Cluster cluster, long requestId,
                               String requestContext, String clusterHostInfo,
                               String commandParams, String hostParams) {
    Stage stage = stageFactory.createNew(requestId,
        BASE_LOG_DIR + File.pathSeparator + requestId,
        cluster.getClusterName(),
        cluster.getClusterId(),
        requestContext,
        clusterHostInfo,
        commandParams,
        hostParams);

    stage.setStageId(id);
    return stage;
  }

  /**
   * Creates a new stage with a single task describing the ServerAction class to invoke and the other
   * task-related information.
   *
   * @param id                the new stage's id
   * @param cluster           the relevant Cluster
   * @param requestId         the relevant request Id
   * @param requestContext    a String describing the stage
   * @param clusterHostInfo   JSON-encoded clusterHostInfo structure
   * @param commandParams     JSON-encoded command parameters
   * @param hostParams        JSON-encoded host parameters
   * @param actionClass       The ServeAction class that implements the action to invoke
   * @param event             The relevant ServiceComponentHostServerActionEvent
   * @param commandParameters a Map of command parameters to attach to the task added to the new
   *                          stage
   * @param commandDetail     a String declaring a descriptive name to pass to the action - null or an
   *                          empty string indicates no value is to be set
   * @param timeout           the timeout for the task/action  @return a newly created Stage
   */
  private Stage createServerActionStage(long id, Cluster cluster, long requestId,
                                        String requestContext, String clusterHostInfo,
                                        String commandParams, String hostParams,
                                        Class<? extends ServerAction> actionClass,
                                        ServiceComponentHostServerActionEvent event,
                                        Map<String, String> commandParameters, String commandDetail,
                                        Integer timeout) throws AmbariException {

    Stage stage = createNewStage(id, cluster, requestId, requestContext, clusterHostInfo, commandParams, hostParams);
    stage.addServerActionCommand(actionClass.getName(),
        Role.AMBARI_SERVER_ACTION,
        RoleCommand.EXECUTE,
        cluster.getClusterName(),
        event,
        commandParameters,
        commandDetail,
        ambariManagementController.findConfigurationTagsWithOverrides(cluster, null),
        timeout, false);

    return stage;
  }

  /**
   * Using the session data from the relevant Cluster object, creates a KerberosCredential,
   * serializes, and than encrypts it.
   * <p/>
   * Since the relevant data is stored in the HTTPSession (which relies on ThreadLocalStorage),
   * it needs to be retrieved now and placed in the action's command parameters so it will be
   * available when needed.  Because command parameters are stored in plaintext in the Ambari database,
   * this (sensitive) data needs to be encrypted, however it needs to be done using a key the can be
   * recreated sometime later when the data needs to be access. Since it is expected that the Cluster
   * object will be able now and later, the hashcode of this object is used to build the key - it
   * is expected that the same instance will be retrieved from the Clusters instance, thus yielding
   * the same hashcode value.
   * <p/>
   * If the Ambari server architecture changes, this will need to be revisited.
   *
   * @param cluster the relevant Cluster
   * @return a serialized and encrypted KerberosCredential, or null if administrator data is not found
   * @throws AmbariException
   */
  private String getEncryptedAdministratorCredentials(Cluster cluster) throws AmbariException {
    String encryptedAdministratorCredentials = null;

    Map<String, Object> sessionAttributes = cluster.getSessionAttributes();
    if (sessionAttributes != null) {
      KerberosCredential credential = KerberosCredential.fromMap(sessionAttributes, "kerberos_admin/");
      if (credential != null) {
        byte[] key = Integer.toHexString(cluster.hashCode()).getBytes();
        encryptedAdministratorCredentials = credential.encrypt(key);
      }
    }

    return encryptedAdministratorCredentials;
  }

  /**
   * Using the session data from the relevant Cluster object, gets the previously stored
   * Kerberos service check identifier value or creates a new one if indicated to do so.
   * <p/>
   * This value is used intended to be used by the KerberosHelper to manage uniquely crated
   * principals for use in service checks.
   *
   * @param cluster the relevant Cluster
   * @return the previously stored Kerberos service check identifier value, or null if
   * not previously stored
   */
  private String getKerberosServiceCheckIdentifier(Cluster cluster, boolean createIfNull) {
    Map<String, Object> sessionAttributes = cluster.getSessionAttributes();
    Object value = (sessionAttributes == null) ? null : sessionAttributes.get(SERVICE_CHECK_IDENTIFIER);
    String serviceCheckIdentifier = (value instanceof String) ? (String) value : null;

    if ((serviceCheckIdentifier == null) && createIfNull) {
      // Create a new (ideally) unique(ish) identifier
      Random random = new Random(System.currentTimeMillis());
      char[] chars = new char[8];

      for (int i = 0; i < 8; i++) {
        chars[i] = (char) ((int) 'a' + random.nextInt(26));
      }

      serviceCheckIdentifier = String.valueOf(chars);
      setKerberosServiceCheckIdentifier(cluster, serviceCheckIdentifier);
    }

    return serviceCheckIdentifier;
  }

  /**
   * Stores the Kerberos service check identifier value into the session data from the
   * relevant Cluster object.
   * <p/>
   * This value is used intended to be used by the KerberosHelper to manage uniquely crated
   * principals for use in service checks.
   *
   * @param cluster the relevant Cluster
   * @param value   the Kerberos service check identifier to store or null to clear any previously set value
   */
  private void setKerberosServiceCheckIdentifier(Cluster cluster, String value) {
    if (value == null) {
      cluster.removeSessionAttribute(SERVICE_CHECK_IDENTIFIER);
    } else {
      cluster.setSessionAttribute(SERVICE_CHECK_IDENTIFIER, value);
    }
  }

  /**
   * Given a Collection of ServiceComponentHosts generates a unique list of hosts.
   *
   * @param serviceComponentHosts a Collection of ServiceComponentHosts from which to to retrieve host names
   * @param allowedStates         a Set of HostStates to use to filter the list of hosts, if null, no filter is applied
   * @return a List of (unique) host names
   * @throws org.apache.ambari.server.AmbariException
   */
  private List<String> createUniqueHostList(Collection<ServiceComponentHost> serviceComponentHosts, Set<HostState> allowedStates)
      throws AmbariException {
    Set<String> hostNames = new HashSet<String>();
    Set<String> visitedHostNames = new HashSet<String>();

    if (serviceComponentHosts != null) {
      for (ServiceComponentHost sch : serviceComponentHosts) {
        String hostname = sch.getHostName();
        if (!visitedHostNames.contains(hostname)) {
          // If allowedStates is null, assume the caller doesnt care about the state of the host
          // so skip the call to get the relevant Host data and just add the host to the list
          if (allowedStates == null) {
            hostNames.add(hostname);
          } else {
            Host host = clusters.getHost(hostname);

            if (allowedStates.contains(host.getState())) {
              hostNames.add(hostname);
            }
          }

          visitedHostNames.add(hostname);
        }
      }
    }

    return new ArrayList<String>(hostNames);
  }

  @Override
  public boolean isClusterKerberosEnabled(Cluster cluster) {
    return cluster.getSecurityType() == SecurityType.KERBEROS;
  }

  @Override
  public boolean shouldExecuteCustomOperations(SecurityType requestSecurityType, Map<String, String> requestProperties) {

    if (((requestSecurityType == SecurityType.KERBEROS) || (requestSecurityType == SecurityType.NONE)) &&
        (requestProperties != null) && !requestProperties.isEmpty()) {
      for (SupportedCustomOperation type : SupportedCustomOperation.values()) {
        if (requestProperties.containsKey(type.name().toLowerCase())) {
          return true;
        }
      }
    }
    return false;
  }

  @Override
  public Boolean getManageIdentitiesDirective(Map<String, String> requestProperties) {
    String value = (requestProperties == null) ? null : requestProperties.get(DIRECTIVE_MANAGE_KERBEROS_IDENTITIES);

    return (value == null)
        ? null
        : !"false".equalsIgnoreCase(value);
  }

  /**
   * Given a list of KerberosIdentityDescriptors, returns a Map fo configuration types to property
   * names and values.
   * <p/>
   * The property names and values are not expected to have any variable replacements done.
   *
   * @param identityDescriptors a List of KerberosIdentityDescriptor from which to retrieve configurations
   * @return a Map of configuration types to property name/value pairs (as a Map)
   */
  private Map<String, Map<String, String>> getConfigurations(List<KerberosIdentityDescriptor> identityDescriptors) {
    Map<String, Map<String, String>> map = new HashMap<String, Map<String, String>>();

    if (identityDescriptors != null) {
      for (KerberosIdentityDescriptor identityDescriptor : identityDescriptors) {
        KerberosPrincipalDescriptor principalDescriptor = identityDescriptor.getPrincipalDescriptor();
        if (principalDescriptor != null) {
          putConfiguration(map, principalDescriptor.getConfiguration(), principalDescriptor.getValue());
        }

        KerberosKeytabDescriptor keytabDescriptor = identityDescriptor.getKeytabDescriptor();
        if (keytabDescriptor != null) {
          putConfiguration(map, keytabDescriptor.getConfiguration(), keytabDescriptor.getFile());
        }
      }
    }

    return map;
  }

  /**
   * Inserts a configuration property and value into a map of configuration types to property
   * name/value pair maps.
   *
   * @param map           the Map to insert into
   * @param configuration a configuration property in the form of config-type/property_name
   * @param value         the value of the configuration property
   */
  private void putConfiguration(Map<String, Map<String, String>> map, String configuration, String value) {
    if (configuration != null) {
      String[] principalTokens = configuration.split("/");

      if (principalTokens.length == 2) {
        String type = principalTokens[0];
        String propertyName = principalTokens[1];

        Map<String, String> properties = map.get(type);
        if (properties == null) {
          properties = new HashMap<String, String>();
          map.put(type, properties);
        }

        properties.put(propertyName, value);
      }
    }
  }

  /**
   * Returns the active identities for the named service component in the cluster.
   *
   * @param cluster            the relevant cluster (mandatory)
   * @param hostname           the name of a host for which to find results, null indicates all hosts
   * @param serviceName        the name of a service for which to find results, null indicates all
   *                           services
   * @param componentName      the name of a component for which to find results, null indicates all
   *                           components
   * @param kerberosDescriptor the relevant Kerberos Descriptor
   * @return a list of KerberosIdentityDescriptors representing the active identities for the
   * requested service component
   * @throws AmbariException if an error occurs processing the cluster's active identities
   */
  private List<KerberosIdentityDescriptor> getActiveIdentities(Cluster cluster,
                                                               String hostname,
                                                               String serviceName,
                                                               String componentName,
                                                               KerberosDescriptor kerberosDescriptor)
      throws AmbariException {

    List<KerberosIdentityDescriptor> identities = new ArrayList<KerberosIdentityDescriptor>();

    List<ServiceComponentHost> serviceComponentHosts = cluster.getServiceComponentHosts(hostname);

    if (serviceComponentHosts != null) {
      for (ServiceComponentHost serviceComponentHost : serviceComponentHosts) {
        String schServiceName = serviceComponentHost.getServiceName();
        String schComponentName = serviceComponentHost.getServiceComponentName();

        if (((serviceName == null) || serviceName.equals(schServiceName)) &&
            ((componentName == null) || componentName.equals(schComponentName))) {

          KerberosServiceDescriptor serviceDescriptor = kerberosDescriptor.getService(schServiceName);

          if (serviceDescriptor != null) {
            List<KerberosIdentityDescriptor> serviceIdentities = serviceDescriptor.getIdentities(true);
            if (serviceIdentities != null) {
              identities.addAll(serviceIdentities);
            }

            KerberosComponentDescriptor componentDescriptor = serviceDescriptor.getComponent(schComponentName);
            if (componentDescriptor != null) {
              List<KerberosIdentityDescriptor> componentIdentities = componentDescriptor.getIdentities(true);
              if (componentIdentities != null) {
                identities.addAll(componentIdentities);
              }
            }
          }
        }
      }
    }

    return identities;
  }


  /* ********************************************************************************************
   * Helper classes and enums
   * ******************************************************************************************** *\

  /**
   * A enumeration of the supported custom operations
   */
  public enum SupportedCustomOperation {
    REGENERATE_KEYTABS
  }

  /**
   * Handler is an interface that needs to be implemented by toggle handler classes to do the
   * "right" thing for the task at hand.
   */
  private abstract class Handler {
    /**
     * Tests the Service and ServiceComponentHost to see if they are in the appropriate security
     * state to be processed for the relevant task.
     *
     * @param desiredSecurityState the SecurityState to be transitioned to
     * @param sch                  the ServiceComponentHost to test
     * @return true if both the Service and ServiceComponentHost are in the appropriate security
     * state to be processed; otherwise false
     * @throws AmbariException of an error occurs while testing
     */
    abstract boolean shouldProcess(SecurityState desiredSecurityState, ServiceComponentHost sch) throws AmbariException;

    /**
     * Returns the new SecurityState to be set as the ServiceComponentHost's _desired_ SecurityState.
     *
     * @return a SecurityState to be set as the ServiceComponentHost's _desired_ SecurityState;
     * or null if no state change is desired
     */
    abstract SecurityState getNewDesiredSCHSecurityState();

    /**
     * Returns the new SecurityState to be set as the ServiceComponentHost's _current_ SecurityState.
     *
     * @return a SecurityState to be set as the ServiceComponentHost's _current_ SecurityState;
     * or null if no state change is desired
     */
    abstract SecurityState getNewSCHSecurityState();


    /**
     * Returns the new SecurityState to be set as the Service's SecurityState.
     *
     * @return a SecurityState to be set as the Service's SecurityState;
     * or null if no state change is desired
     */
    abstract SecurityState getNewServiceSecurityState();

    /**
     * Creates the necessary stages to complete the relevant task and stores them in the supplied
     * or a newly created RequestStageContainer.
     * <p/>
     * If the supplied RequestStageContainer is null, a new one must be created and filled.
     * {@link org.apache.ambari.server.controller.internal.RequestStageContainer#persist()} should
     * not be called since it is not known if the set of states for this container is complete.
     *
     * @param cluster                the relevant Cluster
     * @param clusterHostInfo        JSON-encoded clusterHostInfo structure
     * @param hostParams             JSON-encoded host parameters
     * @param event                  a ServiceComponentHostServerActionEvent to pass to any created tasks
     * @param roleCommandOrder       the RoleCommandOrder to use to generate the RoleGraph for any newly created Stages
     * @param kerberosDetails        a KerberosDetails containing the information about the relevant Kerberos configuration
     * @param dataDirectory          a File pointing to the (temporary) data directory
     * @param requestStageContainer  a RequestStageContainer to store the new stages in, if null a
     *                               new RequestStageContainer will be created
     * @param serviceComponentHosts  a List of ServiceComponentHosts that needs to be updated as part of this operation
     * @param serviceComponentFilter a Map of service names to component names indicating the relevant
     *                               set of services and components - if null, no filter is relevant;
     *                               if empty, the filter indicates no relevant services or components
     * @param identityFilter         a Collection of identity names indicating the relevant identities -
     *                               if null, no filter is relevant; if empty, the filter indicates no
     *                               relevant identities
     * @return the last stage id generated, or -1 if no stages were created
     * @throws AmbariException if an error occurs while creating the relevant stages
     */
    abstract long createStages(Cluster cluster,
                               String clusterHostInfo, String hostParams,
                               ServiceComponentHostServerActionEvent event,
                               RoleCommandOrder roleCommandOrder,
                               KerberosDetails kerberosDetails, File dataDirectory,
                               RequestStageContainer requestStageContainer,
                               List<ServiceComponentHost> serviceComponentHosts,
                               Map<String, ? extends Collection<String>> serviceComponentFilter, Collection<String> identityFilter, Set<String> hostsWithValidKerberosClient)
        throws AmbariException;


    public void addPrepareEnableKerberosOperationsStage(Cluster cluster, String clusterHostInfoJson,
                                                        String hostParamsJson, ServiceComponentHostServerActionEvent event,
                                                        Map<String, String> commandParameters,
                                                        RoleCommandOrder roleCommandOrder, RequestStageContainer requestStageContainer)
        throws AmbariException {
      Stage stage = createServerActionStage(requestStageContainer.getLastStageId(),
          cluster,
          requestStageContainer.getId(),
          "Preparing Operations",
          clusterHostInfoJson,
          "{}",
          hostParamsJson,
          PrepareEnableKerberosServerAction.class,
          event,
          commandParameters,
          "Preparing Operations",
          1200);

      RoleGraph roleGraph = roleGraphFactory.createNew(roleCommandOrder);
      roleGraph.build(stage);
      requestStageContainer.addStages(roleGraph.getStages());
    }

    public void addPrepareKerberosIdentitiesStage(Cluster cluster, String clusterHostInfoJson,
                                                  String hostParamsJson, ServiceComponentHostServerActionEvent event,
                                                  Map<String, String> commandParameters,
                                                  RoleCommandOrder roleCommandOrder, RequestStageContainer requestStageContainer)
        throws AmbariException {
      Stage stage = createServerActionStage(requestStageContainer.getLastStageId(),
          cluster,
          requestStageContainer.getId(),
          "Preparing Operations",
          clusterHostInfoJson,
          "{}",
          hostParamsJson,
          PrepareKerberosIdentitiesServerAction.class,
          event,
          commandParameters,
          "Preparing Operations",
          1200);

      RoleGraph roleGraph = roleGraphFactory.createNew(roleCommandOrder);
      roleGraph.build(stage);
      requestStageContainer.addStages(roleGraph.getStages());
    }

    public void addPrepareDisableKerberosOperationsStage(Cluster cluster, String clusterHostInfoJson,
                                                         String hostParamsJson, ServiceComponentHostServerActionEvent event,
                                                         Map<String, String> commandParameters,
                                                         RoleCommandOrder roleCommandOrder, RequestStageContainer requestStageContainer)
        throws AmbariException {
      Stage stage = createServerActionStage(requestStageContainer.getLastStageId(),
          cluster,
          requestStageContainer.getId(),
          "Preparing Operations",
          clusterHostInfoJson,
          "{}",
          hostParamsJson,
          PrepareDisableKerberosServerAction.class,
          event,
          commandParameters,
          "Preparing Operations",
          1200);

      RoleGraph roleGraph = roleGraphFactory.createNew(roleCommandOrder);
      roleGraph.build(stage);
      requestStageContainer.addStages(roleGraph.getStages());
    }

    public void addCreatePrincipalsStage(Cluster cluster, String clusterHostInfoJson,
                                         String hostParamsJson, ServiceComponentHostServerActionEvent event,
                                         Map<String, String> commandParameters,
                                         RoleCommandOrder roleCommandOrder, RequestStageContainer requestStageContainer)
        throws AmbariException {
      Stage stage = createServerActionStage(requestStageContainer.getLastStageId(),
          cluster,
          requestStageContainer.getId(),
          "Create Principals",
          clusterHostInfoJson,
          "{}",
          hostParamsJson,
          CreatePrincipalsServerAction.class,
          event,
          commandParameters,
          "Create Principals",
          1200);

      RoleGraph roleGraph = roleGraphFactory.createNew(roleCommandOrder);
      roleGraph.build(stage);
      requestStageContainer.addStages(roleGraph.getStages());
    }

    public void addDestroyPrincipalsStage(Cluster cluster, String clusterHostInfoJson,
                                          String hostParamsJson, ServiceComponentHostServerActionEvent event,
                                          Map<String, String> commandParameters,
                                          RoleCommandOrder roleCommandOrder, RequestStageContainer requestStageContainer)
        throws AmbariException {
      Stage stage = createServerActionStage(requestStageContainer.getLastStageId(),
          cluster,
          requestStageContainer.getId(),
          "Destroy Principals",
          clusterHostInfoJson,
          "{}",
          hostParamsJson,
          DestroyPrincipalsServerAction.class,
          event,
          commandParameters,
          "Destroy Principals",
          1200);

      RoleGraph roleGraph = roleGraphFactory.createNew(roleCommandOrder);
      roleGraph.build(stage);
      requestStageContainer.addStages(roleGraph.getStages());
    }

    public void addCreateKeytabFilesStage(Cluster cluster, String clusterHostInfoJson,
                                          String hostParamsJson, ServiceComponentHostServerActionEvent event,
                                          Map<String, String> commandParameters,
                                          RoleCommandOrder roleCommandOrder, RequestStageContainer requestStageContainer)
        throws AmbariException {
      Stage stage = createServerActionStage(requestStageContainer.getLastStageId(),
          cluster,
          requestStageContainer.getId(),
          "Create Keytabs",
          clusterHostInfoJson,
          "{}",
          hostParamsJson,
          CreateKeytabFilesServerAction.class,
          event,
          commandParameters,
          "Create Keytabs",
          1200);

      RoleGraph roleGraph = roleGraphFactory.createNew(roleCommandOrder);
      roleGraph.build(stage);
      requestStageContainer.addStages(roleGraph.getStages());
    }

    public void addDistributeKeytabFilesStage(Cluster cluster, List<ServiceComponentHost> serviceComponentHosts,
                                              String clusterHostInfoJson, String hostParamsJson,
                                              Map<String, String> commandParameters,
                                              RoleCommandOrder roleCommandOrder,
                                              RequestStageContainer requestStageContainer,
                                              Set<String> hostsWithValidKerberosClient)
        throws AmbariException {

      Stage stage = createNewStage(requestStageContainer.getLastStageId(),
          cluster,
          requestStageContainer.getId(),
          "Distribute Keytabs",
          clusterHostInfoJson,
          StageUtils.getGson().toJson(commandParameters),
          hostParamsJson);

      Collection<ServiceComponentHost> filteredComponents = filterServiceComponentHostsForHosts(
          new ArrayList<ServiceComponentHost>(serviceComponentHosts), hostsWithValidKerberosClient);

      if (!filteredComponents.isEmpty()) {
        List<String> hostsToUpdate = createUniqueHostList(filteredComponents, Collections.singleton(HostState.HEALTHY));
        Map<String, String> requestParams = new HashMap<String, String>();
        List<RequestResourceFilter> requestResourceFilters = new ArrayList<RequestResourceFilter>();
        RequestResourceFilter reqResFilter = new RequestResourceFilter(Service.Type.KERBEROS.name(), Role.KERBEROS_CLIENT.name(), hostsToUpdate);
        requestResourceFilters.add(reqResFilter);

        ActionExecutionContext actionExecContext = new ActionExecutionContext(
            cluster.getClusterName(),
            "SET_KEYTAB",
            requestResourceFilters,
            requestParams);
        customCommandExecutionHelper.addExecutionCommandsToStage(actionExecContext, stage, requestParams, false);
      }

      RoleGraph roleGraph = roleGraphFactory.createNew(roleCommandOrder);
      roleGraph.build(stage);
      requestStageContainer.addStages(roleGraph.getStages());
    }

    /**
     * Filter out ServiceComponentHosts that are on on hosts in the specified set of host names.
     * <p/>
     * It is expected that the supplied collection is modifiable. It will be modified inplace.
     *
     * @param serviceComponentHosts a collection of ServiceComponentHost items to test
     * @param hosts                 a set of host names indicating valid hosts
     * @return a collection of filtered ServiceComponentHost items
     */
    private Collection<ServiceComponentHost> filterServiceComponentHostsForHosts(Collection<ServiceComponentHost> serviceComponentHosts,
                                                                                 Set<String> hosts) {

      if ((serviceComponentHosts != null) && (hosts != null)) {
        Iterator<ServiceComponentHost> iterator = serviceComponentHosts.iterator();
        while (iterator.hasNext()) {
          ServiceComponentHost sch = iterator.next();

          if (!hosts.contains(sch.getHostName())) {
            iterator.remove();
          }
        }
      }

      return serviceComponentHosts;
    }

    public void addDeleteKeytabFilesStage(Cluster cluster, List<ServiceComponentHost> serviceComponentHosts,
                                          String clusterHostInfoJson, String hostParamsJson,
                                          Map<String, String> commandParameters,
                                          RoleCommandOrder roleCommandOrder,
                                          RequestStageContainer requestStageContainer,
                                          Set<String> hostsWithValidKerberosClient)
        throws AmbariException {

      Stage stage = createNewStage(requestStageContainer.getLastStageId(),
          cluster,
          requestStageContainer.getId(),
          "Delete Keytabs",
          clusterHostInfoJson,
          StageUtils.getGson().toJson(commandParameters),
          hostParamsJson);

      Collection<ServiceComponentHost> filteredComponents = filterServiceComponentHostsForHosts(
          new ArrayList<ServiceComponentHost>(serviceComponentHosts), hostsWithValidKerberosClient);

      if (!filteredComponents.isEmpty()) {
        List<String> hostsToUpdate = createUniqueHostList(filteredComponents, Collections.singleton(HostState.HEALTHY));

        if (!hostsToUpdate.isEmpty()) {
          Map<String, String> requestParams = new HashMap<String, String>();
          List<RequestResourceFilter> requestResourceFilters = new ArrayList<RequestResourceFilter>();
          RequestResourceFilter reqResFilter = new RequestResourceFilter("KERBEROS", "KERBEROS_CLIENT", hostsToUpdate);
          requestResourceFilters.add(reqResFilter);

          ActionExecutionContext actionExecContext = new ActionExecutionContext(
              cluster.getClusterName(),
              "REMOVE_KEYTAB",
              requestResourceFilters,
              requestParams);
          customCommandExecutionHelper.addExecutionCommandsToStage(actionExecContext, stage, requestParams, false);
        }
      }

      RoleGraph roleGraph = roleGraphFactory.createNew(roleCommandOrder);
      roleGraph.build(stage);
      requestStageContainer.addStages(roleGraph.getStages());
    }

    public void addUpdateConfigurationsStage(Cluster cluster, String clusterHostInfoJson,
                                             String hostParamsJson, ServiceComponentHostServerActionEvent event,
                                             Map<String, String> commandParameters,
                                             RoleCommandOrder roleCommandOrder, RequestStageContainer requestStageContainer)
        throws AmbariException {
      Stage stage = createServerActionStage(requestStageContainer.getLastStageId(),
          cluster,
          requestStageContainer.getId(),
          "Update Configurations",
          clusterHostInfoJson,
          "{}",
          hostParamsJson,
          UpdateKerberosConfigsServerAction.class,
          event,
          commandParameters,
          "Update Service Configurations",
          1200);

      RoleGraph roleGraph = roleGraphFactory.createNew(roleCommandOrder);
      roleGraph.build(stage);
      requestStageContainer.addStages(roleGraph.getStages());
    }

    public void addFinalizeOperationStage(Cluster cluster, String clusterHostInfoJson,
                                          String hostParamsJson, ServiceComponentHostServerActionEvent event,
                                          File dataDirectory,
                                          RoleCommandOrder roleCommandOrder, RequestStageContainer requestStageContainer)
        throws AmbariException {

      // Add the finalize stage...
      Map<String, String> commandParameters = new HashMap<String, String>();
      commandParameters.put(KerberosServerAction.AUTHENTICATED_USER_NAME, ambariManagementController.getAuthName());
      if (dataDirectory != null) {
        commandParameters.put(KerberosServerAction.DATA_DIRECTORY, dataDirectory.getAbsolutePath());
      }

      Stage stage = createServerActionStage(requestStageContainer.getLastStageId(),
          cluster,
          requestStageContainer.getId(),
          "Finalize Operations",
          clusterHostInfoJson,
          "{}",
          hostParamsJson,
          FinalizeKerberosServerAction.class,
          event,
          commandParameters,
          "Finalize Operations", 300);

      RoleGraph roleGraph = roleGraphFactory.createNew(roleCommandOrder);
      roleGraph.build(stage);
      requestStageContainer.addStages(roleGraph.getStages());
    }

    public void addCleanupStage(Cluster cluster, String clusterHostInfoJson,
                                String hostParamsJson, ServiceComponentHostServerActionEvent event,
                                Map<String, String> commandParameters,
                                RoleCommandOrder roleCommandOrder, RequestStageContainer requestStageContainer)
        throws AmbariException {
      Stage stage = createServerActionStage(requestStageContainer.getLastStageId(),
          cluster,
          requestStageContainer.getId(),
          "Kerberization Clean Up",
          clusterHostInfoJson,
          "{}",
          hostParamsJson,
          CleanupServerAction.class,
          event,
          commandParameters,
          "Kerberization Clean Up",
          1200);

      RoleGraph roleGraph = roleGraphFactory.createNew(roleCommandOrder);
      roleGraph.build(stage);
      requestStageContainer.addStages(roleGraph.getStages());
    }
  }

  /**
   * EnableKerberosHandler is an implementation of the Handler interface used to enable Kerberos
   * on the relevant cluster
   * <p/>
   * This implementation attempts to set the Service and ServiceComponentHost _desired_ security
   * states to {@link org.apache.ambari.server.state.SecurityState#SECURED_KERBEROS} and the
   * ServiceComponentHost _current_ security state to {@link org.apache.ambari.server.state.SecurityState#SECURING}.
   * <p/>
   * To complete the process, this implementation creates the following stages:
   * <ol>
   * <li>create principals</li>
   * <li>create keytab files</li>
   * <li>distribute keytab files to the appropriate hosts</li>
   * <li>update relevant configurations</li>
   * </ol>
   */
  private class EnableKerberosHandler extends Handler {
    @Override
    public boolean shouldProcess(SecurityState desiredSecurityState, ServiceComponentHost sch) throws AmbariException {
      return (desiredSecurityState == SecurityState.SECURED_KERBEROS) &&
          (sch.getSecurityState() != SecurityState.SECURED_KERBEROS) &&
          (sch.getSecurityState() != SecurityState.SECURING);
    }

    @Override
    public SecurityState getNewDesiredSCHSecurityState() {
      return SecurityState.SECURED_KERBEROS;
    }

    @Override
    public SecurityState getNewSCHSecurityState() {
      return SecurityState.SECURING;
    }

    @Override
    public SecurityState getNewServiceSecurityState() {
      return SecurityState.SECURED_KERBEROS;
    }

    @Override
    public long createStages(Cluster cluster,
                             String clusterHostInfoJson, String hostParamsJson,
                             ServiceComponentHostServerActionEvent event,
                             RoleCommandOrder roleCommandOrder, KerberosDetails kerberosDetails,
                             File dataDirectory, RequestStageContainer requestStageContainer,
                             List<ServiceComponentHost> serviceComponentHosts,
                             Map<String, ? extends Collection<String>> serviceComponentFilter, Collection<String> identityFilter, Set<String> hostsWithValidKerberosClient)
        throws AmbariException {
      // If there are principals, keytabs, and configurations to process, setup the following sages:
      //  1) generate principals
      //  2) generate keytab files
      //  3) distribute keytab files
      //  4) update configurations

      // If a RequestStageContainer does not already exist, create a new one...
      if (requestStageContainer == null) {
        requestStageContainer = new RequestStageContainer(
            actionManager.getNextRequestId(),
            null,
            requestFactory,
            actionManager);
      }

      Map<String, String> commandParameters = new HashMap<String, String>();
      commandParameters.put(KerberosServerAction.AUTHENTICATED_USER_NAME, ambariManagementController.getAuthName());
      commandParameters.put(KerberosServerAction.DEFAULT_REALM, kerberosDetails.getDefaultRealm());
      if (dataDirectory != null) {
        commandParameters.put(KerberosServerAction.DATA_DIRECTORY, dataDirectory.getAbsolutePath());
      }
      if (serviceComponentFilter != null) {
        commandParameters.put(KerberosServerAction.SERVICE_COMPONENT_FILTER, StageUtils.getGson().toJson(serviceComponentFilter));
      }
      if (identityFilter != null) {
        commandParameters.put(KerberosServerAction.IDENTITY_FILTER, StageUtils.getGson().toJson(identityFilter));
      }

      // *****************************************************************
      // Create stage to prepare operations
      addPrepareEnableKerberosOperationsStage(cluster, clusterHostInfoJson, hostParamsJson, event, commandParameters,
          roleCommandOrder, requestStageContainer);

      if (kerberosDetails.manageIdentities()) {
        commandParameters.put(KerberosServerAction.KDC_TYPE, kerberosDetails.getKdcType().name());
        commandParameters.put(KerberosServerAction.ADMINISTRATOR_CREDENTIAL, getEncryptedAdministratorCredentials(cluster));

        // *****************************************************************
        // Create stage to create principals
        addCreatePrincipalsStage(cluster, clusterHostInfoJson, hostParamsJson, event, commandParameters,
            roleCommandOrder, requestStageContainer);

        // *****************************************************************
        // Create stage to generate keytabs
        addCreateKeytabFilesStage(cluster, clusterHostInfoJson, hostParamsJson, event, commandParameters,
            roleCommandOrder, requestStageContainer);

        // *****************************************************************
        // Create stage to distribute keytabs
        addDistributeKeytabFilesStage(cluster, serviceComponentHosts, clusterHostInfoJson, hostParamsJson,
            commandParameters, roleCommandOrder, requestStageContainer, hostsWithValidKerberosClient);
      }

      // *****************************************************************
      // Create stage to update configurations of services
      addUpdateConfigurationsStage(cluster, clusterHostInfoJson, hostParamsJson, event, commandParameters,
          roleCommandOrder, requestStageContainer);

      return requestStageContainer.getLastStageId();
    }
  }

  /**
   * DisableKerberosHandler is an implementation of the Handler interface used to disable Kerberos
   * on the relevant cluster
   * <p/>
   * This implementation attempts to set the Service and ServiceComponentHost _desired_ security
   * states to {@link org.apache.ambari.server.state.SecurityState#UNSECURED} and the ServiceComponentHost
   * _current_ security state to {@link org.apache.ambari.server.state.SecurityState#UNSECURING}.
   * <p/>
   * To complete the process, this implementation creates the following stages:
   * <ol>
   * <li>update relevant configurations</li>
   * <li>delete keytab files</li>
   * <li>remove principals</li>
   * <li>restart services</li>
   * </ol>
   */
  private class DisableKerberosHandler extends Handler {
    @Override
    public boolean shouldProcess(SecurityState desiredSecurityState, ServiceComponentHost sch) throws AmbariException {
      return (desiredSecurityState == SecurityState.UNSECURED) &&
          ((sch.getDesiredSecurityState() != SecurityState.UNSECURED) || (sch.getSecurityState() != SecurityState.UNSECURED)) &&
          (sch.getSecurityState() != SecurityState.UNSECURING);
    }

    @Override
    public SecurityState getNewDesiredSCHSecurityState() {
      return SecurityState.UNSECURED;
    }

    @Override
    public SecurityState getNewSCHSecurityState() {
      return SecurityState.UNSECURING;
    }

    @Override
    public SecurityState getNewServiceSecurityState() {
      return SecurityState.UNSECURED;
    }

    @Override
    public long createStages(Cluster cluster,
                             String clusterHostInfoJson, String hostParamsJson,
                             ServiceComponentHostServerActionEvent event,
                             RoleCommandOrder roleCommandOrder, KerberosDetails kerberosDetails,
                             File dataDirectory, RequestStageContainer requestStageContainer,
                             List<ServiceComponentHost> serviceComponentHosts,
                             Map<String, ? extends Collection<String>> serviceComponentFilter, Collection<String> identityFilter, Set<String> hostsWithValidKerberosClient) throws AmbariException {
      //  1) revert configurations

      // If a RequestStageContainer does not already exist, create a new one...
      if (requestStageContainer == null) {
        requestStageContainer = new RequestStageContainer(
            actionManager.getNextRequestId(),
            null,
            requestFactory,
            actionManager);
      }

      Map<String, String> commandParameters = new HashMap<String, String>();
      commandParameters.put(KerberosServerAction.AUTHENTICATED_USER_NAME, ambariManagementController.getAuthName());
      commandParameters.put(KerberosServerAction.DEFAULT_REALM, kerberosDetails.getDefaultRealm());
      if (dataDirectory != null) {
        commandParameters.put(KerberosServerAction.DATA_DIRECTORY, dataDirectory.getAbsolutePath());
      }
      if (serviceComponentFilter != null) {
        commandParameters.put(KerberosServerAction.SERVICE_COMPONENT_FILTER, StageUtils.getGson().toJson(serviceComponentFilter));
      }
      if (identityFilter != null) {
        commandParameters.put(KerberosServerAction.IDENTITY_FILTER, StageUtils.getGson().toJson(identityFilter));
      }

      // *****************************************************************
      // Create stage to prepare operations
      addPrepareDisableKerberosOperationsStage(cluster, clusterHostInfoJson, hostParamsJson, event, commandParameters,
          roleCommandOrder, requestStageContainer);

      // *****************************************************************
      // Create stage to update configurations of services
      addUpdateConfigurationsStage(cluster, clusterHostInfoJson, hostParamsJson, event, commandParameters,
          roleCommandOrder, requestStageContainer);

      if (kerberosDetails.manageIdentities()) {
        commandParameters.put(KerberosServerAction.KDC_TYPE, kerberosDetails.getKdcType().name());
        commandParameters.put(KerberosServerAction.ADMINISTRATOR_CREDENTIAL, getEncryptedAdministratorCredentials(cluster));

        // *****************************************************************
        // Create stage to remove principals
        addDestroyPrincipalsStage(cluster, clusterHostInfoJson, hostParamsJson, event, commandParameters,
            roleCommandOrder, requestStageContainer);

        // *****************************************************************
        // Create stage to delete keytabs
        addDeleteKeytabFilesStage(cluster, serviceComponentHosts, clusterHostInfoJson,
            hostParamsJson, commandParameters, roleCommandOrder, requestStageContainer, hostsWithValidKerberosClient);
      }

      // *****************************************************************
      // Create stage to perform data cleanups (e.g. kerberos descriptor artifact database leftovers)
      addCleanupStage(cluster, clusterHostInfoJson, hostParamsJson, event, commandParameters,
          roleCommandOrder, requestStageContainer);


      return requestStageContainer.getLastStageId();
    }
  }

  /**
   * CreatePrincipalsAndKeytabsHandler is an implementation of the Handler interface used to create
   * principals and keytabs and distribute them throughout the cluster.  This is similar to enabling
   * Kerberos however no states or configurations will be updated.
   * <p/>
   * To complete the process, this implementation creates the following stages:
   * <ol>
   * <li>create principals</li>
   * <li>create keytab files</li>
   * <li>distribute keytab files to the appropriate hosts</li>
   * </ol>
   */
  private class CreatePrincipalsAndKeytabsHandler extends Handler {
    /**
     * A boolean value indicating whether to create keytabs for all principals (<code>true</code>)
     * or only the ones that are missing (<code>false</code>).
     */
    private boolean regenerateAllKeytabs;

    /**
     * CreatePrincipalsAndKeytabsHandler constructor to set whether this instance should be used to
     * regenerate all keytabs or just the ones that have not been distributed
     *
     * @param regenerateAllKeytabs A boolean value indicating whether to create keytabs for all
     *                             principals (<code>true</code> or only the ones that are missing
     *                             (<code>false</code>)
     */
    public CreatePrincipalsAndKeytabsHandler(boolean regenerateAllKeytabs) {
      this.regenerateAllKeytabs = regenerateAllKeytabs;
    }

    @Override
    public boolean shouldProcess(SecurityState desiredSecurityState, ServiceComponentHost sch) throws AmbariException {
      return true;
    }

    @Override
    public SecurityState getNewDesiredSCHSecurityState() {
      return null;
    }

    @Override
    public SecurityState getNewSCHSecurityState() {
      return null;
    }

    @Override
    public SecurityState getNewServiceSecurityState() {
      return null;
    }

    @Override
    public long createStages(Cluster cluster,
                             String clusterHostInfoJson, String hostParamsJson,
                             ServiceComponentHostServerActionEvent event,
                             RoleCommandOrder roleCommandOrder, KerberosDetails kerberosDetails,
                             File dataDirectory, RequestStageContainer requestStageContainer,
                             List<ServiceComponentHost> serviceComponentHosts,
                             Map<String, ? extends Collection<String>> serviceComponentFilter, Collection<String> identityFilter, Set<String> hostsWithValidKerberosClient)
        throws AmbariException {

      // If a RequestStageContainer does not already exist, create a new one...
      if (requestStageContainer == null) {
        requestStageContainer = new RequestStageContainer(
            actionManager.getNextRequestId(),
            null,
            requestFactory,
            actionManager);
      }

      if (kerberosDetails.manageIdentities()) {
        // If there are principals and keytabs to process, setup the following sages:
        //  1) prepare identities
        //  2) generate principals
        //  3) generate keytab files
        //  4) distribute keytab files

        Map<String, String> commandParameters = new HashMap<String, String>();
        commandParameters.put(KerberosServerAction.AUTHENTICATED_USER_NAME, ambariManagementController.getAuthName());
        commandParameters.put(KerberosServerAction.DEFAULT_REALM, kerberosDetails.getDefaultRealm());
        if (dataDirectory != null) {
          commandParameters.put(KerberosServerAction.DATA_DIRECTORY, dataDirectory.getAbsolutePath());
        }
        if (serviceComponentFilter != null) {
          commandParameters.put(KerberosServerAction.SERVICE_COMPONENT_FILTER, StageUtils.getGson().toJson(serviceComponentFilter));
        }
        if (identityFilter != null) {
          commandParameters.put(KerberosServerAction.IDENTITY_FILTER, StageUtils.getGson().toJson(identityFilter));
        }

        commandParameters.put(KerberosServerAction.KDC_TYPE, kerberosDetails.getKdcType().name());
        commandParameters.put(KerberosServerAction.ADMINISTRATOR_CREDENTIAL, getEncryptedAdministratorCredentials(cluster));
        commandParameters.put(KerberosServerAction.REGENERATE_ALL, (regenerateAllKeytabs) ? "true" : "false");

        // *****************************************************************
        // Create stage to create principals
        addPrepareKerberosIdentitiesStage(cluster, clusterHostInfoJson, hostParamsJson, event,
            commandParameters, roleCommandOrder, requestStageContainer);

        // *****************************************************************
        // Create stage to create principals
        addCreatePrincipalsStage(cluster, clusterHostInfoJson, hostParamsJson, event,
            commandParameters, roleCommandOrder, requestStageContainer);

        // *****************************************************************
        // Create stage to generate keytabs
        addCreateKeytabFilesStage(cluster, clusterHostInfoJson, hostParamsJson, event,
            commandParameters, roleCommandOrder, requestStageContainer);

        // Create stage to distribute keytabs
        addDistributeKeytabFilesStage(cluster, serviceComponentHosts, clusterHostInfoJson,
            hostParamsJson, commandParameters, roleCommandOrder, requestStageContainer, hostsWithValidKerberosClient);
      }

      return requestStageContainer.getLastStageId();
    }
  }

  /**
   * DeletePrincipalsAndKeytabsHandler is an implementation of the Handler interface used to delete
   * principals and keytabs throughout the cluster.
   * <p/>
   * To complete the process, this implementation creates the following stages:
   * <ol>
   * <li>delete principals</li>
   * <li>remove keytab files</li>
   * </ol>
   */
  private class DeletePrincipalsAndKeytabsHandler extends Handler {

    @Override
    public boolean shouldProcess(SecurityState desiredSecurityState, ServiceComponentHost sch) throws AmbariException {
      return true;
    }

    @Override
    public SecurityState getNewDesiredSCHSecurityState() {
      return null;
    }

    @Override
    public SecurityState getNewSCHSecurityState() {
      return null;
    }

    @Override
    public SecurityState getNewServiceSecurityState() {
      return null;
    }

    @Override
    public long createStages(Cluster cluster,
                             String clusterHostInfoJson, String hostParamsJson,
                             ServiceComponentHostServerActionEvent event,
                             RoleCommandOrder roleCommandOrder, KerberosDetails kerberosDetails,
                             File dataDirectory, RequestStageContainer requestStageContainer,
                             List<ServiceComponentHost> serviceComponentHosts,
                             Map<String, ? extends Collection<String>> serviceComponentFilter, Collection<String> identityFilter, Set<String> hostsWithValidKerberosClient)
        throws AmbariException {

      // If a RequestStageContainer does not already exist, create a new one...
      if (requestStageContainer == null) {
        requestStageContainer = new RequestStageContainer(
            actionManager.getNextRequestId(),
            null,
            requestFactory,
            actionManager);
      }

      if (kerberosDetails.manageIdentities()) {
        // If there are principals and keytabs to process, setup the following sages:
        //  1) prepare
        //  2) delete principals
        //  3) delete keytab files

        Map<String, String> commandParameters = new HashMap<String, String>();
        commandParameters.put(KerberosServerAction.AUTHENTICATED_USER_NAME, ambariManagementController.getAuthName());
        commandParameters.put(KerberosServerAction.DEFAULT_REALM, kerberosDetails.getDefaultRealm());
        if (dataDirectory != null) {
          commandParameters.put(KerberosServerAction.DATA_DIRECTORY, dataDirectory.getAbsolutePath());
        }
        if (serviceComponentFilter != null) {
          commandParameters.put(KerberosServerAction.SERVICE_COMPONENT_FILTER, StageUtils.getGson().toJson(serviceComponentFilter));
        }
        if (identityFilter != null) {
          commandParameters.put(KerberosServerAction.IDENTITY_FILTER, StageUtils.getGson().toJson(identityFilter));
        }

        commandParameters.put(KerberosServerAction.KDC_TYPE, kerberosDetails.getKdcType().name());
        commandParameters.put(KerberosServerAction.ADMINISTRATOR_CREDENTIAL, getEncryptedAdministratorCredentials(cluster));

        // *****************************************************************
        // Create stage to create principals
        addPrepareKerberosIdentitiesStage(cluster, clusterHostInfoJson, hostParamsJson, event,
            commandParameters, roleCommandOrder, requestStageContainer);

        // *****************************************************************
        // Create stage to delete principals
        addDestroyPrincipalsStage(cluster, clusterHostInfoJson, hostParamsJson, event,
            commandParameters, roleCommandOrder, requestStageContainer);

        // *****************************************************************
        // Create stage to delete keytabs
        addDeleteKeytabFilesStage(cluster, serviceComponentHosts, clusterHostInfoJson,
            hostParamsJson, commandParameters, roleCommandOrder, requestStageContainer, hostsWithValidKerberosClient);
      }

      return requestStageContainer.getLastStageId();
    }
  }

  /**
   * KerberosDetails is a helper class to hold the details of the relevant Kerberos-specific
   * configurations so they may be passed around more easily.
   */
  private static class KerberosDetails {
    private String defaultRealm;
    private KDCType kdcType;
    private Map<String, String> kerberosEnvProperties;
    private SecurityType securityType;
    private Boolean manageIdentities;

    public void setDefaultRealm(String defaultRealm) {
      this.defaultRealm = defaultRealm;
    }

    public String getDefaultRealm() {
      return defaultRealm;
    }

    public void setKdcType(KDCType kdcType) {
      this.kdcType = kdcType;
    }

    public KDCType getKdcType() {
      return kdcType;
    }

    public void setKerberosEnvProperties(Map<String, String> kerberosEnvProperties) {
      this.kerberosEnvProperties = kerberosEnvProperties;
    }

    public Map<String, String> getKerberosEnvProperties() {
      return kerberosEnvProperties;
    }

    public void setSecurityType(SecurityType securityType) {
      this.securityType = securityType;
    }

    public SecurityType getSecurityType() {
      return securityType;
    }

    public boolean manageIdentities() {
      if (manageIdentities == null) {
        return (kerberosEnvProperties == null) ||
            !"false".equalsIgnoreCase(kerberosEnvProperties.get("manage_identities"));
      } else {
        return manageIdentities;
      }
    }

    public void setManageIdentities(Boolean manageIdentities) {
      this.manageIdentities = manageIdentities;
    }
  }
}
