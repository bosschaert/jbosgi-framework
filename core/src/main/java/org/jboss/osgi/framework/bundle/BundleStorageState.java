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
package org.jboss.osgi.framework.bundle;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import org.jboss.logging.Logger;
import org.jboss.osgi.vfs.AbstractVFS;
import org.jboss.osgi.vfs.VFSUtils;
import org.jboss.osgi.vfs.VirtualFile;

/**
 * An abstraction of a bundle persistent storage.
 *
 * @author thomas.diesler@jboss.com
 * @since 18-Aug-2009
 */
public final class BundleStorageState
{
   // Provide logging
   final Logger log = Logger.getLogger(BundleStorageState.class);

   public static final String PROPERTY_BUNDLE_FILE = "BundleFile";
   public static final String PROPERTY_BUNDLE_ID = "BundleId";
   public static final String PROPERTY_BUNDLE_LOCATION = "Location";
   public static final String PROPERTY_BUNDLE_REV = "BundleRev";
   public static final String PROPERTY_LAST_MODIFIED = "LastModified";
   public static final String PROPERTY_PERSISTENTLY_STARTED = "PersistentlyStarted";
   public static final String PROPERTY_ACTIVATION_POLICY_USED = "ActivationPolicyUsed";
   public static final String BUNDLE_PERSISTENT_PROPERTIES = "bundle-persistent.properties";

   private final File bundleDir;
   private final VirtualFile rootFile;
   private final Properties props;
   private final String location;
   private final long bundleId;
   private final int revision;

   private long lastModified;

   static Set<String> requiredProps = new HashSet<String>();
   static
   {
      requiredProps.add(PROPERTY_BUNDLE_ID);
      requiredProps.add(PROPERTY_BUNDLE_REV);
      requiredProps.add(PROPERTY_BUNDLE_LOCATION);
      requiredProps.add(PROPERTY_LAST_MODIFIED);
   }

   public static BundleStorageState createFromStorage(File storageDir) throws IOException
   {
      Properties props = loadProperties(storageDir);

      VirtualFile rootFile = null;
      String vfsLocation = props.getProperty(PROPERTY_BUNDLE_FILE);
      if (vfsLocation != null)
         rootFile = AbstractVFS.toVirtualFile(new URL(vfsLocation));

      return new BundleStorageState(storageDir, rootFile, props);
   }

   public static Properties loadProperties(File storageDir) throws FileNotFoundException, IOException
   {
      Properties props = new Properties();
      File propsFile = new File(storageDir + "/" + BUNDLE_PERSISTENT_PROPERTIES);
      if (propsFile.exists())
      {
         FileInputStream input = new FileInputStream(propsFile);
         try
         {
            props.load(input);
         }
         finally
         {
            VFSUtils.safeClose(input);
         }
      }
      return props;
   }

   public static BundleStorageState createBundleStorageState(File storageDir, VirtualFile rootFile, Properties props) throws IOException
   {
      BundleStorageState storageState = new BundleStorageState(storageDir, rootFile, props);
      storageState.writeProperties();
      return storageState;
   }

   private BundleStorageState(File bundleDir, VirtualFile rootFile, Properties props) throws IOException
   {
      if (bundleDir == null)
         throw new IllegalArgumentException("Null storageDir");
      if (bundleDir.isDirectory() == false)
         throw new IllegalArgumentException("Not a directory: " + bundleDir);
      if (props == null)
         throw new IllegalArgumentException("Null properties");

      for (String key : requiredProps)
         if (props.get(key) == null)
            throw new IllegalArgumentException("Required property missing: " + key);

      this.bundleDir = bundleDir;
      this.rootFile = rootFile;
      this.props = props;

      this.location = props.getProperty(PROPERTY_BUNDLE_LOCATION);
      this.bundleId = Long.parseLong(props.getProperty(PROPERTY_BUNDLE_ID));
      this.revision = Integer.parseInt(props.getProperty(PROPERTY_BUNDLE_REV));
      this.lastModified = Long.parseLong(props.getProperty(PROPERTY_LAST_MODIFIED));
   }

   public File getBundleStorageDir()
   {
      return bundleDir;
   }

   public String getLocation()
   {
      return location;
   }

   public VirtualFile getRootFile()
   {
      return rootFile;
   }

   public long getBundleId()
   {
      return bundleId;
   }

   public int getRevision()
   {
      return revision;
   }

   public long getLastModified()
   {
      String value = props.getProperty(PROPERTY_LAST_MODIFIED);
      return new Long(value);
   }

   public void updateLastModified()
   {
      lastModified = System.currentTimeMillis();
      props.setProperty(PROPERTY_LAST_MODIFIED, new Long(lastModified).toString());
      writeProperties();
   }

   public boolean isPersistentlyStarted()
   {
      String value = props.getProperty(PROPERTY_PERSISTENTLY_STARTED);
      return value != null ? new Boolean(value) : false;
   }

   public void setPersistentlyStarted(boolean started)
   {
      props.setProperty(PROPERTY_PERSISTENTLY_STARTED, new Boolean(started).toString());
      writeProperties();
   }

   public boolean isBundleActivationPolicyUsed()
   {
      String value = props.getProperty(PROPERTY_ACTIVATION_POLICY_USED);
      return value != null ? new Boolean(value) : false;
   }

   public void setBundleActivationPolicyUsed(boolean started)
   {
      props.setProperty(PROPERTY_ACTIVATION_POLICY_USED, new Boolean(started).toString());
      writeProperties();
   }

   public void deleteBundleStorage()
   {
      deleteInternal(bundleDir);
   }

   public void deleteRevisionStorage()
   {
      VFSUtils.safeClose(rootFile);
   }

   void deleteInternal(File file)
   {
      if (file.isDirectory())
      {
         for (File aux : file.listFiles())
            deleteInternal(aux);
      }
      file.delete();
   }

   private void writeProperties()
   {
      try
      {
         File propsFile = new File(bundleDir + "/" + BUNDLE_PERSISTENT_PROPERTIES);
         FileOutputStream output = new FileOutputStream(propsFile);
         try
         {
            props.store(output, "Persistent Bundle Properties");
         }
         finally
         {
            VFSUtils.safeClose(output);
         }
      }
      catch (IOException ex)
      {
         log.errorf(ex, "Cannot write persistent storage: %s", bundleDir);
      }
   }

   @Override
   public String toString()
   {
      return "BundleStorageState[id=" + bundleId + ",location=" + location+ ",file=" + rootFile + "]";
   }
}