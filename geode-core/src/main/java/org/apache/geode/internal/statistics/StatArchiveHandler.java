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
package org.apache.geode.internal.statistics;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import org.apache.logging.log4j.Logger;

import org.apache.geode.GemFireException;
import org.apache.geode.GemFireIOException;
import org.apache.geode.distributed.internal.InternalDistributedSystem;
import org.apache.geode.internal.io.RollingFileHandler;
import org.apache.geode.internal.logging.InternalLogWriter;
import org.apache.geode.internal.logging.log4j.LogMarker;
import org.apache.geode.logging.internal.log4j.LogWriterLogger;
import org.apache.geode.logging.internal.log4j.api.LogService;
import org.apache.geode.logging.internal.spi.LogFile;

/**
 * Extracted from {@link HostStatSampler} and {@link GemFireStatSampler}.
 * <p/>
 * The StatArchiveHandler handles statistics samples by archiving them to a file. This handler
 * provides archive file rolling (file size limit) and removal (disk space limit). This handler
 * creates and uses an instance of {@link StatArchiveWriter} for the currently open archive file
 * (unless archiving is disabled).
 *
 * @since GemFire 7.0
 */
public class StatArchiveHandler implements SampleHandler {

  private static final Logger logger = LogService.getLogger();

  /** Configuration used in constructing this handler instance. */
  private final StatArchiveHandlerConfig config;

  /** The collector responsible for sample statistics and notifying handlers. */
  private final SampleCollector collector;

  private final RollingFileHandler rollingFileHandler;

  /**
   * Indicates if archiving has been disabled by specifying empty string for the archive file name.
   * Other threads may call in to changeArchiveFile to manipulate this flag.
   */
  private volatile boolean disabledArchiving = false;

  /** The currently open writer/file. Protected by synchronization on this handler instance. */
  private StatArchiveWriter archiver = null;

  /** Directory to contain archive files. */
  private File archiveDir = null;

  /** The first of two numbers used within the name of rolling archive files. */
  private int mainArchiveId = -1;

  /** The second of two numbers used within the name of rolling archive files. */
  private int archiveId = -1;

  /**
   * Constructs a new instance. The {@link StatArchiveHandlerConfig} and {@link SampleCollector}
   * must not be null.
   */
  public StatArchiveHandler(StatArchiveHandlerConfig config, SampleCollector sampleCollector,
      RollingFileHandler rollingFileHandler) {
    this.config = config;
    collector = sampleCollector;
    this.rollingFileHandler = rollingFileHandler;
  }

  /**
   * Initializes the stat archiver with nanosTimeStamp.
   *
   */
  public void initialize(long nanosTimeStamp) {
    changeArchiveFile(false, nanosTimeStamp);
    assertInitialized();
  }

  /**
   * Closes any {@link StatArchiveWriter} currently in use by this handler.
   *
   */
  public void close() throws GemFireException {
    synchronized (this) {
      if (archiver != null) {
        archiver.close();
      }
    }
  }

  private void handleArchiverException(GemFireException ex) {
    if (archiver.getSampleCount() > 0) {
      StringWriter sw = new StringWriter();
      ex.printStackTrace(new PrintWriter(sw, true));
      logger.warn(LogMarker.STATISTICS_MARKER, "Statistic archiver shutting down because: {}", sw);
    }
    try {
      archiver.close();
    } catch (GemFireException e) {
      if (archiver.getSampleCount() > 0) {
        logger.warn(LogMarker.STATISTICS_MARKER, "Statistic archiver shutdown failed because: {}",
            e.getMessage());
      }
    }
    if (archiver.getSampleCount() == 0 && archiveId != -1) {
      // dec since we didn't use the file and close deleted it.
      archiveId--;
    }
    archiver = null;
  }

