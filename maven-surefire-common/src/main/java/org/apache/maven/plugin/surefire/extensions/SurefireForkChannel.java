package org.apache.maven.plugin.surefire.extensions;

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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.plugin.surefire.booterclient.output.NativeStdOutStreamConsumer;
import org.apache.maven.surefire.api.event.Event;
import org.apache.maven.surefire.api.fork.ForkNodeArguments;
import org.apache.maven.surefire.extensions.CloseableDaemonThread;
import org.apache.maven.surefire.extensions.CommandReader;
import org.apache.maven.surefire.extensions.Completable;
import org.apache.maven.surefire.extensions.EventHandler;
import org.apache.maven.surefire.extensions.ForkChannel;
import org.apache.maven.surefire.extensions.util.CountDownLauncher;
import org.apache.maven.surefire.extensions.util.CountdownCloseable;
import org.apache.maven.surefire.extensions.util.LineConsumerThread;

import javax.annotation.Nonnull;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketOption;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static java.net.StandardSocketOptions.SO_KEEPALIVE;
import static java.net.StandardSocketOptions.SO_REUSEADDR;
import static java.net.StandardSocketOptions.TCP_NODELAY;
import static java.nio.channels.AsynchronousChannelGroup.withThreadPool;
import static java.nio.channels.AsynchronousServerSocketChannel.open;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.apache.maven.surefire.api.util.internal.Channels.newBufferedChannel;
import static org.apache.maven.surefire.api.util.internal.Channels.newChannel;
import static org.apache.maven.surefire.api.util.internal.Channels.newInputStream;
import static org.apache.maven.surefire.api.util.internal.Channels.newOutputStream;
import static org.apache.maven.surefire.api.util.internal.DaemonThreadFactory.newDaemonThreadFactory;

/**
 * The TCP/IP server accepting only one client connection. The forked JVM connects to the server using the
 * {@link #getForkNodeConnectionString() connection string}.
 * The main purpose of this class is to {@link #connectToClient() conect with tthe client}, bind the
 * {@link #bindCommandReader(CommandReader, WritableByteChannel) command reader} to the internal socket's
 * {@link java.io.InputStream}, and bind the
 * {@link #bindEventHandler(EventHandler, CountdownCloseable, ReadableByteChannel) event handler} writing the event
 * objects to the {@link EventHandler event handler}.
 * <br>
 * The objects {@link WritableByteChannel} and {@link ReadableByteChannel} are forked process streams
 * (standard input and output). Both are ignored in this implementation but they are used in {@link LegacyForkChannel}.
 * <br>
 * The channel is closed after the forked JVM has finished normally or the shutdown hook is executed in the plugin.
 */
final class SurefireForkChannel extends ForkChannel
{
    private static final ExecutorService THREAD_POOL = Executors.newCachedThreadPool( newDaemonThreadFactory() );

    private final AsynchronousServerSocketChannel server;
    private final String localHost;
    private final int localPort;
    private final String sessionId;
    private final AtomicReference<AsynchronousSocketChannel> worker = new AtomicReference<>();
    private final Bindings bindings = new Bindings( 3 );
    private volatile LineConsumerThread out;
    private volatile CloseableDaemonThread commandReaderBindings;
    private volatile CloseableDaemonThread eventHandlerBindings;
    private volatile EventBindings eventBindings;
    private volatile CommandBindings commandBindings;

    SurefireForkChannel( @Nonnull ForkNodeArguments arguments ) throws IOException
    {
        super( arguments );
        server = open( withThreadPool( THREAD_POOL ) );
        setTrueOptions( SO_REUSEADDR, TCP_NODELAY, SO_KEEPALIVE );
        InetAddress ip = InetAddress.getLoopbackAddress();
        server.bind( new InetSocketAddress( ip, 0 ), 1 );
        InetSocketAddress localAddress = (InetSocketAddress) server.getLocalAddress();
        localHost = localAddress.getHostString();
        localPort = localAddress.getPort();
        sessionId = arguments.getSessionId();
    }

    @Override
    public Completable connectToClient()
    {
        if ( worker.get() != null )
        {
            throw new IllegalStateException( "already accepted TCP client connection" );
        }
        AcceptanceHandler result = new AcceptanceHandler();
        server.accept( null, result );
        return result;
    }

    @Override
    public String getForkNodeConnectionString()
    {
        return "tcp://" + localHost + ":" + localPort + "?sessionId=" + sessionId;
    }

    @Override
    public int getCountdownCloseablePermits()
    {
        return 3;
    }

    @Override
    public void bindCommandReader( @Nonnull CommandReader commands, WritableByteChannel stdIn )
    {
        commandBindings = new CommandBindings( commands );

        bindings.countDown();
    }

