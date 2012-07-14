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

import static org.jboss.osgi.framework.IntegrationServices.BOOTSTRAP_BUNDLES_INSTALLED;
import static org.jboss.osgi.framework.internal.FrameworkLogger.LOGGER;
import static org.jboss.osgi.framework.internal.FrameworkMessages.MESSAGES;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController.Mode;
import org.jboss.msc.service.ServiceListener;
import org.jboss.msc.service.ServiceRegistry;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.value.InjectedValue;
import org.jboss.osgi.deployment.deployer.Deployment;
import org.jboss.osgi.deployment.deployer.DeploymentFactory;
import org.jboss.osgi.framework.BootstrapBundlesResolved;
import org.jboss.osgi.framework.Constants;
import org.jboss.osgi.framework.Services;
import org.jboss.osgi.spi.BundleInfo;
import org.jboss.osgi.spi.util.StringPropertyReplacer;
import org.jboss.osgi.spi.util.StringPropertyReplacer.PropertyProvider;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;

/**
 * A plugin that installs the auto install bundles on framework startup.
 *
 * @author thomas.diesler@jboss.com
 * @since 18-Aug-2009
 */
class DefaultBootstrapBundlesInstalled extends AbstractPluginService<Void>  {

    private final InjectedValue<BundleManagerPlugin> injectedBundleManager = new InjectedValue<BundleManagerPlugin>();

    static void addIntegrationService(ServiceRegistry registry, ServiceTarget serviceTarget) {
        if (registry.getService(BOOTSTRAP_BUNDLES_INSTALLED) == null) {
            DefaultBootstrapBundlesInstalled service = new DefaultBootstrapBundlesInstalled();
            ServiceBuilder<Void> builder = serviceTarget.addService(BOOTSTRAP_BUNDLES_INSTALLED, service);
            builder.addDependency(Services.BUNDLE_MANAGER, BundleManagerPlugin.class, service.injectedBundleManager);
            builder.addDependency(Services.FRAMEWORK_CREATE);
            builder.setInitialMode(Mode.ON_DEMAND);
            builder.install();
        }
    }

    private DefaultBootstrapBundlesInstalled() {
    }

    @Override
    public void start(StartContext context) throws StartException {
        super.start(context);

        final BundleManagerPlugin bundleManager = injectedBundleManager.getValue();
        final List<URL> autoInstall = new ArrayList<URL>();
        final List<URL> autoStart = new ArrayList<URL>();

        String propValue = (String) bundleManager.getProperty(Constants.PROPERTY_AUTO_INSTALL_URLS);
        if (propValue != null) {
            for (String path : propValue.split(",")) {
                URL url = toURL(bundleManager, path.trim());
                if (url != null) {
                    autoInstall.add(url);
                }
            }
        }
        propValue = (String) bundleManager.getProperty(Constants.PROPERTY_AUTO_START_URLS);
        if (propValue != null) {
            for (String path : propValue.split(",")) {
                URL url = toURL(bundleManager, path.trim());
                if (url != null) {
                    autoStart.add(url);
                }
            }
        }

        // Add the autoStart bundles to autoInstall
        autoInstall.addAll(autoStart);

        // Create the RESOLVED service that listens on the bundle INSTALL services
        BootstrapBundlesResolved bootstrapResolved = new DefaultBootstrapBundlesResolved(autoInstall);
        ServiceBuilder<Void> builder = bootstrapResolved.install(context.getChildTarget());
        if (autoInstall.isEmpty()) {
            builder.install();
        } else {
            // Install the auto install bundles
            ServiceListener<Bundle> listener = bootstrapResolved.getListener();
            for (URL url : autoInstall) {
                try {
                    BundleInfo info = BundleInfo.createBundleInfo(url);
                    Deployment dep = DeploymentFactory.createDeployment(info);
                    dep.setAutoStart(autoStart.contains(url));
                    bundleManager.installBundle(dep, listener);
                } catch (BundleException ex) {
                    LOGGER.errorStateCannotInstallInitialBundle(ex, url.toExternalForm());
                }
            }
        }
    }

    private URL toURL(final BundleManagerPlugin bundleManager, final String path) {

        URL pathURL = null;
        PropertyProvider provider = new PropertyProvider() {
            @Override
            public String getProperty(String key) {
                return (String) bundleManager.getProperty(key);
            }
        };
        String realPath = StringPropertyReplacer.replaceProperties(path, provider);
        try {
            pathURL = new URL(realPath);
        } catch (MalformedURLException ex) {
            // ignore
        }

        if (pathURL == null) {
            try {
                File file = new File(realPath);
                if (file.exists())
                    pathURL = file.toURI().toURL();
            } catch (MalformedURLException ex) {
                throw MESSAGES.illegalArgumentInvalidPath(ex, realPath);
            }
        }

        if (pathURL == null)
            throw MESSAGES.illegalArgumentInvalidPath(null, realPath);

        return pathURL;
    }
}