  @Override
  public void sampled(long nanosTimeStamp, List<ResourceInstance> resourceInstances) {
    synchronized (this) {
      if (logger.isTraceEnabled(LogMarker.STATISTICS_VERBOSE)) {
        logger.trace(LogMarker.STATISTICS_VERBOSE,
            "StatArchiveHandler#sampled resourceInstances={}", resourceInstances);
      }
      if (archiver != null) {
        try {
          archiver.sampled(nanosTimeStamp, resourceInstances);
          if (archiver.getSampleCount() == 1) {
            logger.info(LogMarker.STATISTICS_MARKER, "Archiving statistics to {}.",
                archiver.getArchiveName());
          }
        } catch (IllegalArgumentException e) {
          logger.warn(LogMarker.STATISTICS_MARKER,
              "Use of java.lang.System.nanoTime() resulted in a non-positive timestamp delta. Skipping archival of statistics sample.",
              e);
        } catch (GemFireException ex) {
          handleArchiverException(ex); // this will null out archiver
        }
        if (archiver != null) { // fix npe seen in bug 46917
          long byteLimit = config.getArchiveFileSizeLimit();
          if (byteLimit != 0) {
            long bytesWritten = archiver.bytesWritten();
            if (bytesWritten > byteLimit) {
              // roll the archive
              try {
                changeArchiveFile(true, nanosTimeStamp);
              } catch (GemFireIOException ignore) {
                // it has already been logged
                // We don't want this exception to kill this thread. See 46917
              }
            }
          }
        }
      } else {
        // Check to see if archiving is enabled.
        if (!config.getArchiveFileName().getPath().equals("")) {
          // It is enabled so we must not have an archiver due to an exception.
          // So try to recreate the archiver. See bug 46917.
          try {
            changeArchiveFile(true, nanosTimeStamp);
          } catch (GemFireIOException ignore) {
          }
        }
      }
    } // sync
  }

  void assertInitialized() {
    if (archiver == null && !config.getArchiveFileName().getPath().equals("")) {
      throw new IllegalStateException("This " + this + " was not initialized");
    }
  }

  @Override
  public void allocatedResourceType(ResourceType resourceType) {
    if (logger.isTraceEnabled(LogMarker.STATISTICS_VERBOSE)) {
      logger.trace(LogMarker.STATISTICS_VERBOSE,
          "StatArchiveHandler#allocatedResourceType resourceType={}", resourceType);
    }
    if (archiver != null) {
      try {
        archiver.allocatedResourceType(resourceType);
      } catch (GemFireException ex) {
        handleArchiverException(ex);
      }
    }
  }

  @Override
  public void allocatedResourceInstance(ResourceInstance resourceInstance) {
    if (logger.isTraceEnabled(LogMarker.STATISTICS_VERBOSE)) {
      logger.trace(LogMarker.STATISTICS_VERBOSE,
          "StatArchiveHandler#allocatedResourceInstance resourceInstance={}", resourceInstance);
    }
    if (archiver != null) {
      try {
        archiver.allocatedResourceInstance(resourceInstance);
      } catch (GemFireException ex) {
        handleArchiverException(ex);
      }
    }
  }

  @Override
  public void destroyedResourceInstance(ResourceInstance resourceInstance) {
    if (logger.isTraceEnabled(LogMarker.STATISTICS_VERBOSE)) {
      logger.trace(LogMarker.STATISTICS_VERBOSE,
          "StatArchiveHandler#destroyedResourceInstance resourceInstance={}", resourceInstance);
    }
    if (archiver != null) {
      try {
        archiver.destroyedResourceInstance(resourceInstance);
      } catch (GemFireException ex) {
        handleArchiverException(ex);
      }
    }
  }

  /**
   * Returns the configuration for this handler.
   */
  public StatArchiveHandlerConfig getStatArchiveHandlerConfig() {
    return config;
  }

  @Override
  public String toString() {
    return getClass().getName() + "@" + System.identityHashCode(this) + "{"
        + "config=" + config
        + ", archiveDir=" + archiveDir
        + ", mainArchiveId=" + mainArchiveId
        + ", archiveId=" + archiveId
        + ", archiver=" + archiver
        + "}";
  }

  /**
   * Changes the archive file to the new file or disables archiving if an empty string is specified.
   * This may be invoked by any thread other than the stat sampler.
   * <p/>
   * If the file name matches any archive file(s) already in {@link #archiveDir} then this may
   * trigger rolling and/or removal if appropriate based on
   * {@link StatArchiveHandlerConfig#getArchiveFileSizeLimit() file size limit} and
   * {@link StatArchiveHandlerConfig#getArchiveDiskSpaceLimit() disk space limit}.
   *
   * @param newFile the new archive file to use or "" to disable archiving
   */
  protected void changeArchiveFile(File newFile, long nanosTimeStamp) {
    changeArchiveFile(newFile, true, nanosTimeStamp);
  }

  protected boolean isArchiving() {
    return archiver != null && archiver.bytesWritten() > 0;
  }

