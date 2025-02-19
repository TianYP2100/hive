/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.plan;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.common.HiveStatsUtils;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.Warehouse;
import org.apache.hadoop.hive.ql.exec.Task;
import org.apache.hadoop.hive.ql.exec.Utilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Conditional task resolution interface. This is invoked at run time to get the
 * task to invoke. Developers can plug in their own resolvers
 */
public class ConditionalResolverMergeFiles implements ConditionalResolver,
    Serializable {
  private static final long serialVersionUID = 1L;

  private static final Logger LOG = LoggerFactory.getLogger(ConditionalResolverMergeFiles.class);

  public ConditionalResolverMergeFiles() {
  }

  /**
   * ConditionalResolverMergeFilesCtx.
   *
   */
  public static class ConditionalResolverMergeFilesCtx implements Serializable {
    private static final long serialVersionUID = 1L;
    List<Task<?>> listTasks;
    private String dir;
    private DynamicPartitionCtx dpCtx; // merge task could be after dynamic partition insert
    private ListBucketingCtx lbCtx;

    public ConditionalResolverMergeFilesCtx() {
    }

    /**
     * @param dir
     */
    public ConditionalResolverMergeFilesCtx(
        List<Task<?>> listTasks, String dir) {
      this.listTasks = listTasks;
      this.dir = dir;
    }

    /**
     * @return the dir
     */
    public String getDir() {
      return dir;
    }

    /**
     * @return the listTasks
     */
    public List<Task<?>> getListTasks() {
      return listTasks;
    }

    /**
     * @param listTasks
     *          the listTasks to set
     */
    public void setListTasks(List<Task<?>> listTasks) {
      this.listTasks = listTasks;
    }

    public DynamicPartitionCtx getDPCtx() {
      return dpCtx;
    }

    public void setDPCtx(DynamicPartitionCtx dp) {
      dpCtx = dp;
    }

    /**
     * @return the lbCtx
     */
    public ListBucketingCtx getLbCtx() {
      return lbCtx;
    }

    /**
     * @param lbCtx the lbCtx to set
     */
    public void setLbCtx(ListBucketingCtx lbCtx) {
      this.lbCtx = lbCtx;
    }
  }

  public List<Task<?>> getTasks(HiveConf conf, Object objCtx) {
    ConditionalResolverMergeFilesCtx ctx = (ConditionalResolverMergeFilesCtx) objCtx;
    String dirName = ctx.getDir();

    List<Task<?>> resTsks = new ArrayList<Task<?>>();
    // check if a map-reduce job is needed to merge the files
    // If the current size is smaller than the target, merge
    long trgtSize = conf.getLongVar(HiveConf.ConfVars.HIVEMERGEMAPFILESSIZE);
    long avgConditionSize = conf
        .getLongVar(HiveConf.ConfVars.HIVEMERGEMAPFILESAVGSIZE);
    trgtSize = Math.max(trgtSize, avgConditionSize);

    Task<?> mvTask = ctx.getListTasks().get(0);
    Task<?> mrTask = ctx.getListTasks().get(1);
    Task<?> mrAndMvTask = ctx.getListTasks().get(2);

    try {
      Path dirPath = new Path(dirName);
      FileSystem inpFs = dirPath.getFileSystem(conf);
      DynamicPartitionCtx dpCtx = ctx.getDPCtx();

      if (inpFs.exists(dirPath)) {
        // For each dynamic partition, check if it needs to be merged.
        MapWork work;
        if (mrTask.getWork() instanceof MapredWork) {
          work = ((MapredWork) mrTask.getWork()).getMapWork();
        } else if (mrTask.getWork() instanceof TezWork){
          work = (MapWork) ((TezWork) mrTask.getWork()).getAllWork().get(0);
        } else if (mrTask.getWork() instanceof SparkWork) {
          work = (MapWork) ((SparkWork) mrTask.getWork()).getAllWork().get(0);
        } else {
          work = (MapWork) mrTask.getWork();
        }

        int lbLevel = (ctx.getLbCtx() == null) ? 0 : ctx.getLbCtx().calculateListBucketingLevel();

        /**
         * In order to make code easier to read, we write the following in the way:
         * 1. the first if clause to differ dynamic partition and static partition
         * 2. with static partition, we differ list bucketing from non-list bucketing.
         * Another way to write it is to merge static partition w/ LB wit DP. In that way,
         * we still need to further differ them, since one uses lbLevel and
         * another lbLevel+numDPCols.
         * The first one is selected mainly for easy to read.
         */
        // Dynamic partition: replace input path (root to dp paths) with dynamic partition
        // input paths.
        if (dpCtx != null &&  dpCtx.getNumDPCols() > 0) {
          int numDPCols = dpCtx.getNumDPCols();
          int dpLbLevel = numDPCols + lbLevel;

          generateActualTasks(conf, resTsks, trgtSize, avgConditionSize, mvTask, mrTask,
              mrAndMvTask, dirPath, inpFs, ctx, work, dpLbLevel);
        } else { // no dynamic partitions
          if(lbLevel == 0) {
            // static partition without list bucketing
            long totalSz = getMergeSize(inpFs, dirPath, avgConditionSize);
            Utilities.FILE_OP_LOGGER.debug("merge resolve simple case - totalSz " + totalSz + " from " + dirPath);

            if (totalSz >= 0) { // add the merge job
              setupMapRedWork(conf, work, trgtSize, totalSz);
              resTsks.add(mrTask);
            } else { // don't need to merge, add the move job
              resTsks.add(mvTask);
            }
          } else {
            // static partition and list bucketing
            generateActualTasks(conf, resTsks, trgtSize, avgConditionSize, mvTask, mrTask,
                mrAndMvTask, dirPath, inpFs, ctx, work, lbLevel);
          }
        }
      } else {
        Utilities.FILE_OP_LOGGER.info("Resolver returning movetask for " + dirPath);
        resTsks.add(mvTask);
      }
    } catch (IOException e) {
      LOG.warn("Exception while getting tasks", e);
    }

    // Only one of the tasks should ever be added to resTsks
    assert(resTsks.size() == 1);

    return resTsks;
  }

  /**
   * This method generates actual task for conditional tasks. It could be
   * 1. move task only
   * 2. merge task only
   * 3. merge task followed by a move task.
   * It used to be true for dynamic partition only since static partition doesn't have #3.
   * It changes w/ list bucketing. Static partition has #3 since it has sub-directories.
   * For example, if a static partition is defined as skewed and stored-as-directores,
   * instead of all files in one directory, it will create a sub-dir per skewed value plus
   * default directory. So #3 is required for static partition.
   * So, we move it to a method so that it can be used by both SP and DP.
   * @param conf
   * @param resTsks
   * @param trgtSize
   * @param avgConditionSize
   * @param mvTask
   * @param mrTask
   * @param mrAndMvTask
   * @param dirPath
   * @param inpFs
   * @param ctx
   * @param work
   * @param dpLbLevel
   * @throws IOException
   */
  private void generateActualTasks(HiveConf conf, List<Task<?>> resTsks,
      long trgtSize, long avgConditionSize, Task<?> mvTask,
      Task<?> mrTask, Task<?> mrAndMvTask, Path dirPath,
      FileSystem inpFs, ConditionalResolverMergeFilesCtx ctx, MapWork work, int dpLbLevel)
      throws IOException {
    DynamicPartitionCtx dpCtx = ctx.getDPCtx();
    // get list of dynamic partitions
    List<FileStatus> statusList = HiveStatsUtils.getFileStatusRecurse(dirPath, dpLbLevel, inpFs);
    FileStatus[] status = statusList.toArray(new FileStatus[statusList.size()]);

    // cleanup pathToPartitionInfo
    Map<Path, PartitionDesc> ptpi = work.getPathToPartitionInfo();
    assert ptpi.size() == 1;
    Path path = ptpi.keySet().iterator().next();
    PartitionDesc partDesc = ptpi.get(path);
    TableDesc tblDesc = partDesc.getTableDesc();
    Utilities.FILE_OP_LOGGER.debug("merge resolver removing " + path);
    work.removePathToPartitionInfo(path); // the root path is not useful anymore

    // cleanup pathToAliases
    Map<Path, List<String>> pta = work.getPathToAliases();
    assert pta.size() == 1;
    path = pta.keySet().iterator().next();
    List<String> aliases = pta.get(path);
    work.removePathToAlias(path); // the root path is not useful anymore

    // populate pathToPartitionInfo and pathToAliases w/ DP paths
    long totalSz = 0;
    boolean doMerge = false;
    // list of paths that don't need to merge but need to move to the dest location
    List<Path> toMove = new ArrayList<Path>();
    for (int i = 0; i < status.length; ++i) {
      long len = getMergeSize(inpFs, status[i].getPath(), avgConditionSize);
      if (len >= 0) {
        doMerge = true;
        totalSz += len;
        PartitionDesc pDesc = (dpCtx != null) ? generateDPFullPartSpec(dpCtx, status, tblDesc, i)
            : partDesc;
        if (pDesc == null) {
          Utilities.FILE_OP_LOGGER.warn("merger ignoring invalid DP path " + status[i].getPath());
          continue;
        }
        Utilities.FILE_OP_LOGGER.debug("merge resolver will merge " + status[i].getPath());
        work.resolveDynamicPartitionStoredAsSubDirsMerge(conf, status[i].getPath(), tblDesc,
            aliases, pDesc);
      } else {
        Utilities.FILE_OP_LOGGER.debug("merge resolver will move " + status[i].getPath());

        toMove.add(status[i].getPath());
      }
    }
    if (doMerge) {
      // add the merge MR job
      setupMapRedWork(conf, work, trgtSize, totalSz);

      // add the move task for those partitions that do not need merging
      if (toMove.size() > 0) {
        // Note: this path should be specific to concatenate; never executed in a select query.
        // modify the existing move task as it is already in the candidate running tasks

        // running the MoveTask and MR task in parallel may
        // cause the mvTask write to /ds=1 and MR task write
        // to /ds=1_1 for the same partition.
        // make the MoveTask as the child of the MR Task
        resTsks.add(mrAndMvTask);

        // Originally the mvTask and the child move task of the mrAndMvTask contain the same
        // MoveWork object.
        // If the blobstore optimizations are on and the input/output paths are merged
        // in the move only MoveWork, the mvTask and the child move task of the mrAndMvTask
        // will contain different MoveWork objects, which causes problems.
        // Not just in this case, but also in general the child move task of the mrAndMvTask should
        // be used, because that is the correct move task for the "merge and move" use case.
        Task<?> mergeAndMoveMoveTask = mrAndMvTask.getChildTasks().get(0);
        MoveWork mvWork = (MoveWork) mergeAndMoveMoveTask.getWork();

        LoadFileDesc lfd = mvWork.getLoadFileWork();

        Path targetDir = lfd.getTargetDir();
        List<Path> targetDirs = new ArrayList<Path>(toMove.size());

        for (int i = 0; i < toMove.size(); i++) {
          // Here directly the path name is used, instead of the uri because the uri contains the
          // serialized version of the path. For dynamic partition, we need the non serialized
          // version of the path as this value is used directly as partition name to create the partition.
          // For example, if the dp name is "part=2022-01-16 04:35:56.732" then the uri
          // will contain "part=2022-01-162022-01-16%2004%253A35%253A56.732". When we convert it to
          // partition name, it will come as "part=2022-01-16 04%3A35%3A56.732". But the path will have
          // value "part=2022-01-16 04%3A35%3A56.732", which will get converted to proper name by
          // function escapePathName.
          String[] moveStrSplits = toMove.get(i).toString().split(Path.SEPARATOR);
          int dpIndex = moveStrSplits.length - dpLbLevel;
          Path target = targetDir;
          while (dpIndex < moveStrSplits.length) {
            target = new Path(target, moveStrSplits[dpIndex]);
            dpIndex++;
          }

          targetDirs.add(target);
        }

        LoadMultiFilesDesc lmfd = new LoadMultiFilesDesc(toMove,
            targetDirs, lfd.getIsDfsDir(), lfd.getColumns(), lfd.getColumnTypes());
        mvWork.setLoadFileWork(null);
        mvWork.setLoadTableWork(null);
        mvWork.setMultiFilesDesc(lmfd);
      } else {
        resTsks.add(mrTask);
      }
    } else { // add the move task
      resTsks.add(mvTask);
    }
  }

  private PartitionDesc generateDPFullPartSpec(DynamicPartitionCtx dpCtx, FileStatus[] status,
      TableDesc tblDesc, int i) {
    LinkedHashMap<String, String> fullPartSpec = new LinkedHashMap<>( dpCtx.getPartSpec());
    // Require all the directories to be present with some values.
    if (!Warehouse.makeSpecFromName(fullPartSpec, status[i].getPath(),
        new HashSet<>(dpCtx.getPartSpec().keySet()))) {
      return null;
    }
    return new PartitionDesc(tblDesc, fullPartSpec);
  }

  private void setupMapRedWork(HiveConf conf, MapWork mWork, long targetSize, long totalSize) {
    mWork.setMaxSplitSize(targetSize);
    mWork.setMinSplitSize(targetSize);
    mWork.setMinSplitSizePerNode(targetSize);
    mWork.setMinSplitSizePerRack(targetSize);
    mWork.setIsMergeFromResolver(true);
  }

  private static class AverageSize {
    private final long totalSize;
    private final int numFiles;

    public AverageSize(long totalSize, int numFiles) {
      this.totalSize = totalSize;
      this.numFiles  = numFiles;
    }

    public long getTotalSize() {
      return totalSize;
    }

    public int getNumFiles() {
      return numFiles;
    }
  }

  private AverageSize getAverageSize(FileSystem inpFs, Path dirPath) {
    AverageSize error = new AverageSize(-1, -1);
    try {
      FileStatus[] fStats = inpFs.listStatus(dirPath);

      long totalSz = 0;
      int numFiles = 0;
      for (FileStatus fStat : fStats) {
        Utilities.FILE_OP_LOGGER.debug("Resolver looking at " + fStat.getPath());
        if (fStat.isDir()) {
          AverageSize avgSzDir = getAverageSize(inpFs, fStat.getPath());
          if (avgSzDir.getTotalSize() < 0) {
            return error;
          }
          totalSz += avgSzDir.getTotalSize();
          numFiles += avgSzDir.getNumFiles();
        }
        else {
          totalSz += fStat.getLen();
          numFiles++;
        }
      }

      return new AverageSize(totalSz, numFiles);
    } catch (IOException e) {
      return error;
    }
  }

  /**
   * Whether to merge files inside directory given the threshold of the average file size.
   *
   * @param inpFs input file system.
   * @param dirPath input file directory.
   * @param avgSize threshold of average file size.
   * @return -1 if not need to merge (either because of there is only 1 file or the
   * average size is larger than avgSize). Otherwise the size of the total size of files.
   * If return value is 0 that means there are multiple files each of which is an empty file.
   * This could be true when the table is bucketized and all buckets are empty.
   */
  private long getMergeSize(FileSystem inpFs, Path dirPath, long avgSize) {
    AverageSize averageSize = getAverageSize(inpFs, dirPath);
    if (averageSize.getTotalSize() < 0) {
      return -1;
    }

    if (averageSize.getNumFiles() <= 1) {
      return -1;
    }

    if (averageSize.getTotalSize()/averageSize.getNumFiles() < avgSize) {
      return averageSize.getTotalSize();
    }
    return -1;
  }
}
