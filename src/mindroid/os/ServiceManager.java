/*
 * Copyright (C) 2013 Daniel Himmelein
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

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import mindroid.content.ComponentName;
import mindroid.content.Context;
import mindroid.content.Intent;
import mindroid.content.ServiceConnection;
import mindroid.content.pm.ApplicationInfo;
import mindroid.content.pm.IPackageManager;
import mindroid.content.pm.ResolveInfo;
import mindroid.content.pm.ServiceInfo;
import mindroid.util.Log;
import mindroid.util.Pair;
import mindroid.util.concurrent.CancellationException;
import mindroid.util.concurrent.ExecutionException;
import mindroid.util.concurrent.Future;
import mindroid.util.concurrent.Promise;
import mindroid.runtime.system.Runtime;

public final class ServiceManager {
    private static final String LOG_TAG = "ServiceManager";
    private static final String SYSTEM_SERVICE = "systemService";
    private static IServiceManager sServiceManager;
    private static IServiceManager.Stub sStub;
    private static final HashMap<String, IBinder> sSystemServices = new HashMap<>();
    private static final int SHUTDOWN_TIMEOUT = 10000; //ms
    private final ProcessManager mProcessManager;
    private final HandlerThread mMainThread;
    private HashMap<String, ProcessRecord> mProcesses = new HashMap<>();
    private HashMap<ComponentName, ServiceRecord> mServices = new HashMap<>();
    private int mStartId = 0;
    private IPackageManager mPackageManager;

    static class ProcessManager {
        private final HandlerThread mThread;
        private Handler mHandler;
        private HashMap<String, Pair> mProcesses = new HashMap<>();

        public ProcessManager() {
            mThread = new HandlerThread("ProcessManager");
        }

        public void start() {
            mThread.start();
            mHandler = new Handler(mThread.getLooper());
        }

        public void shutdown() {
            if (mThread.quit()) {
                try {
                    Log.println('D', LOG_TAG, "Shutting down ProcessManager");
                    mThread.join();
                    Log.println('D', LOG_TAG, "ProcessManager has been shut down");
                } catch (InterruptedException e) {
                }
            }
        }

        public synchronized IProcess startProcess(String name) {
            if (!mProcesses.containsKey(name)) {
                Process process = new Process(name);
                IProcess p = process.start();
                mProcesses.put(name, new Pair(process, p));
                return p;
            } else {
                Pair pair = mProcesses.get(name);
                return (IProcess) pair.second;
            }
        }

        public synchronized boolean stopProcess(String name) {
            Pair pair = mProcesses.remove(name);
            if (pair != null) {
                final Process process = (Process) pair.first;
                mHandler.post(new Runnable() {
                    public void run() {
                        process.stop(SHUTDOWN_TIMEOUT);
                    }
                });
                return true;
            } else {
                return false;
            }
        }

        public synchronized Future<Boolean> stopProcess(String name, final long timeout) {
            final Promise<Boolean> promise = new Promise<>();
            Pair pair = mProcesses.remove(name);
            if (pair != null) {
                final Process process = (Process) pair.first;
                mHandler.post(new Runnable() {
                    public void run() {
                        process.stop(timeout);
                        promise.complete(true);
                    }
                });
                return promise;
            } else {
                promise.complete(false);
                return promise;
            }
        }
    }

    static class ProcessRecord {
        final String name;
        final IProcess process;
        private final HashMap<ComponentName, ServiceRecord> services = new HashMap<>();

        public ProcessRecord(String name, IProcess process) {
            this.name = name;
            this.process = process;
        }

        void addService(ComponentName component, ServiceRecord service) {
            services.put(component, service);
        }

        void removeService(ComponentName component) {
            services.remove(component);
        }

        ServiceRecord getService(ComponentName component) {
            return services.get(component);
        }

        boolean containsService(ComponentName component) {
            return services.containsKey(component);
        }

        int numServices() {
            return services.size();
        }
    }

    static class ServiceRecord {
        final String name;
        final ProcessRecord processRecord;
        final boolean systemService;
        boolean alive;
        boolean running;
        private final List<ServiceConnection> serviceConnections = new ArrayList<>();

        ServiceRecord(String name, ProcessRecord processRecord, boolean systemService) {
            this.name = name;
            this.processRecord = processRecord;
            this.systemService = systemService;
            alive = false;
            running = false;
        }

        void addServiceConnection(ServiceConnection conn) {
            serviceConnections.add(conn);
        }

        void removeServiceConnection(ServiceConnection conn) {
            serviceConnections.remove(conn);
        }

        int getNumServiceConnections() {
            return serviceConnections.size();
        }

        boolean hasServiceConnection(ServiceConnection conn) {
            return serviceConnections.contains(conn);
        }
    }

    public ServiceManager() {
        mProcessManager = new ProcessManager();
        mMainThread = new HandlerThread(LOG_TAG);
    }

    public void start() {
        mProcessManager.start();

        mMainThread.start();
        sStub = new ServiceManagerImpl(mMainThread.getLooper());

        addService(Context.SERVICE_MANAGER, sStub);
    }

    public void shutdown() {
        ProcessRecord[] processRecords;
        synchronized (mProcesses) {
            Collection<ProcessRecord> prs = mProcesses.values();
            processRecords = new ProcessRecord[prs.size()];
            prs.toArray(processRecords);
        }
        for (int i = 0; i < processRecords.length; i++) {
            ProcessRecord processRecord = processRecords[i];
            Future<Boolean> future = mProcessManager.stopProcess(processRecord.name, SHUTDOWN_TIMEOUT);
            try {
                future.get();
            } catch (CancellationException e) {
            } catch (ExecutionException e) {
            } catch (InterruptedException e) {
            }
        }

        removeService(sStub);

        if (mMainThread.quit()) {
            try {
                Log.println('D', LOG_TAG, "Shutting down ServiceManager");
                mMainThread.join();
                Log.println('D', LOG_TAG, "ServiceManager has been shut down");
            } catch (InterruptedException e) {
            }
        }

        mProcessManager.shutdown();

        sStub = null;
        sServiceManager = null;
        sSystemServices.clear();
    }

    class ServiceManagerImpl extends IServiceManager.Stub {
        public ServiceManagerImpl(Looper looper) {
            super(looper);
        }

        @Override
        public Promise<ComponentName> startService(Intent intent) {
            intent.putExtra(SYSTEM_SERVICE, false);
            return ServiceManager.this.startService(intent);
        }

        @Override
        public Promise<Boolean> stopService(Intent service) {
            if (mServices.containsKey(service.getComponent())) {
                final ServiceRecord serviceRecord = mServices.get(service.getComponent());
                final ProcessRecord processRecord = serviceRecord.processRecord;
                final IProcess process = processRecord.process;

                if (!serviceRecord.alive) {
                    return new Promise<>(false);
                }

                if (serviceRecord.getNumServiceConnections() != 0) {
                    Log.d(LOG_TAG, "Cannot stop service " + service.getComponent().toShortString() + " due to active bindings");
                    return new Promise<>(false);
                }

                final Promise<Boolean> promise = new Promise<>();
                final RemoteCallback callback = new RemoteCallback(new RemoteCallback.OnResultListener() {
                    public void onResult(Bundle data) {
                        boolean result = data.getBoolean("result");
                        if (result) {
                            Log.d(LOG_TAG, "Service " + serviceRecord.name + " has been stopped");
                        } else {
                            Log.w(LOG_TAG, "Service " + serviceRecord.name + " cannot be stopped");
                        }
                        promise.complete(result);
                    }
                });

                try {
                    process.stopService(service, callback.asInterface());
                } catch (RemoteException e) {
                    throw new RuntimeException("System failure", e);
                }

                serviceRecord.alive = false;
                serviceRecord.running = false;
                mServices.remove(service.getComponent());
                processRecord.removeService(service.getComponent());

                if (processRecord.numServices() == 0) {
                    mProcessManager.stopProcess(processRecord.name);
                    synchronized (mProcesses) {
                        mProcesses.remove(processRecord.name);
                    }
                }

                return promise;
            } else {
                Log.d(LOG_TAG, "Cannot find and stop service " + service.getComponent().toShortString());
                return new Promise<>(false);
            }
        }

        @Override
        public Promise<Boolean> bindService(final Intent intent, final ServiceConnection conn, int flags, IRemoteCallback callback) {
            intent.putExtra(SYSTEM_SERVICE, false);

            if (!prepareService(intent)) {
                return new Promise<>(false);
            }

            if (mServices.containsKey(intent.getComponent())) {
                ServiceRecord serviceRecord = mServices.get(intent.getComponent());
                if (!serviceRecord.hasServiceConnection(conn)) {
                    serviceRecord.addServiceConnection(conn);

                    try {
                        serviceRecord.processRecord.process.bindService(intent, conn, flags, callback);
                    } catch (RemoteException e) {
                        throw new RuntimeException("System failure", e);
                    }

                    Log.d(LOG_TAG, "Bound to service " + serviceRecord.name + " in process " + serviceRecord.processRecord.name);
                }

                return new Promise<>(true);
            } else {
                Log.d(LOG_TAG, "Cannot find and bind service " + intent.getComponent().toShortString());
                return new Promise<>(false);
            }
        }

        @Override
        public void unbindService(Intent service, ServiceConnection conn) {
            unbindService(service, conn, null);
        }

        @Override
        public void unbindService(Intent service, ServiceConnection conn, IRemoteCallback callback) {
            ServiceRecord serviceRecord = mServices.get(service.getComponent());
            if (serviceRecord != null) {
                IProcess process = serviceRecord.processRecord.process;
                try {
                    if (callback != null) {
                        process.unbindService(service, callback);
                    } else {
                        process.unbindService(service);
                    }
                } catch (RemoteException e) {
                    throw new RuntimeException("System failure", e);
                }
                Log.d(LOG_TAG, "Unbound from service " + serviceRecord.name + " in process " + serviceRecord.processRecord.name);

                serviceRecord.removeServiceConnection(conn);
                if (serviceRecord.getNumServiceConnections() == 0) {
                    try {
                        sServiceManager.stopService(service);
                    } catch (RemoteException e) {
                        throw new RuntimeException("System failure", e);
                    }
                }
            }
        }

        @Override
        public Promise<ComponentName> startSystemService(Intent service) {
            if (!service.hasExtra("name")) {
                service.putExtra("name", service.getComponent().getClassName());
            }
            if (!service.hasExtra("process")) {
                service.putExtra("process", service.getComponent().getPackageName());
            }
            service.putExtra(SYSTEM_SERVICE, true);
            return ServiceManager.this.startService(service);
        }

        @Override
        public Promise<Boolean> stopSystemService(Intent service) {
            service.putExtra(SYSTEM_SERVICE, true);
            return stopService(service);
        }
    }

    private IProcess prepareProcess(String name) {
        IProcess process;
        synchronized (mProcesses) {
            if (mProcesses.containsKey(name)) {
                final ProcessRecord processRecord = mProcesses.get(name);
                process = processRecord.process;
            } else {
                process = mProcessManager.startProcess(name);
                mProcesses.put(name, new ProcessRecord(name, process));
            }
        }
        return process;
    }

    private boolean prepareService(final Intent service) {
        ServiceInfo serviceInfo;
        if (service.getBooleanExtra(SYSTEM_SERVICE, false)) {
            ApplicationInfo ai = new ApplicationInfo();
            ai.processName = service.getStringExtra("process");
            ServiceInfo si = new ServiceInfo();
            si.name = service.getComponent().getClassName();
            si.packageName = service.getComponent().getPackageName();
            si.applicationInfo = ai;
            si.processName = ai.processName;
            si.flags |= ServiceInfo.FLAG_SYSTEM_SERVICE;

            serviceInfo = si;
        } else {
            if (mPackageManager == null) {
                mPackageManager = IPackageManager.Stub.asInterface(getSystemService(Context.PACKAGE_MANAGER));
            }
            ResolveInfo resolveInfo = null;
            try {
                resolveInfo = mPackageManager.resolveService(service, 0);
            } catch (NullPointerException e) {
                throw new RuntimeException("System failure", e);
            } catch (RemoteException e) {
                throw new RuntimeException("System failure", e);
            }

            if (resolveInfo == null || resolveInfo.serviceInfo == null) {
                return false;
            }

            serviceInfo = resolveInfo.serviceInfo;
        }

        final IProcess process;
        final ProcessRecord processRecord;
        final ServiceRecord serviceRecord;
        if (!mServices.containsKey(service.getComponent())) {
            process = prepareProcess(serviceInfo.processName);
            if (process == null) {
                return false;
            }
            processRecord = mProcesses.get(serviceInfo.processName);
            boolean systemService = service.getBooleanExtra(SYSTEM_SERVICE, false);
            String name;
            if (systemService) {
                name = service.getStringExtra("name");
            } else {
                name = serviceInfo.packageName + "." + serviceInfo.name;
            }
            serviceRecord = new ServiceRecord(name, processRecord, systemService);
            mServices.put(service.getComponent(), serviceRecord);
            if (!processRecord.containsService(service.getComponent())) {
                processRecord.addService(service.getComponent(), serviceRecord);
            }
        } else {
            serviceRecord = mServices.get(service.getComponent());
            processRecord = serviceRecord.processRecord;
            process = processRecord.process;
        }

        if (!serviceRecord.alive) {
            serviceRecord.alive = true;
            RemoteCallback callback = new RemoteCallback(new RemoteCallback.OnResultListener() {
                public void onResult(Bundle data) {
                    boolean result = data.getBoolean("result");
                    ComponentName component = service.getComponent();
                    if (mServices.containsKey(component)) {
                        ServiceRecord serviceRecord = mServices.get(component);
                        if (result) {
                            Log.d(LOG_TAG, "Service " + serviceRecord.name + " has been created in process " + serviceRecord.processRecord.name);
                        } else {
                            Log.w(LOG_TAG, "Service " + serviceRecord.name + " cannot be created in process " + serviceRecord.processRecord.name
                                    + ". Cleaning up");
                            cleanupService(service);
                        }
                    }
                }
            });

            try {
                process.createService(service, callback.asInterface());
            } catch (RemoteException e) {
                throw new RuntimeException("System failure", e);
            }
        }

        return true;
    }

    private Promise<ComponentName> startService(final Intent service) {
        if (!prepareService(service)) {
            return new Promise<>((ComponentName) null);
        }

        final ServiceRecord serviceRecord = mServices.get(service.getComponent());
        serviceRecord.running = true;

        final Promise<ComponentName> promise = new Promise<>();
        final RemoteCallback callback = new RemoteCallback(new RemoteCallback.OnResultListener() {
            public void onResult(Bundle data) {
                boolean result = data.getBoolean("result");
                if (result) {
                    Log.d(LOG_TAG, "Service " + serviceRecord.name + " has been started in process " + serviceRecord.processRecord.name);
                } else {
                    Log.w(LOG_TAG, "Service " + serviceRecord.name + " cannot be started in process " + serviceRecord.processRecord.name);
                }
                promise.complete(service.getComponent());
            }
        });

        try {
            serviceRecord.processRecord.process.startService(service, 0, mStartId++, callback.asInterface());
        } catch (RemoteException e) {
            throw new RuntimeException("System failure", e);
        }

        return promise;
    }

    private boolean cleanupService(Intent service) {
        if (mServices.containsKey(service.getComponent())) {
            final ServiceRecord serviceRecord = mServices.get(service.getComponent());
            final ProcessRecord processRecord = serviceRecord.processRecord;

            if (serviceRecord.alive) {
                serviceRecord.alive = false;
            }
            if (serviceRecord.running) {
                serviceRecord.running = false;
            }

            mServices.remove(service.getComponent());
            processRecord.removeService(service.getComponent());

            if (processRecord.numServices() == 0) {
                mProcessManager.stopProcess(processRecord.name);
                synchronized (mProcesses) {
                    mProcesses.remove(processRecord.name);
                }
            }

            return true;
        } else {
            Log.d(LOG_TAG, "Cannot find and clean up service " + service.getComponent().toShortString());
            return false;
        }
    }

    public static synchronized IServiceManager getServiceManager() {
        if (sServiceManager != null) {
            return sServiceManager;
        }

        sServiceManager = IServiceManager.Stub.asInterface(sStub);
        return sServiceManager;
    }

    /**
     * Returns a reference to a service with the given name.
     * 
     * @param name the name of the service to get
     * @return a reference to the service, or <code>null</code> if the service doesn't exist
     */
    public static IBinder getSystemService(URI uri) {
        IBinder service = Runtime.getRuntime().getService(uri);
        return service;
    }

    /**
     * @hide
     */
    public static void addService(URI uri, IBinder service) {
        synchronized (sSystemServices) {
            if (!sSystemServices.containsKey(uri.toString())) {
                sSystemServices.put(uri.toString(), service);
                Runtime.getRuntime().addService(uri, service);
                sSystemServices.notifyAll();
            }
        }
    }

    /**
     * @hide
     */
    public static void removeService(IBinder service) {
        synchronized (sSystemServices) {
            Runtime.getRuntime().removeService(service);
            sSystemServices.values().remove(service);
            sSystemServices.notifyAll();
        }
    }

    /**
     * @hide
     */
    public static void waitForSystemService(URI name) throws InterruptedException {
        synchronized (sSystemServices) {
            final long TIMEOUT = 10000;
            long start = SystemClock.uptimeMillis();
            long duration = TIMEOUT;
            while (!sSystemServices.containsKey(name.toString())) {
                sSystemServices.wait(duration);
                if (!sSystemServices.containsKey(name.toString())) {
                    duration = start + TIMEOUT - SystemClock.uptimeMillis();
                    if (duration <= 0) {
                        Log.w(LOG_TAG, "Starting " + name + " takes very long");
                        start = SystemClock.uptimeMillis();
                        duration = TIMEOUT;
                    }
                }
            }
        }
    }

    /**
     * @hide
     */
    public static void waitForSystemServiceShutdown(URI name) throws InterruptedException {
        synchronized (sSystemServices) {
            final long TIMEOUT = 10000;
            long start = SystemClock.uptimeMillis();
            long duration = TIMEOUT;
            while (sSystemServices.containsKey(name.toString())) {
                sSystemServices.wait(duration);
                if (sSystemServices.containsKey(name.toString())) {
                    duration = start + TIMEOUT - SystemClock.uptimeMillis();
                    if (duration <= 0) {
                        Log.w(LOG_TAG, "Stopping " + name + " takes very long");
                        start = SystemClock.uptimeMillis();
                        duration = TIMEOUT;
                    }
                }
            }
        }
    }

    /**
     * @hide
     */
    public static void waitForSystemServiceShutdown(URI name, long timeout) throws InterruptedException {
        synchronized (sSystemServices) {
            long start = SystemClock.uptimeMillis();
            long duration = timeout;
            while (sSystemServices.containsKey(name.toString()) && (duration > 0)) {
                sSystemServices.wait(duration);
                duration = start + timeout - SystemClock.uptimeMillis();
            }
            if (sSystemServices.containsKey(name.toString())) {
                Log.w(LOG_TAG, "Failed to wait for " + name + " shutdown");
            }
        }
    }
}
