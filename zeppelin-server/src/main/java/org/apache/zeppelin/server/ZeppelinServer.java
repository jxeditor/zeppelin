/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.zeppelin.server;

import com.codahale.metrics.servlets.HealthCheckServlet;
import com.codahale.metrics.servlets.PingServlet;
import com.google.gson.Gson;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jetty.InstrumentedQueuedThreadPool;
import io.micrometer.core.instrument.binder.jetty.JettyConnectionMetrics;
import io.micrometer.core.instrument.binder.jetty.JettySslHandshakeMetrics;
import io.micrometer.core.instrument.binder.jetty.TimedHandler;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import io.micrometer.jmx.JmxConfig;
import io.micrometer.jmx.JmxMeterRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.EnumSet;
import javax.inject.Singleton;
import javax.management.remote.JMXServiceURL;
import javax.servlet.DispatcherType;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.websocket.server.ServerEndpointConfig;

import org.apache.commons.lang3.StringUtils;
import org.apache.shiro.web.env.EnvironmentLoaderListener;
import org.apache.shiro.web.servlet.ShiroFilter;
import org.apache.zeppelin.cluster.ClusterManagerServer;
import org.apache.zeppelin.conf.ZeppelinConfiguration;
import org.apache.zeppelin.conf.ZeppelinConfiguration.ConfVars;
import org.apache.zeppelin.display.AngularObjectRegistryListener;
import org.apache.zeppelin.healthcheck.HealthChecks;
import org.apache.zeppelin.helium.ApplicationEventListener;
import org.apache.zeppelin.helium.Helium;
import org.apache.zeppelin.helium.HeliumApplicationFactory;
import org.apache.zeppelin.helium.HeliumBundleFactory;
import org.apache.zeppelin.interpreter.InterpreterFactory;
import org.apache.zeppelin.interpreter.InterpreterSetting;
import org.apache.zeppelin.interpreter.InterpreterSettingManager;
import org.apache.zeppelin.interpreter.recovery.RecoveryStorage;
import org.apache.zeppelin.interpreter.remote.RemoteInterpreterProcessListener;
import org.apache.zeppelin.metric.JVMInfoBinder;
import org.apache.zeppelin.metric.PrometheusServlet;
import org.apache.zeppelin.notebook.NoteEventListener;
import org.apache.zeppelin.notebook.NoteManager;
import org.apache.zeppelin.notebook.NoteParser;
import org.apache.zeppelin.notebook.Notebook;
import org.apache.zeppelin.notebook.AuthorizationService;
import org.apache.zeppelin.notebook.GsonNoteParser;
import org.apache.zeppelin.notebook.Paragraph;
import org.apache.zeppelin.notebook.repo.NotebookRepo;
import org.apache.zeppelin.notebook.repo.NotebookRepoSync;
import org.apache.zeppelin.notebook.scheduler.NoSchedulerService;
import org.apache.zeppelin.notebook.scheduler.QuartzSchedulerService;
import org.apache.zeppelin.notebook.scheduler.SchedulerService;
import org.apache.zeppelin.plugin.PluginManager;
import org.apache.zeppelin.rest.exception.WebApplicationExceptionMapper;
import org.apache.zeppelin.search.LuceneSearch;
import org.apache.zeppelin.search.NoSearchService;
import org.apache.zeppelin.search.SearchService;
import org.apache.zeppelin.service.*;
import org.apache.zeppelin.service.AuthenticationService;
import org.apache.zeppelin.socket.ConnectionManager;
import org.apache.zeppelin.socket.NotebookServer;
import org.apache.zeppelin.socket.SessionConfigurator;
import org.apache.zeppelin.storage.ConfigStorage;
import org.apache.zeppelin.user.AuthenticationInfo;
import org.apache.zeppelin.user.Credentials;
import org.apache.zeppelin.util.ReflectionUtils;
import org.apache.zeppelin.utils.PEMImporter;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.jmx.ConnectorServer;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.ForwardedRequestCustomizer;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.hk2.api.ServiceLocatorFactory;
import org.glassfish.hk2.utilities.ServiceLocatorUtilities;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.servlet.ServletProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Main class of Zeppelin. */
public class ZeppelinServer implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(ZeppelinServer.class);
  private static final String WEB_APP_CONTEXT_NEXT = "/next";
  public static final String DEFAULT_SERVICE_LOCATOR_NAME = "shared-locator";

  private final AtomicBoolean duringShutdown = new AtomicBoolean(false);
  private final ZeppelinConfiguration conf;
  private final Optional<PrometheusMeterRegistry> promMetricRegistry;
  private final Server jettyWebServer;
  private final ServiceLocator sharedServiceLocator;
  private final ConfigStorage storage;

  public ZeppelinServer(ZeppelinConfiguration conf) throws IOException {
    this(conf, DEFAULT_SERVICE_LOCATOR_NAME);
  }

  public ZeppelinServer(ZeppelinConfiguration conf, String serviceLocatorName) throws IOException {
    LOG.info("Instantiated ZeppelinServer");
    this.conf = conf;
    if (conf.isPrometheusMetricEnabled()) {
      promMetricRegistry = Optional.of(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));
    } else {
      promMetricRegistry = Optional.empty();
    }
    jettyWebServer = setupJettyServer();
    sharedServiceLocator = ServiceLocatorFactory.getInstance().create(serviceLocatorName);
    storage = ConfigStorage.createConfigStorage(conf);
  }

  public void startZeppelin() {
    initMetrics();

    TimedHandler timedHandler = new TimedHandler(Metrics.globalRegistry, Tags.empty());
    jettyWebServer.setHandler(timedHandler);

    ContextHandlerCollection contexts = new ContextHandlerCollection();
    timedHandler.setHandler(contexts);
    ServiceLocatorUtilities.enableImmediateScope(sharedServiceLocator);
    ServiceLocatorUtilities.addClasses(sharedServiceLocator,
      ImmediateErrorHandlerImpl.class);
    ImmediateErrorHandlerImpl handler = sharedServiceLocator.getService(ImmediateErrorHandlerImpl.class);


    ServiceLocatorUtilities.bind(
        sharedServiceLocator,
        new AbstractBinder() {
          @Override
          protected void configure() {
            bind(storage).to(ConfigStorage.class);
            bindAsContract(PluginManager.class).in(Singleton.class);
            bind(GsonNoteParser.class).to(NoteParser.class).in(Singleton.class);
            bindAsContract(InterpreterFactory.class).in(Singleton.class);
            bindAsContract(NotebookRepoSync.class).to(NotebookRepo.class).in(Singleton.class);
            bindAsContract(Helium.class).in(Singleton.class);
            bind(conf).to(ZeppelinConfiguration.class);
            bindAsContract(InterpreterSettingManager.class).in(Singleton.class);
            bindAsContract(InterpreterService.class).in(Singleton.class);
            bindAsContract(Credentials.class).in(Singleton.class);
            bindAsContract(AdminService.class).in(Singleton.class);
            bindAsContract(AuthorizationService.class).in(Singleton.class);
            bindAsContract(ConnectionManager.class).in(Singleton.class);
            bindAsContract(NoteManager.class).in(Singleton.class);
            // TODO(jl): Will make it more beautiful
            if (!StringUtils.isBlank(conf.getShiroPath())) {
              bind(ShiroAuthenticationService.class).to(AuthenticationService.class).in(Singleton.class);
            } else {
              // TODO(jl): Will be added more type
              bind(NoAuthenticationService.class).to(AuthenticationService.class).in(Singleton.class);
            }
            bindAsContract(HeliumBundleFactory.class).in(Singleton.class);
            bindAsContract(HeliumApplicationFactory.class).in(Singleton.class);
            bindAsContract(ConfigurationService.class).in(Singleton.class);
            bindAsContract(NotebookService.class).in(Singleton.class);
            bindAsContract(JobManagerService.class).in(Singleton.class);
            bindAsContract(Notebook.class).in(Singleton.class);
            bindAsContract(NotebookServer.class)
                .to(AngularObjectRegistryListener.class)
                .to(RemoteInterpreterProcessListener.class)
                .to(ApplicationEventListener.class)
                .to(NoteEventListener.class)
                .to(WebSocketServlet.class)
                .in(Singleton.class);
            if (conf.isZeppelinNotebookCronEnable()) {
              bind(QuartzSchedulerService.class).to(SchedulerService.class).in(Singleton.class);
            } else {
              bind(NoSchedulerService.class).to(SchedulerService.class).in(Singleton.class);
            }
            if (conf.getBoolean(ConfVars.ZEPPELIN_SEARCH_ENABLE)) {
              bind(LuceneSearch.class).to(SearchService.class).in(Singleton.class);
            } else {
              bind(NoSearchService.class).to(SearchService.class).in(Singleton.class);
            }
          }
        });

    // Multiple Web UI
    final WebAppContext defaultWebApp = setupWebAppContext(contexts, conf, conf.getString(ConfVars.ZEPPELIN_WAR), conf.getServerContextPath());
    final WebAppContext nextWebApp = setupWebAppContext(contexts, conf, conf.getString(ConfVars.ZEPPELIN_ANGULAR_WAR), WEB_APP_CONTEXT_NEXT);

    initWebApp(defaultWebApp);
    initWebApp(nextWebApp);

    NotebookRepo repo =
        ServiceLocatorUtilities.getService(sharedServiceLocator, NotebookRepo.class.getName());
    NoteParser noteParser =
        ServiceLocatorUtilities.getService(sharedServiceLocator, NoteParser.class.getName());
    try {
      repo.init(conf, noteParser);
    } catch (IOException e) {
      LOG.error("Failed to init NotebookRepo", e);
    }
    // Cluster Manager Server
    setupClusterManagerServer();

    initJMX();

    runNoteOnStart(sharedServiceLocator);
    Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));

    // Try to get Notebook from ServiceLocator, because Notebook instantiation is lazy, it is
    // created when user open zeppelin in browser if we don't get it explicitly here.
    // Lazy loading will cause paragraph recovery and cron job initialization is delayed.
    Notebook notebook = ServiceLocatorUtilities.getService(
            sharedServiceLocator, Notebook.class.getName());
    ServiceLocatorUtilities.getService(
      sharedServiceLocator, SearchService.class.getName());
    ServiceLocatorUtilities.getService(
      sharedServiceLocator, SchedulerService.class.getName());
    // Initialization of the Notes in the notebook asynchronously
    notebook.initNotebook();
    // Try to recover here, don't do it in constructor of Notebook, because it would cause deadlock.
    notebook.recoveryIfNecessary();

    LOG.info("Starting zeppelin server");
    try {
      jettyWebServer.start(); // Instantiates ZeppelinServer
      if (conf.getJettyName() != null) {
        org.eclipse.jetty.http.HttpGenerator.setJettyVersion(conf.getJettyName());
      }
    } catch (Exception e) {
      LOG.error("Error while running jettyServer", e);
      System.exit(-1);
    }

    LOG.info("Done, zeppelin server started");
    try {
      List<ErrorData> errorDatas = handler.waitForAtLeastOneConstructionError(5000);
      for (ErrorData errorData : errorDatas) {
        LOG.error("Error in Construction", errorData.getThrowable());
      }
      if (!errorDatas.isEmpty()) {
        LOG.error("{} error(s) while starting - Termination", errorDatas.size());
        System.exit(-1);
      }
    } catch (InterruptedException e) {
      // Many fast unit tests interrupt the Zeppelin server at this point
      LOG.error("Interrupt while waiting for construction errors - init shutdown", e);
      shutdown();
      Thread.currentThread().interrupt();
    }

    if (jettyWebServer.isStopped() || jettyWebServer.isStopping()) {
      LOG.debug("jetty server is stopped {} - is stopping {}", jettyWebServer.isStopped(), jettyWebServer.isStopping());
    } else {
      try {
        jettyWebServer.join();
      } catch (InterruptedException e) {
        LOG.error("Interrupt while waiting for jetty threads - init shutdown", e);
        shutdown();
        Thread.currentThread().interrupt();
      }
    }
  }

  public static void main(String[] args) throws Exception {
    ZeppelinConfiguration conf = ZeppelinConfiguration.load();
    conf.printShortInfo();
    try (ZeppelinServer server = new ZeppelinServer(conf)) {
      server.startZeppelin();
    }
  }

  private void initJMX() {
    // JMX Enable
    if (conf.isJMXEnabled()) {
      int port = conf.getJMXPort();
      // Setup JMX
      MBeanContainer mbeanContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
      jettyWebServer.addBean(mbeanContainer);
      JMXServiceURL jmxURL;
      try {
        jmxURL = new JMXServiceURL(
            String.format(
                "service:jmx:rmi://0.0.0.0:%d/jndi/rmi://0.0.0.0:%d/jmxrmi",
                port, port));
        ConnectorServer jmxServer = new ConnectorServer(jmxURL, "org.eclipse.jetty.jmx:name=rmiconnectorserver");
        jettyWebServer.addBean(jmxServer);
        LOG.info("JMX Enabled with port: {}", port);
      } catch (MalformedURLException e) {
        LOG.error("Invalid JMXServiceURL - JMX Disabled", e);
      }
    }
  }
  private void initMetrics() {
    if (conf.isJMXEnabled()) {
      Metrics.addRegistry(new JmxMeterRegistry(JmxConfig.DEFAULT, Clock.SYSTEM));
    }
    if (promMetricRegistry.isPresent()) {
      Metrics.addRegistry(promMetricRegistry.get());
    }
    new ClassLoaderMetrics().bindTo(Metrics.globalRegistry);
    new JvmMemoryMetrics().bindTo(Metrics.globalRegistry);
    new JvmThreadMetrics().bindTo(Metrics.globalRegistry);
    new FileDescriptorMetrics().bindTo(Metrics.globalRegistry);
    new ProcessorMetrics().bindTo(Metrics.globalRegistry);
    new UptimeMetrics().bindTo(Metrics.globalRegistry);
    new JVMInfoBinder().bindTo(Metrics.globalRegistry);
  }

  public void shutdown(int exitCode) {
    if (!duringShutdown.getAndSet(true)) {
      LOG.info("Shutting down Zeppelin Server ... - ExitCode {}", exitCode);
      try {
        if (jettyWebServer != null) {
          jettyWebServer.stop();
        }
        if (sharedServiceLocator != null) {
          if (!conf.isRecoveryEnabled()) {
            sharedServiceLocator.getService(InterpreterSettingManager.class).close();
          }
          sharedServiceLocator.getService(Notebook.class).close();
        }
      } catch (Exception e) {
        LOG.error("Error while stopping servlet container", e);
      }
      LOG.info("Bye");
      if (exitCode != 0) {
        System.exit(exitCode);
      }
    }
  }

  public void shutdown() {
    shutdown(0);
  }

  private Server setupJettyServer() {
    InstrumentedQueuedThreadPool threadPool =
      new InstrumentedQueuedThreadPool(Metrics.globalRegistry, Tags.empty(),
                           conf.getInt(ConfVars.ZEPPELIN_SERVER_JETTY_THREAD_POOL_MAX),
                           conf.getInt(ConfVars.ZEPPELIN_SERVER_JETTY_THREAD_POOL_MIN),
                           conf.getInt(ConfVars.ZEPPELIN_SERVER_JETTY_THREAD_POOL_TIMEOUT));
    final Server server = new Server(threadPool);
    initServerConnector(server);
    return server;
  }
  private void initServerConnector(Server server) {

    ServerConnector connector;
    HttpConfiguration httpConfig = new HttpConfiguration();
    httpConfig.addCustomizer(new ForwardedRequestCustomizer());
    httpConfig.setSendServerVersion(conf.sendJettyName());
    httpConfig.setRequestHeaderSize(conf.getJettyRequestHeaderSize());
    if (conf.useSsl()) {
      LOG.debug("Enabling SSL for Zeppelin Server on port {}", conf.getServerSslPort());
      httpConfig.setSecureScheme(HttpScheme.HTTPS.asString());
      httpConfig.setSecurePort(conf.getServerSslPort());

      HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
      httpsConfig.addCustomizer(new SecureRequestCustomizer());

      SslConnectionFactory sslConnectionFactory = new SslConnectionFactory(getSslContextFactory(conf), HttpVersion.HTTP_1_1.asString());
      HttpConnectionFactory httpsConnectionFactory = new HttpConnectionFactory(httpsConfig);
      connector =
              new ServerConnector(
                      server,
                      sslConnectionFactory,
                      httpsConnectionFactory);
      connector.setPort(conf.getServerSslPort());
      connector.addBean(new JettySslHandshakeMetrics(Metrics.globalRegistry, Tags.empty()));
    } else {
      HttpConnectionFactory httpConnectionFactory = new HttpConnectionFactory(httpConfig);
      connector =
              new ServerConnector(
                      server,
                      httpConnectionFactory);
      connector.setPort(conf.getServerPort());
    }
    // Set some timeout options to make debugging easier.
    int timeout = 1000 * 30;
    connector.setIdleTimeout(timeout);
    connector.setHost(conf.getServerAddress());
    connector.addBean(new JettyConnectionMetrics(Metrics.globalRegistry, Tags.empty()));
    server.addConnector(connector);
  }

  private void runNoteOnStart(ServiceLocator sharedServiceLocator) {
    String noteIdToRun = conf.getNotebookRunId();
    if (!StringUtils.isEmpty(noteIdToRun)) {
      LOG.info("Running note {} on start", noteIdToRun);
      NotebookService notebookService = ServiceLocatorUtilities.getService(
              sharedServiceLocator, NotebookService.class.getName());

      ServiceContext serviceContext;
      String base64EncodedJsonSerializedServiceContext = conf.getNotebookRunServiceContext();
      if (StringUtils.isEmpty(base64EncodedJsonSerializedServiceContext)) {
        LOG.info("No service context provided. use ANONYMOUS");
        serviceContext = new ServiceContext(AuthenticationInfo.ANONYMOUS, new HashSet<>());
      } else {
        serviceContext = new Gson().fromJson(
                new String(Base64.getDecoder().decode(base64EncodedJsonSerializedServiceContext)),
                ServiceContext.class);
      }

      try {
        boolean success = notebookService.runAllParagraphs(noteIdToRun, null, serviceContext, new ServiceCallback<Paragraph>() {
          @Override
          public void onStart(String message, ServiceContext context) throws IOException {
          }

          @Override
          public void onSuccess(Paragraph result, ServiceContext context) throws IOException {
          }

          @Override
          public void onFailure(Exception ex, ServiceContext context) throws IOException {
          }
        });
        if (conf.getNotebookRunAutoShutdown()) {
          shutdown(success ? 0 : 1);
        }
      } catch (IOException e) {
        LOG.error("Error during Paragraph Execution", e);
      }
    }
  }

  private void setupNotebookServer(WebAppContext webapp) {
    String maxTextMessageSize = conf.getWebsocketMaxTextMessageSize();
    WebSocketServerContainerInitializer
            .configure(webapp, (servletContext, wsContainer) -> {
              wsContainer.setDefaultMaxTextMessageBufferSize(Integer.parseInt(maxTextMessageSize));
              wsContainer.addEndpoint(ServerEndpointConfig.Builder.create(NotebookServer.class, "/ws")
              .configurator(new SessionConfigurator(sharedServiceLocator)).build());
            });
  }

  private void setupClusterManagerServer() {
    if (conf.isClusterMode()) {
      LOG.info("Cluster mode is enabled, starting ClusterManagerServer");
      ClusterManagerServer clusterManagerServer = ClusterManagerServer.getInstance(conf);

      NotebookServer notebookServer = sharedServiceLocator.getService(NotebookServer.class);
      clusterManagerServer.addClusterEventListeners(ClusterManagerServer.CLUSTER_NOTE_EVENT_TOPIC, notebookServer);

      AuthorizationService authorizationService =
          sharedServiceLocator.getService(AuthorizationService.class);
      clusterManagerServer.addClusterEventListeners(ClusterManagerServer.CLUSTER_AUTH_EVENT_TOPIC, authorizationService);

      InterpreterSettingManager interpreterSettingManager =
          sharedServiceLocator.getService(InterpreterSettingManager.class);
      clusterManagerServer.addClusterEventListeners(ClusterManagerServer.CLUSTER_INTP_SETTING_EVENT_TOPIC, interpreterSettingManager);

      // Since the ClusterInterpreterLauncher is lazy, dynamically generated, So in cluster mode,
      // when the zeppelin service starts, Create a ClusterInterpreterLauncher object,
      // This allows the ClusterInterpreterLauncher to listen for cluster events.
      try {
        InterpreterSettingManager intpSettingManager =
            sharedServiceLocator.getService(InterpreterSettingManager.class);
        RecoveryStorage recoveryStorage = ReflectionUtils.createClazzInstance(
                conf.getRecoveryStorageClass(),
                new Class[] {ZeppelinConfiguration.class, InterpreterSettingManager.class},
                new Object[] {conf, intpSettingManager});
        recoveryStorage.init();
        sharedServiceLocator.getService(PluginManager.class).loadInterpreterLauncher(
            InterpreterSetting.CLUSTER_INTERPRETER_LAUNCHER_NAME, recoveryStorage);
      } catch (IOException e) {
        LOG.error(e.getMessage(), e);
      }

      clusterManagerServer.start();
    } else {
      LOG.info("Cluster mode is disabled");
    }
  }

  private static SslContextFactory getSslContextFactory(ZeppelinConfiguration conf) {
    SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();

    // initialize KeyStore
    // Check for PEM files
    if (StringUtils.isNoneBlank(conf.getPemKeyFile(), conf.getPemCertFile())) {
      setupKeystoreWithPemFiles(sslContextFactory, conf);
    } else {
      // Set keystore
      sslContextFactory.setKeyStorePath(conf.getKeyStorePath());
      sslContextFactory.setKeyStoreType(conf.getKeyStoreType());
      sslContextFactory.setKeyStorePassword(conf.getKeyStorePassword());
      sslContextFactory.setKeyManagerPassword(conf.getKeyManagerPassword());
    }

    // initialize TrustStore
    if (conf.useClientAuth()) {
      if (StringUtils.isNotBlank(conf.getPemCAFile())) {
        setupTruststoreWithPemFiles(sslContextFactory, conf);
      } else {
        sslContextFactory.setNeedClientAuth(conf.useClientAuth());
        // Set truststore
        sslContextFactory.setTrustStorePath(conf.getTrustStorePath());
        sslContextFactory.setTrustStoreType(conf.getTrustStoreType());
        sslContextFactory.setTrustStorePassword(conf.getTrustStorePassword());
      }
    }

    return sslContextFactory;
  }

  private static void setupKeystoreWithPemFiles(SslContextFactory.Server sslContextFactory, ZeppelinConfiguration conf) {
    File pemKey = new File(conf.getPemKeyFile());
    File pemCert = new File(conf.getPemCertFile());
    boolean isPemKeyFileReadable = Files.isReadable(pemKey.toPath());
    boolean isPemCertFileReadable = Files.isReadable(pemCert.toPath());
    if (!isPemKeyFileReadable) {
      LOG.warn("PEM key file {} is not readable", pemKey);
    }
    if (!isPemCertFileReadable) {
      LOG.warn("PEM cert file {} is not readable", pemCert);
    }
    if (isPemKeyFileReadable && isPemCertFileReadable) {
      try {
        String password = conf.getPemKeyPassword();
        sslContextFactory.setKeyStore(PEMImporter.loadKeyStore(pemCert, pemKey, password));
        sslContextFactory.setKeyStoreType("JKS");
        sslContextFactory.setKeyStorePassword(password);
      } catch (IOException | GeneralSecurityException e) {
        LOG.error("Failed to initialize KeyStore from PEM files", e);
      }
    } else {
      LOG.error("Failed to read PEM files");
    }
  }

  private static void setupTruststoreWithPemFiles(SslContextFactory.Server sslContextFactory, ZeppelinConfiguration conf) {
    File pemCa = new File(conf.getPemCAFile());
    if (Files.isReadable(pemCa.toPath())) {
      try {
        sslContextFactory.setTrustStore(PEMImporter.loadTrustStore(pemCa));
        sslContextFactory.setTrustStoreType("JKS");
        sslContextFactory.setTrustStorePassword("");
        sslContextFactory.setNeedClientAuth(conf.useClientAuth());
      } catch (IOException | GeneralSecurityException e) {
        LOG.error("Failed to initialize TrustStore from PEM CA file", e);
      }
    } else {
      LOG.error("PEM CA file {} is not readable", pemCa);
    }
  }

  private void setupRestApiContextHandler(WebAppContext webapp) {
    final ServletHolder servletHolder =
        new ServletHolder(new org.glassfish.jersey.servlet.ServletContainer());

    servletHolder.setInitParameter("javax.ws.rs.Application", RestApiApplication.class.getName());
    servletHolder.setName("rest");
    webapp.addServlet(servletHolder, "/api/*");

    String shiroIniPath = conf.getShiroPath();
    if (!StringUtils.isBlank(shiroIniPath)) {
      webapp.setInitParameter("shiroConfigLocations", new File(shiroIniPath).toURI().toString());
      webapp
          .addFilter(ShiroFilter.class, "/api/*", EnumSet.allOf(DispatcherType.class))
          .setInitParameter("staticSecurityManagerEnabled", "true");
      webapp.addEventListener(new EnvironmentLoaderListener());
    }
  }

  private void setupPrometheusContextHandler(WebAppContext webapp) {
    if (promMetricRegistry.isPresent()) {
      webapp.addServlet(new ServletHolder(new PrometheusServlet(promMetricRegistry.get())), "/metrics");
    }
  }

  private static void setupHealthCheckContextHandler(WebAppContext webapp) {
    webapp.addServlet(new ServletHolder(new HealthCheckServlet(HealthChecks.getHealthCheckReadinessRegistry())), "/health/readiness");
    webapp.addServlet(new ServletHolder(new HealthCheckServlet(HealthChecks.getHealthCheckLivenessRegistry())), "/health/liveness");
    webapp.addServlet(new ServletHolder(new PingServlet()), "/ping");
  }

  private static WebAppContext setupWebAppContext(
      ContextHandlerCollection contexts, ZeppelinConfiguration conf, String warPath, String contextPath) {
    WebAppContext webApp = new WebAppContext();
    webApp.setContextPath(contextPath);
    LOG.info("warPath is: {}", warPath);
    File warFile = new File(warPath);
    if (warFile.isDirectory()) {
      // Development mode, read from FS
      // webApp.setDescriptor(warPath+"/WEB-INF/web.xml");
      webApp.setResourceBase(warFile.getPath());
    } else {
      // use packaged WAR
      webApp.setWar(warFile.getAbsolutePath());
      webApp.setExtractWAR(false);
      File warTempDirectory = new File(conf.getAbsoluteDir(ConfVars.ZEPPELIN_WAR_TEMPDIR) + contextPath);
      warTempDirectory.mkdir();
      LOG.info("ZeppelinServer Webapp path: {}", warTempDirectory.getPath());
      webApp.setTempDirectory(warTempDirectory);
    }
    // Explicit bind to root
    webApp.addServlet(new ServletHolder(new IndexHtmlServlet(conf)), "/index.html");
    contexts.addHandler(webApp);

    webApp.addFilter(new FilterHolder(new CorsFilter(conf)), "/*", EnumSet.allOf(DispatcherType.class));

    webApp.setInitParameter(
        "org.eclipse.jetty.servlet.Default.dirAllowed",
        Boolean.toString(conf.getBoolean(ConfVars.ZEPPELIN_SERVER_DEFAULT_DIR_ALLOWED)));
    return webApp;
  }

  private void initWebApp(WebAppContext webApp) {
    webApp.addEventListener(
            new ServletContextListener() {
              @Override
              public void contextInitialized(ServletContextEvent servletContextEvent) {
                servletContextEvent
                        .getServletContext()
                        .setAttribute(ServletProperties.SERVICE_LOCATOR, sharedServiceLocator);
              }

              @Override
              public void contextDestroyed(ServletContextEvent servletContextEvent) {}
            });

    // Create `ZeppelinServer` using reflection and setup REST Api
    setupRestApiContextHandler(webApp);

    // prometheus endpoint
    setupPrometheusContextHandler(webApp);
    // health endpoints
    setupHealthCheckContextHandler(webApp);

    // Notebook server
    setupNotebookServer(webApp);
  }

  @Override
  public void close() throws Exception {
    shutdown();
  }
}
