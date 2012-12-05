/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2010 - 2012 JBoss by Red Hat
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-2.1.html>.
 * #L%
 */
package org.jboss.osgi.framework.internal;

import static org.jboss.osgi.framework.FrameworkLogger.LOGGER;
import static org.jboss.osgi.framework.FrameworkMessages.MESSAGES;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import org.jboss.osgi.framework.spi.BundleManager;
import org.jboss.osgi.framework.spi.FrameworkEvents;
import org.jboss.osgi.resolver.XBundle;
import org.jboss.osgi.resolver.XEnvironment;
import org.jboss.osgi.resolver.XResolver;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.startlevel.BundleStartLevel;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleRevisions;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.service.resolver.ResolutionException;

/**
 * An implementation of the {@link FrameworkWiringSupport} service.
 *
 * @author Thomas.Diesler@jboss.com
 * @since 08-Nov-2012
 */
public final class FrameworkWiringImpl implements FrameworkWiring {

    private final BundleManager bundleManager;
    private final FrameworkEvents events;
    private final XEnvironment environment;
    private final XResolver resolver;
    private final ExecutorService executorService;


    public FrameworkWiringImpl(BundleManager bundleManager, FrameworkEvents events, XEnvironment environment, XResolver resolver, ExecutorService executorService) {
        this.bundleManager = bundleManager;
        this.events = events;
        this.environment = environment;
        this.resolver = resolver;
        this.executorService = executorService;
    }

    @Override
    public Bundle getBundle() {
        return bundleManager.getSystemBundle();
    }

    @Override
    public void refreshBundles(final Collection<Bundle> bundles, final FrameworkListener... listeners) {

        final Collection<Bundle> bundlesToRefresh = new ArrayList<Bundle>();
        if (bundles == null) {

            // 4.2 core spec 7.5.3.11 on null:
            // all bundles updated or uninstalled since the last call to this method.

            // a bundle with more than 1 revision has been updated since the last refresh packages call
            for (Bundle bundle : bundleManager.getBundles(null)) {
                List<BundleRevision> revs = bundle.adapt(BundleRevisions.class).getRevisions();
                if (bundle.getState() == Bundle.UNINSTALLED || revs.size() > 1) {
                    bundlesToRefresh.add(bundle);
                }
            }
        } else {
            bundlesToRefresh.addAll(bundles);
        }

        LOGGER.debugf("Refresh bundles %s", bundlesToRefresh);

        Runnable runner = new Runnable() {

            @Override
            public void run() {

                Set<Bundle> stopBundles = new HashSet<Bundle>();
                Set<Bundle> refreshBundles = new HashSet<Bundle>();
                Set<Bundle> uninstallBundles = new HashSet<Bundle>();

                for (Bundle auxBundle : bundlesToRefresh) {
                    XBundle bundle = (XBundle) auxBundle;
                    if (bundle.getState() == Bundle.UNINSTALLED)
                        uninstallBundles.add(bundle);
                    else if (bundle.isResolved() == true)
                        refreshBundles.add(bundle);
                }

                // Compute all depending bundles that need to be stopped and unresolved.
                Collection<Bundle> dependencyClosure = getDependencyClosure(bundlesToRefresh);
                for (Bundle bundle : dependencyClosure) {
                    int state = bundle.getState();
                    if (state == Bundle.ACTIVE || state == Bundle.STARTING) {
                        stopBundles.add(bundle);
                    }
                }

                List<Bundle> stopList = new ArrayList<Bundle>(stopBundles);
                List<Bundle> refreshList = new ArrayList<Bundle>(dependencyClosure);

                BundleStartLevelComparator startLevelComparator = new BundleStartLevelComparator();
                Collections.sort(stopList, startLevelComparator);

                for (ListIterator<Bundle> it = stopList.listIterator(stopList.size()); it.hasPrevious();) {
                    Bundle hostBundle = it.previous();
                    try {
                        hostBundle.stop(Bundle.STOP_TRANSIENT);
                    } catch (Exception th) {
                        events.fireFrameworkEvent(hostBundle, FrameworkEvent.ERROR, th);
                    }
                }

                BundleManagerPlugin bundleManagerPlugin = BundleManagerPlugin.assertBundleManagerPlugin(bundleManager);
                for (Bundle userBundle : uninstallBundles) {
                    bundleManagerPlugin.removeBundle((UserBundleState) userBundle, 0);
                    refreshList.remove(userBundle);
                }

                for (Bundle userBundle : refreshList) {
                    try {
                        ((UserBundleState) userBundle).refresh();
                    } catch (Exception th) {
                        events.fireFrameworkEvent(userBundle, FrameworkEvent.ERROR, th);
                    }
                }

                for (Bundle hostBundle : stopList) {
                    try {
                        hostBundle.start(Bundle.START_TRANSIENT);
                    } catch (Exception th) {
                        events.fireFrameworkEvent(hostBundle, FrameworkEvent.ERROR, th);
                    }
                }

                Bundle systemBundle = bundleManager.getSystemBundle();
                events.fireFrameworkEvent(systemBundle, FrameworkEvent.PACKAGES_REFRESHED, null, listeners);
            }
        };

        if (!executorService.isShutdown()) {
            executorService.execute(runner);
        }
    }

