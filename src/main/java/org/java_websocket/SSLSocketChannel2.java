/**
 * Copyright (C) 2003 Alexander Kout
 * Originally from the jFxp project (http://jfxp.sourceforge.net/).
 * Copied with permission June 11, 2012 by Femi Omojola (fomojola@ideasynthesis.com).
 */
package org.java_websocket;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;

import com.crashlytics.android.Crashlytics;
import com.piazza.android.supp.LogInterface;

/**
 * Implements the relevant portions of the SocketChannel interface with the SSLEngine wrapper.
 */
public class SSLSocketChannel2 implements ByteChannel, WrappedByteChannel {
	/**
	 * This object is used to feed the {@link SSLEngine}'s wrap and unwrap methods during the handshake phase.
	 **/
	protected static ByteBuffer emptybuffer = ByteBuffer.allocate( 0 );

	protected ExecutorService exec;

	protected List<Future<?>> tasks;

	/** raw payload incomming */
	protected ByteBuffer inData;
	/** encrypted data outgoing */
	protected ByteBuffer outCrypt;
	/** encrypted data incoming */
	protected ByteBuffer inCrypt;

	/** the underlying channel */
	protected SocketChannel socketChannel;
	/** used to set interestOP SelectionKey.OP_WRITE for the underlying channel */
	protected SelectionKey selectionKey;

	protected SSLEngine sslEngine;
	protected SSLEngineResult readEngineResult;
	protected SSLEngineResult writeEngineResult;

	/**
	 * Should be used to count the buffer allocations.
	 * But because of #190 where HandshakeStatus.FINISHED is not properly returned by nio wrap/unwrap this variable is used to check whether {@link #createBuffers(SSLSession)} needs to be called.
	 **/
	protected int bufferallocations = 0;

	public SSLSocketChannel2( SocketChannel channel , SSLEngine sslEngine , ExecutorService exec , SelectionKey key ) throws IOException {
		if( channel == null || sslEngine == null || exec == null )
			throw new IllegalArgumentException( "parameter must not be null" );

		this.socketChannel = channel;
		this.sslEngine = sslEngine;
		this.exec = exec;

		readEngineResult = writeEngineResult = new SSLEngineResult( Status.BUFFER_UNDERFLOW, sslEngine.getHandshakeStatus(), 0, 0 ); // init to prevent NPEs

		tasks = new ArrayList<Future<?>>( 3 );
		if( key != null ) {
			key.interestOps( key.interestOps() | SelectionKey.OP_WRITE );
			this.selectionKey = key;
		}
		createBuffers( sslEngine.getSession() );
		// kick off handshake
		socketChannel.write( wrap( emptybuffer ) );// initializes res
		processHandshake();
	}

	private void consumeFutureUninterruptible( Future<?> f ) {
		try {
			boolean interrupted = false;
			while ( true ) {
				try {
					f.get();
					break;
				} catch ( InterruptedException e ) {
					interrupted = true;
				}
			}
			if( interrupted )
				Thread.currentThread().interrupt();
		} catch ( ExecutionException e ) {
			throw new RuntimeException( e );
		}
	}

