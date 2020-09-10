package org.cryptomator.fusecloudaccess;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import dagger.Module;
import dagger.Provides;
import org.cryptomator.cloudaccess.api.CloudPath;

import javax.inject.Named;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.locks.StampedLock;

/**
 * Fragen: Wenn mehrere Mounts ausgeführt werden, teilen sich diese die gleiche ThreadFactory, moveLock und den scheduler??
 * -> Scope mit "PerFilesystem" erstellen?
 */
@Module
class CloudAccessFSModule {

	private static final ThreadFactory SCHEDULER_THREAD_FACTORY = new ThreadFactoryBuilder().setDaemon(false).setNameFormat("scheduler-%d").build();

	@Provides
	@FileSystemScoped
	static ScheduledExecutorService provideScheduler() {
		return Executors.newSingleThreadScheduledExecutor(SCHEDULER_THREAD_FACTORY);
	}

	@Provides
	@FileSystemScoped
	static ExecutorService provideExecutorService() {
		return Executors.newCachedThreadPool();
	}

	@Provides
	@FileSystemScoped
	static StampedLock provideMoveLock() {
		return new StampedLock();
	}

	@Provides
	@FileSystemScoped
	@Named("openFiles")
	static ConcurrentMap<CloudPath, OpenFile> provideOpenFilesMap() {
		return new ConcurrentHashMap<>();
	}

	@Provides
	@FileSystemScoped
	@Named("uploadTasks")
	static ConcurrentMap<CloudPath, Future<?>> provideUploadTasksMap() {
		return new ConcurrentHashMap<>();
	}
/*
	@Provides
	CloudProvider provideCloudProvider(CloudProvider provider){
		return provider;
	}

	@Provides
	Path provideCacheDir(Path cacheDir){
		return cacheDir;
	}

	@Provides
	CloudPath provideUploadDir(CloudPath uploadDir){
		return uploadDir;
	}
*/
}