    @Override
    public void bindEventHandler( @Nonnull EventHandler<Event> eventHandler,
                                  @Nonnull CountdownCloseable countdown,
                                  ReadableByteChannel stdOut )
    {
        ForkNodeArguments args = getArguments();
        out = new LineConsumerThread( "fork-" + args.getForkChannelId() + "-out-thread", stdOut,
            new NativeStdOutStreamConsumer( args.getConsoleLogger() ), countdown );
        out.start();

        eventBindings = new EventBindings( eventHandler, countdown );

        bindings.countDown();
    }

    @Override
    public void disable()
    {
        if ( eventHandlerBindings != null )
        {
            eventHandlerBindings.disable();
        }

        if ( commandReaderBindings != null )
        {
            commandReaderBindings.disable();
        }
    }

    @Override
    public void close() throws IOException
    {
        //noinspection unused,EmptyTryBlock,EmptyTryBlock
        try ( Closeable c1 = worker.get(); Closeable c2 = server; Closeable c3 = out )
        {
            // only close all channels
        }
    }

    @SafeVarargs
    private final void setTrueOptions( SocketOption<Boolean>... options )
        throws IOException
    {
        for ( SocketOption<Boolean> option : options )
        {
            if ( server.supportedOptions().contains( option ) )
            {
                server.setOption( option, true );
            }
        }
    }

    private final class AcceptanceHandler
        implements CompletionHandler<AsynchronousSocketChannel, Void>, Completable
    {
        private final CountDownLatch acceptanceSynchronizer = new CountDownLatch( 1 );
        private final CountDownLatch authSynchronizer = new CountDownLatch( 1 );
        private volatile String messageOfIOException;
        private volatile String messageOfInvalidSessionIdException;

        @Override
        public void completed( AsynchronousSocketChannel channel, Void attachment )
        {
            if ( worker.compareAndSet( null, channel ) )
            {
                acceptanceSynchronizer.countDown();
                final ByteBuffer buffer = ByteBuffer.allocate( sessionId.length() );
                channel.read( buffer, null, new CompletionHandler<Integer, Object>()
                {
                    @Override
                    public void completed( Integer read, Object attachment )
                    {
                        if ( read == -1 )
                        {
                            messageOfIOException = "Channel closed while verifying the client.";
                        }
                        ( (Buffer) buffer ).flip();
                        String clientSessionId = new String( buffer.array(), US_ASCII );
                        if ( !clientSessionId.equals( sessionId ) )
                        {
                            messageOfInvalidSessionIdException = "The actual sessionId '" + clientSessionId
                                + "' does not match '" + sessionId + "'.";
                        }
                        authSynchronizer.countDown();

                        bindings.countDown();
                    }

                    @Override
                    public void failed( Throwable exception, Object attachment )
                    {
                        getArguments().dumpStreamException( exception );
                    }
                } );
            }
            else
            {
                getArguments().dumpStreamText( "Another TCP client attempts to connect." );
            }
        }

        @Override
        public void failed( Throwable exception, Void attachment )
        {
            getArguments().dumpStreamException( exception );
            acceptanceSynchronizer.countDown();
        }

        @Override
        public void complete() throws IOException, InterruptedException
        {
            completeAcceptance();
            authSynchronizer.await();
        }

        void completeAcceptance() throws InterruptedException
        {
            acceptanceSynchronizer.await();
        }
    }

    private class EventBindings
    {
        private final EventHandler<Event> eventHandler;
        private final CountdownCloseable countdown;

        private EventBindings( EventHandler<Event> eventHandler, CountdownCloseable countdown )
        {
            this.eventHandler = eventHandler;
            this.countdown = countdown;
        }

        void bindEventHandler( AsynchronousSocketChannel source )
        {
            ForkNodeArguments args = getArguments();
            String threadName = "fork-" + args.getForkChannelId() + "-event-thread";
            ReadableByteChannel channel = newBufferedChannel( newInputStream( source ) );
            eventHandlerBindings = new EventConsumerThread( threadName, channel, eventHandler, countdown, args );
            eventHandlerBindings.start();
        }
    }

    private class CommandBindings
    {
        private final CommandReader commands;

        private CommandBindings( CommandReader commands )
        {
            this.commands = commands;
        }

        void bindCommandSender( AsynchronousSocketChannel source )
        {
            // dont use newBufferedChannel here - may cause the command is not sent and the JVM hangs
            // only newChannel flushes the message
            // newBufferedChannel does not flush
            ForkNodeArguments args = getArguments();
            WritableByteChannel channel = newChannel( newOutputStream( source ) );
            String threadName = "commands-fork-" + args.getForkChannelId();
            commandReaderBindings = new StreamFeeder( threadName, channel, commands, args.getConsoleLogger() );
            commandReaderBindings.start();
        }
    }

    private class Bindings extends CountDownLauncher
    {
        private Bindings( int count )
        {
            super( count );
        }

        @Override
        protected void job()
        {
            AsynchronousSocketChannel channel = worker.get();
            eventBindings.bindEventHandler( channel );
            commandBindings.bindCommandSender( channel );
        }
    }
}
