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

package mindroid.os;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import mindroid.content.ComponentName;
import mindroid.content.Context;
import mindroid.content.Intent;
import mindroid.content.ServiceConnection;
import mindroid.content.pm.IPackageManager;
import mindroid.content.pm.ResolveInfo;
import mindroid.content.pm.ServiceInfo;
import mindroid.app.ContextImpl;
import mindroid.app.Service;
import mindroid.util.Log;

public class Process {
    private static final String LOG_TAG = "Process";
    private static final HashMap<Integer, String> sPids = new HashMap<>();
    private final String mName;
    private final ThreadGroup mThreadGroup;
    private final HandlerThread mMainThread;
    private IProcess.Stub mStub;
    private IPackageManager mPackageManager;
    private final HashMap<ComponentName, Service> mServices;
    private final Debug mDebug;
    private boolean mUncaughtException = false;

    Process(String name) {
        mName = name;
        mThreadGroup = new ThreadGroup("Process {" + name + "}") {
            public void uncaughtException(Thread thread, Throwable e) {
                String message = e.getMessage();
                if (message == null) {
                    message = e.toString();
                }
                Log.println('A', LOG_TAG, "Uncaught exception in process " + mName + ": " + message);
                mUncaughtException = true;
                synchronized (mServices) {
                    mServices.notifyAll();
                }
                super.uncaughtException(thread, e);
            }
        };
        mServices = new HashMap<>();
        mMainThread = new HandlerThread(mThreadGroup, "Process {" + name + "}");
        mDebug = Debug.Creator.createInstance();
    }

    IProcess start() {
        Log.d(LOG_TAG, "Starting process " + mName);

        synchronized (sPids) {
            sPids.put(getId(), mName);
        }

        mMainThread.start();
        mStub = new ProcessImpl(mMainThread.getLooper());

        mDebug.start(this);

        return IProcess.Stub.asInterface(mStub);
    }

    void stop(long timeout) {
        Log.d(LOG_TAG, "Stopping process " + mName);

        long start = SystemClock.uptimeMillis();
        mDebug.stop();

        synchronized (mServices) {
            if (!mServices.isEmpty()) {
                IProcess process = IProcess.Stub.asInterface(mStub);

                Iterator<Map.Entry<ComponentName, Service>> itr = mServices.entrySet().iterator();
                while (itr.hasNext()) {
                    Map.Entry<ComponentName, Service> entry = itr.next();
                    ComponentName component = entry.getKey();
                    Intent intent = new Intent();
                    intent.setComponent(component);

                    try {
                        process.stopService(intent);
                    } catch (RemoteException e) {
                        throw new RuntimeException("System failure", e);
                    }
                }

                long duration = timeout;
                while (!mUncaughtException && !mServices.isEmpty() && (duration > 0)) {
                    try {
                        mServices.wait(duration);
                    } catch (InterruptedException e) {
                    }
                    duration = start + timeout - SystemClock.uptimeMillis();
                }
            }
        }

        if (mMainThread.quit()) {
            mMainThread.interrupt();
            try {
                mMainThread.join();
            } catch (InterruptedException e) {
            }
        }

        synchronized (sPids) {
            sPids.remove(getId());
        }

        Log.d(LOG_TAG, "Process " + mName + " has been stopped");
        if (SystemClock.uptimeMillis() - start >= 1000) {
            Log.println('W', LOG_TAG, "Stopping process " + mName + " took " + (SystemClock.uptimeMillis() - start) + "ms");
        }
    }

    int getId() {
        return mThreadGroup.hashCode();
    }

    String getName() {
        return mName;
    }

    ThreadGroup getThreadGroup() {
        return mThreadGroup;
    }

    HandlerThread getMainThread() {
        return mMainThread;
    }

    boolean isAlive() {
        return mMainThread.isAlive();
    }

    private class ProcessImpl extends IProcess.Stub {
        public ProcessImpl(Looper looper) {
            super(looper);
        }

