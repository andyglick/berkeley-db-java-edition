/*-
 * Copyright (C) 2002, 2018, Oracle and/or its affiliates. All rights reserved.
 *
 * This file was distributed by Oracle as part of a version of Oracle Berkeley
 * DB Java Edition made available at:
 *
 * http://www.oracle.com/technetwork/database/database-technologies/berkeleydb/downloads/index.html
 *
 * Please see the LICENSE file included in the top-level directory of the
 * appropriate version of Oracle Berkeley DB Java Edition for a copy of the
 * license and additional information.
 */

package com.sleepycat.je.cleaner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import com.sleepycat.bind.tuple.SortedPackedLongBinding;
import com.sleepycat.bind.tuple.TupleBase;
import com.sleepycat.bind.tuple.TupleInput;
import com.sleepycat.bind.tuple.TupleOutput;
import com.sleepycat.je.CacheMode;
import com.sleepycat.je.Cursor;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.DatabaseNotFoundException;
import com.sleepycat.je.DbInternal;
import com.sleepycat.je.Durability;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentFailureException;
import com.sleepycat.je.EnvironmentMutableConfig;
import com.sleepycat.je.Get;
import com.sleepycat.je.JEVersion;
import com.sleepycat.je.LockConflictException;
import com.sleepycat.je.LockMode;
import com.sleepycat.je.OperationFailureException;
import com.sleepycat.je.ReadOptions;
import com.sleepycat.je.ScanFilter;
import com.sleepycat.je.ThreadInterruptedException;
import com.sleepycat.je.TransactionConfig;
import com.sleepycat.je.config.EnvironmentParams;
import com.sleepycat.je.dbi.CursorImpl;
import com.sleepycat.je.dbi.DatabaseId;
import com.sleepycat.je.dbi.DatabaseImpl;
import com.sleepycat.je.dbi.DbConfigManager;
import com.sleepycat.je.dbi.DbTree;
import com.sleepycat.je.dbi.DbType;
import com.sleepycat.je.dbi.DupKeyData;
import com.sleepycat.je.dbi.EnvConfigObserver;
import com.sleepycat.je.dbi.EnvironmentImpl;
import com.sleepycat.je.dbi.PutMode;
import com.sleepycat.je.dbi.SortedLSNTreeWalker;
import com.sleepycat.je.log.LogEntryType;
import com.sleepycat.je.log.ReplicationContext;
import com.sleepycat.je.tree.BIN;
import com.sleepycat.je.tree.IN;
import com.sleepycat.je.tree.Key;
import com.sleepycat.je.tree.LN;
import com.sleepycat.je.tree.Node;
import com.sleepycat.je.tree.Tree;
import com.sleepycat.je.txn.BasicLocker;
import com.sleepycat.je.txn.Locker;
import com.sleepycat.je.txn.LockerFactory;
import com.sleepycat.je.txn.Txn;
import com.sleepycat.je.utilint.DbLsn;
import com.sleepycat.je.utilint.LoggerUtils;
import com.sleepycat.je.utilint.StoppableThreadFactory;
import com.sleepycat.je.utilint.TestHook;
import com.sleepycat.je.utilint.TestHookExecute;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Performs async processing for Record Extinction and Database Extinction,
 * via the {@link RecordExtinction} and {@link DatabaseExtinction} tasks.
 *
 * <p>When the application calls {@link Environment#discardExtinctRecords}
 * (record extinction) or {@link Environment#removeDatabase} or
 * {@link Environment#truncateDatabase} (DB extinction), we store a scan
 * record in an internal scan DB and queue the scan task.</p>
 *
 * <p>The scan DB is a replicated DB, but the scan records themselves may
 * be replicated or non-replicated as described below. (This mixing is only
 * possible for internal DBs, and is also used for the DbTree naming DB.)</p>
 *
 * <ul>
 *     <li>For record extinction, the scan record is replicated and is
 *     inserted in the user transaction passed to discardExtinctRecords.
 *     The durability of this record is therefore the same as the durability
 *     of the record extinction, and if this txn aborts, the scan record
 *     insertion will also be aborted. When this record is replicated, the
 *     replica replay will initiate the scan on the replica.</li>
 *
 *     <li>For DB extinction, the scan record is inserted after the commit of
 *     the txn passed to removeDatabase or truncateDatabase. This insertion
 *     is not replicated, and instead the replay of NameLN causes insertion
 *     of the scan record on each replica. If there is a crash between the
 *     user txn commit and insertion of the scan record, recovery redo of the
 *     NameLN will cause insertion of the scan record.</li>
 * </ul>
 *
 * <p>The scan task is always performed independently on each node in a rep
 * group, and therefore update and deletion of the scan record are
 * non-replicated operations. Recovery will re-queue the scan task for all
 * scan records not yet deleted.</p>
 *
 * <p>For now, although a thread executor is used, it is limited to just use
 * one thread. So for now, all extinction tasks will execute serially. If
 * using multiple threads becomes necessary, some synchronization changes will
 * be needed.</p>
 */
public class ExtinctionScanner implements EnvConfigObserver {

    /* ExtinctionTask record types. */
    private static final int RECORD_EXTINCTION = 0;
    private static final int DATABASE_EXTINCTION = 1;

    private static final JEVersion MIN_JE_VERSION = new JEVersion("18.1.7");
    public static JEVersion TEST_MIN_JE_VERSION = null;

    private static final ReadOptions NOLOCK_UNCHANGED = new ReadOptions()
        .setCacheMode(CacheMode.UNCHANGED)
        .setLockMode(LockMode.READ_UNCOMMITTED);

    private static final TransactionConfig NO_SYNC_TXN =
        new TransactionConfig().
            setLocalWrite(false).
            setDurability(Durability.COMMIT_NO_SYNC);

    public static JEVersion getMinJEVersion() {
        if (TEST_MIN_JE_VERSION != null) {
            return TEST_MIN_JE_VERSION;
        }
        return MIN_JE_VERSION;
    }

    /*
     * Max time to wait for the discardExtinctRecords user txn to commit. If
     * the timeout elapses we'll try again later, but we need a limit for
     * blocking in the scanner thread.
     */
    private static final int COMMIT_LOCK_TIMEOUT_MS = 500;

    @NonNull private final EnvironmentImpl envImpl;
    @NonNull private final Logger logger;
    @Nullable private DatabaseImpl scanDb;
    @Nullable private final ThreadPoolExecutor threadPool;
    private final AtomicLong lastRepScanID = new AtomicLong();
    private final AtomicLong lastNonRepScanID = new AtomicLong();

    private final Map<Long, RecordExtinction> activeRecordTasks =
        Collections.synchronizedMap(new HashMap<>());

    private final Set<Long> completedRecordScans =
        Collections.synchronizedSet(new HashSet<>());

    private final boolean enabled;
    private final int batchSize;
    private final int batchDelayMs;
    private final long flushObsoleteBytes;
    private volatile boolean shutdownRequested;
    private int terminateMillis;
    private volatile List<ExtinctionTask> recoveredTasks = new ArrayList<>();

    TestHook beforeScan1Hook;
    TestHook beforeScan1FlushHook;
    TestHook beforeScan2Hook;

    public TestHook<?> dbBeforeWriteTaskHook;
    TestHook<?> dbBeforeExecTaskHook;
    TestHook<?> dbBeforeDeleteMapLNHook;
    TestHook<?> dbBeforeDeleteTaskLNHook;

    public ExtinctionScanner(@NonNull final EnvironmentImpl envImpl) {

        this.envImpl = envImpl;
        logger = LoggerUtils.getLogger(getClass());

        final DbConfigManager configManager = envImpl.getConfigManager();

        enabled = configManager.getBoolean(
            EnvironmentParams.ENV_RUN_EXTINCT_RECORD_SCANNER);

        batchSize = configManager.getInt(
            EnvironmentParams.CLEANER_EXTINCT_SCAN_BATCH_SIZE);

        batchDelayMs = configManager.getDuration(
            EnvironmentParams.CLEANER_EXTINCT_SCAN_BATCH_DELAY);

        flushObsoleteBytes = configManager.getLong(
            EnvironmentParams.CLEANER_FLUSH_EXTINCT_OBSOLETE);

        terminateMillis = configManager.getDuration(
            EnvironmentParams.EVICTOR_TERMINATE_TIMEOUT);

        envImpl.addConfigObserver(this);

        /* Use a single thread (for now) and an unbounded queue. */
        threadPool = envImpl.isReadOnly() ?
            null :
            new ThreadPoolExecutor(
                1 /*corePoolSize*/, 1 /*maxPoolThreads*/,
                0 /*keepAliveTime*/, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                new StoppableThreadFactory(
                    envImpl, "JEExtinctRecordScanner", logger));
    }

    public void shutdown() {

        if (threadPool == null) {
            return;
        }

        /*
         * Set the shutdown flag so that outstanding tasks end early. The
         * call to threadPool.shutdown is an orderly shutdown that waits for
         * in-flight tasks to end.
         */
        shutdownRequested = true;
        threadPool.shutdown();

        /*
         * awaitTermination() will wait for the timeout period, or will be
         * interrupted, but we don't really care which it is. The scan
         * shouldn't be interrupted, but if it is, something urgent is
         * happening.
         */
        boolean shutdownFinished = false;
        try {
            shutdownFinished = threadPool.awaitTermination(
                terminateMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            /* We've been interrupted, just give up and end. */
        } finally {
            if (!shutdownFinished) {
                threadPool.shutdownNow();
            }
        }
    }

    public void requestShutdown() {

        if (threadPool == null) {
            return;
        }

        shutdownRequested = true;
        threadPool.shutdown();
    }

    @Override
    public void envConfigUpdate(
        final DbConfigManager configManager,
        final EnvironmentMutableConfig ignore) {

        terminateMillis = configManager.getDuration(
            EnvironmentParams.EVICTOR_TERMINATE_TIMEOUT);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isEmpty() {
        assert threadPool != null;
        return threadPool.getQueue().isEmpty() &&
            threadPool.getActiveCount() == 0;
    }

    public long getLastLocalId() {
        return lastNonRepScanID.get();
    }

    public long getLastReplicatedId() {
        return lastRepScanID.get();
    }

    public void setLastIds(final long lastRepScanID,
                           final long lastNonRepScanID) {
        this.lastRepScanID.set(
            Math.min(this.lastRepScanID.get(), lastRepScanID));
        this.lastNonRepScanID.set(
            Math.max(this.lastNonRepScanID.get(), lastNonRepScanID));
    }

    /**
     * Opens the DB if it is not already open.
     * Assumes that envImpl.isReadOnly() is false.
     *
     * @throws com.sleepycat.je.rep.ReplicaWriteException if the scan DB
     * NameLN has not yet been replicated, including when the master node is
     * running older software and the scan DB has not been created on the
     * master. To check for this exception in JE standalone (i.e., in this
     * class), call {@link OperationFailureException#isReplicaWrite()}.
     */
    private synchronized void openDb() {

        if (scanDb != null) {
            return;
        }

        final DbTree dbTree = envImpl.getDbTree();
        final TransactionConfig txnConfig = new TransactionConfig();

        Locker locker = Txn.createLocalAutoTxn(envImpl, txnConfig);

        try {
            scanDb = dbTree.getDb(
                locker, DbType.EXTINCT_SCANS.getInternalName(),
                null /*databaseHandle*/, false);
        } finally {
            locker.operationEnd(true);
        }

        if (scanDb != null) {
            return;
        }

        if (envImpl.isReadOnly()) {
            /* This should have been checked earlier. */
            throw EnvironmentFailureException.unexpectedState();
        }

        final DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setReplicated(true);
        dbConfig.setTransactional(true);

        final ReplicationContext repContext = envImpl.isReplicated() ?
            ReplicationContext.MASTER : ReplicationContext.NO_REPLICATE;

        txnConfig.setDurability(Durability.COMMIT_WRITE_NO_SYNC);

        txnConfig.setConsistencyPolicy(
            envImpl.getConsistencyPolicy("NoConsistencyRequiredPolicy"));

        locker = Txn.createAutoTxn(envImpl, txnConfig, repContext);

        boolean success = false;
        try {
            scanDb = dbTree.createInternalDb(
                locker, DbType.EXTINCT_SCANS.getInternalName(), dbConfig);
            success = true;
        } finally {
            locker.operationEnd(success);
        }
    }

    /**
     * @return whether the given ID is still present in the extinction scan DB,
     * meaning that that scan is not complete.
     */
    public boolean isScanTaskActive(final long id) {

        try {
            openDb();
        } catch (OperationFailureException e) {
            if (e.isReplicaWrite()) {
                /* Scan DB NameLN has not yet been replayed. */
                return true;
            }
            throw e;
        }

        final DatabaseEntry keyEntry = new DatabaseEntry();
        serializeKey(id, keyEntry);

        final Locker locker =
            BasicLocker.createBasicLocker(envImpl, false /*noWait*/);

        try (final Cursor cursor =
                 DbInternal.makeCursor(scanDb, locker, null)) {

            return cursor.get(keyEntry, null, Get.SEARCH, null) != null;

        } finally {
            locker.operationEnd();
        }
    }

    /**
     * Fires an assertion, or logs a warning if assertions are disabled.
     */
    private void assertOrWarn(final String msg) {
        assert false : msg;
        LoggerUtils.warning(logger, envImpl, msg);
    }
    private void deleteTask(final long id) {

        if (id == 0) {
            /* Non-persistent task. */
            return;
        }

        /*
         * Use non-replicated locker so the deletion is not replicated.
         * The extinction records are deleted independently on each node.
         */
        final Locker locker = Txn.createLocalAutoTxn(envImpl, NO_SYNC_TXN);
        boolean success = false;

        try (final Cursor cursor =
                 DbInternal.makeCursor(scanDb, locker, null)) {

            final DatabaseEntry keyEntry = new DatabaseEntry();
            serializeKey(id, keyEntry);

            if (cursor.get(keyEntry, null, Get.SEARCH,
                LockMode.RMW.toReadOptions()) == null) {

                assertOrWarn(
                    "Completed extinction task not found in DB, id=" +
                        id);
                return;
            }

            DbInternal.deleteWithRepContext(
                cursor, ReplicationContext.NO_REPLICATE);

            success = true;

        } finally {
            locker.operationEnd(success);
        }
    }

    /**
     * During recovery, save incomplete scan tasks after reading them from the
     * scans DB. Incomplete scans are held until after recovery is finished,
     * and then executed by {@link #executeRecoveredTasks()}. If an extinction
     * is requested at the end of recovery (e.g., for temp DB deletion), it
     * is queued rather than being executed immediately.
     */
    public void recoverIncompleteTasks() {

        assert recoveredTasks != null;

        if (envImpl.isReadOnly() || !enabled) {
            return;
        }

        try {
            openDb();
        } catch (OperationFailureException e) {
            if (e.isReplicaWrite()) {
                /* Scan DB NameLN not yet replayed. */
                return;
            }
            throw e;
        }

        final DatabaseEntry keyEntry = new DatabaseEntry();
        final DatabaseEntry dataEntry = new DatabaseEntry();

        final Locker locker =
            BasicLocker.createBasicLocker(envImpl, false /*noWait*/);

        try (final Cursor cursor =
                 DbInternal.makeCursor(scanDb, locker, null)) {

            while (cursor.get(
                keyEntry, dataEntry, Get.NEXT, null) != null) {

                final ExtinctionTask task =
                    materializeTask(keyEntry, dataEntry);

                if (task instanceof RecordExtinction) {
                    final RecordExtinction rTask = (RecordExtinction) task;
                    activeRecordTasks.put(rTask.id, rTask);
                }

                recoveredTasks.add(task);
            }
        } finally {
            locker.operationEnd();
        }
    }

    /**
     * After recovery, queue any incomplete scans for execution.
     */
    public synchronized void executeRecoveredTasks() {

        if (envImpl.isReadOnly() || !enabled) {
            return;
        }

        assert threadPool != null;

        /*
         * This method is called more than once when multiple handles are
         * opened for a single env. We must execute recovered tasks only once.
         */
        synchronized (this) {
            if (recoveredTasks == null) {
                return;
            }

            for (final ExtinctionTask task : recoveredTasks) {
                threadPool.execute(task);
            }

            recoveredTasks = null;
        }
    }

    /**
     * Queues a task to process a scan LN that was replayed on this replica.
     * The LN replay must be done before this method is called, since {@link
     * RecordExtinction#isTaskCommitted()} assumes that the record is in the
     * Btree.
     */
    public void replay(final byte[] scanKey, final byte[] scanData) {

        assert !envImpl.isReadOnly();
        assert threadPool != null;

        if (!enabled) {
            return;
        }

        final ExtinctionTask task = materializeTask(
            new DatabaseEntry(scanKey),
            new DatabaseEntry(scanData));

        if (task instanceof RecordExtinction) {
            final RecordExtinction rTask = (RecordExtinction) task;
            activeRecordTasks.put(rTask.id, rTask);
        }

        /*
         * Do as little work as possible in the replay thread. Open the DB
         * as part of the async task.
         */
        threadPool.execute(() -> {
            openDb();
            task.run();
        });
    }

    /**
     * Used during recovery or replica replay to create a scan record that is
     * stored in the database. During recovery, these are incomplete scans.
     * During replay, they're new scans coming from the master.
     */
    private ExtinctionTask materializeTask(final DatabaseEntry keyEntry,
                                           final DatabaseEntry dataEntry) {

        final long id = materializeKey(keyEntry);

        /*
         * Must update last scan IDs during replay in case this node is
         * elected master. This is also important when called via
         * recoverIncompleteTasks when recovery has read a old format
         * CheckpointEnd, since extinction IDs were not added to CheckpointEnd
         * until log version 17 [#26973].
         *
         * We don't have to worry about an atomic update (for now) because
         * recovery is single threaded and there is only one scanner thread.
         */
        if (id < 0) {
            if (lastRepScanID.get() > id) {
                lastRepScanID.set(id);
            }
        } else {
            if (lastNonRepScanID.get() < id) {
                lastNonRepScanID.set(id);
            }
        }

        final TupleInput in = TupleBase.entryToInput(dataEntry);

        switch (in.readPackedInt()) {
        case RECORD_EXTINCTION:
            return new RecordExtinction(id, in);
        case DATABASE_EXTINCTION:
            return new DatabaseExtinction(id, in);
        default:
            throw EnvironmentFailureException.unexpectedState();
        }
    }

    private static void serializeKey(final long id,
                                     final DatabaseEntry keyEntry) {

        SortedPackedLongBinding.longToEntry(id, keyEntry);
    }

    public static long materializeKey(final DatabaseEntry keyEntry) {
        return SortedPackedLongBinding.entryToLong(keyEntry);
    }

    private static TupleOutput serializeType(int taskType) {
        final TupleOutput out = new TupleOutput();
        out.writePackedInt(taskType);
        return out;
    }

    private interface ExtinctionTask extends Runnable {
        boolean isExtinctionForDb(DatabaseId dbId);
    }

    /* ------------------------*
     * Record Extinction methods
     * ------------------------*/

    /**
     * Inserts the scan record in the DB and queues the scan for execution.
     *
     * Caller must check for a read-write env and {@link #isEnabled()}.
     *
     * @throws DatabaseNotFoundException if a DB name does not exist.
     */
    public long discardExtinctRecords(
        @NonNull final Locker locker,
        @NonNull final Set<String> dbNames,
        @Nullable final DatabaseEntry beginKey,
        @Nullable final DatabaseEntry endKey,
        @Nullable final ScanFilter filter,
        @NonNull final String label) {

        assert enabled;
        assert threadPool != null;

        openDb();

        final long id = envImpl.isReplicated() ?
            lastRepScanID.decrementAndGet() :
            lastNonRepScanID.incrementAndGet();

        final RecordExtinction task = new RecordExtinction(
            id, dbNamesToIds(dbNames), beginKey, endKey, filter, label);

        /* Insert a record using a user-level locker. It will be replicated. */
        task.writeTask(0, null, locker, true /*insert*/);

        activeRecordTasks.put(id, task);

        /*
         * It is important to queue the task _after_ writing the task record,
         * so that the record exists in the Btree when read by the scan, since
         * RecordExtinction.isTaskCommitted assumes that the record is in
         * the Btree.
         */
        threadPool.execute(task);

        return task.id;
    }

    /**
     * Transforms a set of DB names into a sorted set of DB IDs.
     *
     * @throws DatabaseNotFoundException if a DB name does not exist.
     */
    private NavigableSet<Long> dbNamesToIds(final Set<String> dbNames) {

        final DbTree dbTree = envImpl.getDbTree();
        final NavigableSet<Long> dbIds = new TreeSet<>();

        for (final String dbName : dbNames) {

            final Locker locker = BasicLocker.createBasicLocker(envImpl);

            try {
                final DatabaseId id =
                    dbTree.getDbIdFromName(locker, dbName, null, false);

                if (id == null) {
                    throw new DatabaseNotFoundException(
                        "DB does not exist: " + dbName);
                }

                dbIds.add(id.getId());

            } finally {
                locker.operationEnd();
            }
        }

        return dbIds;
    }

    /**
     * Returns whether the given DB and key are targeted by an active record
     * extinction.
     */
    boolean isRecordExtinctionIncomplete(final DatabaseImpl dbImpl,
                                         final byte[] key) {
        synchronized (activeRecordTasks) {
            for (final RecordExtinction task : activeRecordTasks.values()) {
                if (task.isKeyTargeted(dbImpl, key)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Get a copy of the completed scan IDs before starting a checkpoint.
     */
    public Set<Long> getCompletedRecordScans() {

        synchronized (completedRecordScans) {
            return new HashSet<>(completedRecordScans);
        }
    }

    /**
     * After a checkpoint, delete the scans previously returned by
     * {@link #getCompletedRecordScans()}.
     */
    public void deleteCompletedRecordScans(final Set<Long> scanIds) {

        if (scanIds.isEmpty()) {
            return;
        }

        assert scanDb != null;

        for (final long id : scanIds) {
            deleteTask(id);
        }

        completedRecordScans.removeAll(scanIds);
        activeRecordTasks.keySet().removeAll(scanIds);

        LoggerUtils.info(
            logger, envImpl,
            "Deleted complete extinct record scans after checkpoint, " +
                "ids=" + scanIds);
    }


    /**
     * The RecordExtinction task makes a set of records extinct as specified by
     * {@link Environment#discardExtinctRecords}.
     *
     * <p>The user passes a key range and an optional serializable
     * ScanFilter to discardExtinctRecords. The serialized ScanFilter is
     * stored in the scan record so it can be used on all nodes for
     * executing the scan.</p>
     *
     * <p>Two key-only cursor scans of the specified key range are performed,
     * using the optional ScanFilter to identify the extinct records. The
     * life cycle of the scan task is as follows.</p>
     * <ol>
     *     <li>
     *     The first scan counts LN utilization using the lastLoggedSize that
     *     is stored in the BIN slots. When counting is complete, utilization
     *     is flushed (FileSummaryLNs are written for each impacted file), the
     *     countComplete field is set to true, and the scan record is updated
     *     to record this persistently. The first scan is skipped for a
     *     duplicates DB, since all LNs are already obsolete.
     *     </li>
     *     <li>
     *     The second scan sets the KnownDeleted flags on the BIN slots of all
     *     extinct records, and queues the BINs for the compressor. When the
     *     second scan is done, its record ID is added to a
     *     completedRecordScans set.
     *     </li>
     *     <li>
     *     When a checkpoint starts, the completedRecordScans set is copied.
     *     When the checkpoint finishes, the deletion of all slots for those
     *     scans will have been persisted, either after compressing them
     *     (hopefully) or via the KnownDeleted flag. The completed scan
     *     records are then deleted.
     *     </li>
     * </ol>
     *
     * <p>If a crash occurs, the incomplete scan LNs are read during recovery.
     * countComplete is set to true or false, depending on whether step 1
     * finished earlier. If not, countComplete is false and step 1 is re-run.
     * Steps 2 and 3 are always re-run.</p>
     *
     * <p>The app can optionally supply a environment-wide ExtinctionFilter
     * callback.  As described in its javadoc, if it is not supplied then
     * cleaning of extinct LNs will be more expensive because a Btree lookup is
     * required. Since this callback can cause LNs to be cleaned _before_ their
     * BIN slot is marked KnownDeleted, an LOG_FILE_NOT_FOUND LN fetch error is
     * ignored (record is considered deleted) if the callback indicates the
     * record is extinct.</p>
     *
     * <p>The reason for two scans is that we cannot start marking BIN slots
     * deleted until LN utilization changes are flushed to disk, or the counts
     * might be lost due to a crash. If the deletion of BIN slots is flushed by
     * a checkpoint, then after a crash there would be no way to find them
     * again in order to count the LNs.</p>
     *
     * <p>In the future we may be able to use a single scan, combining counting
     * and slot deletion for non-duplicates DBs, if we can flush the LN
     * utilization changes before a non-provisional IN is logged. This would
     * require that splits are not logged, INs are logged provisionally by
     * recovery, as well as changes to checkpointing and eviction. This
     * complexity may not be worthwhile, since the scans are very quick.</p>
     *
     * <p>Discarded approach: calculate overall utilization using full scans to
     * get rid of the complexity of transactional utilization counting, while
     * using partial scans to update utilization more quickly for specific
     * changes like drop table or DB removal. This doesn't work well because
     * there is no way to accurately update overall utilization at the end
     * of a full scan, because of variable overlap between what is counted
     * by full and partial scans. A worst case scenario may be dropping a
     * large table that is counted obsolete by both a partial and a full
     * scan.</p>
     *
     * <pre>
     * Ideas for implementing dynamic TTL using a similar approach.
     * - Dynamic TTL and record extinction are different:
     *   - Extinction has no filtering requirement. Make the app do it.
     *   - Extinction has a trigger event for scanning.
     * - Since an expensive callback should not be used for filtering, app must
     *   must supply the TTL in ReadOptions. JE will filter using this value.
     * - The TTL must also be specified using WriteOptions in order to update
     *   the histogram.
     * - Do scans, similar to for record extinction, but instead of using a
     *   callback, use old/new TTL values supplied by the app.
     * - When TTL is reduced, some slots will become obsolete and can be
     *   deleted as above.
     * - Histogram is updated by subtracting slot/LN size for old TTL and
     *   adding it for new TTL.
     * - How to prevent further TTL changes until the partial scan is complete?
     *   Perhaps add an API to return incomplete partial scans.
     *   Or do multiple scans really produce incorrect results?
     * - An expensive (table lookup) dynamic TTL callback must still be used
     *   for eviction (as well as cleaning). This callback is distinct from
     *   the record extinction callback and it simply maps key to expiration
     *   time.
     * - For cleaning LNs we must call the dynamic TTL callback and the record
     *   extinction callback, both of which do a table lookup. We should
     *   combine them somehow, to avoid two table lookups.
     * How to handle TTL decrease?
     * - Problem: May be dangling references: BIN slot still exists, but LN
     *   does not.
     * - Solution: Consider it expired if slot has dynamic TTL. Table
     *   metadata must have a "TTL existed at one time" flag, even if it has
     *   been set to zero.
     * How to handle TTL increase?
     * - Problem: When TTL is increased, some already expired records will be
     *   resurrected.
     * - Solution:
     *   - Calculate max record creation time for which records are expired
     *     using the _old_ TTL. Save that maxCreationTime in the table
     *     metadata.
     *   - When determining expiration, any record having a creation time LTE
     *     maxCreationTime should be considered expired. This is in addition to
     *     records with later creation times but that are expired according to
     *     the _new_ TTL.
     * For example:
     * - TTL is 1 month. Data is written during January-June.
     * - On July 15, TTL is changed to 2 months.
     * - Data for June has not been purged and will now have a TTL of 2 months.
     * - Data prior to June 1 may have been purged and we must consider it
     *   expired.
     * - TTL-min-creationDate is set to June 1. Data before this time is
     *   considered expired.
     * </pre>
     */
    private class RecordExtinction implements ExtinctionTask {

        /* Fields describing the scan. */
        private final long id;
        private final NavigableSet<Long> dbIds;
        private final byte[] beginKey;
        private final byte[] endKey;
        private final ScanFilter filter;
        private final String label;
        private boolean countComplete;

        /*
         * When LN utilization is flushed before the COUNT phase is complete,
         * we save the DB ID and the key of the last record read. On redo of a
         * scan, LN utilization is counted only for records past this position.
         */
        private final long flushAtLastDb;
        private final byte[] flushAtLastKey;

        /* Transient fields. */
        private LocalUtilizationTracker tracker;
        private long unflushedBytes;
        private boolean countLNsThisDB;
        private boolean checkFlushKeyThisDB;
        private long scannedRecords;
        private long extinctRecords;

        /**
         * Create a new scan when discardExtinctRecords is called.
         */
        RecordExtinction(final long id,
                         final NavigableSet<Long> dbIds,
                         final DatabaseEntry beginKey,
                         final DatabaseEntry endKey,
                         final ScanFilter filter,
                         final String label) {

            tracker = new LocalUtilizationTracker(envImpl);

            this.id = id;
            this.dbIds = dbIds;
            this.beginKey =
                (beginKey != null) ? LN.copyEntryData(beginKey) : null;
            this.endKey =
                (endKey != null) ? LN.copyEntryData(endKey) : null;
            this.filter = filter;
            this.label = label;

            countComplete = false;
            flushAtLastDb = 0;
            flushAtLastKey = null;
        }

        /**
         * Materialize a scan record that was read from the scans DB.
         */
        RecordExtinction(final long id,
                         final TupleInput in) {

            tracker = new LocalUtilizationTracker(envImpl);
            this.id = id;

            in.readPackedInt(); // Discard version for now
            countComplete = in.readBoolean();

            /* Simple run length encoding. */
            final int nDbs = in.readPackedInt();
            dbIds = new TreeSet<>();
            long prevDbId = 0;

            for (int i = 0; i < nDbs; i += 1) {
                prevDbId += in.readPackedLong();
                dbIds.add(prevDbId);
            }

            int len = in.readPackedInt();
            if (len == 0) {
                beginKey = null;
            } else {
                beginKey = new byte[len];
                in.read(beginKey);
            }

            len = in.readPackedInt();
            if (len == 0) {
                endKey = null;
            } else {
                endKey = new byte[len];
                in.read(endKey);
            }

            len = in.readPackedInt();
            if (len == 0) {
                filter = null;
            } else {
                final byte[] bytes = new byte[len];
                in.read(bytes);

                filter = (ScanFilter) DatabaseImpl.bytesToObject(
                    bytes, "ScanFilter", envImpl.getClassLoader());
            }

            label = in.readString();

            flushAtLastDb = in.readPackedLong();

            len = in.readPackedInt();
            if (len == 0) {
                flushAtLastKey = null;
            } else {
                flushAtLastKey = new byte[len];
                in.read(flushAtLastKey);
            }
        }

        void serializeData(final long flushedDbId,
                           final byte[] flushedKey,
                           final TupleOutput out) {

            out.writePackedInt(0); // Version
            out.writeBoolean(countComplete);

            /* Simple run length encoding. */
            out.writePackedInt(dbIds.size());
            long prevDbId = 0;

            for (final long id : dbIds) {
                out.writePackedLong(id - prevDbId);
                prevDbId = id;
            }

            if (beginKey == null) {
                out.writePackedInt(0);
            } else {
                out.writePackedInt(beginKey.length);
                out.write(beginKey);
            }

            if (endKey == null) {
                out.writePackedInt(0);
            } else {
                out.writePackedInt(endKey.length);
                out.write(endKey);
            }

            if (filter == null) {
                out.writePackedInt(0);
            } else {
                final byte[] bytes = DatabaseImpl.objectToBytes(
                    filter, "ScanFilter");

                out.writePackedInt(bytes.length);
                out.write(bytes);
            }

            out.writeString(label);

            out.writePackedLong(flushedDbId);

            if (flushedKey == null) {
                out.writePackedInt(0);
            } else {
                out.writePackedInt(flushedKey.length);
                out.write(flushedKey);
            }
        }

        /**
         * Inserts or updates a RecordExtinction task record.
         *
         * flushedDbId and flushedKey are specified when LN utilization has
         * been flushed and we want to save the point where this happened by
         * updating the record. In this case, insert should be false.
         *
         * When the env is replicated, the locker is used to determine whether
         * to replicate the write. A replicated locker should be used when when
         * insert is true. A non-replicated locker should be used to prevent
         * replication when insert is false.
         */
        void writeTask(final long flushedDbId,
                       final byte[] flushedKey,
                       final Locker locker,
                       final boolean insert) {

            assert !envImpl.isReplicated() || insert == locker.isReplicated();
            assert scanDb != null;

            final DatabaseEntry keyEntry = new DatabaseEntry();
            final DatabaseEntry dataEntry = new DatabaseEntry();

            serializeKey(id, keyEntry);
            final TupleOutput out = serializeType(RECORD_EXTINCTION);
            serializeData(flushedDbId, flushedKey, out);
            TupleBase.outputToEntry(out, dataEntry);

            try (final Cursor cursor =
                     DbInternal.makeCursor(scanDb, locker, null)) {

                if (DbInternal.putWithRepContext(
                    cursor, keyEntry, dataEntry,
                    insert ? PutMode.NO_OVERWRITE : PutMode.OVERWRITE,
                    locker.isReplicated() ?
                        scanDb.getRepContext() :
                        ReplicationContext.NO_REPLICATE) == null) {

                    throw EnvironmentFailureException.unexpectedState();
                }
            }
        }

        public boolean isExtinctionForDb(DatabaseId dbId) {
            return false;
        }

        /**
         * Ensures that the discardExtinctRecords txn has committed before a
         * scan can proceed. This is important whether discardExtinctRecords
         * was called on this master node, or is being replayed on a replica.
         *
         * <p>If the record does not exist, the txn was aborted and this
         * method removes the task from activeRecordTasks and returns false.
         * For this to work reliably, the scan LN must be added to the Btree
         * before this method is called.</p>
         *
         * <p>If a lock timeout occurs, the commit/abort is not yet
         * available and we re-queue the task to check again later and
         * return false. Otherwise, return true.</p>
         *
         * @return true if the task is committed and can be run. Returns false
         * if the user txn was aborted or the record is locked.
         */
        private boolean isTaskCommitted() {

            final Locker locker =
                BasicLocker.createBasicLocker(envImpl, false /*noWait*/);

            /* Override the users's default timeout. */
            locker.setLockTimeout(COMMIT_LOCK_TIMEOUT_MS);

            final DatabaseEntry keyEntry = new DatabaseEntry();
            serializeKey(id, keyEntry);

            try (final Cursor cursor =
                     DbInternal.makeCursor(scanDb, locker, null)) {

                if (cursor.get(keyEntry, null, Get.SEARCH, null) != null) {
                    return true;
                } else {
                    activeRecordTasks.remove(id);
                    return false;
                }

            } catch (LockConflictException e) {

                LoggerUtils.info(
                    logger, envImpl,
                    "Delaying extinct record scan," +
                        " discardExtinctRecord txn is still open, id=" +
                        id +" label=" + label);

                assert threadPool != null;

                threadPool.execute(this);
                return false;

            } finally {
                locker.operationEnd();
            }
        }

        /**
         * Execute the two scans for all requested DBs. If we're re-running a
         * scan, only count LN utilization if counting was not previously
         * completed, and only from the saved db/key onward.
         */
        @Override
        public void run() {

            assert !completedRecordScans.contains(id);

            try {
                if (!isTaskCommitted()) {
                    return;
                }

                LoggerUtils.info(
                    logger, envImpl,
                    "Start extinct record scan, id=" + id +" label=" + label);

                final long pass1Scanned;
                final long pass1Extinct;

                if (!countComplete) {

                    if (beforeScan1Hook != null) {
                        beforeScan1Hook.doHook();
                    }

                    for (final long dbId : dbIds) {
                        if (shutdownRequested) {
                            return;
                        }
                        scanDb(dbId);
                    }

                    if (beforeScan1FlushHook != null) {
                        beforeScan1FlushHook.doHook();
                    }

                    countComplete = true;
                    flushUtilization(0, null);

                    pass1Scanned = scannedRecords;
                    pass1Extinct = extinctRecords;
                    scannedRecords = 0;
                    extinctRecords = 0;
                } else {
                    pass1Scanned = 0;
                    pass1Extinct = 0;
                }

                if (beforeScan2Hook != null) {
                    beforeScan2Hook.doHook();
                }

                for (final long dbId : dbIds) {
                    if (shutdownRequested) {
                        return;
                    }
                    scanDb(dbId);
                }

                completedRecordScans.add(id);

                LoggerUtils.info(
                    logger, envImpl,
                    "End extinct record scan, wait for checkpoint, id=" + id +
                        " pass1Scanned=" + pass1Scanned +
                        " pass1Extinct=" + pass1Extinct +
                        " pass2Scanned=" + scannedRecords +
                        " pass2Extinct=" + extinctRecords +
                        " label=" + label);

            } catch (Exception e) {
                LoggerUtils.warning(
                    logger, envImpl,
                    "Fatal exception during extinct record scan, id=" + id +
                        " label=" + label + ": " + e);
                throw e;
            }
        }

        /**
         * Execute scan of a single DB. Perform the scan in batches. We must
         * release the DB after each batch to allow for DB removal/truncation
         * operations. We also delay after each batch to avoid hogging CPU.
         */
        private void scanDb(final long dbId) {

            final DbTree dbTree = envImpl.getDbTree();
            final DatabaseEntry keyEntry = new DatabaseEntry();
            /* dataEntry is used only for dup DBs. */
            DatabaseEntry dataEntry = null;
            boolean firstBatch = true;

            while (true) {
                envImpl.checkOpen();

                if (shutdownRequested) {
                    return;
                }

                final DatabaseImpl dbImpl =
                    dbTree.getDb(new DatabaseId(dbId));
                try {
                    if (dbImpl == null || dbImpl.isDeleting()) {
                        LoggerUtils.warning(
                            logger, envImpl,
                            "DB=" + dbId +
                                " deleted during extinct record scan," +
                                " id=" + id + " label=" + label);
                        return;
                    }

                    if (firstBatch) {
                        if (countComplete) {
                            countLNsThisDB = false;
                            checkFlushKeyThisDB = false;
                        } else {
                            /*
                             * LNs were already counted obsolete in a dup
                             * DB, so no need to count them here. Also no
                             * need to count LNs if the entire DB was
                             * counted in a prior scan.
                             */
                            countLNsThisDB =
                                !dbImpl.isLNImmediatelyObsolete() &&
                                (flushAtLastKey == null ||
                                    dbId >= flushAtLastDb);

                            if (!countLNsThisDB) {
                                return;
                            }

                            /* Only check the flush key if it is in this DB. */
                            checkFlushKeyThisDB =
                                flushAtLastKey != null &&
                                dbId == flushAtLastDb;
                        }

                        /* Setup scan begin key for initial positioning. */
                        if (beginKey != null) {
                            keyEntry.setData(beginKey);
                        }

                        /*
                         * Need data entry for repositioning in a dup db. The
                         * dup data is embedded, so this doesn't cause a fetch.
                         */
                        if (dbImpl.getSortedDuplicates()) {
                            dataEntry = new DatabaseEntry();
                        }
                    }

                    if (!scanBatch(dbImpl, keyEntry, dataEntry, firstBatch)) {
                        return;
                    }

                    firstBatch = false;
                } finally {
                    dbTree.releaseDb(dbImpl);
                }

                if (batchDelayMs > 0) {
                    try {
                        Thread.sleep(batchDelayMs);
                    } catch (InterruptedException e) {
                        LoggerUtils.warning(
                            logger, envImpl,
                            "Extinct record scan interrupted, id=" + id +
                                " label=" + label);
                        throw new ThreadInterruptedException(envImpl, e);
                    }
                }
            }
        }

        /**
         * Scan a single batch (within a given DB).
         *
         * Returns true if the batch size is reached and there are more
         * records in the DB. When true is returned, the key/data pair is
         * the next record to be processed.
         *
         * Returns false if DB is finished or we are shutting down.
         */
        private boolean scanBatch(
            final DatabaseImpl dbImpl,
            final DatabaseEntry keyEntry,
            final DatabaseEntry dataEntry,
            final boolean firstBatch) {

            final Locker locker =
                LockerFactory.getInternalReadOperationLocker(envImpl);

            try (final Cursor cursor =
                     DbInternal.makeCursor(dbImpl, locker, null, false)) {

                DbInternal.excludeFromOpStats(cursor);
                cursor.setCacheMode(CacheMode.UNCHANGED);

                /*
                 * If the scan does not have a begin key, start at the first
                 * record in the DB.
                 *
                 * Else if the scan has a begin key and this is the first
                 * batch, use SEARCH_GTE to position at the start of the first
                 * batch. Note that this will position at the first dup for
                 * a given secondary key.
                 *
                 * Otherwise use SEARCH_ANY_GTE to move to the next record,
                 * which was saved in the key/data entry at the end of the
                 * prior batch. If that record has been deleted, this will
                 * position at the next highest existing key/data pair.
                 */
                if (cursor.get(
                    keyEntry, dataEntry,
                    (keyEntry.getData() == null) ? Get.FIRST :
                        (firstBatch ? Get.SEARCH_GTE : Get.SEARCH_ANY_GTE),
                    NOLOCK_UNCHANGED) == null) {
                    return false;
                }

                final long prevScanned = scannedRecords;

                while (true) {
                    if (shutdownRequested) {
                        return false;
                    }

                    final CursorImpl cursorImpl =
                        DbInternal.getCursorImpl(cursor);

                    cursorImpl.latchBIN();
                    try {
                        final BIN bin = cursorImpl.getBIN();
                        bin.mutateToFullBIN(false /*leaveFreeSlot*/);

                        if (!scanBIN(bin, cursorImpl.getIndex())) {
                            return false;
                        }

                        /* Cause NEXT op below to move to next BIN. */
                        cursorImpl.setIndex(bin.getNEntries() - 1);
                    } finally {
                        cursorImpl.releaseBIN();
                    }

                    if (cursor.get(
                        keyEntry, dataEntry, Get.NEXT,
                        NOLOCK_UNCHANGED) == null) {
                        return false;
                    }

                    if (unflushedBytes >= flushObsoleteBytes) {
                        /*
                         * This feature is disabled for now. It is not clear
                         * whether it is needed, and it is not well tested.
                         *
                        flushUtilization(
                            dbImpl.getId().getId(),
                            cursorImpl.getCurrentKey());
                         */
                        unflushedBytes = 0;
                    }

                    if (scannedRecords - prevScanned >= batchSize) {
                        return true;
                    }
                }
            } finally {
                locker.operationEnd();
            }
        }

        /**
         * Flush tracked LN utilization when it has exceeded the size limit.
         * Update the scan record in the DB to save the db/key of the last
         * counted LN.
         */
        void flushUtilization(final long currentDbId,
                              final byte[] currentKey) {

            /*
             * Flush FileSummaryLNs prior to flushing task records. If there
             * is a crash between the two, double counting (over cleaning) can
             * occur, but under counting (a disk leak) cannot occur.
             */
            envImpl.getUtilizationProfile().flushLocalTracker(tracker);
            tracker = new LocalUtilizationTracker(envImpl);

            /*
             * Use non-replicated locker so the update is not replicated.
             * The extinction records are updated independently on each node.
             */
            final Locker locker = Txn.createLocalAutoTxn(envImpl, NO_SYNC_TXN);
            boolean success = false;
            try {
                writeTask(
                    currentDbId, currentKey, locker, false /*insert*/);
                success = true;
            } finally {
                locker.operationEnd(success);
            }
        }

        /**
         * Scan a single BIN, latched by the caller. Marks all extinct slots
         * as known-deleted and preempts any locks held on them. If there are
         * any extinct slots, adds the BIN to the compressor queue.
         *
         * For each extinct slot the LN is counted obsolete in the local
         * tracker, but only if it was not previously counted obsolete.
         *
         * Returns true if there may be more records for this DB, or false if
         * DB is finished.
         */
        private boolean scanBIN(final BIN bin, final int startIndex) {

            final DatabaseImpl dbImpl = bin.getDatabase();
            final int nEntries = bin.getNEntries();

            boolean checkEndKeyThisBIN = (endKey != null);
            boolean countLNsThisBIN = countLNsThisDB;
            boolean checkFlushKeyThisBIN =
                countLNsThisBIN && checkFlushKeyThisDB;

            /*
             * Avoid checking every key in the BIN by checking the last key,
             * whenever possible. This is only worthwhile if there are more
             * than just a couple keys in the BIN.
             */
            if (nEntries - startIndex > 3 &&
                (checkEndKeyThisBIN || checkFlushKeyThisBIN)) {

                final byte[] lastBinKey = bin.getKey(nEntries - 1);

                if (checkEndKeyThisBIN) {
                    /*
                     * If the BIN's last key is LT the scan end key, then all
                     * keys in the BIN are also LT the scan end key.
                     */
                    if (!passedEndKey(lastBinKey, dbImpl)) {
                        checkEndKeyThisBIN = false;
                    }
                }

                if (checkFlushKeyThisBIN) {
                    /*
                     * If the BIN's last key is LTE the key of the last LN
                     * counted, then all LNs in this BIN have been counted.
                     */
                    if (Key.compareKeys(
                        lastBinKey, flushAtLastKey,
                        dbImpl.getKeyComparator()) <= 0) {

                        countLNsThisBIN = false;
                        checkFlushKeyThisBIN = false;
                    }
                }
            }

            boolean moreKeys = true;
            boolean addToCompressorQueue = false;

            for (int i = startIndex; i < nEntries && moreKeys; i += 1) {

                scannedRecords += 1;

                /*
                 * Don't get the BIN key unless we need it, since this
                 * normally involves creating a new byte array.
                 */
                byte[] slotKey = null;

                if (checkEndKeyThisBIN) {

                    slotKey = bin.getKey(i);

                    if (passedEndKey(slotKey, dbImpl)) {
                        moreKeys = false;
                        break;
                    }
                }

                if (filter != null) {

                    if (slotKey == null) {
                        slotKey = bin.getKey(i);
                    }

                    final byte[] priKey = dbImpl.getSortedDuplicates() ?
                        DupKeyData.getData(slotKey, 0, slotKey.length) :
                        slotKey;

                    switch (filter.checkKey(priKey)) {
                    case INCLUDE:
                        break;
                    case EXCLUDE:
                        continue;
                    case INCLUDE_STOP:
                        moreKeys = false;
                        break;
                    case EXCLUDE_STOP:
                        moreKeys = false;
                        continue;
                    }
                }

                extinctRecords += 1;

                /* Mark known-deleted if we're doing the 2nd scan. */
                if (countComplete) {
                    bin.setKnownDeleted(i);
                    addToCompressorQueue = true;
                }

                /*
                 * It is extinct, but do not count obsolete if already counted.
                 *
                 * An LN was previously counted obsolete if in a dup DB, and
                 * countLNsThisBIN will be false in this case. If it is
                 * embedded or defunct then it was also previously counted.
                 */
                if (!countLNsThisBIN ||
                    bin.isEmbeddedLN(i) ||
                    bin.isDefunct(i)) {
                    continue;
                }

                /* If it is transient or null, then it was never logged. */
                final long lsn = bin.getLsn(i);
                if (DbLsn.isTransientOrNull(lsn)) {
                    continue;
                }

                /*
                 * It may have been counted by a previous incomplete scan.
                 * We must check flushAtLastKey if it is in this DB and may be
                 * in this BIN. Note that countLNsThisBIN is false if the
                 * flush key comes after the keys in this BIN.
                 */
                if (checkFlushKeyThisBIN) {

                    if (slotKey == null) {
                        slotKey = bin.getKey(i);
                    }

                    if (Key.compareKeys(
                        slotKey, flushAtLastKey,
                        dbImpl.getKeyComparator()) < 0) {
                        continue;
                    }

                    /* BIN key is GT the flush key. */
                    checkFlushKeyThisBIN = false;
                    checkFlushKeyThisDB = false;
                }

                final int size = bin.getLastLoggedSize(i);

                tracker.countObsolete(
                    lsn, null, size,
                    false /*trackOffset*/, false /*checkDupOffset*/);

                unflushedBytes += size;
            }

            if (addToCompressorQueue) {
                /*
                 * To support erasure of extinct data, ensure that extinct
                 * slots are deleted before logging by prohibiting the next
                 * BIN-delta. Slots cannot be deleted when logging a delta.
                 */
                bin.setProhibitNextDelta(true);
                envImpl.addToCompressorQueue(bin);
            }

            return moreKeys;
        }

        /**
         * Returns whether the given BIN key is GTE the scan's end key.
         */
        private boolean passedEndKey(final byte[] binKey,
                                     final DatabaseImpl dbImpl) {

            return dbImpl.getSortedDuplicates() ?

                DupKeyData.compareMainKey(
                    binKey, endKey, 0, endKey.length,
                    dbImpl.getBtreeComparator()) >= 0 :

                Key.compareKeys(
                    binKey, endKey,
                    dbImpl.getBtreeComparator()) >= 0;
        }

        private boolean isKeyTargeted(final DatabaseImpl dbImpl,
                                      final byte[] key) {

            if (!dbIds.contains(dbImpl.getId().getId())) {
                return false;
            }

            if (beginKey != null &&
                compareMainKey(key, beginKey, dbImpl) < 0) {
                return false;
            }

            if (endKey != null &&
                compareMainKey(key, endKey, dbImpl) > 0) {
                return false;
            }

            if (filter != null && !filter.checkKey(key).getInclude()) {
                return false;
            }

            return true;
        }
    }

    private static int compareMainKey(final byte[] key1,
                                      final byte[] key2,
                                      final DatabaseImpl dbImpl) {

        return dbImpl.getSortedDuplicates() ?

            DupKeyData.compareMainKey(
                key1, key2,
                dbImpl.getBtreeComparator()) :

            Key.compareKeys(
                key1, key2,
                dbImpl.getBtreeComparator());
    }

    /* ---------------------------*
     * Database Extinction methods
     * ---------------------------*/

    /**
     * Must be called before writing a NameLN, when {@link #startDbExtinction}
     * will be called, i.e., for a DB remove/truncate operation. This method
     * ensures that the scan DB can be opened on the replica when replaying
     * the NameLN.
     *
     * <p>Returns without taking any action if this is a replica and the NameLN
     * for the scan DB has not yet been replayed (the DB does not exist), and
     * the truncate/remove operation itself is not replicated. See
     * startDbExtinction for handling of this special case.</p>
     *
     * @throws com.sleepycat.je.rep.ReplicaWriteException if this is a replica
     * node and the scan DB NameLN has not yet been replayed.
     */
    public void prepareForDbExtinction(final ReplicationContext repContext) {
        try {
            openDb();
        } catch (OperationFailureException e) {
            if (e.isReplicaWrite() && !repContext.inReplicationStream()) {
                /* startDbExtinction will execute extinction synchronously. */
                return;
            }
            throw e;
        }
    }

    /**
     * Used after transaction commit or non-transactional operation end in
     * these cases:
     * <ul>
     *    <li>purge the deleted database after a commit of
     *    Environment.removeDatabase</li>
     *
     *    <li>purge the deleted database after a commit of
     *    Environment.truncateDatabase</li>
     *
     *    <li>purge the newly created database after an abort of
     *    Environment.truncateDatabase</li>
     * </ul>
     *
     * <p>Note that the processing of the naming tree means the MapLN is never
     * actually accessible from the current tree, but making the DB extinct
     * is necessary to reclaim the memory and disk space it occupies.</p>
     * <p>This method initiates an async task to perform the following:</p>
     * <ul>
     *     <li>Release the INs for the deleted database,</li>
     *     <li>count all log entries for this database as obsolete,</li>
     *     <li>delete the MapLN,</li>
     *     <li>set the DatabaseImpl state to DELETE_FINISHED,</li>
     *     <li>and call DbTree.releaseDb.</li>
     * </ul>
     *
     * <p>In one special case the task is executed synchronously (see
     * below).</p>
     */
    public void startDbExtinction(final DatabaseImpl dbImpl)
        throws DatabaseException {

        assert dbImpl.isDeleting();
        assert !dbImpl.isDeleteFinished();
        assert threadPool != null;

        assert TestHookExecute.doHookIfSet(dbBeforeWriteTaskHook);

        /*
         * If scanDb is null then prepareForDbExtinction encountered a
         * ReplicaWriteException when trying to open the scan DB because it
         * does not yet exist on this replica, and the truncate/remove
         * operation is not replicated. In this corner case, we have no choice
         * but to execute the DB extinction task synchronously. This is
         * acceptable in that it should be rare and will not cause a delay
         * during replica replay (because the operation is not replicated).
         */
        if (scanDb == null) {
            /* Task ID zero indicates it is non-persistent. */
            final DatabaseExtinction task = new DatabaseExtinction(0, dbImpl);
            task.run();
        } else {
            /* Otherwise, add task to the scan DB. */
            final DatabaseExtinction task = new DatabaseExtinction(
                lastNonRepScanID.incrementAndGet(), dbImpl);

            task.writeTask();

            /*
             * If recovery is complete then execute the task, else save it
             * until recovery is complete.
             */
            if (recoveredTasks == null) {
                threadPool.execute(task);
            } else {
                recoveredTasks.add(task);
            }
        }
    }

    /**
     * Called during recovery when a NameLN delete/truncate is not followed by
     * the appropriate MapLN deletion. Add an async task for the DB extinction
     * if one does not already exist. Ensures that DbTree.releaseDb is called.
     */
    public void ensureDbExtinction(final DatabaseImpl dbImpl) {

        assert recoveredTasks != null;
        final DatabaseId dbId = dbImpl.getId();

        for (final ExtinctionTask task : recoveredTasks) {
            if (task.isExtinctionForDb(dbId)) {
                envImpl.getDbTree().releaseDb(dbImpl);
                return;
            }
        }

        dbImpl.setDeleteStarted();
        startDbExtinction(dbImpl);
    }

    /**
     * Entire databases become extinct when they are removed or truncated by
     * the app (via {@link Environment#removeDatabase} or {@link
     * Environment#truncateDatabase), or automatically in the case of
     * temporary DBs. We cannot perform utilization counting synchronously
     * (in the app operation thread) because it could cause timeouts, most
     * importantly during replica replay. Therefore, a record is added to
     * the scan DB and the database extinction is performed asynchronously,
     * like record extinction.
     *
     * <p>You may ask, why can't we rely on redo of the NameLN or MapLN to
     * trigger retry of utilization counting after a crash, to avoid inserting
     * a record in the scan DB? The reasons are:</p>
     * <ul>
     *     <li>The NameLN is logged/committed before counting. So it will
     *     not be replayed by recovery when a checkpoint occurs after the
     *     commit. So counting will not be retried if the following sequence
     *     occurs before counting is complete: NameLN commit, checkpoint,
     *     crash.</li>
     *
     *     <li>The MapLN cannot be deleted until after counting, since
     *     deleting it before counting would make its Btree inaccessible for
     *     retrying after a crash. This is because deletion flushes the
     *     MapLN and its possibly outdated rootLSN, without first flushing
     *     its descendant INs.</li>
     * </ul>
     *
     * <p>The statement about MapLN deletion above also implies that dirty
     * nodes in a DB that has not yet been counted must be flushed by the
     * checkpointer. Otherwise, their children would become inaccessible
     * after the checkpoint, if a crash occurs before counting is complete.
     * This scenario is tested by testRemoveWithIntermediateCheckpoint in
     * TruncateAndRemoveTest.</p>
     */
    private class DatabaseExtinction implements ExtinctionTask {

        private final long id;
        private final DatabaseId dbId;
        /* dbImpl is null when task is materialized from scan DB. */
        private DatabaseImpl dbImpl;

        DatabaseExtinction(final long id, final DatabaseImpl dbImpl) {
            this.id = id;
            dbId = dbImpl.getId();
            this.dbImpl = dbImpl;
        }

        DatabaseExtinction(final long id, final TupleInput in) {
            this.id = id;
            in.readPackedInt(); // Discard version for now
            dbId = new DatabaseId(in.readPackedLong());
        }

        void serializeData(final TupleOutput out) {
            out.writePackedInt(0); // Version
            out.writePackedLong(dbId.getId());
        }

        void writeTask() {
            assert scanDb != null;
            assert id >= 0;

            final DatabaseEntry keyEntry = new DatabaseEntry();
            final DatabaseEntry dataEntry = new DatabaseEntry();

            serializeKey(id, keyEntry);
            final TupleOutput out = serializeType(DATABASE_EXTINCTION);
            serializeData(out);
            TupleBase.outputToEntry(out, dataEntry);

            /*
             * Use non-replicated locker so the update is not replicated.
             * The NameLN, which is replicated, is the trigger for inserting
             * the extinction record independently on each node.
             */
            final Locker locker = Txn.createLocalAutoTxn(envImpl, NO_SYNC_TXN);
            boolean success = false;

            try (final Cursor cursor =
                     DbInternal.makeCursor(scanDb, locker, null)) {

                if (DbInternal.putWithRepContext(
                    cursor, keyEntry, dataEntry,
                    PutMode.NO_OVERWRITE,
                    ReplicationContext.NO_REPLICATE) == null) {

                    throw EnvironmentFailureException.unexpectedState();
                }

                success = true;
            } finally {
                locker.operationEnd(success);
            }
        }

        public boolean isExtinctionForDb(final DatabaseId dbId) {
            return dbId.equals(this.dbId);
        }

        @Override
        public void run() {
            assert TestHookExecute.doHookIfSet(dbBeforeExecTaskHook);

            final DbTree dbTree = envImpl.getDbTree();

            if (dbImpl == null) {
                /* Task was materialized from DB. */
                dbImpl = dbTree.getDb(dbId);
                if (dbImpl == null) {
                    /* Extinction was completed earlier. */
                    deleteTask(id);
                    return;
                }
                dbImpl.setDeleteStarted();
            } else {
                /*
                 * The truncate/remove operation has already called
                 * DbTree.getDb and DatabaseImpl.setDeleteStarted().
                 */
                assert dbImpl.isInUse();
                assert dbImpl.isDeleting();
                assert !dbImpl.isDeleteFinished();
            }

            try {
                final Tree tree = dbImpl.getTree();
                final long rootLsn = tree.getRootLsn();
                final IN rootIN = tree.getResidentRootIN(false);

                final LocalUtilizationTracker tracker =
                    new LocalUtilizationTracker(envImpl);

                if (rootLsn != DbLsn.NULL_LSN) {
                    tracker.countObsoleteNodeInexact(
                        rootLsn, LogEntryType.LOG_IN, 0);
                }

                /* Fetch LNs to count LN sizes only if so configured. */
                final boolean fetchLNSize =
                    envImpl.getCleaner().getFetchObsoleteSize(dbImpl);

                /* Use SLTW to visit every child LSN in the tree. */
                final ObsoleteProcessor obsoleteProcessor =
                    new ObsoleteProcessor(dbImpl, tracker, id);

                final SortedLSNTreeWalker walker = new ObsoleteTreeWalker(
                    dbImpl, rootLsn, fetchLNSize, obsoleteProcessor, rootIN);

                LoggerUtils.info(
                    logger, envImpl,
                    "Start DB remove/truncate scan, id=" + id +
                        " dbId=" + dbImpl.getId() +
                        " dbName=" + dbImpl.getName());

                /*
                 * At this point, it's possible for the evictor to find an IN
                 * for this database on the INList. It should be ignored.
                 */
                walker.walk();

                LoggerUtils.info(
                    logger, envImpl,
                    "End DB remove/truncate scan, id=" + id +
                        " scanned=" + obsoleteProcessor.scannedRecords +
                        " dbId=" + dbImpl.getId() +
                        " dbName=" + dbImpl.getName());

                /*
                 * Delete MapLN after flushing FileSummaryLNs, since the
                 * presence of the MapLN is used to determine whether counting
                 * is needed after a crash. More importantly, if the MapLN
                 * were deleted before utilization counting is complete (and
                 * flushed), the INs of the extinct DB would become unavailable
                 * (and perhaps even cleaned) making it impossible to count
                 * utilization of the IN's child nodes.
                 */
                envImpl.getUtilizationProfile().flushLocalTracker(tracker);

                assert TestHookExecute.doHookIfSet(dbBeforeDeleteMapLNHook);

                dbTree.deleteMapLN(dbImpl.getId());

                /*
                 * Flush the FileSummaryLNs and MapLN deletion to reduce the
                 * window for double-counting after a crash.
                 */
                envImpl.getLogManager().flushNoSync();

                assert TestHookExecute.doHookIfSet(dbBeforeDeleteTaskLNHook);

                /*
                 * The task record is no longer needed. While we could flush
                 * the log here, it isn't necessary -- if this task is
                 * processed again after a crash, it will find the MapLN
                 * deleted and will do nothing.
                 */
                deleteTask(id);

                /*
                 * Remove INs from cache only after deleting the MapLN, since
                 * it references the root IN.
                 */
                removeFromINList();

                /*
                 * Deletion is finished. The cleaner can now delete the files
                 * containing the DB's entries. And the evictor will expect
                 * that no INs for deleted DBs are in the INList.
                 */
                dbImpl.setDeleteFinished();

            } finally {
                /*
                 * Unconditionally call releaseDb to balance the call to getDb
                 * by the truncate or remove method.
                 */
                dbTree.releaseDb(dbImpl);
            }
        }

        private void removeFromINList() {

            long memoryChange = 0;

            try {
                final Iterator<IN> iter =
                    envImpl.getInMemoryINs().iterator();

                while (iter.hasNext()) {
                    final IN in = iter.next();

                    if (in.getDatabase() != dbImpl) {
                        continue;
                    }

                    iter.remove();
                    memoryChange -= in.getBudgetedMemorySize();
                }
            } finally {
                envImpl.getMemoryBudget().
                    updateTreeMemoryUsage(memoryChange);
            }
        }
    }

    private static class ObsoleteTreeWalker extends SortedLSNTreeWalker {

        private final IN rootIN;

        private ObsoleteTreeWalker(final DatabaseImpl dbImpl,
                                   final long rootLsn,
                                   final boolean fetchLNSize,
                                   final TreeNodeProcessor callback,
                                   final IN rootIN)
            throws DatabaseException {

            super(new DatabaseImpl[] { dbImpl },
                new long[] { rootLsn },
                callback,
                null,  /* savedException */
                null); /* exception predicate */

            accumulateLNs = fetchLNSize;
            this.rootIN = rootIN;

            /*
             * FUTURE: Set mem limit to remainder of JE cache size during
             * recovery. For now, use a smallish value (50 MB).
             */
            setInternalMemoryLimit(50L * 1024L * 1024L);
        }

        @Override
        public IN getResidentRootIN(@SuppressWarnings("unused")
                                    final DatabaseImpl ignore) {
            if (rootIN != null) {
                rootIN.latchShared();
            }
            return rootIN;
        }
    }

    /* Mark each LSN obsolete in the utilization tracker. */
    private class ObsoleteProcessor
        implements SortedLSNTreeWalker.TreeNodeProcessor {

        private final LocalUtilizationTracker tracker;
        private final boolean isLnImmediatelyObsolete;
        private final DatabaseImpl dbImpl;
        private final long id;
        private long scannedRecords;

        ObsoleteProcessor(final DatabaseImpl dbImpl,
                          final LocalUtilizationTracker tracker,
                          final long id) {
            this.dbImpl = dbImpl;
            this.tracker = tracker;
            this.id = id;
            this.isLnImmediatelyObsolete = dbImpl.isLNImmediatelyObsolete();
        }

        @Override
        public void processLSN(final long childLsn,
                               final LogEntryType childType,
                               final Node node,
                               final byte[] lnKey,
                               final int lastLoggedSize,
                               final boolean isEmbedded) {

            if (isEmbedded || DbLsn.isTransientOrNull(childLsn)) {
                return;
            }

            /*
             * Count the LN log size if an LN node and key are available, i.e.,
             * we are certain this is an LN. [#15365]
             */
            int size = 0;
            if (lnKey != null && childType.isLNType()) {
                if (isLnImmediatelyObsolete) {
                    return;
                }
                size = lastLoggedSize;
            }

            tracker.countObsoleteNodeInexact(childLsn, childType, size);

            noteScanned();
        }

        @Override
        public void processDirtyDeletedLN(final long childLsn,
                                          final LN ln,
                                          @SuppressWarnings("unused")
                                          final byte[] lnKey) {
            assert ln != null;

            /*
             * Do not count the size (pass zero) because the LN is dirty and
             * the logged LN is not available.
             */
            tracker.countObsoleteNodeInexact(
                childLsn, ln.getGenericLogType(), 0);

            noteScanned();
        }

        private void noteScanned() {
            scannedRecords += 1;
            if (scannedRecords % batchSize != 0) {
                return;
            }
            if (batchDelayMs <= 0) {
                return;
            }
            try {
                Thread.sleep(batchDelayMs);
            } catch (InterruptedException e) {
                LoggerUtils.warning(
                    logger, envImpl,
                    "DB remove/truncate scan interrupted, id=" + id +
                        " dbId=" + dbImpl.getId() +
                        " dbName=" + dbImpl.getName());
                throw new ThreadInterruptedException(envImpl, e);
            }
        }

        @Override
        public void noteMemoryExceeded() {
            /* Do nothing. */
        }
    }
}
