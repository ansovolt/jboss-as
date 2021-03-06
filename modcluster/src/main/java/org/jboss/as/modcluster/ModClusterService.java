/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011, Red Hat Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.modcluster;

import org.apache.catalina.Engine;
import org.jboss.as.network.SocketBinding;
import org.jboss.as.network.SocketBindingManager;
import org.jboss.as.web.WebServer;
import org.jboss.modcluster.config.impl.ModClusterConfig;
import org.jboss.modcluster.container.catalina.CatalinaEventHandlerAdapter;
import org.jboss.modcluster.load.LoadBalanceFactorProvider;
import org.jboss.msc.inject.Injector;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.value.InjectedValue;

import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.jboss.as.modcluster.ModClusterLogger.ROOT_LOGGER;

/**
 * Service configuring and starting modcluster.
 *
 * @author Jean-Frederic Clere
 */
class ModClusterService implements ModCluster, Service<ModCluster> {

    static final ServiceName NAME = ServiceName.JBOSS.append("mod-cluster");


    private CatalinaEventHandlerAdapter adapter;
    private LoadBalanceFactorProvider load;
    private ModClusterConfig config;

    private final InjectedValue<WebServer> webServer = new InjectedValue<WebServer>();
    private final InjectedValue<SocketBindingManager> bindingManager = new InjectedValue<SocketBindingManager>();
    private final InjectedValue<SocketBinding> binding = new InjectedValue<SocketBinding>();

    /* Depending on configuration we use one of the other */
    private org.jboss.modcluster.ModClusterService service;

    ModClusterService(ModClusterConfig config, LoadBalanceFactorProvider load) {
        this.config = config;
        this.load = load;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void start(StartContext context) throws StartException {
        ROOT_LOGGER.debugf("Starting Mod_cluster Extension");

        boolean isMulticast = isMulticastEnabled(bindingManager.getValue().getDefaultInterfaceBinding().getNetworkInterfaces());

        // Set some defaults...
        if (config.getProxyList() == null) {
            config.setAdvertise(isMulticast);
        }

        // Read node to set configuration.
        if (config.getAdvertise()) {
            // There should be a socket-binding....
            final SocketBinding binding = this.binding.getValue();
            if (binding != null) {
                config.setAdvertisePort(binding.getMulticastPort());
                config.setAdvertiseGroupAddress(binding.getMulticastSocketAddress().getHostName());
                config.setAdvertiseInterface(binding.getSocketAddress().getAddress().getHostAddress());
                if (!isMulticast) {
                    ROOT_LOGGER.multicastInterfaceNotAvailable();
                }
            }
        }
        if (config.getExcludedContexts() != null) {
            // read the default host.
            String defaulthost = ((Engine) webServer.getValue().getService().getContainer()).getDefaultHost();
            StringBuilder excludedContexts = new StringBuilder();
            for (String excludedContext : config.getExcludedContexts().split(",")) {
                String[] parts = excludedContext.trim().split(":");
                if (parts.length != 1) {
                    excludedContexts.append(",").append(excludedContext);
                } else {
                    excludedContexts.append(",").append(defaulthost).append(":").append(excludedContext);
                }
            }
            config.setExcludedContexts(excludedContexts.toString());
        } else {
            config.setExcludedContexts("ROOT,invoker,jbossws,juddi,console");
        }

        service = new org.jboss.modcluster.ModClusterService(config, load);
        adapter = new CatalinaEventHandlerAdapter(service, webServer.getValue().getServer());
        adapter.start();
    }

    private boolean isMulticastEnabled(Collection<NetworkInterface> ifaces) {
        for (NetworkInterface iface : ifaces) {
            try {
                if (iface.isUp() && (iface.supportsMulticast() || iface.isLoopback())) {
                    return true;
                }
            } catch (SocketException e) {
                // Ignore
            }
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void stop(StopContext context) {
        if (adapter != null) {
            adapter.stop();
            adapter = null;
        }
    }

    @Override
    public synchronized ModCluster getValue() throws IllegalStateException, IllegalArgumentException {
        return this;
    }


    public Injector<WebServer> getWebServer() {
        return webServer;
    }

    public Injector<SocketBinding> getBinding() {
        return binding;
    }

    public Injector<SocketBindingManager> getBindingManager() {
        return bindingManager;
    }

    @Override
    public Map<InetSocketAddress, String> getProxyInfo() {
        return service.getProxyInfo();
    }

    @Override
    public void refresh() {
        service.refresh();
    }

    @Override
    public void reset() {
        service.reset();
    }

    @Override
    public void enable() {
        service.enable();
    }

    @Override
    public void disable() {
        service.disable();
    }

    @Override
    public void stop(int waittime) {
        service.stop(waittime, TimeUnit.SECONDS);
    }

    @Override
    public boolean enableContext(String host, String context) {
        return service.enableContext(host, context);
    }

    @Override
    public boolean disableContext(String host, String context) {
        return service.disableContext(host, context);
    }

    @Override
    public boolean stopContext(String host, String context, int waittime) {
        return service.stopContext(host, context, waittime, TimeUnit.SECONDS);
    }

    @Override
    public void addProxy(String host, int port) {
        service.addProxy(host, port);
    }

    @Override
    public void removeProxy(String host, int port) {
        service.removeProxy(host, port);
    }

    @Override
    public Map<InetSocketAddress, String> getProxyConfiguration() {
        return service.getProxyConfiguration();
    }
}