    @Override
    public boolean resolveBundles(Collection<Bundle> bundles) {

        // Only bundles that are in state INSTALLED  qualify as resolvable
        if (bundles == null) {
            Set<XBundle> bundleset = bundleManager.getBundles(Bundle.INSTALLED);
            bundles = new ArrayList<Bundle>(bundleset);
        }

        LOGGER.debugf("Resolve bundles %s", bundles);

        // Get the set of resolvable bundle revisions
        Set<BundleRevision> resolvableRevisions = new LinkedHashSet<BundleRevision>();
        for (Bundle aux : bundles) {
            XBundle bundleState = (XBundle) aux;
            resolvableRevisions.add(bundleState.getBundleRevision());
        }

        boolean result = true;
        try {
            resolver.resolveAndApply(resolver.createResolveContext(environment, resolvableRevisions, null));
            for (BundleRevision aux : resolvableRevisions) {
                if (aux.getWiring() == null) {
                    result = false;
                    break;
                }
            }
        } catch (ResolutionException ex) {
            LOGGER.debugf(ex, "Cannot resolve: " + resolvableRevisions);
            result = false;
        }
        return result;
    }

    @Override
    public Collection<Bundle> getRemovalPendingBundles() {
        Collection<Bundle> result = new HashSet<Bundle>();
        for (XBundle bundle : bundleManager.getBundles()) {
            BundleWiring wiring = bundle.adapt(BundleWiring.class);
            if (!wiring.isCurrent() || !wiring.isInUse()) {
                result.add(bundle);
            }
        }
        return Collections.unmodifiableCollection(result);
    }

    @Override
    public Collection<Bundle> getDependencyClosure(Collection<Bundle> bundles) {
        if (bundles == null)
            throw MESSAGES.illegalArgumentNull("bundles");

        Collection<Bundle> result = new HashSet<Bundle>(bundles);
        for (XBundle bundle : bundleManager.getBundles(Bundle.RESOLVED | Bundle.ACTIVE)) {
            if (bundle instanceof HostBundleState) {
                HostBundleState hostBundle = HostBundleState.assertBundleState(bundle);
                for (UserBundleState depBundle : hostBundle.getDependentBundles()) {
                    if (bundles.contains(depBundle)) {
                        result.add(hostBundle);
                        break;
                    }
                }
            }
        }
        return Collections.unmodifiableCollection(result);
    }

    private static class BundleStartLevelComparator implements Comparator<Bundle> {

        @Override
        public int compare(Bundle o1, Bundle o2) {
            int sl1 = o1.adapt(BundleStartLevel.class).getStartLevel();
            int sl2 = o2.adapt(BundleStartLevel.class).getStartLevel();
            return sl1 < sl2 ? -1 : (sl1 == sl2 ? 0 : 1);
        }
    }
}