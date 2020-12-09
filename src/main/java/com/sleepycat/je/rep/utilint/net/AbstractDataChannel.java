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

package com.sleepycat.je.rep.utilint.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.channels.SocketChannel;

import com.sleepycat.je.rep.net.DataChannel;

/**
 * An abstract class that utilizes a delegate socketChannel for network
 * I/O, but which provides an abstract ByteChannel interface for callers.
 * This allows more interesting communication mechanisms to be introduced.
 */
abstract public class AbstractDataChannel implements DataChannel {

    /**
     * The cached value of whether the channel is blocking.
     *
     * The method {@link SocketChannel#isBlocking} acquires the {@link
     * SocketChannel#blockingLock}. Some socket read operations also acquires
     * such lock, e.g., sun.nio.ch.ChannelInputStream#read. Thus a non-blocking
     * implementation calling isBlocking() might be blocked by concurrent
     * operations. Use a cached value to avoid such blocking behavior.
     *
     * Accessors to this field must synchronize on the object.
     */
    protected boolean configuredBlocking;

    /**
     * The underlying socket channel
     */
    protected final SocketChannel socketChannel;

    /**
     * Constructor for sub-classes.
     * @param socketChannel The underlying SocketChannel over which data will
     *        be sent.  This should be the lowest-level socket so that select
     *        operations can be performed on it.
     */
    protected AbstractDataChannel(SocketChannel socketChannel) {
        this.socketChannel = socketChannel;
        this.configuredBlocking = socketChannel.isBlocking();
    }

    /**
     * Checks whether the channel is connected.
     */
    @Override
    public boolean isConnected() {
        return socketChannel.isConnected();
    }

    /**
     * Retrieves a socket associated with this channel.
     */
    @Override
    public Socket socket() {
        return socketChannel.socket();
    }

    /**
     * Returns the remote address to which this channel's socket is connected.
     */
    @Override
    public SocketAddress getRemoteAddress() throws IOException {
        /* Not using SocketChannel#getRemoteAddress for jdk6 compatiblity */
        return socket().getRemoteSocketAddress();
    }

    /**
     * Configures the channel to be blocking.
     */
    @Override
    public synchronized void configureBlocking(boolean block)
        throws IOException {

        socketChannel.configureBlocking(block);
        configuredBlocking = block;
    }

    /**
     * Tells whether or not every I/O operation on this channel will block
     * until it completes.
     */
    @Override
    public synchronized boolean isBlocking() {
        return configuredBlocking;
    }

    /**
     * Accessor for the underlying SocketChannel.  Use of this accessor is
     * discouraged -- see DataChannel.
     */
    @Override
    public SocketChannel getSocketChannel() {
        return socketChannel;
    }

    /**
     * Ensures this channel is in blocking mode when calling close.
     */
    protected void ensureCloseForBlocking() {
        if (!isBlocking()) {
            throw new IllegalStateException(
                    "Calling close on non-blocking channel");
        }
    }

    /**
     * Ensures this channel is in non-blocking mode when calling closeAsync.
     */
    protected void ensureCloseAsyncForNonBlocking() {
        if (isBlocking()) {
            throw new IllegalStateException(
                    "Calling closeAsync on blocking channel");
        }
    }

}

