/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.admin.internal;

import static org.apache.geode.admin.internal.InetAddressUtilsWithLogging.validateHost;

import java.io.File;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;

import org.apache.geode.admin.ManagedEntityConfig;
import org.apache.geode.internal.GemFireVersion;
import org.apache.geode.internal.admin.GemFireVM;
import org.apache.geode.internal.inet.LocalHostUtil;

/**
 * The abstract superclass of objects that configure a managed entity such as a GemFire cache server
 * or a distribution locator. It contains configuration state and behavior common to all managed
 * entities.
 *
 * @since GemFire 4.0
 */
public abstract class ManagedEntityConfigImpl implements ManagedEntityConfig {

  /** The name of the host on which the managed entity runs */
  private String host;

  /** Directory in which the locator runs */
  private String workingDirectory;

  /** The directory in which GemFire is installed */
  private String productDirectory;

  /** Command used to launch locator on remote machine */
  private String remoteCommand;

  /**
   * The managed entity configured by this object.
   *
   * @see #isReadOnly
   */
  private InternalManagedEntity entity = null;

  ///////////////////// Static Methods /////////////////////

  /**
   * Returns the {@linkplain InetAddress#getCanonicalHostName canonical name} of the local machine.
   */
  protected static String getLocalHostName() {
    try {
      return LocalHostUtil.getCanonicalLocalHostName();

    } catch (UnknownHostException ex) {
      throw new IllegalStateException(
          "Could not determine localhost?!", ex);
    }
  }

  /**
   * Returns the current working directory for this VM.
   */
  private static File getCurrentWorkingDirectory() {
    File cwd = new File(System.getProperty("user.dir"));
    return cwd.getAbsoluteFile();
  }

  /**
   * Returns the location of the GemFire product installation. This is determined by finding the
   * location of the gemfire jar and working backwards.
   */
  private static File getGemFireInstallation() {
    URL url = GemFireVersion.getJarURL();
    if (url == null) {
      throw new IllegalStateException(
          "Could not find gemfire.jar.");
    }

    File gemfireJar = new File(url.getPath());
    File lib = gemfireJar.getParentFile();

    return lib.getParentFile();
  }

  ////////////////////// Constructors //////////////////////

  /**
   * Creates a <code>ManagedEntityConfigImpl</code> with the default configuration.
   */
  protected ManagedEntityConfigImpl() {
    host = getLocalHostName();
    workingDirectory = getCurrentWorkingDirectory().getAbsolutePath();
    productDirectory = getGemFireInstallation().getAbsolutePath();
    remoteCommand = null; // Delegate to AdminDistributedSystem
  }

  /**
   * Creates a new <code>ManagedEntityConfigImpl</code> based on the configuration of a running
   * <code>GemFireVM</code>
   */
  protected ManagedEntityConfigImpl(GemFireVM vm) {
    host = vm.getHost().getHostName();
    workingDirectory = vm.getWorkingDirectory().getAbsolutePath();
    productDirectory = vm.getGeodeHomeDir().getAbsolutePath();
    remoteCommand = null;
  }

  /**
   * A copy constructor that creates a new <code>ManagedEntityConfigImpl</code> with the same
   * configuration as another <code>ManagedEntityConfig</code>.
   */
  protected ManagedEntityConfigImpl(ManagedEntityConfig other) {
    host = other.getHost();
    workingDirectory = other.getWorkingDirectory();
    productDirectory = other.getProductDirectory();
    remoteCommand = other.getRemoteCommand();
  }

  //////////////////// Instance Methods ////////////////////

  /**
   * Checks to see if this config object is "read only". If it is, then an
   * {@link IllegalStateException} is thrown. It should be called by every setter method.
   *
   * @see #isReadOnly
   */
  public void checkReadOnly() {
    if (isReadOnly()) {
      throw new IllegalStateException(
          "This configuration cannot be modified while its managed entity is running.");
    }
  }

  /**
   * Returns whether or not this <code>ManagedEntityConfigImpl</code> is read-only (can be
   * modified).
   */
  protected boolean isReadOnly() {
    return entity != null && entity.isRunning();
  }

  /**
   * Sets the entity that is configured by this config object. Once the entity is running, the
   * config object cannot be modified.
   *
   * @see #checkReadOnly
   */
  public void setManagedEntity(InternalManagedEntity entity) {
    this.entity = entity;
  }

  /**
   * Notifies any configuration listeners that this configuration has changed.
   */
  protected abstract void configChanged();

  @Override
  public String getHost() {
    return host;
  }

  @Override
  public void setHost(String host) {
    checkReadOnly();
    this.host = host;
    configChanged();
  }

  @Override
  public String getWorkingDirectory() {
    return workingDirectory;
  }

  @Override
  public void setWorkingDirectory(String workingDirectory) {
    checkReadOnly();
    this.workingDirectory = workingDirectory;
    configChanged();
  }

  @Override
  public String getProductDirectory() {
    return productDirectory;
  }

  @Override
  public void setProductDirectory(String productDirectory) {
    checkReadOnly();
    this.productDirectory = productDirectory;
    configChanged();
  }

  @Override
  public String getRemoteCommand() {
    return remoteCommand;
  }

  @Override
  public void setRemoteCommand(String remoteCommand) {
    checkReadOnly();
    this.remoteCommand = remoteCommand;
    configChanged();
  }

  /**
   * Validates this configuration.
   *
   * @throws IllegalStateException If this config is not valid
   */
  @Override
  public void validate() {
    if (validateHost(host) == null) {
      throw new IllegalStateException(
          String.format("Invalid host %s", host));
    }
  }

  @Override
  public Object clone() throws CloneNotSupportedException {
    // Since all fields are immutable objects, no deep cloning is
    // necessary.
    ManagedEntityConfigImpl clone = (ManagedEntityConfigImpl) super.clone();
    clone.entity = null;
    return clone;
  }

  @Override
  public String toString() {
    String className = getClass().getName();
    int index = className.lastIndexOf('.');
    className = className.substring(index + 1);

    return className
        + " host="
        + getHost()
        + " workingDirectory="
        + getWorkingDirectory()
        + " productDirectory="
        + getProductDirectory()
        + " remoteCommand=\""
        + getRemoteCommand()
        + "\"";
  }
}