	/**
	 * This method will do whatever necessary to process the sslengine handshake.
	 * Thats why it's called both from the {@link #read(ByteBuffer)} and {@link #write(ByteBuffer)}
	 **/
	private synchronized void processHandshake() throws IOException {
        LogInterface.d(TAG, "--> processHandshake()");
        LogInterface.i(TAG, "1 " + sslEngine.getHandshakeStatus() + " : " + readEngineResult.getHandshakeStatus() + " : " + writeEngineResult.getHandshakeStatus());
		if( sslEngine.getHandshakeStatus() == HandshakeStatus.NOT_HANDSHAKING ) {
	        LogInterface.d(TAG, "<-- processHandshake()");
			return; // since this may be called either from a reading or a writing thread and because this method is synchronized it is necessary to double check if we are still handshaking.
		}
		if( !tasks.isEmpty() ) {
			Iterator<Future<?>> it = tasks.iterator();
			while ( it.hasNext() ) {
				Future<?> f = it.next();
				if( f.isDone() ) {
					it.remove();
				} else {
					if( isBlocking() )
						consumeFutureUninterruptible( f );
			        LogInterface.d(TAG, "<-- processHandshake()");
					return;
				}
			}
	        LogInterface.i(TAG, "2 " + sslEngine.getHandshakeStatus() + " : " + readEngineResult.getHandshakeStatus() + " : " + writeEngineResult.getHandshakeStatus());
		}

		if( sslEngine.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_UNWRAP ) {
			if( !isBlocking() || readEngineResult.getStatus() == Status.BUFFER_UNDERFLOW ) {
				inCrypt.compact();
				int read = socketChannel.read( inCrypt );
				if( read == -1 ) {
			        LogInterface.d(TAG, "*** processHandshake()");
					throw new IOException( "connection closed unexpectedly by peer" );
				}
				inCrypt.flip();
			}
			inData.compact();
	        LogInterface.i(TAG, "3.1 " + sslEngine.getHandshakeStatus() + " : " + readEngineResult.getHandshakeStatus() + " : " + writeEngineResult.getHandshakeStatus());
			unwrap();
	        LogInterface.i(TAG, "3.2 " + sslEngine.getHandshakeStatus() + " : " + readEngineResult.getHandshakeStatus() + " : " + writeEngineResult.getHandshakeStatus());
	        if (sslEngine.getHandshakeStatus() == HandshakeStatus.NOT_HANDSHAKING) {
	            LogInterface.i(TAG, "Not handshaking!");
	        }
			if( readEngineResult.getHandshakeStatus() == HandshakeStatus.FINISHED ) {
				createBuffers( sslEngine.getSession() );
		        LogInterface.d(TAG, "<-- processHandshake()");
				return;
			}
		}
		consumeDelegatedTasks();
        LogInterface.i(TAG, "4 " + sslEngine.getHandshakeStatus() + " : " + readEngineResult.getHandshakeStatus() + " : " + writeEngineResult.getHandshakeStatus());
		if( tasks.isEmpty() || sslEngine.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_WRAP ) {
			socketChannel.write( wrap( emptybuffer ) );
	        LogInterface.i(TAG, "5 " + sslEngine.getHandshakeStatus() + " : " + readEngineResult.getHandshakeStatus() + " : " + writeEngineResult.getHandshakeStatus());
			if( writeEngineResult.getHandshakeStatus() == HandshakeStatus.FINISHED ) {
				createBuffers( sslEngine.getSession() );
		        LogInterface.d(TAG, "<-- processHandshake()");
				return;
			}
		}
        LogInterface.d(TAG, "<-- processHandshake()");
		assert ( sslEngine.getHandshakeStatus() != HandshakeStatus.NOT_HANDSHAKING );// this function could only leave NOT_HANDSHAKING after createBuffers was called unless #190 occurs which means that nio wrap/unwrap never return HandshakeStatus.FINISHED

		bufferallocations = 1; // look at variable declaration why this line exists and #190. Without this line buffers would not be be recreated when #190 AND a rehandshake occur.
	}
	
	private synchronized ByteBuffer wrap( ByteBuffer b ) throws SSLException {
	    //LogInterface.d(TAG, "--> wrap()");
		outCrypt.compact();
		writeEngineResult = sslEngine.wrap( b, outCrypt );
		outCrypt.flip();
        //LogInterface.d(TAG, "<-- wrap()");
		return outCrypt;
	}

	/**
	 * performs the unwrap operation by unwrapping from {@link #inCrypt} to {@link #inData}
	 **/
	private synchronized ByteBuffer unwrap() throws SSLException {
		int rem;
		do {
			rem = inData.remaining();
			
			boolean isDebug = false;
			LogInterface.d(TAG, "DEBUG?" + isDebug);
			if (isDebug) {
			    try {
	                Class<?> cSE = sslEngine.getClass();
                    Field fRP = cSE.getDeclaredField("recordProtocol");
                    fRP.setAccessible(true);
                    Object recordProtocol = fRP.get(sslEngine);
                    Class<?> cRP = recordProtocol.getClass();
                    Method mGDS = cRP.getDeclaredMethod("getDataSize", Integer.TYPE);
                    mGDS.setAccessible(true);
                    mGDS.invoke(recordProtocol, inCrypt.remaining());
                } catch (NoSuchFieldException e) {
                    e.printStackTrace();
                } catch (IllegalArgumentException e) {
                    e.printStackTrace();
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                } catch (NoSuchMethodException e) {
                    e.printStackTrace();
                } catch (InvocationTargetException e) {
                    e.printStackTrace();
                }
			}
			
			readEngineResult = sslEngine.unwrap( inCrypt, inData );
		} while ( readEngineResult.getStatus() == SSLEngineResult.Status.OK && ( rem != inData.remaining() || sslEngine.getHandshakeStatus() == HandshakeStatus.NEED_UNWRAP ) );
		inData.flip();
		return inData;
	}

