/*
 * Copyright (C) 2020 yvolk (Yuri Volkov), http://yurivolkov.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.andstatus.app.context;

import android.app.Activity;
import android.content.Intent;

import androidx.annotation.NonNull;

import org.andstatus.app.FirstActivity;
import org.andstatus.app.HelpActivity;
import org.andstatus.app.data.DbUtils;
import org.andstatus.app.net.http.TlsSniSocketFactory;
import org.andstatus.app.os.AsyncTaskLauncher;
import org.andstatus.app.os.ExceptionsCounter;
import org.andstatus.app.os.NonUiThreadExecutor;
import org.andstatus.app.service.MyServiceManager;
import org.andstatus.app.syncadapter.SyncInitiator;
import org.andstatus.app.util.IdentifiableInstance;
import org.andstatus.app.util.InstanceId;
import org.andstatus.app.util.MyLog;
import org.andstatus.app.util.SharedPreferencesUtil;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import io.vavr.control.Try;

import static org.andstatus.app.context.MyContextHolder.myContextHolder;

/**
 * @author yvolk@yurivolkov.com
 */
public class MyFutureContext implements IdentifiableInstance {
    private static final String TAG = MyFutureContext.class.getSimpleName();

    protected final long createdAt = MyLog.uniqueCurrentTimeMS();
    protected final long instanceId = InstanceId.next();

    @NonNull
    private final MyContext previousContext;
    public final CompletableFuture<MyContext> future;

    public MyFutureContext releaseNow(Supplier<String> reason) {
        MyContext previousContext = getNow();
        release(previousContext, reason);
        MyFutureContext future = completedFuture(previousContext);
        return future;
    }

    public static MyFutureContext fromPrevious(MyFutureContext previousFuture, Object calledBy) {
        if (previousFuture.needToInitialize()) {
            MyContext previousContext = previousFuture.getNow();
            CompletableFuture<MyContext> future =
                completedFuture(previousContext).future
                .thenApplyAsync(initializeMyContext(calledBy), NonUiThreadExecutor.INSTANCE);
            return new MyFutureContext(previousContext, future);
        } else {
            return previousFuture;
        }
    }

    private static UnaryOperator<MyContext> initializeMyContext(Object calledBy) {
        return previousContext -> {
            release(previousContext, () -> "Starting initialization by " + calledBy);
            MyContext myContext = previousContext.newInitialized(previousContext);
            SyncInitiator.register(myContext);
            return myContext;
        };
    }

    private static void release(MyContext previousContext, Supplier<String> reason) {
        SyncInitiator.unregister(previousContext);
        MyServiceManager.setServiceUnavailable();
        TlsSniSocketFactory.forget();
        previousContext.save(reason);
        AsyncTaskLauncher.forget();
        ExceptionsCounter.forget();
        MyLog.forget();
        SharedPreferencesUtil.forget();
        previousContext.release(reason);
        // There is InterruptedException after above..., so we catch it below:
        DbUtils.waitMs(TAG, 10);
        MyLog.v(TAG, () -> "release completed, " + reason.get());
    }

    public static MyFutureContext completedFuture(MyContext myContext) {
        CompletableFuture<MyContext> future = new CompletableFuture<>();
        future.complete(myContext);
        return new MyFutureContext(MyContext.EMPTY, future);
    }

    private MyFutureContext(@NonNull MyContext previousContext, CompletableFuture<MyContext> future) {
        this.previousContext = previousContext;
        this.future = future;
    }

    public boolean needToInitialize() {
        if (future.isDone()) {
            return future.isCancelled() || future.isCompletedExceptionally()
                    || getNow().isExpired() || !getNow().isReady();
        }
        return false;
    }

    public MyContext getNow() {
        return Try.success(previousContext).map(future::getNow).getOrElse(previousContext);
    }

    @Override
    public long getInstanceId() {
        return instanceId;
    }

    public static BiConsumer<MyContext, Throwable> startNextActivity(FirstActivity firstActivity) {
        return (myContext, throwable) -> {
            boolean launched = false;
            if (myContext != null && myContext.isReady() && !myContext.isExpired()) {
                try {
                    firstActivity.startNextActivitySync(myContext);
                    launched = true;
                } catch (android.util.AndroidRuntimeException e) {
                    MyLog.w(TAG, "Launching next activity from firstActivity", e);
                } catch (java.lang.SecurityException e) {
                    MyLog.d(TAG, "Launching activity", e);
                }
            }
            if (!launched) {
                HelpActivity.startMe(
                        myContext == null ? myContextHolder.getNow().context() : myContext.context(),
                        true, HelpActivity.PAGE_LOGO);
            }
            firstActivity.finish();
        };
    }

    public MyFutureContext whenSuccessAsync(Consumer<MyContext> consumer, Executor executor) {
        return with(future -> future.whenCompleteAsync((myContext, throwable) -> {
            if (myContext != null) {
                consumer.accept(myContext);
            }
        }, executor));
    }

    public MyFutureContext with(UnaryOperator<CompletableFuture<MyContext>> futures) {
        return new MyFutureContext(previousContext, futures.apply(future));
    }

    public static Consumer<MyContext> startActivity(Activity activity) {
        return startIntent(activity.getIntent());
    }

    public static Consumer<MyContext> startIntent(Intent intent) {
        return myContext -> {
            if (intent != null) {
                boolean launched = false;
                if (myContext.isReady() && !myContext.isExpired()) {
                    try {
                        myContext.context().startActivity(intent);
                        launched = true;
                    } catch (android.util.AndroidRuntimeException e) {
                        try {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            myContext.context().startActivity(intent);
                            launched = true;
                        } catch (Exception e2) {
                            MyLog.e(TAG, "Launching activity with Intent.FLAG_ACTIVITY_NEW_TASK flag", e);
                        }
                    } catch (java.lang.SecurityException e) {
                        MyLog.d(TAG, "Launching activity", e);
                    }
                }
                if (!launched) {
                    HelpActivity.startMe(myContext.context(), true, HelpActivity.PAGE_LOGO);
                }
            }
        };
    }

    public Try<MyContext> tryBlocking() {
        return Try.of(future::get);
    }

    @Override
    public String classTag() {
        return TAG;
    }
}
