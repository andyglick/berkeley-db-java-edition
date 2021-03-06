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

package com.sleepycat.je.jca.ra;

import java.io.PrintWriter;
import java.io.Serializable;
import java.util.Iterator;
import java.util.Set;

import javax.resource.ResourceException;
import javax.resource.spi.ConnectionManager;
import javax.resource.spi.ConnectionRequestInfo;
import javax.resource.spi.ManagedConnection;
import javax.resource.spi.ManagedConnectionFactory;
import javax.security.auth.Subject;

import com.sleepycat.je.DbInternal;
import com.sleepycat.je.EnvironmentFailureException;
import com.sleepycat.je.dbi.EnvironmentImpl;

public class JEManagedConnectionFactory
    implements ManagedConnectionFactory, Serializable {

    private static final long serialVersionUID = 658705244L;

    public JEManagedConnectionFactory() {
    }

    public Object createConnectionFactory(ConnectionManager cxManager) {
        return new JEConnectionFactoryImpl(cxManager, this);
    }

    public Object createConnectionFactory() {
        throw EnvironmentFailureException.unexpectedState
            ("must supply a connMgr");
    }

    public ManagedConnection
        createManagedConnection(Subject subject,
                                ConnectionRequestInfo info)
        throws ResourceException {

        JERequestInfo jeInfo = (JERequestInfo) info;
        return new JEManagedConnection(subject, jeInfo);
    }

    public ManagedConnection
        matchManagedConnections(Set connectionSet,
                                Subject subject,
                                ConnectionRequestInfo info) {
        JERequestInfo jeInfo = (JERequestInfo) info;
        Iterator iter = connectionSet.iterator();
        while (iter.hasNext()) {
            Object next = iter.next();
            if (next instanceof JEManagedConnection) {
                JEManagedConnection mc = (JEManagedConnection) next;
                EnvironmentImpl nextEnvImpl =
                    DbInternal.getNonNullEnvImpl(mc.getEnvironment());
                /* Do we need to match on more than root dir and r/o? */
                if (nextEnvImpl.getEnvironmentHome().
                    equals(jeInfo.getJERootDir()) &&
                    nextEnvImpl.isReadOnly() ==
                    jeInfo.getEnvConfig().getReadOnly()) {
                    return mc;
                }
            }
        }
        return null;
    }

    public void setLogWriter(PrintWriter out) {
    }

    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }

        if (obj instanceof JEManagedConnectionFactory) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return 0;
    }
}