	protected void consumeDelegatedTasks() {
		Runnable task;
		while ( ( task = sslEngine.getDelegatedTask() ) != null ) {
			tasks.add( exec.submit( task ) );
			// task.run();
		}
	}

	public static final String TAG = "SSLSocketChannel2";
	
	protected void createBuffers( SSLSession session ) {
        LogInterface.e(TAG, "--> createBuffers()");
		int appBufferMax = session.getApplicationBufferSize();
		int netBufferMax = session.getPacketBufferSize();
		LogInterface.d(TAG, "net " + netBufferMax + " : " + "app " + appBufferMax);
		appBufferMax = Math.max(appBufferMax, netBufferMax);
        LogInterface.d(TAG, "net " + netBufferMax + " : " + "app " + appBufferMax);
		
		if( inData == null ) {
			inData = ByteBuffer.allocate( appBufferMax );
			outCrypt = ByteBuffer.allocate( netBufferMax );
			inCrypt = ByteBuffer.allocate( netBufferMax );
		} else {
			if( inData.capacity() != appBufferMax )
				inData = ByteBuffer.allocate( appBufferMax );
			if( outCrypt.capacity() != netBufferMax )
				outCrypt = ByteBuffer.allocate( netBufferMax );
			if( inCrypt.capacity() != netBufferMax )
				inCrypt = ByteBuffer.allocate( netBufferMax );
		}
		inData.rewind();
		inData.flip();
		inCrypt.rewind();
		inCrypt.flip();
		outCrypt.rewind();
		outCrypt.flip();
		bufferallocations++;
        LogInterface.e(TAG, "<-- createBuffers()");
	}

	public int write( ByteBuffer src ) throws IOException {
        //LogInterface.d(TAG, "--> write()");
		if( !isHandShakeComplete() ) {
			processHandshake();
	        //LogInterface.d(TAG, "<-- write()");
			return 0;
		}
		// assert ( bufferallocations > 1 ); //see #190
//		if( bufferallocations <= 1 ) {
//			createBuffers( sslEngine.getSession() );
//		}
		int num = socketChannel.write( wrap( src ) );
        //LogInterface.d(TAG, "<-- write() : " + num);
		return num;

	}

