/*
 * JBoss, Home of Professional Open Source
 * Copyright 2005, JBoss Inc., and individual contributors as indicated
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
package org.jboss.osgi.framework.internal;

import static org.jboss.osgi.framework.internal.InternalServices.AUTOINSTALL_BUNDLES;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;
import org.jboss.msc.service.AbstractService;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController.Mode;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.value.InjectedValue;
import org.jboss.osgi.deployment.deployer.Deployment;
import org.jboss.osgi.deployment.deployer.DeploymentFactory;
import org.jboss.osgi.framework.AutoInstallProvider;
import org.jboss.osgi.framework.ServiceNames;
import org.jboss.osgi.spi.util.BundleInfo;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;

/**
 * A plugin that installs/starts bundles on framework startup.
 *
 * @author thomas.diesler@jboss.com
 * @since 18-Aug-2009
 */
final class AutoInstallProcessor extends AbstractPluginService<AutoInstallProcessor> {

    // Provide logging
    static final Logger log = Logger.getLogger(AutoInstallProcessor.class);

    private final InjectedValue<BundleManager> injectedBundleManager = new InjectedValue<BundleManager>();
    private final InjectedValue<AutoInstallProvider> injectedProvider = new InjectedValue<AutoInstallProvider>();

    static void addService(ServiceTarget serviceTarget) {
        AutoInstallProcessor service = new AutoInstallProcessor();
        ServiceBuilder<AutoInstallProcessor> builder = serviceTarget.addService(AUTOINSTALL_BUNDLES, service);
        builder.addDependency(ServiceNames.AUTOINSTALL_PROVIDER, AutoInstallProvider.class, service.injectedProvider);
        builder.addDependency(ServiceNames.BUNDLE_MANAGER, BundleManager.class, service.injectedBundleManager);
        builder.addDependency(ServiceNames.FRAMEWORK_INIT);
        builder.setInitialMode(Mode.ON_DEMAND);
        builder.install();
    }

    private AutoInstallProcessor() {
    }

    @Override
    public void start(StartContext context) throws StartException {
        super.start(context);
        try {
            AutoInstallProvider provider = injectedProvider.getValue();
            List<URL> autoInstall = new ArrayList<URL>(provider.getAutoInstallList());
            List<URL> autoStart = new ArrayList<URL>(provider.getAutoStartList());
            ServiceTarget serviceTarget = context.getChildTarget();
            installBundles(serviceTarget, autoInstall, autoStart);
        } catch (BundleException ex) {
            throw new IllegalStateException("Cannot start auto install bundles", ex);
        }
    }

    @Override
    public AutoInstallProcessor getValue() {
        return this;
    }

    private void installBundles(ServiceTarget serviceTarget, final List<URL> autoInstall, final List<URL> autoStart) throws BundleException {

        // Add the autoStart bundles to autoInstall
        autoInstall.addAll(autoStart);

        final BundleManager bundleManager = injectedBundleManager.getValue();
        final Map<ServiceName, Deployment> pendingServices = new HashMap<ServiceName, Deployment>();

        // Install autoInstall bundles
        for (URL url : autoInstall) {
            BundleInfo info = BundleInfo.createBundleInfo(url);
            Deployment dep = DeploymentFactory.createDeployment(info);
            dep.setAutoStart(autoStart.contains(url));

            ServiceName serviceName = bundleManager.installBundle(serviceTarget, dep);
            pendingServices.put(serviceName, dep);
        }
        
        // Install a service that has a dependency on all pending bundle INSTALLED services
        ServiceName servicesInstalled = InternalServices.AUTOINSTALL_BUNDLES.append("INSTALLED");
        ServiceBuilder<Void> builder = serviceTarget.addService(servicesInstalled, new AbstractService<Void>() {
            public void start(StartContext context) throws StartException {
                log.debugf("Auto bundles installed");
            }
        });
        builder.addDependencies(pendingServices.keySet());
        builder.install();
        
        // Install a service that starts the persistent bundles
        builder = serviceTarget.addService(InternalServices.AUTOINSTALL_BUNDLES_ACTIVE, new AbstractService<Void>() {
            public void start(StartContext context) throws StartException {
                for (Deployment dep : pendingServices.values()) {
                    if (dep.isAutoStart()) {
                        Bundle bundle = dep.getAttachment(Bundle.class);
                        try {
                            bundle.start();
                        } catch (BundleException ex) {
                            log.errorf(ex, "Cannot start persistent bundle: %s", bundle);
                        }
                    }
                }
                log.debugf("Auto bundles bundles started");
            }
        });
        builder.addDependencies(servicesInstalled);
        builder.install();
    }
}