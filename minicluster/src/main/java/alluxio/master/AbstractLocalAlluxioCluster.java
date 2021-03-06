/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.master;

import alluxio.AlluxioTestDirectory;
import alluxio.Configuration;
import alluxio.ConfigurationTestUtils;
import alluxio.Constants;
import alluxio.PropertyKey;
import alluxio.cli.Format;
import alluxio.client.block.BlockWorkerClientTestUtils;
import alluxio.client.file.FileSystem;
import alluxio.client.file.FileSystemContext;
import alluxio.client.util.ClientTestUtils;
import alluxio.proxy.ProxyProcess;
import alluxio.security.GroupMappingServiceTestUtils;
import alluxio.security.LoginUserTestUtils;
import alluxio.underfs.LocalFileSystemCluster;
import alluxio.underfs.UnderFileSystem;
import alluxio.underfs.UnderFileSystemCluster;
import alluxio.util.UnderFileSystemUtils;
import alluxio.util.io.PathUtils;
import alluxio.util.network.NetworkAddressUtils;
import alluxio.worker.WorkerProcess;

import com.google.common.base.Joiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * Local Alluxio cluster.
 */
@NotThreadSafe
public abstract class AbstractLocalAlluxioCluster {
  private static final Logger LOG = LoggerFactory.getLogger(AbstractLocalAlluxioCluster.class);

  private static final Random RANDOM_GENERATOR = new Random();
  private static final int DEFAULT_BLOCK_SIZE_BYTES = Constants.KB;
  private static final long DEFAULT_WORKER_MEMORY_BYTES = 100 * Constants.MB;

  protected ProxyProcess mProxyProcess;
  protected Thread mProxyThread;

  protected List<WorkerProcess> mWorkers;
  protected List<Thread> mWorkerThreads;

  protected UnderFileSystemCluster mUfsCluster;

  protected String mWorkDirectory;
  protected String mHostname;

  private int mNumWorkers;

  /**
   * @param numWorkers the number of workers to run
   */
  AbstractLocalAlluxioCluster(int numWorkers) {
    mProxyProcess = ProxyProcess.Factory.create();
    mNumWorkers = numWorkers;
    mWorkerThreads = new ArrayList<>();
  }

  /**
   * Starts both master and a worker using the configurations in test conf respectively.
   */
  public void start() throws Exception {
    // Disable HDFS client caching to avoid file system close() affecting other clients
    System.setProperty("fs.hdfs.impl.disable.cache", "true");

    resetClientPools();

    setupTest();
    startMasters();
    startWorkers();
    startProxy();

    // Reset contexts so that they pick up the updated configuration.
    reset();
  }

  /**
   * Configures and starts the master(s).
   */
  protected abstract void startMasters() throws Exception;

  /**
   * Configures and starts the proxy.
   */
  private void startProxy() throws Exception {
    mProxyProcess = ProxyProcess.Factory.create();
    Runnable runMaster = new Runnable() {
      @Override
      public void run() {
        try {
          mProxyProcess.start();
        } catch (Exception e) {
          // Log the exception as the RuntimeException will be caught and handled silently by JUnit
          LOG.error("Start proxy error", e);
          throw new RuntimeException(e + " \n Start Proxy Error \n" + e.getMessage(), e);
        }
      }
    };

    mProxyThread = new Thread(runMaster);
    mProxyThread.start();
    mProxyProcess.waitForReady();
  }

  /**
   * Configures and starts the worker(s).
   */
  protected void startWorkers() throws Exception {
    mWorkers = new ArrayList<>();
    for (int i = 0; i < mNumWorkers; i++) {
      mWorkers.add(WorkerProcess.Factory.create());
    }

    for (final WorkerProcess worker : mWorkers) {
      Runnable runWorker = new Runnable() {
        @Override
        public void run() {
          try {
            worker.start();

          } catch (Exception e) {
            // Log the exception as the RuntimeException will be caught and handled silently by
            // JUnit
            LOG.error("Start worker error", e);
            throw new RuntimeException(e + " \n Start Worker Error \n" + e.getMessage(), e);
          }
        }
      };
      Thread thread = new Thread(runWorker);
      mWorkerThreads.add(thread);
      thread.start();
    }

    for (WorkerProcess worker : mWorkers) {
      worker.waitForReady();
    }
  }

