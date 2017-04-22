// Copyright 2015 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net;

import android.os.ConditionVariable;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Callback that tracks information from different callbacks and and has a
 * method to block thread until the stream completes on another thread.
 * Allows to cancel, block stream or throw an exception from an arbitrary step.
 */
public class TestBidirectionalStreamCallback extends BidirectionalStream.Callback {
    public UrlResponseInfo mResponseInfo;
    public CronetException mError;

    public ResponseStep mResponseStep = ResponseStep.NOTHING;

    public boolean mOnErrorCalled = false;
    public boolean mOnCanceledCalled = false;

    public int mHttpResponseDataLength = 0;
    public String mResponseAsString = "";

    public UrlResponseInfo.HeaderBlock mTrailers;

    private static final int READ_BUFFER_SIZE = 32 * 1024;

    // When false, the consumer is responsible for all calls into the stream
    // that advance it.
    private boolean mAutoAdvance = true;

    // Conditionally fail on certain steps.
    private FailureType mFailureType = FailureType.NONE;
    private ResponseStep mFailureStep = ResponseStep.NOTHING;

    // Signals when the stream is done either successfully or not.
    private final ConditionVariable mDone = new ConditionVariable();

    // Signaled on each step when mAutoAdvance is false.
    private final ConditionVariable mReadStepBlock = new ConditionVariable();
    private final ConditionVariable mWriteStepBlock = new ConditionVariable();

    // Executor Service for Cronet callbacks.
    private final ExecutorService mExecutorService =
            Executors.newSingleThreadExecutor(new ExecutorThreadFactory());
    private Thread mExecutorThread;

    // position() of ByteBuffer prior to read() call.
    private int mBufferPositionBeforeRead;

    // Data to write.
    private ArrayList<ByteBuffer> mWriteBuffers = new ArrayList<ByteBuffer>();

    private class ExecutorThreadFactory implements ThreadFactory {
        public Thread newThread(Runnable r) {
            mExecutorThread = new Thread(r);
            return mExecutorThread;
        }
    }

    public enum ResponseStep {
        NOTHING,
        ON_REQUEST_HEADERS_SENT,
        ON_RESPONSE_STARTED,
        ON_READ_COMPLETED,
        ON_WRITE_COMPLETED,
        ON_TRAILERS,
        ON_CANCELED,
        ON_FAILED,
        ON_SUCCEEDED
    }

    public enum FailureType {
        NONE,
        CANCEL_SYNC,
        CANCEL_ASYNC,
        // Same as above, but continues to advance the stream after posting
        // the cancellation task.
        CANCEL_ASYNC_WITHOUT_PAUSE,
        THROW_SYNC
    }

    public void setAutoAdvance(boolean autoAdvance) {
        mAutoAdvance = autoAdvance;
    }

    public void setFailure(FailureType failureType, ResponseStep failureStep) {
        mFailureStep = failureStep;
        mFailureType = failureType;
    }

    public void blockForDone() {
        mDone.block();
    }

    public void waitForNextReadStep() {
        mReadStepBlock.block();
        mReadStepBlock.close();
    }

    public void waitForNextWriteStep() {
        mWriteStepBlock.block();
        mWriteStepBlock.close();
    }

    public Executor getExecutor() {
        return mExecutorService;
    }

    public void shutdownExecutor() {
        mExecutorService.shutdown();
    }

    public void addWriteData(byte[] data) {
        ByteBuffer writeBuffer = ByteBuffer.allocateDirect(data.length);
        writeBuffer.put(data);
        writeBuffer.flip();
        mWriteBuffers.add(writeBuffer);
    }

    @Override
    public void onRequestHeadersSent(BidirectionalStream stream) {
        assertEquals(mExecutorThread, Thread.currentThread());
        assertFalse(stream.isDone());
        assertEquals(ResponseStep.NOTHING, mResponseStep);
        assertNull(mError);

        mResponseStep = ResponseStep.ON_REQUEST_HEADERS_SENT;
        if (maybeThrowCancelOrPause(stream, mWriteStepBlock)) {
            return;
        }
        startNextWrite(stream);
    }

    @Override
    public void onResponseHeadersReceived(BidirectionalStream stream, UrlResponseInfo info) {
        assertEquals(mExecutorThread, Thread.currentThread());
        assertFalse(stream.isDone());
        assertTrue(mResponseStep == ResponseStep.NOTHING
                || mResponseStep == ResponseStep.ON_REQUEST_HEADERS_SENT
                || mResponseStep == ResponseStep.ON_WRITE_COMPLETED);
        assertNull(mError);

        mResponseStep = ResponseStep.ON_RESPONSE_STARTED;
        mResponseInfo = info;
        if (maybeThrowCancelOrPause(stream, mReadStepBlock)) {
            return;
        }
        startNextRead(stream);
    }

    @Override
    public void onReadCompleted(
            BidirectionalStream stream, UrlResponseInfo info, ByteBuffer byteBuffer) {
        assertEquals(mExecutorThread, Thread.currentThread());
        assertFalse(stream.isDone());
        assertTrue(mResponseStep == ResponseStep.ON_RESPONSE_STARTED
                || mResponseStep == ResponseStep.ON_READ_COMPLETED
                || mResponseStep == ResponseStep.ON_WRITE_COMPLETED
                || mResponseStep == ResponseStep.ON_TRAILERS);
        assertNull(mError);

        mResponseStep = ResponseStep.ON_READ_COMPLETED;

        final int bytesRead = byteBuffer.position() - mBufferPositionBeforeRead;
        mHttpResponseDataLength += bytesRead;
        final byte[] lastDataReceivedAsBytes = new byte[bytesRead];
        // Rewind byteBuffer.position() to pre-read() position.
        byteBuffer.position(mBufferPositionBeforeRead);
        // This restores byteBuffer.position() to its value on entrance to
        // this function.
        byteBuffer.get(lastDataReceivedAsBytes);

        mResponseAsString += new String(lastDataReceivedAsBytes);

        if (maybeThrowCancelOrPause(stream, mReadStepBlock)) {
            return;
        }
        startNextRead(stream);
    }