        @Override
        public void createService(Intent intent, IRemoteCallback callback) throws RemoteException {
            Service service = null;
            Bundle result = new Bundle();

            final String componentName = intent.getComponent().getPackageName() + "." + intent.getComponent().getClassName();
            try {
                if (intent.getBooleanExtra("systemService", false)) {
                    try {
                        Class<?> clazz = Class.forName(componentName);
                        service = (Service) clazz.newInstance();
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "Cannot find class \'" + componentName + "\': " + e.getMessage(), e);
                        throw e;
                    } catch (LinkageError e) {
                        Log.e(LOG_TAG, "Linkage error: " + e.getMessage(), e);
                        throw e;
                    }
                } else {
                    if (mPackageManager == null) {
                        mPackageManager = IPackageManager.Stub.asInterface(ServiceManager.getSystemService(Context.PACKAGE_MANAGER));
                    }
                    ResolveInfo resolveInfo = null;
                    ServiceInfo serviceInfo = null;
                    try {
                        resolveInfo = mPackageManager.resolveService(intent, 0);
                    } catch (NullPointerException e) {
                        throw new RuntimeException("System failure", e);
                    } catch (RemoteException e) {
                        throw new RuntimeException("System failure", e);
                    }
                    if (resolveInfo != null && resolveInfo.serviceInfo != null) {
                        serviceInfo = resolveInfo.serviceInfo;
                    } else {
                        throw new Exception("Unknown service " + intent.getComponent().toShortString());
                    }

                    if (!serviceInfo.isEnabled()) {
                        throw new Exception("Service not enabled " + intent.getComponent().toShortString());
                    }

                    List<URL> urls = new ArrayList<>();
                    if (serviceInfo.applicationInfo.libraries != null) {
                        for (int i = 0; i < serviceInfo.applicationInfo.libraries.length; i++) {
                            urls.add(new File(serviceInfo.applicationInfo.libraries[i]).toURI().toURL());
                        }
                    }
                    urls.add(new File(serviceInfo.applicationInfo.fileName).toURI().toURL());
                    URLClassLoader classLoader = new URLClassLoader(urls.toArray(new URL[urls.size()]), getClass().getClassLoader());
                    try {
                        Class<?> clazz = Class.forName(componentName, true, classLoader);
                        service = (Service) clazz.newInstance();
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "Cannot find class \'" + componentName + "\': " + e.getMessage(), e);
                        throw e;
                    } catch (LinkageError e) {
                        Log.e(LOG_TAG, "Linkage error: " + e.getMessage(), e);
                        throw e;
                    }
                }

                service.attach(new ContextImpl(mMainThread, intent.getComponent()), this, intent.getComponent().getClassName());
                service.onCreate();
                result.putBoolean("result", true);

                synchronized (mServices) {
                    mServices.put(intent.getComponent(), service);
                }
            } catch (RuntimeException e) {
                Log.e(LOG_TAG, "Cannot create service " + componentName + ": " + e, e);
                throw e;
            } catch (Exception e) {
                Log.e(LOG_TAG, "Cannot create service " + componentName + ": " + e, e);
                result.putBoolean("result", false);
            } catch (Error e) {
                Log.e(LOG_TAG, "Cannot create service " + componentName + ": " + e, e);
                result.putBoolean("result", false);
            }