  /**
   * Changes the archive file using the same configured archive file name.
   * <p/>
   * If the file name matches any archive file(s) already in {@link #archiveDir} then this may
   * trigger rolling and/or removal if appropriate based on
   * {@link StatArchiveHandlerConfig#getArchiveFileSizeLimit() file size limit} and
   * {@link StatArchiveHandlerConfig#getArchiveDiskSpaceLimit() disk space limit}.
   * <p/>
   * If resetHandler is true, then this handler will reset itself with the SampleCollector by
   * removing and re-adding itself in order to receive allocation notifications about all resource
   * types and instances.
   *
   * @param resetHandler true if the handler should reset itself with the SampleCollector in order
   *        to receive allocation notifications about all resource types and instances
   *
   */
  private void changeArchiveFile(boolean resetHandler, long nanosTimeStamp) {
    changeArchiveFile(config.getArchiveFileName(), resetHandler, nanosTimeStamp);
  }

  /**
   * Changes the archive file to the new file or disables archiving if an empty string is specified.
   * <p/>
   * If the file name matches any archive file(s) already in {@link #archiveDir} then this may
   * trigger rolling and/or removal if appropriate based on
   * {@link StatArchiveHandlerConfig#getArchiveFileSizeLimit() file size limit} and
   * {@link StatArchiveHandlerConfig#getArchiveDiskSpaceLimit() disk space limit}.
   * <p/>
   * If resetHandler is true, then this handler will reset itself with the SampleCollector by
   * removing and re-adding itself in order to receive allocation notifications about all resource
   * types and instances.
   *
   */
  private void changeArchiveFile(File newFile, boolean resetHandler, long nanosTimeStamp) {
    final boolean isDebugEnabled_STATISTICS = logger.isTraceEnabled(LogMarker.STATISTICS_VERBOSE);
    if (isDebugEnabled_STATISTICS) {
      logger.trace(LogMarker.STATISTICS_VERBOSE,
          "StatArchiveHandler#changeArchiveFile newFile={}, nanosTimeStamp={}", newFile,
          nanosTimeStamp);
    }
    StatArchiveWriter newArchiver = null;
    boolean archiveClosed = false;
    if (newFile.getPath().equals("")) {
      // disable archiving
      if (!disabledArchiving) {
        disabledArchiving = true;
        logger.info(LogMarker.STATISTICS_MARKER, "Disabling statistic archival.");
      }
    } else {
      disabledArchiving = false;
      if (config.getArchiveFileSizeLimit() != 0) {
        // To fix bug 51133 need to always write to newFile.
        // Need to close any existing archive and then rename it
        // to getRollingArchiveName(newFile).
        if (archiver != null) {
          archiveClosed = true;
          synchronized (this) {
            if (resetHandler) {
              if (isDebugEnabled_STATISTICS) {
                logger.trace(LogMarker.STATISTICS_VERBOSE,
                    "StatArchiveHandler#changeArchiveFile removing handler");
              }
              collector.removeSampleHandler(this);
            }
            try {
              archiver.close();
            } catch (GemFireException e) {
              logger.warn(LogMarker.STATISTICS_MARKER,
                  "Statistic archive close failed because: {}",
                  e.getMessage());
            }
          }
        }
      }
      if (newFile.exists()) {
        File oldFile;
        if (config.getArchiveFileSizeLimit() != 0) {
          oldFile = getRollingArchiveName(newFile, archiveClosed);
        } else {
          oldFile = getRenameArchiveName(newFile);
        }
        if (!newFile.renameTo(oldFile)) {
          logger.warn(LogMarker.STATISTICS_MARKER,
              "Could not rename {} to {}.",
              new Object[] {newFile, oldFile});
        } else {
          logger.info(LogMarker.STATISTICS_MARKER, "Renamed old existing archive to {}.", oldFile);
        }
      } else {
        if (!newFile.getAbsoluteFile().getParentFile().equals(archiveDir)) {
          archiveDir = newFile.getAbsoluteFile().getParentFile();
          if (!archiveDir.exists()) {
            archiveDir.mkdirs();
          }
        }
        if (config.getArchiveFileSizeLimit() != 0) {
          initMainArchiveId(newFile);
        }
      }
      try {
        StatArchiveDescriptor archiveDescriptor = new StatArchiveDescriptor.Builder()
            .setArchiveName(newFile.getAbsolutePath()).setSystemId(config.getSystemId())
            .setSystemStartTime(config.getSystemStartTime())
            .setSystemDirectoryPath(config.getSystemDirectoryPath())
            .setProductDescription(config.getProductDescription()).build();
        newArchiver = new StatArchiveWriter(archiveDescriptor);
        newArchiver.initialize(nanosTimeStamp);
      } catch (GemFireIOException ex) {
        logger.warn(LogMarker.STATISTICS_MARKER,
            "Could not open statistic archive {}. Cause: {}",
            new Object[] {newFile, ex.getLocalizedMessage()});
        throw ex;
      }
    }

    synchronized (this) {
      if (archiveClosed) {
        if (archiver != null) {
          removeOldArchives(newFile, config.getArchiveDiskSpaceLimit());
        }
      } else {
        if (resetHandler) {
          if (isDebugEnabled_STATISTICS) {
            logger.trace(LogMarker.STATISTICS_VERBOSE,
                "StatArchiveHandler#changeArchiveFile removing handler");
          }
          collector.removeSampleHandler(this);
        }
        if (archiver != null) {
          try {
            archiver.close();
          } catch (GemFireException e) {
            logger.warn(LogMarker.STATISTICS_MARKER,
                "Statistic archive close failed because: {}",
                e.getMessage());
          }
          removeOldArchives(newFile, config.getArchiveDiskSpaceLimit());
        }
      }
      archiver = newArchiver;
      if (resetHandler && newArchiver != null) {
        if (isDebugEnabled_STATISTICS) {
          logger.trace(LogMarker.STATISTICS_VERBOSE,
              "StatArchiveHandler#changeArchiveFile adding handler");
        }
        collector.addSampleHandler(this);
      }
    }
  }

