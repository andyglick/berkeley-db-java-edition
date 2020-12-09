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

package com.sleepycat.je;

import static com.sleepycat.je.dbi.BTreeStatDefinition.BT_COUNT_BINS_BYLEVEL;
import static com.sleepycat.je.dbi.BTreeStatDefinition.BT_COUNT_BINS;
import static com.sleepycat.je.dbi.BTreeStatDefinition.BT_COUNT_BIN_ENTRIES_HISTOGRAM;
import static com.sleepycat.je.dbi.BTreeStatDefinition.BT_COUNT_DELETED_LNS;
import static com.sleepycat.je.dbi.BTreeStatDefinition.BT_COUNT_INS_BYLEVEL;
import static com.sleepycat.je.dbi.BTreeStatDefinition.BT_COUNT_INS;
import static com.sleepycat.je.dbi.BTreeStatDefinition.BT_COUNT_LNS;
import static com.sleepycat.je.dbi.BTreeStatDefinition.BT_COUNT_MAINTREE_MAXDEPTH;
import static com.sleepycat.je.dbi.BTreeStatDefinition.BT_COUNT_GROUP_DESC;
import static com.sleepycat.je.dbi.BTreeStatDefinition.BT_COUNT_GROUP_NAME;

import com.sleepycat.je.utilint.StatGroup;

/**
 * The BtreeStats object is used to return Btree database statistics.
 */
public class BtreeStats extends DatabaseStats {

    private static final long serialVersionUID = 298825033L;

    private final StatGroup stats;

    /**
     * @hidden
     * Internal use only.
     */
    public BtreeStats() {
        stats = new StatGroup(BT_COUNT_GROUP_NAME, BT_COUNT_GROUP_DESC);
    }

    /**
     * @hidden
     * Internal use only.
     */
    public BtreeStats(final StatGroup stats) {
        this.stats = stats;
    }

    /**
     * Returns the number of Bottom Internal Nodes in the database btree.
     */
    public long getBottomInternalNodeCount() {
        return stats.getLong(BT_COUNT_BINS);
    }

    /**
     * @deprecated as of 5.0, returns zero.
     */
    public long getDuplicateBottomInternalNodeCount() {
        return 0;
    }

    /**
     * Returns the number of deleted data records in the database btree that
     * are pending removal by the compressor.
     */
    public long getDeletedLeafNodeCount() {
        return stats.getLong(BT_COUNT_DELETED_LNS);
    }

    /**
     * @deprecated as of 5.0, returns zero.
     */
    public long getDupCountLeafNodeCount() {
        return 0;
    }

    /**
     * Returns the number of Internal Nodes in the database btree.
     */
    public long getInternalNodeCount() {
        return stats.getLong(BT_COUNT_INS);
    }

    /**
     * @deprecated as of 5.0, returns zero.
     */
    public long getDuplicateInternalNodeCount() {
        return 0;
    }

    /**
     * Returns the number of leaf nodes in the database tree, which can equal
     * the number of records. This is calculated without locks or transactions,
     * and therefore is only an accurate count of the current number of records
     * when the database is quiescent.
     */
    public long getLeafNodeCount() {
        return stats.getLong(BT_COUNT_LNS);
    }

    /**
     * Returns the number of levels in the database btree.
     */
    public int getMainTreeMaxDepth() {
        return stats.getInt(BT_COUNT_MAINTREE_MAXDEPTH);
    }

    /**
     * @deprecated as of 5.0, returns zero.
     */
    public int getDuplicateTreeMaxDepth() {
        return 0;
    }

    /**
     * Returns the count of Internal Nodes per level, indexed by level.
     *
     * @return count of Internal Nodes per level, indexed by level.
     */
    public long[] getINsByLevel() {
        return stats.getLongArray(BT_COUNT_INS_BYLEVEL);
    }

    /**
     * Returns the count of Bottom Internal Nodes per level, indexed by level.
     *
     * @return count of Bottom Internal Nodes per level, indexed by level.
     */
    public long[] getBINsByLevel() {
        return stats.getLongArray(BT_COUNT_BINS_BYLEVEL);
    }

    /**
     * Returns an array representing a histogram of the number of Bottom
     * Internal Nodes with various percentages of non-deleted entry counts.
     * The array is 10 elements and each element represents a range of 10%.
     *
     * <pre>
     * element [0]: # BINs with 0% to 9% entries used by non-deleted values
     * element [1]: # BINs with 10% to 19% entries used by non-deleted values
     * element [2]: # BINs with 20% to 29% entries used by non-deleted values
     * ...
     * element [0]: # BINs with 90% to 100% entries used by non-deleted values
     * </pre>
     */
    public long[] getBINEntriesHistogram() {
        return stats.getLongArray(BT_COUNT_BIN_ENTRIES_HISTOGRAM);
    }

    /**
     * @deprecated as of 5.0, returns an empty array.
     */
    public long[] getDINsByLevel() {
        return new long[0];
    }

    /**
     * @deprecated as of 5.0, returns an empty array.
     */
    public long[] getDBINsByLevel() {
        return new long[0];
    }

    /**
     * @deprecated as of JE 18.1 and zero is always returned; use
     * {@link EnvironmentStats#getRelatchesRequired()} instead.
     */
    public long getRelatches() {
        return 0;
    }

    /**
     * @deprecated as of JE 18.1 and zero is always returned; use
     * {@link EnvironmentStats#getRootSplits()} instead.
     */
    public int getRootSplits() {
        return 0;
    }

    /**
     * Returns a String representation of the stats in the form of
     * &lt;stat&gt;=&lt;value&gt;
     */
    @Override
    public String toString() {
        return stats.toString();
    }

    /**
     * Returns a String representation of the stats which includes stats
     * descriptions in addition to &lt;stat&gt;=&lt;value&gt;
     */
    public String toStringVerbose() {
        return stats.toStringVerbose();
    }
}
