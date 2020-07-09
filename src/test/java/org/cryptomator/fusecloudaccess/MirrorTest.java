package org.cryptomator.fusecloudaccess;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;
import java.util.UUID;

import com.google.common.base.Preconditions;
import org.cryptomator.cloudaccess.localfs.LocalFsCloudProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.impl.SimpleLogger;

public class MirrorTest {

	static {
		System.setProperty(SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "debug");
		System.setProperty(SimpleLogger.SHOW_DATE_TIME_KEY, "true");
		System.setProperty(SimpleLogger.DATE_TIME_FORMAT_KEY, "HH:mm:ss.SSS");
	}

	private static final Logger LOG = LoggerFactory.getLogger(MirrorTest.class);
	private static final String OS_NAME = System.getProperty("os.name").toLowerCase();
	private static final Path USER_HOME = Path.of(System.getProperty("user.home"));

	public static class MacMirror {

		public static void main(String args[]) {
			Preconditions.checkState(OS_NAME.contains("mac"), "Test designed to run on macOS.");

			try (Scanner scanner = new Scanner(System.in)) {
				System.out.println("Enter path to the directory you want to mirror:");
				Path p = Path.of(scanner.nextLine());
				Path m = Path.of("/Volumes/" + UUID.randomUUID().toString());
				var cloudAccessProvider = new LocalFsCloudProvider(p);
				var fs = new CloudAccessFS(cloudAccessProvider, 1000);
				var flags = new String[]{
						"-ouid=" + Files.getAttribute(USER_HOME, "unix:uid"),
						"-ogid=" + Files.getAttribute(USER_HOME, "unix:gid"),
						"-oatomic_o_trunc",
						"-oauto_xattr",
						"-oauto_cache",
						"-ovolname=CloudAccessMirror",
						"-ordonly", // TODO remove once we support writing
						"-omodules=iconv,from_code=UTF-8,to_code=UTF-8-MAC", // show files names in Unicode NFD encoding
						"-onoappledouble", // vastly impacts performance for some reason...
						"-odefault_permissions" // let the kernel assume permissions based on file attributes etc
				};
				LOG.info("Mounting FUSE file system at {}...", m);
				fs.mount(m, true, true, flags);
				LOG.info("Unmounted {}.", m);
			} catch (IOException e) {
				LOG.error("mount failed", e);
			}
		}

	}
	
}