  /**
   * Returns the modified archive file name to use after incrementing {@link #mainArchiveId} and
   * {@link #archiveId} based on existing files {@link #archiveDir}. This is only used if
   * {@link StatArchiveHandlerConfig#getArchiveFileSizeLimit() file size limit} has been specified
   * as non-zero (which enables file rolling).
   *
   * @param archive the archive file name to modify
   * @param archiveClosed true if archive was just being written by us; false if it was written by
   *        the previous process.
   *
   * @return the modified archive file name to use; it is modified by applying mainArchiveId and
   *         archiveId to the name for supporting file rolling
   */
  File getRollingArchiveName(File archive, boolean archiveClosed) {
    if (mainArchiveId != -1) {
      // leave mainArchiveId as is. Bump archiveId.
    } else {
      archiveDir = archive.getAbsoluteFile().getParentFile();
      boolean mainArchiveIdCalculated = false;
      if (config.getLogFile().isPresent()) {
        LogFile logFile = config.getLogFile().get();
        File logDir = logFile.getLogDir();
        if (archiveDir.equals(logDir)) {
          mainArchiveId = logFile.getMainLogId();
          if (mainArchiveId > 1 && logFile.useChildLogging()) {
            mainArchiveId--;
          }
          mainArchiveIdCalculated = true;
        }
      }
      if (!mainArchiveIdCalculated) {
        if (!archiveDir.exists()) {
          archiveDir.mkdirs();
        }
        mainArchiveId = rollingFileHandler.calcNextMainId(archiveDir, false);
        mainArchiveIdCalculated = true;
      }
      if (mainArchiveId == 0) {
        mainArchiveId = 1;
      }
      archiveId = rollingFileHandler.calcNextChildId(archive, mainArchiveId);
      if (archiveId > 0) {
        archiveId--;
      }
    }
    File result = null;
    do {
      archiveId++;
      StringBuilder buf = new StringBuilder(archive.getPath());
      int insertIdx = buf.lastIndexOf(".");
      if (insertIdx == -1) {
        buf.append(rollingFileHandler.formatId(mainArchiveId))
            .append(rollingFileHandler.formatId(archiveId));
      } else {
        buf.insert(insertIdx, rollingFileHandler.formatId(archiveId));
        buf.insert(insertIdx, rollingFileHandler.formatId(mainArchiveId));
      }
      result = new File(buf.toString());
    } while (result.exists());
    if (archiveId == 1) {
      // see if a marker file exists. If so delete it.
      String markerName = archive.getPath();
      int dotIdx = markerName.lastIndexOf(".");
      if (dotIdx != -1) {
        // strip the extension off
        markerName = markerName.substring(0, dotIdx);
      }
      final String buf = markerName + rollingFileHandler.formatId(mainArchiveId)
          + rollingFileHandler.formatId(0) + ".marker";
      File marker = new File(buf);
      if (marker.exists()) {
        if (!marker.delete()) {
          // could not delete it; nothing to be done
        }
      }
    }
    if (!archiveClosed) {
      mainArchiveId++;
      archiveId = 0;
      // create an empty file which we can use on startup when we don't roll
      // to correctly rename the old archive that did not roll.
      String markerName = archive.getPath();
      int dotIdx = markerName.lastIndexOf(".");
      if (dotIdx != -1) {
        // strip the extension off
        markerName = markerName.substring(0, dotIdx);
      }
      final String buf = markerName + rollingFileHandler.formatId(mainArchiveId)
          + rollingFileHandler.formatId(0) + ".marker";
      File marker = new File(buf);
      if (!marker.exists()) {
        try {
          if (!marker.createNewFile()) {
            // could not create it; that is ok
          }
        } catch (IOException ignore) {
          // If we can't create the marker that is ok
        }
      }
    }
    return result;
  }