  /**
   * Sets up corresponding directories for tests.
   */
  protected void setupTest() throws IOException {
    UnderFileSystem ufs = UnderFileSystem.Factory.getForRoot();
    String underfsAddress = Configuration.get(PropertyKey.MASTER_MOUNT_TABLE_ROOT_UFS);

    // Deletes the ufs dir for this test from to avoid permission problems
    UnderFileSystemUtils.deleteDirIfExists(ufs, underfsAddress);

    // Creates ufs dir. This must be called before starting UFS with UnderFileSystemCluster.get().
    UnderFileSystemUtils.mkdirIfNotExists(ufs, underfsAddress);

    // Creates storage dirs for worker
    int numLevel = Configuration.getInt(PropertyKey.WORKER_TIERED_STORE_LEVELS);
    for (int level = 0; level < numLevel; level++) {
      PropertyKey tierLevelDirPath =
          PropertyKey.Template.WORKER_TIERED_STORE_LEVEL_DIRS_PATH.format(level);
      String[] dirPaths = Configuration.get(tierLevelDirPath).split(",");
      for (String dirPath : dirPaths) {
        UnderFileSystemUtils.mkdirIfNotExists(ufs, dirPath);
      }
    }

    // Starts the UFS for integration tests. If this is for HDFS profiles, it starts miniDFSCluster
    // (see also {@link alluxio.LocalMiniDFSCluster} and sets up the folder like
    // "hdfs://xxx:xxx/alluxio*".
    mUfsCluster = UnderFileSystemCluster.get(mWorkDirectory);

    // Sets the journal folder
    String journalFolder =
        mUfsCluster.getUnderFilesystemAddress() + "/journal" + RANDOM_GENERATOR.nextLong();
    Configuration.set(PropertyKey.MASTER_JOURNAL_FOLDER, journalFolder);

    // Formats the journal
    Format.format(Format.Mode.MASTER);

    // If we are using anything except LocalFileSystemCluster as UnderFS,
    // we need to update the MASTER_MOUNT_TABLE_ROOT_UFS to point to the cluster's current address.
    // This must happen after UFS is started with UnderFileSystemCluster.get().
    if (!mUfsCluster.getClass().getName().equals(LocalFileSystemCluster.class.getName())) {
      String ufsAddress = mUfsCluster.getUnderFilesystemAddress() + mWorkDirectory;
      UnderFileSystemUtils.mkdirIfNotExists(ufs, ufsAddress);
      Configuration.set(PropertyKey.MASTER_MOUNT_TABLE_ROOT_UFS, ufsAddress);
    }
  }

  /**
   * Stops both the alluxio and underfs service threads.
   */
  public void stop() throws Exception {
    stopFS();
    stopUFS();
    ConfigurationTestUtils.resetConfiguration();
    reset();
    LoginUserTestUtils.resetLoginUser();
  }

  /**
   * Stops the alluxio filesystem's service thread only.
   */
  public void stopFS() throws Exception {
    LOG.info("stop Alluxio filesystem");
    stopProxy();
    stopWorkers();
    stopMasters();
  }

  /**
   * Cleans up the underfs cluster test folder only.
   */
  protected void stopUFS() throws Exception {
    LOG.info("stop under storage system");
    if (mUfsCluster != null) {
      mUfsCluster.cleanup();
    }
  }

  /**
   * Stops the masters.
   */
  protected abstract void stopMasters() throws Exception;

  /**
   * Stops the proxy.
   */
  protected void stopProxy() throws Exception {
    mProxyProcess.stop();
  }

  /**
   * Stops the workers.
   */
  public void stopWorkers() throws Exception {
    for (WorkerProcess worker : mWorkers) {
      worker.stop();
    }
    for (Thread thread : mWorkerThreads) {
      thread.interrupt();
    }
    mWorkerThreads.clear();
  }

