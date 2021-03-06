/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
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

package org.jboss.capedwarf.common.infinispan;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.hibernate.search.Environment;
import org.hibernate.search.backend.impl.jgroups.JGroupsChannelProvider;
import org.hibernate.search.cfg.EntityMapping;
import org.hibernate.search.cfg.SearchMapping;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.cache.IndexingConfigurationBuilder;
import org.infinispan.distexec.DefaultExecutorService;
import org.infinispan.distexec.DistributedExecutorService;
import org.infinispan.io.GridFile;
import org.infinispan.io.GridFilesystem;
import org.infinispan.lifecycle.ComponentStatus;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.remoting.transport.Address;
import org.jboss.capedwarf.common.app.Application;
import org.jboss.capedwarf.common.jndi.JndiLookupUtils;
import org.jgroups.JChannel;

/**
 * @author <a href="mailto:ales.justin@jboss.org">Ales Justin</a>
 */
public class InfinispanUtils {
    private static Map<String, Lock> locks = new ConcurrentHashMap<String, Lock>();

    private static String[] defaultJndiNames = {"java:jboss/infinispan/container/capedwarf"};
    private static final String MUX_GEN  = "mux_gen";
    private static final int INDEXING_CACHES = 4;

    private static volatile int cacheManagerUsers;
    private static EmbeddedCacheManager cacheManager;
    private static final Map<String, GridFilesystem> gridFilesystems = new HashMap<String, GridFilesystem>();

    protected static EmbeddedCacheManager getCacheManager() {
        return cacheManager;
    }

    // this method should already hold synch monitor
    private static <K, V> Cache<K, V> checkCache(String cacheName) {
        final Cache<K, V> cache = cacheManager.getCache(cacheName, false);
        if (cache != null) {
            final ComponentStatus status = cache.getStatus();
            if (status != ComponentStatus.INITIALIZING && status != ComponentStatus.RUNNING) {
                cache.start(); // re-start stopped cache
            }
        }
        return cache;
    }

    protected static <K, V> Cache<K, V> getCache(CacheName config, String appId, ConfigurationCallback callback) {
        final String cacheName = toCacheName(config, appId);

        final Lock lock = locks.get(appId);
        lock.lock();
        try {
            final Cache<K, V> cache = checkCache(cacheName);
            if (cache != null)
                return cache;

            final ConfigurationBuilder builder = callback.configure(cacheManager);
            if (builder != null) {
                cacheManager.defineConfiguration(cacheName, builder.build());
            }

            return cacheManager.getCache(cacheName, true);
        } finally {
            lock.unlock();
        }
    }

    protected static String toCacheName(CacheName config, String appId) {
        return config.getName() + "_" + appId;
    }

    public static Configuration getConfiguration(CacheName config) {
        if (cacheManager == null)
            throw new IllegalArgumentException("CacheManager is null, should not be here?!");
        if (config == null)
            throw new IllegalArgumentException("Null config enum!");

        final Configuration c = cacheManager.getCacheConfiguration(config.getName());
        if (c == null)
            throw new IllegalArgumentException("No such default cache config: " + config);

        return c;
    }

    public static SearchMapping applyIndexing(CacheName config, ConfigurationBuilder builder) {
        final CacheIndexing ci = config.getIndexing();
        if (ci == null)
            throw new IllegalArgumentException("Missing cache indexing info: " + config);

        final String appId = Application.getAppId();
        final IndexingConfigurationBuilder indexing = builder.indexing();
        indexing.addProperty("hibernate.search.default.indexBase", "./indexes_" + appId);
        final SearchMapping mapping = new SearchMapping();
        for (Class<?> clazz : ci.getClasses()) {
            final EntityMapping entity = mapping.entity(clazz);
            entity.indexed().indexName(toCacheName(config, appId) + "__" + clazz.getName());
        }
        indexing.setProperty(Environment.MODEL_MAPPING, mapping);

        final JChannel channel = JndiLookupUtils.lookup("infinispan.indexing.channel", JChannel.class, "java:jboss/capedwarf/indexing/channel");
        indexing.setProperty(JGroupsChannelProvider.CHANNEL_INJECT, channel);
        indexing.setProperty(JGroupsChannelProvider.CLASSLOADER, InfinispanUtils.class.getClassLoader());

        short muxId = (short) ((INDEXING_CACHES / 2) * getMuxId(appId) * ci.getPrefix() + ci.getOffset());
        indexing.setProperty(JGroupsChannelProvider.MUX_ID, muxId);

        return mapping;
    }