  void initMainArchiveId(File archive) {
    if (mainArchiveId != -1) {
      // already initialized
      return;
    }
    archiveDir = archive.getAbsoluteFile().getParentFile();
    boolean mainArchiveIdCalculated = false;
    if (config.getLogFile().isPresent()) {
      LogFile logFile = config.getLogFile().get();
      File logDir = logFile.getLogDir();
      if (archiveDir.equals(logDir)) {
        mainArchiveId = logFile.getMainLogId();
        mainArchiveIdCalculated = true;
      }
    }
    if (!mainArchiveIdCalculated) {
      if (!archiveDir.exists()) {
        archiveDir.mkdirs();
      }
      mainArchiveId = rollingFileHandler.calcNextMainId(archiveDir, false);
      mainArchiveId++;
      mainArchiveIdCalculated = true;
    }
    if (mainArchiveId == 0) {
      mainArchiveId = 1;
    }
    archiveId = 0;
    // create an empty file which we can use on startup when we don't roll
    // to correctly rename the old archive that did not roll.
    String markerName = archive.getPath();
    int dotIdx = markerName.lastIndexOf(".");
    if (dotIdx != -1) {
      // strip the extension off
      markerName = markerName.substring(0, dotIdx);
    }
    final String buf = markerName + rollingFileHandler.formatId(mainArchiveId)
        + rollingFileHandler.formatId(0) + ".marker";
    File marker = new File(buf);
    if (!marker.exists()) {
      try {
        if (!marker.createNewFile()) {
          // could not create it; that is ok
        }
      } catch (IOException ignore) {
        // If we can't create the marker that is ok
      }
    }
  }

  /**
   * Modifies the desired archive file name with a main id (similar to {@link #mainArchiveId} if the
   * archive file's dir already contains GemFire stat archive or log files containing a main id in
   * the file name.
   *
   * @param archive the archive file name to modify
   *
   * @return the modified archive file name to use; it is modified by applying the next main id if
   *         any files in the dir already have a main id in the file name
   */
  File getRenameArchiveName(File archive) {
    File dir = archive.getAbsoluteFile().getParentFile();
    int previousMainId = rollingFileHandler.calcNextMainId(dir, false);
    if (previousMainId == 0) {
      previousMainId = 1;
    }
    previousMainId--;
    File result = null;
    do {
      previousMainId++;
      StringBuilder buf = new StringBuilder(archive.getPath());
      int insertIdx = buf.lastIndexOf(".");
      if (insertIdx == -1) {
        buf.append(rollingFileHandler.formatId(previousMainId))
            .append(rollingFileHandler.formatId(1));
      } else {
        buf.insert(insertIdx, rollingFileHandler.formatId(1));
        buf.insert(insertIdx, rollingFileHandler.formatId(previousMainId));
      }
      result = new File(buf.toString());
    } while (result.exists());
    return result;
  }

  /**
   * Remove old versions of the specified archive file name in order to stay under the specified
   * disk space limit. Old versions of the archive file are those that match based on using a
   * pattern which ignores mainArchiveId and archiveId.
   *
   * @param archiveFile the archive file to remove old versions of
   * @param spaceLimit the disk space limit
   */
  private void removeOldArchives(File archiveFile, long spaceLimit) {
    if (spaceLimit == 0 || archiveFile == null || archiveFile.getPath().equals("")) {
      return;
    }
    File archiveDir = archiveFile.getAbsoluteFile().getParentFile();
    rollingFileHandler.checkDiskSpace("archive", archiveFile, spaceLimit, archiveDir,
        getOrCreateLogWriter());
  }

  private InternalLogWriter getOrCreateLogWriter() {
    InternalLogWriter lw = InternalDistributedSystem.getStaticInternalLogWriter();
    if (lw == null) {
      lw = LogWriterLogger.create(logger);
    }
    return lw;
  }
}