  /**
   * Creates a default {@link Configuration} for testing.
   */
  public void initConfiguration() throws IOException {
    setAlluxioWorkDirectory();
    setHostname();

    Configuration.set(PropertyKey.TEST_MODE, "true");
    Configuration.set(PropertyKey.WORK_DIR, mWorkDirectory);
    Configuration.set(PropertyKey.USER_BLOCK_SIZE_BYTES_DEFAULT,
        Integer.toString(DEFAULT_BLOCK_SIZE_BYTES));
    Configuration.set(PropertyKey.USER_BLOCK_REMOTE_READ_BUFFER_SIZE_BYTES, Integer.toString(64));
    Configuration.set(PropertyKey.MASTER_HOSTNAME, mHostname);
    Configuration.set(PropertyKey.MASTER_RPC_PORT, Integer.toString(0));
    Configuration.set(PropertyKey.MASTER_WEB_PORT, Integer.toString(0));
    Configuration.set(PropertyKey.MASTER_TTL_CHECKER_INTERVAL_MS, Integer.toString(1000));
    Configuration.set(PropertyKey.MASTER_WORKER_THREADS_MIN, "1");
    Configuration.set(PropertyKey.MASTER_WORKER_THREADS_MAX, "100");
    Configuration.set(PropertyKey.MASTER_STARTUP_CONSISTENCY_CHECK_ENABLED, false);
    Configuration.set(PropertyKey.MASTER_JOURNAL_FLUSH_TIMEOUT_MS, 1000);

    Configuration.set(PropertyKey.MASTER_BIND_HOST, mHostname);
    Configuration.set(PropertyKey.MASTER_WEB_BIND_HOST, mHostname);

    // If tests fail to connect they should fail early rather than using the default ridiculously
    // high retries
    Configuration.set(PropertyKey.USER_RPC_RETRY_MAX_NUM_RETRY, "3");

    // Since tests are always running on a single host keep the resolution timeout low as otherwise
    // people running with strange network configurations will see very slow tests
    Configuration.set(PropertyKey.NETWORK_HOST_RESOLUTION_TIMEOUT_MS, "250");

    Configuration.set(PropertyKey.PROXY_WEB_PORT, Integer.toString(0));

    // default write type becomes MUST_CACHE, set this value to CACHE_THROUGH for tests.
    // default Alluxio storage is STORE, and under storage is SYNC_PERSIST for tests.
    // TODO(binfan): eliminate this setting after updating integration tests
    Configuration.set(PropertyKey.USER_FILE_WRITE_TYPE_DEFAULT, "CACHE_THROUGH");

    Configuration.set(PropertyKey.WEB_THREADS, "1");
    Configuration.set(PropertyKey.WEB_RESOURCES, PathUtils
        .concatPath(System.getProperty("user.dir"), "../core/server/common/src/main/webapp"));

    Configuration.set(PropertyKey.WORKER_RPC_PORT, Integer.toString(0));
    Configuration.set(PropertyKey.WORKER_DATA_PORT, Integer.toString(0));
    Configuration.set(PropertyKey.WORKER_WEB_PORT, Integer.toString(0));
    Configuration.set(PropertyKey.WORKER_DATA_FOLDER, "/datastore");
    Configuration.set(PropertyKey.WORKER_MEMORY_SIZE, Long.toString(DEFAULT_WORKER_MEMORY_BYTES));
    Configuration.set(PropertyKey.WORKER_BLOCK_HEARTBEAT_INTERVAL_MS, Integer.toString(15));
    Configuration.set(PropertyKey.WORKER_BLOCK_THREADS_MIN, Integer.toString(1));
    Configuration.set(PropertyKey.WORKER_BLOCK_THREADS_MAX, Integer.toString(2048));
    Configuration.set(PropertyKey.WORKER_NETWORK_NETTY_WORKER_THREADS, Integer.toString(2));

    Configuration.set(PropertyKey.WORKER_BIND_HOST, mHostname);
    Configuration.set(PropertyKey.WORKER_DATA_BIND_HOST, mHostname);
    Configuration.set(PropertyKey.WORKER_WEB_BIND_HOST, mHostname);

    // Performs an immediate shutdown of data server. Graceful shutdown is unnecessary and slow
    Configuration.set(PropertyKey.WORKER_NETWORK_NETTY_SHUTDOWN_QUIET_PERIOD, Integer.toString(0));
    Configuration.set(PropertyKey.WORKER_NETWORK_NETTY_SHUTDOWN_TIMEOUT, Integer.toString(0));

    // Sets up the tiered store
    String ramdiskPath = PathUtils.concatPath(mWorkDirectory, "ramdisk");
    Configuration.set(
        PropertyKey.Template.WORKER_TIERED_STORE_LEVEL_ALIAS.format(0), "MEM");
    Configuration.set(
        PropertyKey.Template.WORKER_TIERED_STORE_LEVEL_DIRS_PATH.format(0),
        ramdiskPath);

    int numLevel = Configuration.getInt(PropertyKey.WORKER_TIERED_STORE_LEVELS);
    for (int level = 1; level < numLevel; level++) {
      PropertyKey tierLevelDirPath =
          PropertyKey.Template.WORKER_TIERED_STORE_LEVEL_DIRS_PATH.format(level);
      String[] dirPaths = Configuration.get(tierLevelDirPath).split(",");
      List<String> newPaths = new ArrayList<>();
      for (String dirPath : dirPaths) {
        String newPath = mWorkDirectory + dirPath;
        newPaths.add(newPath);
      }
      Configuration.set(
          PropertyKey.Template.WORKER_TIERED_STORE_LEVEL_DIRS_PATH.format(level),
          Joiner.on(',').join(newPaths));
    }

    // For some test profiles, default properties get overwritten by system properties (e.g., s3
    // credentials for s3Test).
    // TODO(binfan): have one dedicated property (e.g., alluxio.test.properties) to carry on all the
    // properties we want to overwrite in tests, rather than simply merging all system properties.
    Configuration.merge(System.getProperties());
  }

  /**
   * Returns a {@link FileSystem} client.
   *
   * @return a {@link FileSystem} client
   */
  public abstract FileSystem getClient() throws IOException;

  /**
   * @return the local Alluxio master
   */
  protected abstract LocalAlluxioMaster getLocalAlluxioMaster();

  /**
   * Gets the proxy process.
   *
   * @return the proxy
   */
  public ProxyProcess getProxyProcess() {
    return mProxyProcess;
  }

  /**
   * Resets the cluster to original state.
   */
  protected void reset() {
    ClientTestUtils.resetClient();
    GroupMappingServiceTestUtils.resetCache();
  }

  /**
   * Resets the client pools to the original state.
   */
  protected void resetClientPools() {
    BlockWorkerClientTestUtils.reset();
    FileSystemContext.INSTANCE.reset();
  }

  /**
   * Sets hostname.
   */
  protected void setHostname() {
    mHostname = NetworkAddressUtils.getLocalHostName(100);
  }

  /**
   * Sets Alluxio work directory.
   */
  protected void setAlluxioWorkDirectory() {
    mWorkDirectory =
        AlluxioTestDirectory.createTemporaryDirectory("test-cluster").getAbsolutePath();
  }
}