    protected static short getMuxId(String appId) {
        Cache<String, ?> dist = cacheManager.getCache(CacheName.DIST.getName());
        Object generator = dist.get(MUX_GEN);
        if (generator == null)
            throw new IllegalArgumentException("No mux id generator stored in dist cache!");

        // use reflection hack
        try {
            Method getMuxId = generator.getClass().getMethod("getMuxId", String.class);
            return (Short) getMuxId.invoke(generator, appId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static <K, V> Cache<K, V> getCache(CacheName config) {
        if (cacheManager == null)
            throw new IllegalArgumentException("CacheManager is null, should not be here?!");
        if (config == null)
            throw new IllegalArgumentException("Null config enum!");
        if (config.hasConfig())
            throw new IllegalArgumentException("Cache " + config + " needs custom configuration!");

        final String appId = Application.getAppId();

        final Lock lock = locks.get(appId);
        lock.lock();
        try {
            final String cacheName = toCacheName(config, appId);
            final Cache<K, V> cache = checkCache(cacheName);
            if (cache != null)
                return cache;
        } finally {
            lock.unlock();
        }

        final Configuration existing = getConfiguration(config);
        final ConfigurationBuilder builder = new ConfigurationBuilder();
        builder.read(existing);

        final ConfigurationCallback callback = new ConfigurationCallback() {
            public ConfigurationBuilder configure(EmbeddedCacheManager manager) {
                return builder;
            }
        };

        return getCache(config, appId, callback);
    }

    public static <K, V> Cache<K, V> getCache(CacheName config, ConfigurationCallback callback) {
        if (cacheManager == null)
            throw new IllegalArgumentException("CacheManager is null, should not be here?!");
        if (config == null)
            throw new IllegalArgumentException("Null config enum!");
        if (config.hasConfig() == false)
            throw new IllegalArgumentException("Cache " + config + " has default configuration!");

        final String appId = Application.getAppId();
        return getCache(config, appId, callback);
    }

    public static <R> R submit(final CacheName config, final Callable<R> task, Object... keys) {
        if (cacheManager == null)
            throw new IllegalArgumentException("CacheManager is null, should not be here?!");

        final Cache cache = getCache(config);
        try {
            final DistributedExecutorService des = new DefaultExecutorService(cache);
            final Future<R> result = des.submit(task, keys);
            return result.get();
        } catch (Exception e) {
            throw (e instanceof RuntimeException) ? (RuntimeException) e : new RuntimeException(e);
        }
    }

    public static GridFilesystem getGridFilesystem() {
        final String appId = Application.getAppId();
        GridFilesystem gfs = gridFilesystems.get(appId);
        if (gfs == null) {
            synchronized (gridFilesystems) {
                gfs = gridFilesystems.get(appId);
                if (gfs == null) {
                    final Cache<String, byte[]> data = getCache(CacheName.DATA);
                    final Cache<String, GridFile.Metadata> metadata = getCache(CacheName.METADATA);
                    gfs = new GridFilesystem(data, metadata);
                    gridFilesystems.put(appId, gfs);
                }
            }
        }
        return gfs;
    }

    @SuppressWarnings("UnusedParameters")
    public static synchronized void initApplicationData(String appId) {
        if (cacheManager == null) {
            cacheManager = JndiLookupUtils.lazyLookup("infinispan.jndi.name", EmbeddedCacheManager.class, defaultJndiNames);
        }
        cacheManagerUsers++;
        // add lock
        locks.put(appId, new ReentrantLock());
    }

    public static synchronized void clearApplicationData(String appId) {
        // remove lock
        locks.remove(appId);

        synchronized (gridFilesystems) {
            gridFilesystems.remove(appId);
        }
        cacheManagerUsers--;
        if (cacheManagerUsers == 0) {
            cacheManager = null;
        }
    }

    public static Address getLocalNode() {
        return InfinispanUtils.getCache(CacheName.DEFAULT).getAdvancedCache().getRpcManager().getAddress();
    }
}
