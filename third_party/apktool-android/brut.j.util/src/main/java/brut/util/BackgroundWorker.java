/*
 *  Modified for Material Files (2026); see third_party/apktool-android/README.md.
 *  Copyright (C) 2010 Ryszard Wiśniewski <brut.alll@gmail.com>
 *  Copyright (C) 2010 Connor Tumbleson <connor.tumbleson@gmail.com>
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package brut.util;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class BackgroundWorker {
    private final ExecutorService mExecutor;
    private final List<Future<?>> mWorkerFutures;
    private volatile boolean mSubmitAllowed;

    public BackgroundWorker(int threads) {
        mExecutor = Executors.newFixedThreadPool(threads);
        mWorkerFutures = new ArrayList<>();
        mSubmitAllowed = true;
    }

    public void waitForFinish() {
        checkState();
        mSubmitAllowed = false;
        try {
            for (Future<?> future : mWorkerFutures) {
                future.get();
            }
            mWorkerFutures.clear();
            mSubmitAllowed = true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            shutdownNow();
            throw new RuntimeException("Apktool operation cancelled", ex);
        } catch (ExecutionException | RuntimeException ex) {
            shutdownNow();
            throw new RuntimeException(ex);
        }
    }

    public void clearFutures() {
        mWorkerFutures.clear();
    }

    private void checkState() {
        if (!mSubmitAllowed) {
            throw new IllegalStateException("BackgroundWorker is not ready");
        }
    }

    public void shutdownNow() {
        mSubmitAllowed = false;
        for (Future<?> future : mWorkerFutures) {
            future.cancel(true);
        }
        mExecutor.shutdownNow();
        // A cancelled Future becomes done before its runnable has stopped.
        // Do not let the caller remove a workspace that is still in use.
        boolean interrupted = Thread.interrupted();
        while (!mExecutor.isTerminated()) {
            try {
                mExecutor.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                interrupted = true;
            }
        }
        mWorkerFutures.clear();
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public ExecutorService getExecutor() {
        return mExecutor;
    }

    public void submit(Runnable task) {
        checkState();
        OS.throwIfInterrupted();
        mWorkerFutures.add(mExecutor.submit(() -> {
            OS.throwIfInterrupted();
            task.run();
        }));
    }

    public <T> Future<T> submit(Callable<T> task) {
        checkState();
        OS.throwIfInterrupted();
        Future<T> future = mExecutor.submit(() -> {
            OS.throwIfInterrupted();
            return task.call();
        });
        mWorkerFutures.add(future);
        return future;
    }
}