            callback.sendResult(result);
        }

        @Override
        public void startService(Intent intent, int flags, int startId, IRemoteCallback callback) throws RemoteException {
            Service service;
            Bundle result = new Bundle();

            synchronized (mServices) {
                service = mServices.get(intent.getComponent());
            }
            if (service != null) {
                final String componentName = intent.getComponent().getPackageName() + "." + intent.getComponent().getClassName();
                try {
                    service.onStartCommand(intent, flags, startId);
                    result.putBoolean("result", true);
                } catch (RuntimeException e) {
                    Log.e(LOG_TAG, "Cannot start service " + componentName + ": " + e, e);
                    throw e;
                } catch (Exception e) {
                    Log.e(LOG_TAG, "Cannot start service " + componentName + ": " + e, e);
                    result.putBoolean("result", false);
                } catch (Error e) {
                    Log.e(LOG_TAG, "Cannot start service " + componentName + ": " + e, e);
                    result.putBoolean("result", false);
                }
            } else {
                result.putBoolean("result", false);
            }

            callback.sendResult(result);
        }

        @Override
        public void stopService(Intent intent) throws RemoteException {
            stopService(intent, null);
        }

        @Override
        public void stopService(Intent intent, IRemoteCallback callback) throws RemoteException {
            Service service;
            Bundle result = new Bundle();

            synchronized (mServices) {
                service = mServices.get(intent.getComponent());
            }
            if (service != null) {
                final String componentName = intent.getComponent().getPackageName() + "." + intent.getComponent().getClassName();
                try {
                    service.onDestroy();
                    result.putBoolean("result", true);
                } catch (RuntimeException e) {
                    Log.e(LOG_TAG, "Cannot destroy service " + componentName + ": " + e, e);
                    throw e;
                } catch (Exception e) {
                    Log.e(LOG_TAG, "Cannot destroy service " + componentName + ": " + e, e);
                    result.putBoolean("result", false);
                } catch (Error e) {
                    Log.e(LOG_TAG, "Cannot destroy service " + componentName + ": " + e, e);
                    result.putBoolean("result", false);
                }

                Context context = service.getBaseContext();
                if (context instanceof ContextImpl) {
                    ((ContextImpl) context).cleanup();
                }
                synchronized (mServices) {
                    mServices.remove(intent.getComponent());
                    mServices.notifyAll();
                }
            } else {
                result.putBoolean("result", false);
            }

            if (callback != null) {
                callback.sendResult(result);
            }
        }

        @Override
        public void bindService(Intent intent, ServiceConnection conn, int flags, IRemoteCallback callback) throws RemoteException {
            Service service;
            Bundle result = new Bundle();

            synchronized (mServices) {
                service = mServices.get(intent.getComponent());
            }
            if (service != null) {
                final String componentName = intent.getComponent().getPackageName() + "." + intent.getComponent().getClassName();
                try {
                    IBinder binder = service.onBind(intent);
                    result.putBoolean("result", true);
                    result.putBinder("binder", binder);
                } catch (RuntimeException e) {
                    Log.w(LOG_TAG, "Cannot bind to service " + componentName + ": " + e, e);
                    throw e;
                } catch (Exception e) {
                    Log.w(LOG_TAG, "Cannot bind to service " + componentName + ": " + e, e);
                    result.putBoolean("result", false);
                } catch (Error e) {
                    Log.w(LOG_TAG, "Cannot bind to service " + componentName + ": " + e, e);
                    result.putBoolean("result", false);
                }
            } else {
                result.putBoolean("result", false);
            }

            callback.sendResult(result);
        }

        @Override
        public void unbindService(Intent intent) throws RemoteException {
            unbindService(intent, null);
        }

        @Override
        public void unbindService(Intent intent, IRemoteCallback callback) throws RemoteException {
            Service service;
            Bundle result = new Bundle();

            synchronized (mServices) {
                service = mServices.get(intent.getComponent());
            }
            if (service != null) {
                final String componentName = intent.getComponent().getPackageName() + "." + intent.getComponent().getClassName();
                try {
                    service.onUnbind(intent);
                    result.putBoolean("result", true);
                } catch (RuntimeException e) {
                    Log.w(LOG_TAG, "Cannot unbind from service " + componentName + ": " + e, e);
                    throw e;
                } catch (Exception e) {
                    Log.w(LOG_TAG, "Cannot unbind from service " + componentName + ": " + e, e);
                    result.putBoolean("result", false);
                } catch (Error e) {
                    Log.w(LOG_TAG, "Cannot unbind from service " + componentName + ": " + e, e);
                    result.putBoolean("result", false);
                }
            } else {
                result.putBoolean("result", false);
            }

            if (callback != null) {
                callback.sendResult(result);
            }
        }
    }

    /**
     * Returns the identifier of this process.
     */
    public static final int myPid() {
        ThreadGroup threadGroup = Thread.currentThread().getThreadGroup();
        if (threadGroup != null) {
            return threadGroup.hashCode();
        }
        return 0;
    }

    public static final String getName(int pid) {
        synchronized (sPids) {
            return sPids.get(pid);
        }
    }
}