	/**
	 * Blocks when in blocking mode until at least one byte has been decoded.<br>
	 * When not in blocking mode 0 may be returned.
	 * 
	 * @return the number of bytes read.
	 **/
	public int read( ByteBuffer dst ) throws IOException {
        LogInterface.d(TAG, "--> read()");
        if (Thread.currentThread().getStackTrace().length >= 40) {
            Crashlytics.logException(new Throwable("Averted stack overflow!"));
            LogInterface.e("SSLSocketChannel2", "Averted stack overflow!");
            LogInterface.d(TAG, "<-- read()");
            return -1;
        }
		if( !dst.hasRemaining() ) {
	        LogInterface.d(TAG, "<-- read()");
			return 0;
		}
		if( !isHandShakeComplete() ) {
			if( isBlocking() ) {
				while ( !isHandShakeComplete() ) {
					processHandshake();
				}
			} else {
				processHandshake();
				if( !isHandShakeComplete() ) {
			        LogInterface.d(TAG, "<-- read()");
					return 0;
				}
			}
		}
		// assert ( bufferallocations > 1 ); //see #190
//		if( bufferallocations <= 1 ) {
//			createBuffers( sslEngine.getSession() );
//		}
		/* 1. When "dst" is smaller than "inData" readRemaining will fill "dst" with data decoded in a previous read call.
		 * 2. When "inCrypt" contains more data than "inData" has remaining space, unwrap has to be called on more time(readRemaining)
		 */
		int purged = readRemaining( dst );
		if( purged != 0 ) {
	        LogInterface.d(TAG, "<-- read()");
			return purged;
		}
		
		/* We only continue when we really need more data from the network.
		 * Thats the case if inData is empty or inCrypt holds to less data than necessary for decryption
		 */
		assert ( inData.position() == 0 );
		inData.clear();

		if( !inCrypt.hasRemaining() )
			inCrypt.clear();
		else
			inCrypt.compact();

		if( isBlocking() || readEngineResult.getStatus() == Status.BUFFER_UNDERFLOW )
			if( socketChannel.read( inCrypt ) == -1 ) {
		        LogInterface.d(TAG, "<-- read()");
				return -1;
			}
		inCrypt.flip();
		unwrap();

		int transfered = transfereTo( inData, dst );
		if( transfered == 0 && isBlocking() ) {
			int red = read( dst ); // "transfered" may be 0 when not enough bytes were received or during rehandshaking
	        LogInterface.d(TAG, "<-- read()");
	        return red;
		}
        LogInterface.d(TAG, "<-- read()");
		return transfered;
	}
	/**
	 * {@link #read(ByteBuffer)} may not be to leave all buffers(inData, inCrypt)
	 **/
	private int readRemaining( ByteBuffer dst ) throws SSLException {
		if( inData.hasRemaining() ) {
			return transfereTo( inData, dst );
		}
		if( !inData.hasRemaining() )
			inData.clear();
		// test if some bytes left from last read (e.g. BUFFER_UNDERFLOW)
		if( inCrypt.hasRemaining() ) {
			unwrap();
			int amount = transfereTo( inData, dst );
			if( amount > 0 )
				return amount;
		}
		return 0;
	}

	public boolean isConnected() {
		return socketChannel.isConnected();
	}

	public void close() throws IOException {
		sslEngine.closeOutbound();
		sslEngine.getSession().invalidate();
		if( socketChannel.isOpen() )
			socketChannel.write( wrap( emptybuffer ) );// FIXME what if not all bytes can be written
		socketChannel.close();
		exec.shutdownNow();
	}

	private boolean isHandShakeComplete() {
		HandshakeStatus status = sslEngine.getHandshakeStatus();
		return status == SSLEngineResult.HandshakeStatus.FINISHED || status == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING;
	}

	public SelectableChannel configureBlocking( boolean b ) throws IOException {
		return socketChannel.configureBlocking( b );
	}

	public boolean connect( SocketAddress remote ) throws IOException {
		return socketChannel.connect( remote );
	}

	public boolean finishConnect() throws IOException {
		return socketChannel.finishConnect();
	}

	public Socket socket() {
		return socketChannel.socket();
	}

	public boolean isInboundDone() {
		return sslEngine.isInboundDone();
	}

	@Override
	public boolean isOpen() {
		return socketChannel.isOpen();
	}

	@Override
	public boolean isNeedWrite() {
		return outCrypt.hasRemaining() || !isHandShakeComplete(); // FIXME this condition can cause high cpu load during handshaking when network is slow
	}

	@Override
	public void writeMore() throws IOException {
		write( outCrypt );
	}

	@Override
	public boolean isNeedRead() {
		return inData.hasRemaining() || ( inCrypt.hasRemaining() && readEngineResult.getStatus() != Status.BUFFER_UNDERFLOW && readEngineResult.getStatus() != Status.CLOSED );
	}

	@Override
	public int readMore( ByteBuffer dst ) throws SSLException {
		return readRemaining( dst );
	}

	private int transfereTo( ByteBuffer from, ByteBuffer to ) {
		int fremain = from.remaining();
		int toremain = to.remaining();
		if( fremain > toremain ) {
			// FIXME there should be a more efficient transfer method
			int limit = Math.min( fremain, toremain );
			for( int i = 0 ; i < limit ; i++ ) {
				to.put( from.get() );
			}
			return limit;
		} else {
			to.put( from );
			return fremain;
		}

	}

	@Override
	public boolean isBlocking() {
		return socketChannel.isBlocking();
	}

}