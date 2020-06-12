/*
 * Copyright (C) 2012 Daniel Himmelein
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

package mindroid.util.logging;

import mindroid.os.Bundle;
import mindroid.os.IInterface;
import mindroid.os.IBinder;
import mindroid.os.Binder;
import mindroid.os.RemoteException;
import mindroid.util.concurrent.Promise;

public interface ILogger extends IInterface {
    abstract class Stub extends Binder implements ILogger {
        private static final String DESCRIPTOR = "mindroid://interfaces/mindroid/util/logging/ILogger";

        public Stub() {
            this.attachInterface(this, DESCRIPTOR);
        }

        public static ILogger asInterface(IBinder binder) {
            if (binder == null) {
                return null;
            }
            return new ILogger.Proxy(binder);
        }

        public IBinder asBinder() {
            return this;
        }

        @Override
        protected void onTransact(int what, int num, Object obj, Bundle data, Promise<?> result) throws RemoteException {
            switch (what) {
            case MSG_ASSUME_THAT: {
                String tag = data.getString("tag");
                String message = data.getString("message");
                long timeout = data.getLong("timeout");
                ((Promise<String>) result).completeWith(assumeThat(tag, message, timeout));
                break;
            }
            case MSG_MARK: {
                mark();
                result.complete(null);
                break;
            }
            case MSG_RESET: {
                reset();
                result.complete(null);
                break;
            }
            default:
                super.onTransact(what, num, obj, data, result);
            }
        }

        private static class Proxy implements ILogger {
            private final IBinder mRemote;

            Proxy(IBinder remote) {
                mRemote = remote;
            }

            public IBinder asBinder() {
                return mRemote;
            }

            public boolean equals(final Object obj) {
                if (obj == null) return false;
                if (obj == this) return true;
                if (obj instanceof Stub.Proxy) {
                    final Stub.Proxy that = (Stub.Proxy) obj;
                    return this.mRemote.equals(that.mRemote);
                }
                return false;
            }

            public int hashCode() {
                return mRemote.hashCode();
            }

            public Promise<String> assumeThat(String tag, String message, long timeout) throws RemoteException {
                Bundle data = new Bundle();
                data.putString("tag", tag);
                data.putString("message", message);
                data.putLong("timeout", timeout);
                Promise<String> promise = new Promise<>();
                mRemote.transact(MSG_ASSUME_THAT, 0, null, data, promise, 0);
                return promise;
            }

            public void mark() throws RemoteException {
                Promise<Void> promise = new Promise<>();
                mRemote.transact(MSG_MARK, 0, null, null, promise, 0);
                Binder.get(promise);
            }

            public void reset() throws RemoteException {
                Promise<Void> promise = new Promise<>();
                mRemote.transact(MSG_RESET, 0, null, null, promise, 0);
                Binder.get(promise);
            }
        }

        static final int MSG_ASSUME_THAT = 1;
        static final int MSG_MARK = 2;
        static final int MSG_RESET = 3;
    }

    class Proxy implements ILogger {
        private final IBinder mBinder;
        private final Stub mStub;
        private final ILogger mProxy;

        Proxy(IBinder binder) {
            mBinder = binder;
            if (binder.getUri().getScheme().equals("mindroid")) {
                mStub = (Stub) binder.queryLocalInterface(Stub.DESCRIPTOR);
                mProxy = new Stub.Proxy(binder);
            } else {
                mindroid.runtime.system.Runtime runtime = mindroid.runtime.system.Runtime.getRuntime();
                mStub = (Stub) runtime.getBinder(binder.getId());
                mProxy = (ILogger) runtime.getProxy(binder);
            }
        }

        public IBinder asBinder() {
            return mBinder;
        }

        public boolean equals(final Object obj) {
            if (obj == null) return false;
            if (obj == this) return true;
            if (obj instanceof Proxy) {
                final Proxy that = (Proxy) obj;
                return this.mBinder.equals(that.mBinder);
            }
            return false;
        }

        public int hashCode() {
            return mBinder.hashCode();
        }

        public Promise<String> assumeThat(String tag, String message, long timeout) throws RemoteException {
            if (mStub != null && mStub.isCurrentThread()) {
                return mStub.assumeThat(tag, message, timeout);
            } else {
                return mProxy.assumeThat(tag, message, timeout);
            }
        }

        public void mark() throws RemoteException {
            if (mStub != null && mStub.isCurrentThread()) {
                mStub.mark();
            } else {
                mProxy.mark();
            }
        }

        public void reset() throws RemoteException {
            if (mStub != null && mStub.isCurrentThread()) {
                mStub.reset();
            } else {
                mProxy.reset();
            }
        }
    }

    Promise<String> assumeThat(String tag, String message, long timeout) throws RemoteException;
    void mark() throws RemoteException;
    void reset() throws RemoteException;
}
