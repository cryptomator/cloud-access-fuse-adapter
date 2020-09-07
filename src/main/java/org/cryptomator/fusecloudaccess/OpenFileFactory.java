package org.cryptomator.fusecloudaccess;

import com.google.common.base.Preconditions;
import jnr.constants.platform.OpenFlags;
import org.cryptomator.cloudaccess.api.CloudItemMetadata;
import org.cryptomator.cloudaccess.api.CloudItemType;
import org.cryptomator.cloudaccess.api.CloudPath;
import org.cryptomator.cloudaccess.api.CloudProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

class OpenFileFactory {

	private static final AtomicLong FILE_HANDLE_GEN = new AtomicLong();
	private static final Logger LOG = LoggerFactory.getLogger(OpenFileFactory.class);

	/*
	 * activeFiles.compute is the primary barrier for synchronized access when creating/closing/moving OpenFiles
	 * OpenFile.close() as well as modifications to OpenFile.getOpenFileHandleCount() MUST be protected by this
	 * means of synchronization.
	 */
	private final ConcurrentMap<CloudPath, OpenFile> activeFiles;
	private final Map<Long, OpenFile> fileHandles;
	private final CloudProvider provider;
	private final OpenFileUploader uploader;
	private final Path cacheDir;

	public OpenFileFactory(CloudProvider provider, OpenFileUploader uploader, Path cacheDir) {
		this(new ConcurrentHashMap<>(), provider, uploader, cacheDir);
	}

	// visible for testing
	OpenFileFactory(ConcurrentMap<CloudPath, OpenFile> activeFiles, CloudProvider provider, OpenFileUploader uploader, Path cacheDir) {
		this.activeFiles = activeFiles;
		this.fileHandles = new HashMap<>();
		this.provider = provider;
		this.uploader = uploader;
		this.cacheDir = cacheDir;
	}

	/**
	 * @param path  path of the file to open
	 * @param flags file open options
	 * @return file handle used to identify and close open files.
	 */
	public long open(CloudPath path, Set<OpenFlags> flags, long initialSize, Instant lastModified) throws IOException {
		try {
			if (flags.contains(OpenFlags.O_RDWR) || flags.contains(OpenFlags.O_WRONLY)) {
				uploader.cancelUpload(path);
			}
			var openFile = activeFiles.compute(path, (p, file) -> {
				if (file == null) {
					file = createOpenFile(p, initialSize, lastModified); // TODO remove redundant lastModified?
				}
				file.getOpenFileHandleCount().incrementAndGet();
				file.setLastModified(lastModified);
				return file;
			});
			if (flags.contains(OpenFlags.O_TRUNC)) {
				openFile.truncate(0);
			}
			var handleId = FILE_HANDLE_GEN.incrementAndGet();
			fileHandles.put(handleId, openFile);
			return handleId;
		} catch (UncheckedIOException e) {
			throw new IOException(e);
		}
	}

	//visible for testing
	OpenFile createOpenFile(CloudPath path, long initialSize, Instant lastModified) {
		try {
			var tmpFile = cacheDir.resolve(UUID.randomUUID().toString());
			return OpenFile.create(path, tmpFile, provider, initialSize, lastModified);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public Optional<OpenFile> get(long fileHandle) {
		return Optional.ofNullable(fileHandles.get(fileHandle));
	}

	/**
	 * Updates existing cached data for <code>newPath</code> (if any) with contents formerly mapped to <code>oldPath</code>,
	 * invalidates <code>oldPath</code> and cancel pending uploads for both paths (if any).
	 * <p>
	 * Cached data previously mapped to <code>newPath</code> will be discarded. No-op if no data cached for either path.
	 * <p>
	 *
	 * @param oldPath Path to a cached file before it has been moved
	 * @param newPath New path which is used to access the cached file
	 */
	public synchronized void move(CloudPath oldPath, CloudPath newPath) {
		Preconditions.checkArgument(!oldPath.equals(newPath));
		var wasUploading = uploader.cancelUpload(oldPath);
		uploader.cancelUpload(newPath);
		var activeFile = activeFiles.remove(oldPath);
		activeFiles.compute(newPath, (p, previouslyActiveFile) -> {
			assert previouslyActiveFile == null || previouslyActiveFile != activeFile; // if previousActiveFile is non-null, it must not be the same as activeFile!
			if (previouslyActiveFile != null) {
				previouslyActiveFile.close();
			}
			if (activeFile != null) {
				activeFile.updatePath(newPath);
				if (wasUploading) {
					uploader.scheduleUpload(activeFile, this::onFinishedUpload);
				}
			}
			return activeFile;
		});
	}

	/**
	 * Invalidates any mapping for the given <code>path</code>.
	 * <p>
	 * Cached data for the given <code>path</code> will be discarded and any pending upload from a previous change is canceled.
	 * No-op if no data is cached for the given path.
	 *
	 * @param path Path to a cached file
	 */
	public void delete(CloudPath path) {
		// TODO what about descendants of path?
		uploader.cancelUpload(path);
		activeFiles.computeIfPresent(path, (p, file) -> {
			file.close();
			return null; // removes entry from map
		});
	}

	/**
	 * Closes the fileHandle. If all handles for a given file are closed, the file contents are scheduled for persistence and the file will be marked for eventual eviction.
	 *
	 * @param handleId file handle
	 */
	public void close(long handleId) {
		OpenFile file = fileHandles.remove(handleId);
		if (file == null) {
			LOG.warn("No such file handle: {}", handleId);
			return;
		}
		var path = file.getPath();
		activeFiles.computeIfPresent(path, (p, activeFile) -> {
			if (activeFile.getOpenFileHandleCount().decrementAndGet() == 0) { // was this the last file handle?
				uploader.scheduleUpload(activeFile, this::onFinishedUpload);
			}
			return activeFile; // DO NOT remove the mapping yet! this might be done in #onFinishedUpload
		});
	}

	private void onFinishedUpload(OpenFile file) {
		// TODO wait 10s before removing from activeFiles:
		// FIXME this is not how we want the 10s to work:
		try {
			Thread.sleep(10000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		activeFiles.computeIfPresent(file.getPath(), (p, activeFile) -> {
			if (activeFile.getOpenFileHandleCount().get() > 0) { // file has been reopened
				return activeFile; // keep the mapping
			} else {
				activeFile.close();
				return null; // remove mapping
			}
		});
	}

	/**
	 * Returns metadata from cache. This is not threadsafe and the returned metadata might refer to an
	 * file that got evicted just in this moment.
	 * @param path
	 * @return Optional metadata, which is present if cached
	 */
	public Optional<CloudItemMetadata> getCachedMetadata(CloudPath path) {
		AtomicReference<CloudItemMetadata> result = new AtomicReference<>();
		activeFiles.computeIfPresent(path, (p, file) -> {
			var lastModified = Optional.of(file.getLastModified());
			var size = Optional.of(file.getSize());
			result.set(new CloudItemMetadata(path.getFileName().toString(), path, CloudItemType.FILE, lastModified, size));
			return file;
		});
		return Optional.ofNullable(result.get());
	}

}