    @Override
    public void onWriteCompleted(
            BidirectionalStream stream, UrlResponseInfo info, ByteBuffer buffer) {
        assertEquals(mExecutorThread, Thread.currentThread());
        assertFalse(stream.isDone());
        assertNull(mError);
        mResponseStep = ResponseStep.ON_WRITE_COMPLETED;
        if (!mWriteBuffers.isEmpty()) {
            assertEquals(buffer, mWriteBuffers.get(0));
            mWriteBuffers.remove(0);
        }
        if (maybeThrowCancelOrPause(stream, mWriteStepBlock)) {
            return;
        }
        startNextWrite(stream);
    }

    @Override
    public void onResponseTrailersReceived(BidirectionalStream stream, UrlResponseInfo info,
            UrlResponseInfo.HeaderBlock trailers) {
        assertEquals(mExecutorThread, Thread.currentThread());
        assertFalse(stream.isDone());
        assertNull(mError);
        mResponseStep = ResponseStep.ON_TRAILERS;
        mTrailers = trailers;
        if (maybeThrowCancelOrPause(stream, mReadStepBlock)) {
            return;
        }
    }

    @Override
    public void onSucceeded(BidirectionalStream stream, UrlResponseInfo info) {
        assertEquals(mExecutorThread, Thread.currentThread());
        assertTrue(stream.isDone());
        assertTrue(mResponseStep == ResponseStep.ON_RESPONSE_STARTED
                || mResponseStep == ResponseStep.ON_READ_COMPLETED
                || mResponseStep == ResponseStep.ON_WRITE_COMPLETED
                || mResponseStep == ResponseStep.ON_TRAILERS);
        assertFalse(mOnErrorCalled);
        assertFalse(mOnCanceledCalled);
        assertNull(mError);

        mResponseStep = ResponseStep.ON_SUCCEEDED;
        mResponseInfo = info;
        openDone();
        maybeThrowCancelOrPause(stream, mReadStepBlock);
    }

    @Override
    public void onFailed(BidirectionalStream stream, UrlResponseInfo info, CronetException error) {
        assertEquals(mExecutorThread, Thread.currentThread());
        assertTrue(stream.isDone());
        // Shouldn't happen after success.
        assertTrue(mResponseStep != ResponseStep.ON_SUCCEEDED);
        // Should happen at most once for a single stream.
        assertFalse(mOnErrorCalled);
        assertFalse(mOnCanceledCalled);
        assertNull(mError);
        mResponseStep = ResponseStep.ON_FAILED;

        mOnErrorCalled = true;
        mError = error;
        openDone();
        maybeThrowCancelOrPause(stream, mReadStepBlock);
    }

    @Override
    public void onCanceled(BidirectionalStream stream, UrlResponseInfo info) {
        assertEquals(mExecutorThread, Thread.currentThread());
        assertTrue(stream.isDone());
        // Should happen at most once for a single stream.
        assertFalse(mOnCanceledCalled);
        assertFalse(mOnErrorCalled);
        assertNull(mError);
        mResponseStep = ResponseStep.ON_CANCELED;

        mOnCanceledCalled = true;
        openDone();
        maybeThrowCancelOrPause(stream, mReadStepBlock);
    }

    public void startNextRead(BidirectionalStream stream) {
        startNextRead(stream, ByteBuffer.allocateDirect(READ_BUFFER_SIZE));
    }

    public void startNextRead(BidirectionalStream stream, ByteBuffer buffer) {
        mBufferPositionBeforeRead = buffer.position();
        stream.read(buffer);
    }

    public void startNextWrite(BidirectionalStream stream) {
        if (!mWriteBuffers.isEmpty()) {
            boolean isLastBuffer = mWriteBuffers.size() == 1;
            stream.write(mWriteBuffers.get(0), isLastBuffer);
        }
    }

    public boolean isDone() {
        // It's not mentioned by the Android docs, but block(0) seems to block
        // indefinitely, so have to block for one millisecond to get state
        // without blocking.
        return mDone.block(1);
    }

    protected void openDone() {
        mDone.open();
    }

    /**
     * Returns {@code false} if the callback should continue to advance the
     * stream.
     */
    private boolean maybeThrowCancelOrPause(
            final BidirectionalStream stream, ConditionVariable stepBlock) {
        if (mResponseStep != mFailureStep || mFailureType == FailureType.NONE) {
            if (!mAutoAdvance) {
                stepBlock.open();
                return true;
            }
            return false;
        }

        if (mFailureType == FailureType.THROW_SYNC) {
            throw new IllegalStateException("Callback Exception.");
        }
        Runnable task = new Runnable() {
            public void run() {
                stream.cancel();
            }
        };
        if (mFailureType == FailureType.CANCEL_ASYNC
                || mFailureType == FailureType.CANCEL_ASYNC_WITHOUT_PAUSE) {
            getExecutor().execute(task);
        } else {
            task.run();
        }
        return mFailureType != FailureType.CANCEL_ASYNC_WITHOUT_PAUSE;
    }
}